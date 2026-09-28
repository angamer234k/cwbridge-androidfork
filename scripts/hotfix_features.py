#!/usr/bin/env python3
"""Inject Ctrl chords via Shizuku IInputManager (shell uid). Fallback: tap + button."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

SHIZUKU_INJECT = r'''
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

'''

def patch_shizuku() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = p.read_text()

    # Ensure imports
    if "import android.view.InputDevice" not in t:
        t = t.replace(
            "import android.view.KeyEvent\n",
            "import android.view.InputDevice\nimport android.view.KeyCharacterMap\nimport android.view.KeyEvent\n",
            1,
        )

    start = t.find("    fun injectKeyEventShell")
    if start < 0:
        start = t.find("    fun pressCtrlT")
    if start < 0:
        raise SystemExit("no insert point")
    end = t.find("    fun pressEnter", start)
    if end < 0:
        raise SystemExit("pressEnter missing")

    t = t[:start] + SHIZUKU_INJECT + t[end:]
    # dedupe focusRoblox
    while t.count("fun focusRoblox") > 1:
        # remove second occurrence
        first = t.find("    fun focusRoblox")
        second = t.find("    fun focusRoblox", first + 1)
        if second < 0:
            break
        # find end of second function
        m = re.search(r"\n    fun ", t[second + 10:])
        if not m:
            break
        end2 = second + 10 + m.start() + 1
        t = t[:second] + t[end2:]
        print("removed dup focusRoblox")

    p.write_text(t)
    print("ShizukuShell IInputManager inject installed")

def patch_bridge_opentab() -> None:
    """If Ctrl+T fails, tap the CatWeb + button by percent coords."""
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()
    if "newTabFallback" in t:
        print("newTabFallback already present")
        return

    # Replace openDomains body logic for ctrl failure path
    old = '''            val ctrl = when {
                ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                svc != null -> svc.pressCtrlT()
                else -> false
            }
            if (!ctrl) {
                results += "$d: Ctrl+T failed"
                continue
            }'''
    new = '''            var ctrl = when {
                ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                svc != null -> svc.pressCtrlT()
                else -> false
            }
            if (!ctrl) {
                // CatWeb shows a "+" on the tab bar — tap it (default top-right)
                val plusX = 92f
                val plusY = 4f
                val tapped = svc?.clickAtPercent(plusX, plusY) == true
                LogBuffer.w("Control", "Ctrl+T failed — + button tap @$plusX,$plusY ok=$tapped")
                if (!tapped) {
                    results += "$d: Ctrl+T and + tap failed"
                    continue
                }
                ctrl = true
            }'''
    if old in t:
        t = t.replace(old, new, 1)
        p.write_text(t)
        print("openDomains + fallback")
    else:
        print("WARN: openDomains ctrl block not found")

def patch_tapservice() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/TapService.kt"
    t = p.read_text()
    m = re.search(r"    fun pressCtrlT\(\): Boolean \{.*?\n    \}\n", t, re.S)
    if not m:
        print("WARN: pressCtrlT missing in TapService")
        return
    new = '''    fun pressCtrlT(): Boolean {
        LogBuffer.i("A11y", "pressCtrlT")
        if (ShizukuShell.isReady() && ShizukuShell.pressCtrlT()) {
            LogBuffer.i("A11y", "pressCtrlT via Shizuku IInputManager/cmds OK")
            return true
        }
        if (injectCtrlChord(KeyEvent.KEYCODE_T)) {
            LogBuffer.i("A11y", "pressCtrlT local inject OK")
            return true
        }
        LogBuffer.e("A11y", "pressCtrlT FAILED")
        return false
    }

'''
    t = t[: m.start()] + new + t[m.end() :]
    p.write_text(t)
    print("TapService updated")

def main() -> None:
    patch_shizuku()
    patch_tapservice()
    patch_bridge_opentab()
    print("done")

if __name__ == "__main__":
    main()
