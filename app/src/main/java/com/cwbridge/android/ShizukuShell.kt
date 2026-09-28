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


    /**
     * Ctrl+T sequence:
     *  1) normal keycombination / keyevent chords
     *  2) hold Ctrl (DOWN), press T, release Ctrl
     *  3) fail — caller shows the error
     */
    fun pressCtrlT(): Boolean = pressCtrlKey(KeyEvent.KEYCODE_T, "T")

    /** Ctrl+1..9 — same sequence as Ctrl+T. */
    fun pressCtrlNumber(n: Int): Boolean {
        val key = 7 + n.coerceIn(1, 9) // KEYCODE_0=7, KEYCODE_1=8
        return pressCtrlKey(key, n.coerceIn(1, 9).toString())
    }

    private fun pressCtrlKey(keyCode: Int, label: String): Boolean {
        focusRoblox()
        try { Thread.sleep(300) } catch (_: InterruptedException) {}

        // --- phase 1: normal chord key events ---
        val normal = listOf(
            "cmd input keycombination 113 $keyCode",
            "input keycombination 113 $keyCode",
            "cmd input keycombination 114 $keyCode",
            "input keycombination 114 $keyCode",
            "cmd input keycombination KEYCODE_CTRL_LEFT $keyCode",
            "input keycombination KEYCODE_CTRL_LEFT $keyCode",
        )
        for (cmd in normal) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "Ctrl+$label normal: $cmd exit=$code ${out.take(60)}")
            if (code == 0 && outLooksOk(out)) {
                LogBuffer.i("Shizuku", "Ctrl+$label OK (normal) via $cmd")
                return true
            }
        }

        // --- phase 2: hold Ctrl, press key, release Ctrl ---
        // input cannot truly "hold" across processes, so we chain DOWN-ish longpress
        // then the key, then an explicit Ctrl up where supported.
        val holdScripts = listOf(
            // longpress Ctrl then key (best-effort hold)
            "input keyevent --longpress 113; input keyevent $keyCode",
            "cmd input keyevent --longpress 113; cmd input keyevent $keyCode",
            "input keyevent --longpress KEYCODE_CTRL_LEFT; input keyevent $keyCode",
            // background Ctrl longpress overlapping the key
            "input keyevent --longpress 113 & sleep 0.05; input keyevent $keyCode; wait",
            "cmd input keyevent --longpress 113 & sleep 0.05; cmd input keyevent $keyCode; wait",
        )
        for (cmd in holdScripts) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "Ctrl+$label hold: $cmd exit=$code ${out.take(60)}")
            if (code == 0 && outLooksOk(out)) {
                LogBuffer.i("Shizuku", "Ctrl+$label OK (hold) via $cmd")
                return true
            }
        }

        LogBuffer.e("Shizuku", "Ctrl+$label FAILED — all normal + hold methods exhausted")
        return false
    }

    private fun outLooksOk(out: String): Boolean {
        if (out.isBlank()) return true
        val bad = listOf("Error", "Unknown", "not found", "No such", "Exception", "denied")
        return bad.none { out.contains(it, ignoreCase = true) }
    }

    fun focusRoblox(packageName: String = "com.roblox.client") {
        val cmds = listOf(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName",
        )
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "focusRoblox $cmd exit=$code ${out.take(60)}")
            if (code == 0) return
        }
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
