#!/usr/bin/env python3
"""Fix Ctrl+T (focus Roblox + real key inject) and diagnose button JS."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def patch_shizuku() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = p.read_text()
    m = re.search(r"    fun pressCtrlT\(\): Boolean \{.*?\n    \}\n\n    fun pressCtrlNumber", t, re.S)
    if not m:
        # try without blank line
        m = re.search(r"    fun pressCtrlT\(\): Boolean \{.*?\n    \}\n    fun pressCtrlNumber", t, re.S)
    if not m:
        raise SystemExit("pressCtrlT block not found")

    repl = r'''    fun pressCtrlT(): Boolean {
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

    fun pressCtrlNumber'''

    t = t[: m.start()] + repl + t[m.end() :]
    # pressCtrlNumber also needs focus for tabs — leave as is
    p.write_text(t)
    print("ShizukuShell pressCtrlT improved")

def patch_tapservice() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/TapService.kt"
    t = p.read_text()
    # Improve injectCtrlChord timing and use WAIT mode when possible
    old = """    fun injectCtrlChord(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val events = listOf(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
        )
        for (e in events) {
            if (!injectKeyEvent(e)) return false
            try { Thread.sleep(8) } catch (_: InterruptedException) {}
        }
        return true
    }"""
    new = """    fun injectCtrlChord(keyCode: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun ev(action: Int, code: Int, metaState: Int, whenMs: Long) =
            KeyEvent(downTime, whenMs, action, code, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
        var t = downTime
        val events = listOf(
            ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta, t),
            ev(KeyEvent.ACTION_DOWN, keyCode, meta, t + 20),
            ev(KeyEvent.ACTION_UP, keyCode, meta, t + 40),
            ev(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, t + 50),
        )
        for (e in events) {
            if (!injectKeyEvent(e)) {
                LogBuffer.w("A11y", "injectCtrlChord failed on key=${e.keyCode} action=${e.action}")
                return false
            }
            try { Thread.sleep(15) } catch (_: InterruptedException) {}
        }
        return true
    }"""
    if old in t:
        t = t.replace(old, new, 1)
        print("injectCtrlChord improved")
    else:
        print("WARN: injectCtrlChord pattern miss")

    # pressCtrlT: try a11y inject even when Shizuku claims success (OEM lie)
    old2 = """    fun pressCtrlT(): Boolean {
        LogBuffer.i("A11y", "pressCtrlT")
        if (ShizukuShell.isReady()) {
            val ok = ShizukuShell.pressCtrlT()
            LogBuffer.i("A11y", "pressCtrlT via Shizuku ok=$ok")
            if (ok) return true
        } else {
            LogBuffer.w("A11y", "Shizuku not ready — ${ShizukuShell.statusLine()}")
        }
        val ok = injectCtrlChord(KeyEvent.KEYCODE_T)
        if (ok) {
            LogBuffer.i("A11y", "pressCtrlT inject ok=true")
            return true
        }
        LogBuffer.w("A11y", "pressCtrlT failed — start Shizuku + grant CWBridge in Shizuku app")
        return false
    }"""
    new2 = """    fun pressCtrlT(): Boolean {
        LogBuffer.i("A11y", "pressCtrlT")
        var shizukuOk = false
        if (ShizukuShell.isReady()) {
            shizukuOk = ShizukuShell.pressCtrlT()
            LogBuffer.i("A11y", "pressCtrlT via Shizuku ok=$shizukuOk")
        } else {
            LogBuffer.w("A11y", "Shizuku not ready — ${ShizukuShell.statusLine()}")
        }
        // Always also try local inject — some OEMs report exit 0 for keycombination but deliver nothing.
        val injectOk = injectCtrlChord(KeyEvent.KEYCODE_T)
        LogBuffer.i("A11y", "pressCtrlT inject ok=$injectOk")
        if (shizukuOk || injectOk) return true
        LogBuffer.w("A11y", "pressCtrlT failed — Roblox focused? Shizuku granted?")
        return false
    }"""
    if old2 in t:
        t = t.replace(old2, new2, 1)
        print("pressCtrlT dual-path")
    else:
        print("WARN: pressCtrlT pattern miss")

    p.write_text(t)

def patch_webui_diagnose() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()
    # Replace diagnose JS with simpler ASCII-only version that also wires the button
    m = re.search(r"async function runDiagnose\(\) \{.*?async function openDomains", t, re.S)
    if not m:
        raise SystemExit("runDiagnose block not found")
    repl = r'''async function runDiagnose() {
  var ul = D('diagOut');
  if (!ul) { alert('diagOut missing'); return; }
  ul.innerHTML = '<li>checking...</li>';
  try {
    var s = await api('/api/diagnose');
    var items = [];
    function row(ok, label, extra) {
      return '<li class="' + (ok ? 'ok' : 'bad') + '">' + (ok ? '[OK] ' : '[FAIL] ') + esc(label)
        + (extra ? ' (' + esc(extra) + ')' : '') + '</li>';
    }
    items.push(row(!!s.a11yBound, 'Accessibility bound', s.a11yListed && !s.a11yBound ? 'listed but not bound' : ''));
    items.push(row(!!s.shizukuReady, 'Shizuku ready', s.shizuku || ''));
    items.push(row(true, 'Screenshot: ' + (s.screenshot || ''), ''));
    items.push(row(true, 'Android SDK ' + s.androidSdk, ''));
    (s.issues || []).forEach(function(i){ items.push('<li class="bad">- ' + esc(i) + '</li>'); });
    if (!s.issues || !s.issues.length) items.push('<li class="ok">- no blocking issues</li>');
    ul.innerHTML = items.join('');
  } catch (e) {
    ul.innerHTML = '<li class="bad">' + esc(e.message || e) + '</li>';
  }
}
async function openDomains'''
    t = t[: m.start()] + repl + t[m.end() :]

    # Also change button to use addEventListener-friendly id
    t = t.replace(
        '<button onclick="runDiagnose()">Diagnose issues</button>',
        '<button type="button" id="btnDiagnose" onclick="runDiagnose();return false;">Diagnose issues</button>',
        1,
    )
    p.write_text(t)
    print("diagnose JS fixed")

def patch_control_message() -> None:
    """Return more detail from ctrl-t API."""
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    old = '''            "ctrl-t" -> {
                val ok = when {
                    ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                    svc != null -> svc.pressCtrlT()
                    else -> false
                }
                if (ok) "Ctrl+T sent" else "Ctrl+T failed"
            }'''
    new = '''            "ctrl-t" -> {
                val ok = when {
                    svc != null -> svc.pressCtrlT()
                    ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                    else -> false
                }
                if (ok) "Ctrl+T sent (focus Roblox first if nothing happened)"
                else "Ctrl+T failed — open Roblox, grant Shizuku, check console logs"
            }'''
    if old in t:
        t = t.replace(old, new, 1)
        p.write_text(t)
        print("ctrl-t API message")
    else:
        print("WARN: ctrl-t block miss")

def main() -> None:
    patch_shizuku()
    patch_tapservice()
    patch_webui_diagnose()
    patch_control_message()
    print("done")

if __name__ == "__main__":
    main()
