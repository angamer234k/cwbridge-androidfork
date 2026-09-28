#!/usr/bin/env python3
from pathlib import Path
import re

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"

def main() -> None:
    t = P.read_text()
    fixed = (
        'val domains = raw.lines().flatMap { it.split(",", ";") }'
        ".map { it.trim() }.filter { it.isNotEmpty() }"
    )
    t2, n = re.subn(
        r"val domains = raw\.split\(.*?filter \{ it\.isNotEmpty\(\) \}",
        fixed,
        t,
        count=1,
        flags=re.S,
    )
    if n == 0:
        raise SystemExit("not found")
    P.write_text(t2)
    print("ok")

if __name__ == "__main__":
    main()
