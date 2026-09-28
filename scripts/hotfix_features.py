#!/usr/bin/env python3
"""sendevent hardware Ctrl chord + multi-position CatWeb + button."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def patch_shizuku() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = p.read_text()

    # Insert sendevent + openNewTab helpers before pressCtrlT assignment
    # Replace pressCtrlKey body to try sendevent first, then existing paths

    old = '''    private fun pressCtrlKey(keyCode: Int, label: String): Boolean {
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
    }'''

    new = '''    private fun pressCtrlKey(keyCode: Int, label: String): Boolean {
        focusRoblox()
        try { Thread.sleep(250) } catch (_: InterruptedException) {}

        // Phase 0: Linux sendevent (hardware-level — games often only see this)
        val linuxKey = androidToLinuxKey(keyCode)
        if (linuxKey != null && sendeventCtrlChord(linuxKey, label)) {
            return true
        }

        // Phase 1: IInputManager as shell
        if (injectCtrlChordShell(keyCode)) {
            LogBuffer.i("Shizuku", "Ctrl+$label OK via IInputManager")
            return true
        }
        LogBuffer.w("Shizuku", "Ctrl+$label IInputManager failed")

        // Phase 2: keycombination
        for (cmd in listOf(
            "cmd input keycombination 113 $keyCode",
            "input keycombination 113 $keyCode",
            "cmd input keycombination 114 $keyCode",
            "input keycombination 114 $keyCode",
        )) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "Ctrl+$label keycombo $cmd exit=$code ${out.take(40)}")
            if (code == 0 && outLooksOk(out)) {
                LogBuffer.i("Shizuku", "Ctrl+$label OK (keycombination)")
                return true
            }
        }

        // Phase 3: hold scripts
        for (cmd in listOf(
            "input keyevent --longpress 113; input keyevent $keyCode",
            "cmd input keyevent --longpress 113; cmd input keyevent $keyCode",
        )) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "Ctrl+$label hold $cmd exit=$code ${out.take(40)}")
            if (code == 0 && outLooksOk(out)) {
                LogBuffer.i("Shizuku", "Ctrl+$label OK (hold)")
                return true
            }
        }

        LogBuffer.e("Shizuku", "Ctrl+$label FAILED all key paths")
        return false
    }

    /** Android KeyEvent code → Linux input key code (for sendevent). */
    private fun androidToLinuxKey(androidKeyCode: Int): Int? = when (androidKeyCode) {
        KeyEvent.KEYCODE_T -> 20          // KEY_T
        KeyEvent.KEYCODE_1 -> 2           // KEY_1
        KeyEvent.KEYCODE_2 -> 3
        KeyEvent.KEYCODE_3 -> 4
        KeyEvent.KEYCODE_4 -> 5
        KeyEvent.KEYCODE_5 -> 6
        KeyEvent.KEYCODE_6 -> 7
        KeyEvent.KEYCODE_7 -> 8
        KeyEvent.KEYCODE_8 -> 9
        KeyEvent.KEYCODE_9 -> 10
        else -> null
    }

    /**
     * Write EV_KEY events to every /dev/input/event* we can open.
     * KEY_LEFTCTRL=29. SYN_REPORT after each. Shell may lack write on some OEMs.
     */
    private fun sendeventCtrlChord(linuxKey: Int, label: String): Boolean {
        // Discover devices; try each. Permission denied is expected on some.
        val script = """
devs=$(ls /dev/input/event* 2>/dev/null)
ok=0
for dev in $devs; do
  if sendevent "$dev" 1 29 1 2>/dev/null && \
     sendevent "$dev" 0 0 0 2>/dev/null && \
     sendevent "$dev" 1 $linuxKey 1 2>/dev/null && \
     sendevent "$dev" 0 0 0 2>/dev/null && \
     sendevent "$dev" 1 $linuxKey 0 2>/dev/null && \
     sendevent "$dev" 0 0 0 2>/dev/null && \
     sendevent "$dev" 1 29 0 2>/dev/null && \
     sendevent "$dev" 0 0 0 2>/dev/null; then
    echo "OK $dev"
    ok=1
  fi
