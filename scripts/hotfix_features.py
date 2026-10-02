#!/usr/bin/env python3
"""Declare missing lastInvoke* fields so InvokeEngine compiles."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"


def main():
    t = P.read_text()
    if "@Volatile private var lastInvokePayload" in t or "private var lastInvokePayload" in t:
        print("fields already present")
        return

    # Insert right after: private var job: Job? = null
    old = "    private var job: Job? = null\n"
    new = (
        "    private var job: Job? = null\n"
        "\n"
        "    /** Same invoke| line often appears 2–3x in Roblox logcat. */\n"
        "    @Volatile private var lastInvokePayload: String = \"\"\n"
        "    @Volatile private var lastInvokeAtMs: Long = 0L\n"
        "    private val invokeDedupMs: Long = 3000L\n"
    )
    if old not in t:
        raise SystemExit("job field anchor missing")
    t = t.replace(old, new, 1)
    P.write_text(t)
    print("declared dedup fields", P.stat().st_size)


if __name__ == "__main__":
    main()
