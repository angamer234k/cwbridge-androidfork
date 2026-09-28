package com.cwbridge.android

import android.app.Activity
import android.content.pm.PackageManager
import android.view.InputDevice
import android.view.KeyCharacterMap
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

    /**
     * Real key injection through IInputManager as shell uid (Shizuku).
     * The plain `input` shell command often reports exit 0 but games never see meta keys.
     * KeyMapper / Input Leaf use this same path.
     */
    fun injectKeyEventShell(event: KeyEvent): Boolean {
        if (!isReady()) return false
        return try {
            val raw = rikka.shizuku.SystemServiceHelper.getSystemService("input")
                ?: return false.also { LogBuffer.w("Shizuku", "no input service") }
            val wrapped = rikka.shizuku.ShizukuBinderWrapper(raw)
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            val im = asInterface.invoke(null, wrapped) ?: return false
            val inject = im.javaClass.methods.firstOrNull { m ->
                m.name == "injectInputEvent" && m.parameterTypes.size >= 2
            } ?: return false.also { LogBuffer.w("Shizuku", "injectInputEvent missing") }
            // mode 0 = ASYNC, 2 = WAIT_FOR_FINISH
            val result = inject.invoke(im, event, 0)
            val ok = result == null || result == true || result == 0
            if (!ok) LogBuffer.w("Shizuku", "injectInputEvent returned $result")
            ok
        } catch (t: Throwable) {
            LogBuffer.w("Shizuku", "injectKeyEventShell: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Hold Ctrl, press [keyCode], release Ctrl — via IInputManager. */
    fun injectCtrlChordShell(keyCode: Int): Boolean {
        val downTime = android.os.SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun ev(action: Int, code: Int, metaState: Int, whenMs: Long): KeyEvent =
            KeyEvent(
                downTime, whenMs, action, code, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD,
            )
        var t = downTime
        val seq = listOf(
            ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta, t),
            ev(KeyEvent.ACTION_DOWN, keyCode, meta, t + 15),
            ev(KeyEvent.ACTION_UP, keyCode, meta, t + 30),
            ev(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, t + 45),
        )
        for (e in seq) {
            if (!injectKeyEventShell(e)) return false
            try { Thread.sleep(12) } catch (_: InterruptedException) {}
        }
        return true
    }

    /**
     * Ctrl+T:
     *  1) IInputManager chord (real inject as shell)
     *  2) input keycombination fallbacks
     *  3) hold-style shell scripts
     */
    fun pressCtrlT(): Boolean = pressCtrlKey(KeyEvent.KEYCODE_T, "T")

    fun pressCtrlNumber(n: Int): Boolean {
        val key = 7 + n.coerceIn(1, 9)
        return pressCtrlKey(key, n.coerceIn(1, 9).toString())
    }

    private fun pressCtrlKey(keyCode: Int, label: String): Boolean {
        focusRoblox()
        try { Thread.sleep(250) } catch (_: InterruptedException) {}

        // Phase 1: real IInputManager inject (this is what actually reaches games)
        if (injectCtrlChordShell(keyCode)) {
            LogBuffer.i("Shizuku", "Ctrl+$label OK via IInputManager")
            return true
        }
        LogBuffer.w("Shizuku", "Ctrl+$label IInputManager failed — trying input cmds")

        // Phase 2: normal keycombination
        val normal = listOf(
            "cmd input keycombination 113 $keyCode",
            "input keycombination 113 $keyCode",
            "cmd input keycombination 114 $keyCode",
            "input keycombination 114 $keyCode",
        )
        for (cmd in normal) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "Ctrl+$label normal $cmd exit=$code ${out.take(50)}")
            if (code == 0 && outLooksOk(out)) {
                LogBuffer.i("Shizuku", "Ctrl+$label OK (keycombination)")
                return true
            }
        }

        // Phase 3: hold-style
        val hold = listOf(
            "input keyevent --longpress 113; input keyevent $keyCode",
            "cmd input keyevent --longpress 113; cmd input keyevent $keyCode",
        )
        for (cmd in hold) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "Ctrl+$label hold $cmd exit=$code ${out.take(50)}")
            if (code == 0 && outLooksOk(out)) {
                LogBuffer.i("Shizuku", "Ctrl+$label OK (hold script)")
                return true
            }
        }

        LogBuffer.e("Shizuku", "Ctrl+$label FAILED all inject paths")
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
            LogBuffer.i("Shizuku", "focusRoblox $cmd exit=$code ${out.take(50)}")
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
