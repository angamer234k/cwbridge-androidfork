#!/usr/bin/env python3
"""AI paste auto-presses Enter after pasting the model reply."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
t = p.read_text()

old_paste = """    private suspend fun pasteIntoGame(text: String) {
        val svc = TapService.instance ?: return
        setClipboard(text)
        svc.clickAtPercent(focusXPct, focusYPct)
        delay(1000)
        svc.pasteClipboard()
        delay(400)
        if (submitXPx > 0f || submitYPx > 0f) {
            svc.clickAt(submitXPx, submitYPx)
        }
    }"""

new_paste = """    private suspend fun pasteIntoGame(text: String, pressEnter: Boolean = false) {
        val svc = TapService.instance
        setClipboard(text)
        if (svc != null) {
            svc.clickAtPercent(focusXPct, focusYPct)
            delay(1000)
            svc.pasteClipboard()
            delay(400)
            if (submitXPx > 0f || submitYPx > 0f) {
                svc.clickAt(submitXPx, submitYPx)
                delay(200)
            }
            if (pressEnter) {
                val ok = svc.pressEnter()
                LogBuffer.i("Invoke", "pasteEnter a11y=$ok")
            }
        } else if (ShizukuShell.isReady()) {
            ShizukuShell.exec("input keyevent KEYCODE_PASTE")
            delay(400)
            if (pressEnter) {
                val ok = ShizukuShell.pressEnter()
                LogBuffer.i("Invoke", "pasteEnter shizuku=$ok")
            }
        } else {
            LogBuffer.w("Invoke", "pasteIntoGame: no TapService/Shizuku")
        }
    }"""

if old_paste in t:
    t = t.replace(old_paste, new_paste, 1)
    print("pasteIntoGame: pressEnter param")
elif "pressEnter: Boolean = false" in t:
    print("pasteIntoGame already has pressEnter")
else:
    raise SystemExit("pasteIntoGame block not found")

old_ai_paste = "pasteIntoGame(text)\n                    replyOk(\"ai\", text.take(500))"
new_ai_paste = "pasteIntoGame(text, pressEnter = true)\n                    replyOk(\"ai\", text.take(500))"
if old_ai_paste in t:
    t = t.replace(old_ai_paste, new_ai_paste, 1)
    print("AI: auto Enter after paste")
elif "pasteIntoGame(text, pressEnter = true)" in t:
    print("AI already auto-Enter")
else:
    idx = t.find('"ai" ->')
    if idx < 0:
        raise SystemExit("ai handler missing")
    chunk = t[idx:idx+2000]
    if "pasteIntoGame(text)" in chunk and "pressEnter = true" not in chunk:
        t = t[:idx] + chunk.replace("pasteIntoGame(text)", "pasteIntoGame(text, pressEnter = true)", 1) + t[idx+2000:]
        print("AI: auto Enter (broad)")
    else:
        print("AI paste replace miss")

p.write_text(t)
print("InvokeEngine", p.stat().st_size)
print("hotfix ai-enter OK")
