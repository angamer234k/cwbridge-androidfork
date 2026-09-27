#!/usr/bin/env python3
"""One-shot source patches for CWBridge Android."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    # fix: constructor param needs val to be usable in methods
    if "preferredPort: Int = 8080," in t and "private val preferredPort" not in t:
        t = t.replace("preferredPort: Int = 8080,", "private val preferredPort: Int = 8080,", 1)
        p.write_text(t)
        print("fixed preferredPort to private val")
    else:
        print("preferredPort already ok or missing")


if __name__ == "__main__":
    main()
