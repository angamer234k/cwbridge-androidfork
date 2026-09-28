#!/usr/bin/env python3
"""Ctrl chord: 1) normal key events 2) hold Ctrl then key 3) hard fail."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

CTRL_BLOCK = r'''
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

'''

def patch_shizuku() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = p.read_text()
    # Replace from pressCtrlT through end of pressCtrlNumber (old)
    start = t.find("    fun pressCtrlT()")
    if start < 0:
        raise SystemExit("pressCtrlT missing")
    # Find pressEnter after pressCtrlNumber
    end = t.find("    fun pressEnter()", start)
    if end < 0:
        raise SystemExit("pressEnter missing")
    # Keep focusRoblox if it sits between — move it before our block if needed
    focus = ""
    fm = re.search(r"    fun focusRoblox\(.*?\n    \}\n", t[start:end], re.S)
    if fm:
        focus = fm.group(0) + "\n"
    # Also grab focus if it's before pressCtrlT
    if "fun focusRoblox" not in focus:
        fm2 = re.search(r"    fun focusRoblox\(.*?\n    \}\n\n", t, re.S)
        if fm2 and fm2.start() < start:
            focus = ""  # already outside, leave it

    new = CTRL_BLOCK
    if "fun focusRoblox" not in t[:start] and "fun focusRoblox" not in new:
        new = new + '''    fun focusRoblox(packageName: String = "com.roblox.client") {
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

'''

    # Remove old focusRoblox from the replaced region by not including it
    t2 = t[:start] + new + t[end:]
    # Dedupe focusRoblox if now duplicated
    parts = t2.split("    fun focusRoblox")
    if len(parts) > 2:
        # keep first only
        first = parts[0] + "    fun focusRoblox" + parts[1]
        # strip subsequent full functions
        rest = "".join(parts[2:])
        # rest starts mid-function — find next fun at class level
        m = re.search(r"\n    fun ", rest)
        if m:
            rest = rest[m.start()+1:]
            t2 = first + rest
        else:
            t2 = first
        print("deduped focusRoblox")
    p.write_text(t2)
    print("ShizukuShell Ctrl sequence updated")

def patch_tapservice() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/TapService.kt"
    t = p.read_text()
    # pressCtrlT: phase1 shizuku normal+hold, phase2 local hold inject, else fail
    old = None
    m = re.search(r"    fun pressCtrlT\(\): Boolean \{.*?\n    \}\n", t, re.S)
    if not m:
        raise SystemExit("TapService.pressCtrlT missing")
    new = '''    fun pressCtrlT(): Boolean {
        LogBuffer.i("A11y", "pressCtrlT")
        if (ShizukuShell.isReady()) {
            if (ShizukuShell.pressCtrlT()) {
                LogBuffer.i("A11y", "pressCtrlT Shizuku OK")
                return true
            }
            LogBuffer.w("A11y", "pressCtrlT Shizuku exhausted normal+hold")
        } else {
            LogBuffer.w("A11y", "Shizuku not ready — ${ShizukuShell.statusLine()}")
        }
        // Local hold: Ctrl DOWN, T DOWN/UP, Ctrl UP
        if (injectCtrlChord(KeyEvent.KEYCODE_T)) {
            LogBuffer.i("A11y", "pressCtrlT local hold inject OK")
            return true
        }
        LogBuffer.e("A11y", "pressCtrlT FAILED")
        return false
    }

'''
    t = t[: m.start()] + new + t[m.end() :]
    p.write_text(t)
    print("TapService.pressCtrlT updated")

def patch_api_errors() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    old = '''            "ctrl-t" -> {
                val ok = when {
                    svc != null -> svc.pressCtrlT()
                    ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                    else -> false
                }
                if (ok) "Ctrl+T sent (focus Roblox first if nothing happened)"
                else "Ctrl+T failed — open Roblox, grant Shizuku, check console logs"
            }'''
    new = '''            "ctrl-t" -> {
                val ok = when {
                    svc != null -> svc.pressCtrlT()
                    ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                    else -> false
                }
                if (ok) "Ctrl+T sent"
                else "ERROR: Ctrl+T failed after normal keys + hold-Ctrl methods — is Roblox focused? Shizuku granted?"
            }'''
    if old in t:
        t = t.replace(old, new, 1)
        print("api ctrl-t error text")
    else:
        # looser replace
        t2, n = re.subn(
            r'"ctrl-t" -> \{.*?\n            \}',
            '''"ctrl-t" -> {
                val ok = when {
                    svc != null -> svc.pressCtrlT()
                    ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                    else -> false
                }
                if (ok) "Ctrl+T sent"
                else "ERROR: Ctrl+T failed after normal keys + hold-Ctrl methods — is Roblox focused? Shizuku granted?"
            }''',
            t,
            count=1,
            flags=re.S,
        )
        if n:
            t = t2
            print("api ctrl-t error text (regex)")
        else:
            print("WARN: ctrl-t block not found")
    p.write_text(t)

def main() -> None:
    patch_shizuku()
    patch_tapservice()
    patch_api_errors()
    print("done")

if __name__ == "__main__":
    main()
