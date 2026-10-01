#!/usr/bin/env python3
"""Dedup invoke lines (triple paste) + add invoke|enter."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"


def main():
    t = P.read_text()

    # --- dedup fields on class ---
    if "lastInvokePayload" not in t:
        # after running AtomicBoolean or similar
        anchor = "private val running = AtomicBoolean(false)"
        if anchor not in t:
            # try other
            for a in ["private val scope", "private val store", "class InvokeEngine"]:
                if a in t:
                    pass
            # inject after class opening fields
            m = "private val running"
            if m in t:
                # find full line
                idx = t.find("private val running")
                line_end = t.find("\n", idx)
                inject = (
                    "\n    /** Same invoke| line often appears 2–3x in Roblox logcat."
                    "\n     * Skip identical payload within the window. */"
                    "\n    @Volatile private var lastInvokePayload: String = \"\""
                    "\n    @Volatile private var lastInvokeAtMs: Long = 0L"
                    "\n    private val invokeDedupMs: Long = 3000L"
                )
                t = t[:line_end] + inject + t[line_end:]
            else:
                raise SystemExit("cannot find running field")

    # --- dedup in handleLine ---
    if "dedup skip" not in t:
        old = '''        val payload = raw.substring(idx + "invoke|".length).trim()
        if (payload.isEmpty()) return

        // Avoid re-entrancy on our own reply lines
        if (raw.contains("cwbridge|")) return
'''
        new = '''        val payload = raw.substring(idx + "invoke|".length).trim()
        if (payload.isEmpty()) return

        // Avoid re-entrancy on our own reply lines
        if (raw.contains("cwbridge|")) return

        val now = System.currentTimeMillis()
        if (payload == lastInvokePayload && now - lastInvokeAtMs < invokeDedupMs) {
            LogBuffer.i("Invoke", "dedup skip (${now - lastInvokeAtMs}ms) ${payload.take(60)}")
            return
        }
        lastInvokePayload = payload
        lastInvokeAtMs = now
'''
        if old not in t:
            raise SystemExit("handleLine anchor missing")
        t = t.replace(old, new, 1)

    # Cancel previous dispatch job so concurrent pastes don't stack
    if "job?.cancel()" not in t[t.find("fun handleLine"):t.find("fun handleLine")+800]:
        old_job = "        job = scope.launch(Dispatchers.Main) {"
        new_job = "        job?.cancel()\n        job = scope.launch(Dispatchers.Main) {"
        # only first occurrence after handleLine
        hi = t.find("fun handleLine")
        pos = t.find(old_job, hi)
        if pos > 0:
            t = t[:pos] + new_job + t[pos + len(old_job):]

    # --- invoke|enter ---
    if '"enter" ->' not in t and "\"enter\" ->" not in t:
        enter_block = '''
            "enter", "return" -> {
                val svc = TapService.instance
                val ok = when {
                    svc != null -> svc.pressEnter()
                    ShizukuShell.isReady() -> ShizukuShell.pressEnter()
                    else -> false
                }
                if (ok) replyOk("enter", "Enter sent")
                else replyErr("enter", "Enter failed (need a11y or Shizuku)")
            }

'''
        # insert before submit or paste
        if '"submit" ->' in t:
            t = t.replace('"submit" ->', enter_block + '            "submit" ->', 1)
        elif '"paste" ->' in t:
            t = t.replace('"paste" ->', enter_block + '            "paste" ->', 1)
        else:
            raise SystemExit("no insert point for enter")

    # help string
    if "enter |" not in t and "| enter" not in t:
        t = t.replace(
            "status | tap.x.y | paste.text | clip | focus | submit | wait | toast | echo | help",
            "status | tap.x.y | paste.text | enter | clip | focus | submit | wait | toast | echo | help",
            1,
        )

    P.write_text(t)
    print("InvokeEngine patched", P.stat().st_size)
    print("dedup", "dedup skip" in t, "enter", '"enter"' in t or "enter", "return" in t)


if __name__ == "__main__":
    main()
