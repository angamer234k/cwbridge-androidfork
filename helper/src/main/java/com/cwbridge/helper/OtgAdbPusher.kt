package com.cwbridge.helper

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Base64
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cwbridge.helper.adb.UsbStreamPair
import java.io.File
import java.nio.charset.StandardCharsets

/** OTG ADB host: connect to tablet, install/update CWBridge, grant READ_LOGS. */
class OtgAdbPusher(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.cwbridge.helper.USB_PERMISSION"
        const val TARGET_PKG = "com.cwbridge.android.debug"
        const val PERM_LOGS = "android.permission.READ_LOGS"
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

    fun push(apk: File, device: UsbDevice, log: (String) -> Unit) {
        if (!usb.hasPermission(device)) {
            throw IllegalStateException("No USB permission — grant and retry")
        }
        val pair = openAdbStreams(device, log)
        try {
            val crypto = loadOrCreateCrypto(log)
            log("ADB connect… (accept prompt on tablet if shown)")
            val conn = AdbConnection.create(pair.input, pair.output, crypto)
            conn.connect()
            log("ADB connected")

            val installed = shell(conn, "pm path $TARGET_PKG", log).contains("package:")
            log(if (installed) "CWBridge present → update" else "CWBridge missing → install")

            log("Streaming APK via cmd package install (${apk.length()} bytes)…")
            val installOut = execInstall(conn, apk, log)
            log("install: ${installOut.take(300)}")
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
            log("grant: ${grant.ifBlank { "ok (empty output)" }}")

            val path = shell(conn, "pm path $TARGET_PKG", log).trim()
            log("Done. $path")
            conn.close()
        } finally {
            pair.close()
        }
    }

    private fun execInstall(conn: AdbConnection, apk: File, log: (String) -> Unit): String {
        val size = apk.length()
        val stream = conn.open("exec:cmd package install -r -t -S $size")
        apk.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val chunk = if (n == buf.size) buf else buf.copyOf(n)
                stream.write(chunk, true)
            }
        }
        val out = StringBuilder()
        try {
            while (!stream.isClosed) {
                val chunk = stream.read() ?: break
                out.append(String(chunk, StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
        }
        try { stream.close() } catch (_: Exception) {}
        return out.toString()
    }

    private fun shell(conn: AdbConnection, cmd: String, log: (String) -> Unit): String {
        log("$ $cmd")
        val stream = conn.open("shell:$cmd")
        val out = StringBuilder()
        try {
            while (!stream.isClosed) {
                val chunk = stream.read() ?: break
                out.append(String(chunk, StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
        }
        try { stream.close() } catch (_: Exception) {}
        return out.toString()
    }

    private fun openAdbStreams(device: UsbDevice, log: (String) -> Unit): UsbStreamPair {
        val connection = usb.openDevice(device)
            ?: throw IllegalStateException("openDevice failed")
        var adbIface: android.hardware.usb.UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 255 && iface.interfaceSubclass == 66 && iface.interfaceProtocol == 1) {
                adbIface = iface
                break
            }
        }
        if (adbIface == null) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var hasIn = false
                var hasOut = false
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                        if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                    }
                }
                if (hasIn && hasOut) {
                    adbIface = iface
                    break
                }
            }
        }
        val iface = adbIface ?: throw IllegalStateException("No ADB USB interface — is USB debugging on?")
        if (!connection.claimInterface(iface, true)) {
            throw IllegalStateException("claimInterface failed")
        }
        var epIn: android.hardware.usb.UsbEndpoint? = null
        var epOut: android.hardware.usb.UsbEndpoint? = null
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
        }
        if (epIn == null || epOut == null) {
            throw IllegalStateException("Missing bulk endpoints")
        }
        log("USB ADB iface=${iface.id} in=${epIn.address} out=${epOut.address}")
        return UsbStreamPair(connection, iface, epIn, epOut)
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
            log("Generating ADB keypair (one-time)")
            val crypto = AdbCrypto.generateAdbKeyPair(b64)
            crypto.saveAdbKeyPair(priv, pub)
            crypto
        }
    }
}
