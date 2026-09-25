package com.cwbridge.helper

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.UsbChannel
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** OTG ADB host using flashbot UsbChannel. */
class OtgAdbPusher(private val context: Context) {

    class StallException(msg: String) : Exception(msg)

    companion object {
        const val ACTION_USB_PERMISSION = "com.cwbridge.helper.USB_PERMISSION"
        const val TARGET_PKG = "com.cwbridge.android.debug"
        const val PERM_LOGS = "android.permission.READ_LOGS"
        private const val CONNECT_TIMEOUT_SEC = 25L
        private const val ADB_CHUNK = 4096
        /** No bytes forwarded for this long → stall. */
        private const val PROGRESS_STALL_MS = 30_000L
        /** Countdown before force reconnect. */
        private const val RECONNECT_COUNTDOWN_SEC = 10
        private const val MAX_PUSH_ATTEMPTS = 2
    }

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun listDevices(): List<UsbDevice> = usb.deviceList.values.toList()

    fun hasPermission(device: UsbDevice): Boolean = usb.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE,
        )
        usb.requestPermission(device, pi)
    }

    fun resetAdbKeys(log: (String) -> Unit) {
        val dir = File(context.filesDir, "adbkey")
        var n = 0
        dir.listFiles()?.forEach { if (it.delete()) n++ }
        log("Deleted $n ADB key file(s)")
        log("On tablet: Developer options → Revoke USB debugging authorizations")
    }

    fun push(apk: File, device: UsbDevice, log: (String) -> Unit) {
        if (!usb.hasPermission(device)) {
            throw IllegalStateException("No USB permission — grant and retry")
        }
        var last: Throwable? = null
        for (attempt in 1..MAX_PUSH_ATTEMPTS) {
            log("—— attempt $attempt/$MAX_PUSH_ATTEMPTS ——")
            try {
                pushOnce(apk, device, log)
                return
            } catch (e: StallException) {
                last = e
                log("STALL: ${e.message}")
                if (attempt < MAX_PUSH_ATTEMPTS) {
                    log("Reconnecting in ${RECONNECT_COUNTDOWN_SEC}s…")
                    for (s in RECONNECT_COUNTDOWN_SEC downTo 1) {
                        log("  $s…")
                        Thread.sleep(1000)
                    }
                    log("Force reconnect…")
                }
            } catch (e: Exception) {
                last = e
                log("FAIL attempt $attempt: ${e.message}")
                if (attempt < MAX_PUSH_ATTEMPTS) {
                    log("Retry after ${RECONNECT_COUNTDOWN_SEC}s…")
                    for (s in RECONNECT_COUNTDOWN_SEC downTo 1) {
                        log("  $s…")
                        Thread.sleep(1000)
                    }
                }
            }
        }
        throw last ?: IllegalStateException("push failed")
    }

    private fun pushOnce(apk: File, device: UsbDevice, log: (String) -> Unit) {
        val connection = usb.openDevice(device)
            ?: throw IllegalStateException("openDevice failed")
        val iface = findAdbInterface(device)
            ?: throw IllegalStateException("No ADB interface — USB debugging on?")

        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw IllegalStateException("claimInterface failed")
        }
        log("Claimed ADB iface=${iface.id} eps=${iface.endpointCount}")

        val channel = UsbChannel(connection, iface)
        try {
            val crypto = loadOrCreateCrypto(log)
            log("ADB connect (max ${CONNECT_TIMEOUT_SEC}s)…")
            val conn = connectWithTimeout(channel, crypto)
            log("ADB connected")

            val installed = shell(conn, "pm path $TARGET_PKG", log).contains("package:")
            log(if (installed) "CWBridge present → update" else "CWBridge missing → install")

            log("Streaming APK (${apk.length()} bytes, ${ADB_CHUNK}B chunks)…")
            log("Stall watchdog: ${PROGRESS_STALL_MS / 1000}s no progress → reconnect")
            val installOut = execInstall(conn, apk, log)
            log("install: ${installOut.take(400)}")
            if (!installOut.contains("Success", ignoreCase = true) &&
                !installOut.contains("success", ignoreCase = true)
            ) {
                val check = shell(conn, "pm path $TARGET_PKG", log)
                if (!check.contains("package:")) {
                    throw IllegalStateException("Install failed: $installOut")
                }
            }

            log("Granting READ_LOGS…")
            val grant = shell(conn, "pm grant $TARGET_PKG $PERM_LOGS", log)
            log("grant: ${grant.ifBlank { "ok" }}")
            log("Done. ${shell(conn, "pm path $TARGET_PKG", log).trim()}")
            try {
                conn.close()
            } catch (_: Exception) {
            }
        } finally {
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun connectWithTimeout(channel: UsbChannel, crypto: AdbCrypto): AdbConnection {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit(
                Callable {
                    val c = AdbConnection.create(channel, crypto)
                    c.connect()
                    c
                },
            )
            return try {
                future.get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw IllegalStateException("ADB handshake timed out")
            } catch (e: Exception) {
                val cause = e.cause ?: e
                throw IllegalStateException("ADB connect failed: ${cause.message}", cause)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun execInstall(conn: AdbConnection, apk: File, log: (String) -> Unit): String {
        val size = apk.length()
        val stream = conn.open("exec:cmd package install -r -t -S $size")
        val buf = ByteArray(ADB_CHUNK)
        val sent = AtomicLong(0)
        val lastProgress = AtomicLong(System.currentTimeMillis())
        val abort = AtomicBoolean(false)
        val done = AtomicBoolean(false)

        val watchdog = Thread({
            try {
                while (!done.get() && !abort.get()) {
                    Thread.sleep(1000)
                    val idle = System.currentTimeMillis() - lastProgress.get()
                    if (idle >= PROGRESS_STALL_MS) {
                        log("No progress for ${idle / 1000}s — stall detected")
                        for (s in RECONNECT_COUNTDOWN_SEC downTo 1) {
                            if (done.get()) return@Thread
                            log("  reconnect in $s…")
                            Thread.sleep(1000)
                        }
                        abort.set(true)
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "install-watchdog").also { it.isDaemon = true; it.start() }

        try {
            var lastPct = -1
            apk.inputStream().use { input ->
                while (true) {
                    if (abort.get()) {
                        throw StallException("install stalled — forcing reconnect")
                    }
                    val n = input.read(buf)
                    if (n <= 0) break
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    stream.write(chunk)
                    val total = sent.addAndGet(n.toLong())
                    lastProgress.set(System.currentTimeMillis())
                    val pct = if (size > 0) ((total * 100) / size).toInt() else 100
                    if (pct != lastPct && (pct % 5 == 0 || pct == 100)) {
                        lastPct = pct
                        log("  … $pct% ($total / $size)")
                    }
                }
            }
            log("  write done, waiting for pm…")
            lastProgress.set(System.currentTimeMillis())
            val out = StringBuilder()
            try {
                while (!stream.isClosed) {
                    if (abort.get()) throw StallException("install stalled waiting for pm")
                    val chunk = stream.read() ?: break
                    out.append(String(chunk, StandardCharsets.UTF_8))
                    lastProgress.set(System.currentTimeMillis())
                }
            } catch (e: StallException) {
                throw e
            } catch (_: Exception) {
            }
            try {
                stream.close()
            } catch (_: Exception) {
            }
            return out.toString()
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
    }

    private fun shell(conn: AdbConnection, cmd: String, log: (String) -> Unit): String {
        log("\$ $cmd")
        val stream = conn.open("shell:$cmd")
        val out = StringBuilder()
        try {
            while (!stream.isClosed) {
                val chunk = stream.read() ?: break
                out.append(String(chunk, StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
        }
        try {
            stream.close()
        } catch (_: Exception) {
        }
        return out.toString()
    }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 255 &&
                iface.interfaceSubclass == 66 &&
                iface.interfaceProtocol == 1
            ) {
                return iface
            }
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var hasIn = false
            var hasOut = false
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
            }
            if (hasIn && hasOut) return iface
        }
        return null
    }

    private fun loadOrCreateCrypto(log: (String) -> Unit): AdbCrypto {
        val dir = File(context.filesDir, "adbkey")
        dir.mkdirs()
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        val b64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
        return if (priv.exists() && pub.exists()) {
            log("Loaded existing ADB key")
            AdbCrypto.loadAdbKeyPair(b64, priv, pub)
        } else {
            log("Generating ADB keypair")
            val crypto = AdbCrypto.generateAdbKeyPair(b64)
            crypto.saveAdbKeyPair(priv, pub)
            crypto
        }
    }
}