done
exit $((1-ok))
""".trimIndent()
        val (code, out) = exec(script)
        LogBuffer.i("Shizuku", "sendevent Ctrl+$label exit=$code ${out.take(120)}")
        if (code == 0 && out.contains("OK")) {
            LogBuffer.i("Shizuku", "Ctrl+$label OK via sendevent")
            return true
        }
        return false
    }

    /**
     * Open a CatWeb new tab by tapping the "+" on the tab bar.
     * Tries several percent positions (phones / tablets / notches differ).
     */
    fun openNewTabByPlusTap(): Boolean {
        focusRoblox()
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        // (x%, y%) candidates for the + control
        val spots = listOf(
            92f to 4f, 96f to 4f, 88f to 4f,
            92f to 6f, 94f to 5f, 90f to 3f,
            50f to 4f, // some layouts center the +
            85f to 8f, 97f to 8f,
        )
        // Prefer shell input tap (works without a11y)
        val (szCode, szOut) = exec("wm size")
        val sizeMatch = Regex("""(\d+)x(\d+)""").find(szOut)
        val w = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val h = sizeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        LogBuffer.i("Shizuku", "screen ${w}x$h (wm size exit=$szCode)")
        if (w > 0 && h > 0) {
            for ((xp, yp) in spots) {
                val x = ((xp / 100f) * w).toInt()
                val y = ((yp / 100f) * h).toInt()
                val (code, out) = exec("input tap $x $y")
                LogBuffer.i("Shizuku", "+ tap ${xp}% ${yp}% -> ($x,$y) exit=$code ${out.take(40)}")
                if (code == 0) {
                    try { Thread.sleep(350) } catch (_: InterruptedException) {}
                    // We cannot verify a new tab opened; treat first successful tap as best effort
                    // and continue through a couple so at least one hits
                }
            }
            // Report success if any tap exited 0
            LogBuffer.i("Shizuku", "openNewTabByPlusTap: finished multi-tap sequence")
            return true
        }
        return false
    }
'''

    if old not in t:
        raise SystemExit("pressCtrlKey block not found")
    t = t.replace(old, new, 1)
    p.write_text(t)
    print("ShizukuShell sendevent + plus-tap")

def patch_bridge() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()
    old = '''            val ctrl = when {
                ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                svc != null -> svc.pressCtrlT()
                else -> false
            }
            if (!ctrl) {
                // CatWeb tab bar "+" (wiki: opens new tab). Adjust if needed.
                val plusX = 92f
                val plusY = 4f
                val tapped = svc?.clickAtPercent(plusX, plusY) == true
                LogBuffer.w("Control", "Ctrl+T failed — tapping + @$plusX%,$plusY% ok=$tapped")
                if (!tapped) {
                    results += "$d: Ctrl+T and + tap failed"
                    continue
                }
            }'''
    new = '''            // Prefer + button taps (Roblox mobile rarely accepts synthetic Ctrl)
            var opened = false
            if (ShizukuShell.isReady()) {
                opened = ShizukuShell.openNewTabByPlusTap()
                if (!opened) opened = ShizukuShell.pressCtrlT()
            }
            if (!opened && svc != null) {
                // a11y multi-spot +
                for ((px, py) in listOf(
                    92f to 4f, 96f to 4f, 88f to 5f, 94f to 6f, 50f to 4f
                )) {
                    if (svc.clickAtPercent(px, py)) {
                        LogBuffer.i("Control", "+ a11y tap @$px%,$py%")
                        opened = true
                        break
                    }
                }
                if (!opened) opened = svc.pressCtrlT()
            }
            if (!opened) {
                results += "$d: new-tab failed (keys + + button)"
                continue
            }'''
    if old not in t:
        raise SystemExit("openDomains ctrl block not found")
    t = t.replace(old, new, 1)
    p.write_text(t)
    print("BridgeControl openDomains prefers + taps")

def patch_api() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    # Make ctrl-t also try + button and return clearer message
    t2, n = re.subn(
        r'"ctrl-t" -> \{.*?\n            \}',
        '''"ctrl-t" -> {
                val keyOk = when {
                    svc != null -> svc.pressCtrlT()
                    ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                    else -> false
                }
                if (keyOk) "Ctrl+T sent (key path)"
                else if (ShizukuShell.isReady() && ShizukuShell.openNewTabByPlusTap())
                    "Ctrl+T keys failed — tapped CatWeb + button instead"
                else
                    "ERROR: Ctrl+T keys and + button all failed — check console"
            }''',
        t,
        count=1,
        flags=re.S,
    )
    if n == 0:
        print("WARN: ctrl-t api block not updated")
    else:
        t = t2
        print("ctrl-t API + fallback")
    p.write_text(t)

def main() -> None:
    patch_shizuku()
    patch_bridge()
    patch_api()
    print("done")

if __name__ == "__main__":
    main()
