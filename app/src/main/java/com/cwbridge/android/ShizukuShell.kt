package com.cwbridge.android

import android.app.Activity
import android.content.pm.PackageManager
import android.view.KeyEvent
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Privileged shell via Shizuku (ADB/shell uid).
 * Used for Ctrl+T and other key combos OEMs block for normal apps.
 *
 * Flow:
 * 1. Install Shizuku on this device
 * 2. Helper (OTG) → Start Shizuku  OR  start from Shizuku app
 * 3. CWBridge → grant Shizuku permission when prompted
 * 4. Ctrl+T / openTab via: input keycombination
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
        !isServiceRunning() -> "Shizuku: not running (Helper → Start Shizuku)"
        !hasPermission() -> "Shizuku: running, need permission"
        else -> "Shizuku: ready (privileged shell)"
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
                ?: return -1 to "newProcess returned null"
            val out = StringBuilder()
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        out.appendLine(line)
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        out.appendLine(line)
                    }
                }
            } catch (_: Throwable) {
            }
            val code = try {
                process.waitFor()
            } catch (_: Throwable) {
                -1
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
        val primary = exec("input keycombination ${KeyEvent.KEYCODE_CTRL_LEFT} ${KeyEvent.KEYCODE_T}")
        if (primary.first == 0) {
            LogBuffer.i("Shizuku", "Ctrl+T keycombination ok")
            return true
        }
        LogBuffer.w("Shizuku", "keycombination failed (${primary.first}): ${primary.second.take(120)}")
        val fallback = exec(
            "input keyevent ${KeyEvent.KEYCODE_CTRL_LEFT} ${KeyEvent.KEYCODE_T}",
        )
        val ok = fallback.first == 0
        LogBuffer.i("Shizuku", "Ctrl+T keyevent fallback ok=$ok out=${fallback.second.take(80)}")
        return ok
    }

    fun pressEnter(): Boolean {
        val r = exec("input keyevent ${KeyEvent.KEYCODE_ENTER}")
        LogBuffer.i("Shizuku", "Enter ok=${r.first == 0}")
        return r.first == 0
    }

    fun inputText(text: String): Boolean {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(" ", "%s")
            .replace("'", "\\'")
        val r = exec("input text \"$escaped\"")
        return r.first == 0
    }

    private fun newProcess(cmd: Array<String>): Process? {
        return try {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
                .invoke(null, cmd, null, null) as? Process
        } catch (t: Throwable) {
            LogBuffer.w("Shizuku", "newProcess: ${t.message}")
            null
        }
    }
}
