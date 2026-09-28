package com.cwbridge.android

import android.app.Activity
import android.content.pm.PackageManager
import android.view.KeyEvent
import com.cwbridge.android.bridge.LogBuffer
import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku

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
                ?: return -1 to "newProcess null — Shizuku API blocked? grant CWBridge + restart Shizuku"
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
        // Bring Roblox to front — keys go nowhere if another app is focused.
        focusRoblox()
        try { Thread.sleep(350) } catch (_: InterruptedException) {}

        // keycombination is the real chord. Exit 0 is not always trustworthy on OEMs,
        // so try several forms and always report what ran.
        val cmds = listOf(
            "cmd input keycombination 113 48",
            "input keycombination 113 48",
            "cmd input keycombination 114 48",
            "input keycombination 114 48",
            // Some builds want symbolic names (ignored if unsupported)
            "cmd input keycombination KEYCODE_CTRL_LEFT KEYCODE_T",
            "input keycombination KEYCODE_CTRL_LEFT KEYCODE_T",
        )
        var anyZero = false
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "pressCtrlT cmd=$cmd exit=$code ${out.take(100)}")
            if (code == 0) anyZero = true
        }
        // Fallback: short Ctrl hold via two keyevents with no gap (best-effort)
        if (!anyZero) {
            val (c1, o1) = exec("input keyevent KEYCODE_CTRL_LEFT")
            LogBuffer.i("Shizuku", "fallback CTRL exit=$c1 $o1")
            val (c2, o2) = exec("input keyevent KEYCODE_T")
            LogBuffer.i("Shizuku", "fallback T exit=$c2 $o2")
            anyZero = c1 == 0 && c2 == 0
        }
        return anyZero
    }

    fun focusRoblox(packageName: String = "com.roblox.client") {
        val cmds = listOf(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName",
        )
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "focusRoblox cmd=$cmd exit=$code ${out.take(80)}")
            if (code == 0) return
        }
    }

    fun pressCtrlNumber(n: Int): Boolean {
        val key = 7 + n.coerceIn(1, 9)
        val cmds = listOf(
            "input keycombination 113 $key",
            "input keycombination 114 $key",
        )
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "pressCtrl+$n cmd=$cmd exit=$code ${out.take(60)}")
            if (code == 0) return true
        }
        return false
    }


    fun pressEnter(): Boolean {
        val enter = KeyEvent.KEYCODE_ENTER
        val attempts = listOf(
            "input keyevent $enter",
            "cmd input keyevent $enter",
            "input keyevent 66",
            "cmd input keyevent 66",
            "toybox input keyevent 66",
            "input keyevent KEYCODE_ENTER",
        )
        for (cmd in attempts) {
            LogBuffer.i("Shizuku", "try: $cmd")
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "  exit=$code out=${out.take(80).ifBlank { "(empty)" }}")
            if (code == 0) {
                LogBuffer.i("Shizuku", "Enter OK via $cmd")
                return true
            }
        }
        LogBuffer.w("Shizuku", "all Enter strategies failed — ${statusLine()}")
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
