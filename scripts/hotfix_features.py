#!/usr/bin/env python3
from pathlib import Path
import re

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"

def replace_arm(src: str, name: str, new_arm: str) -> str:
    # Allow blank lines between when-branches
    pattern = rf'(            "{name}" -> \{{)(.*?)(\n\s*"[a-z])'
    m = re.search(pattern, src, flags=re.S)
    if not m:
        raise SystemExit(f"arm {name} not found")
    return src[: m.start()] + new_arm + m.group(3) + src[m.end() :]

def main() -> None:
    t = P.read_text()

    if "import com.cwbridge.android.data.DatastoreRateLimit" not in t:
        t = t.replace(
            "import com.cwbridge.android.data.Store",
            "import com.cwbridge.android.data.DatastoreRateLimit\nimport com.cwbridge.android.data.Store",
            1,
        )

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

    if "DatastoreRateLimit.checkAndConsume(context, domain)" not in t or '"save" ->' in t and "checkAndConsume" not in t[t.find('"save" ->'):t.find('"save" ->')+400]:
        t = replace_arm(t, "save", save_new)
        print("save replaced")
    else:
        print("save already")

    if "pasteIntoGame(value)" not in t:
        t = replace_arm(t, "load", load_new)
        print("load replaced")
    else:
        print("load already")

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
    # sanity
    if "pasteIntoGame(value)" not in t:
        raise SystemExit("load paste not present after write")
    if "DatastoreRateLimit.checkAndConsume" not in t:
        raise SystemExit("rate limit not present after write")
    print("done")

if __name__ == "__main__":
    main()
