package com.cwbridge.android

import android.app.Activity
import android.content.pm.PackageManager
import android.view.KeyEvent
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Privileged shell via Shizuku (ADB/shell uid).
 * Ctrl+T tries several input strategies because OEMs differ.
 */
object ShizukuShell {

    private const val PERM_REQ = 0xCB01

    @Volatile
    private var listenersHooked = false

    fun ensureListeners() {
        if (listenersHooked) return
        listenersHooked = true
        try {
            Shizuku.addBinderReceivedListenerSticky {
                LogBuffer.i("Shizuku", "binder connected (service running)")
            }
            Shizuku.addBinderDeadListener {
                LogBuffer.w("Shizuku", "binder dead — restart Shizuku after reboot")
            }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode != PERM_REQ) return@addRequestPermissionResultListener
                val ok = grantResult == PackageManager.PERMISSION_GRANTED
                LogBuffer.i("Shizuku", "permission ${if (ok) "GRANTED" else "DENIED"}")
            }
        } catch (t: Throwable) {
            LogBuffer.w("Shizuku", "listeners: ${t.message}")
        }
    }

    fun isServiceRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isServiceRunning() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun isReady(): Boolean = hasPermission()

    fun statusLine(): String = when {
        !isServiceRunning() -> "Shizuku: OFF — Helper→Start Shizuku or open Shizuku app"
        !hasPermission() -> "Shizuku: ON but CWBridge not allowed — open Shizuku → Apps"
        else -> "Shizuku: ready"
    }

    fun requestPermissionIfNeeded(activity: Activity? = null) {
        ensureListeners()
        try {
            if (!isServiceRunning()) {
                LogBuffer.w("Shizuku", "cannot request perm — service not running")
                return
            }
            if (hasPermission()) {
                LogBuffer.i("Shizuku", "already granted")
                return
            }
            Shizuku.requestPermission(PERM_REQ)
            LogBuffer.i("Shizuku", "permission dialog requested")
        } catch (t: Throwable) {
            LogBuffer.w("Shizuku", "requestPermission: ${t.message}")
        }
    }

    fun exec(command: String): Pair<Int, String> {
        ensureListeners()
        if (!isReady()) {
            return -1 to "Shizuku not ready (${statusLine()})"
        }
        return try {
            val process = newProcess(arrayOf("sh", "-c", command))
                ?: return -1 to "newProcess null — Shizuku API blocked?"
            val out = StringBuilder()
            val readerThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            out.appendLine(line)
                        }
                    }
                } catch (_: Throwable) {
                }
            }.also { it.isDaemon = true; it.start() }
            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            out.appendLine(line)
                        }
                    }
                } catch (_: Throwable) {
                }
            }.also { it.isDaemon = true; it.start() }
            val code = try {
                process.waitFor()
            } catch (_: Throwable) {
                -1
            }
            try {
                readerThread.join(2000)
                errThread.join(500)
            } catch (_: Throwable) {
            }
            try {
                process.destroy()
            } catch (_: Throwable) {
            }
            code to out.toString().trim().take(2000)
        } catch (t: Throwable) {
            -1 to "exec failed: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun pressCtrlT(): Boolean {
        val ctrl = KeyEvent.KEYCODE_CTRL_LEFT
        val t = KeyEvent.KEYCODE_T
        val attempts = listOf(
            "input keycombination $ctrl $t",
            "cmd input keycombination $ctrl $t",
            "input keyevent $ctrl $t",
        )
        for (cmd in attempts) {
            LogBuffer.i("Shizuku", "try: $cmd")
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "  exit=$code out=${out.take(100).ifBlank { "(empty)" }}")
            if (code == 0) {
                LogBuffer.i("Shizuku", "Ctrl+T OK via: $cmd")
                return true
            }
        }
        LogBuffer.w("Shizuku", "all Ctrl+T strategies failed — ${statusLine()}")
        return false
    }

    fun pressEnter(): Boolean {
        for (cmd in listOf(
            "input keyevent ${KeyEvent.KEYCODE_ENTER}",
            "cmd input keyevent ${KeyEvent.KEYCODE_ENTER}",
        )) {
            val (code, _) = exec(cmd)
            if (code == 0) {
                LogBuffer.i("Shizuku", "Enter OK via $cmd")
                return true
            }
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(" ", "%s")
            .replace("'", "\\'")
        val (code, out) = exec("input text \"$escaped\"")
        LogBuffer.i("Shizuku", "input text exit=$code ${out.take(80)}")
        return code == 0
    }

    private fun newProcess(cmd: Array<String>): Process? {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            m.invoke(null, cmd, null, null) as? Process
        } catch (t: Throwable) {
            LogBuffer.w("Shizuku", "newProcess: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }
}
