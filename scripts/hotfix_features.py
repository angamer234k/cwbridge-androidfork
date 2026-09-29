#!/usr/bin/env python3
from pathlib import Path
import re

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"

def main() -> None:
    t = P.read_text()

    if "import com.cwbridge.android.data.DatastoreRateLimit" not in t:
        t = t.replace(
            "import com.cwbridge.android.data.Store",
            "import com.cwbridge.android.data.DatastoreRateLimit\nimport com.cwbridge.android.data.Store",
            1,
        )

    # Replace entire "save" -> { ... } arm via regex (non-greedy until next "xxx" ->)
    save_new = '''            "save" -> {
                // save.<key>.<value>[.<domain.rbx>]
                if (data1.isEmpty()) {
                    replyErr("save", "need save.key.value[.domain.rbx]")
                    return
                }
                val (value, domain) = splitOptionalDomain(data2)
                val rl = DatastoreRateLimit.checkAndConsume(context, domain)
                if (rl != null) {
                    replyErr("save", rl)
                    return
                }
                val result = store.save(domain, data1, value)
                result.fold(
                    onSuccess = { replyOk("save", "$data1 → $domain (${value.length} chars)") },
                    onFailure = { replyErr("save", it.message ?: "fail") },
                )
            }'''

    load_new = '''            "load" -> {
                // load.<key>.<domain.rbx> — pastes actual key value into the game
                if (data1.isEmpty() || data2.isEmpty()) {
                    replyErr("load", "need load.key.domain (domain like weather.rbx)")
                    return
                }
                val domain = data2.trim()
                val rl = DatastoreRateLimit.checkAndConsume(context, domain)
                if (rl != null) {
                    replyErr("load", rl)
                    return
                }
                val result = store.load(domain, data1)
                result.fold(
                    onSuccess = { value ->
                        pasteIntoGame(value)
                        replyOk("load", value.take(500))
                    },
                    onFailure = { e ->
                        val msg = when (e) {
                            is NoSuchElementException -> "NOTFOUND ${e.message}"
                            else -> e.message ?: "fail"
                        }
                        replyErr("load", msg)
                    },
                )
            }'''

    # Careful: in the Python string above ${e.message} is literal for Kotlin

    def replace_arm(src: str, name: str, new_arm: str) -> str:
        # Match from "name" -> { through the closing brace of that when-branch
        pattern = rf'(            "{name}" -> \{{)(.*?)(\n            "[a-z])'
        m = re.search(pattern, src, flags=re.S)
        if not m:
            raise SystemExit(f"arm {name} not found")
        return src[:m.start()] + new_arm + m.group(3) + src[m.end():]

    if "DatastoreRateLimit.checkAndConsume" not in t or '"save"' not in t.split("checkAndConsume")[0][-200:]:
        t = replace_arm(t, "save", save_new)
        print("save replaced")
    else:
        print("save ok")

    if "pasteIntoGame" not in t:
        t = replace_arm(t, "load", load_new)
        print("load replaced")
    else:
        print("load ok")

    if "private suspend fun pasteIntoGame" not in t:
        helper = '''
    /** Focus → paste [text] into game. Does not emit replyOk (caller does). */
    private suspend fun pasteIntoGame(text: String) {
        val svc = TapService.instance ?: return
        setClipboard(text)
        svc.clickAtPercent(focusXPct, focusYPct)
        delay(1000)
        svc.pasteClipboard()
        delay(400)
        if (submitXPx > 0f || submitYPx > 0f) {
            svc.clickAt(submitXPx, submitYPx)
        }
    }

'''
        if "private suspend fun runPasteSequence" not in t:
            raise SystemExit("runPasteSequence missing")
        t = t.replace(
            "    private suspend fun runPasteSequence",
            helper + "    private suspend fun runPasteSequence",
            1,
        )
        print("pasteIntoGame added")

    P.write_text(t)
    print("done")

if __name__ == "__main__":
    main()
