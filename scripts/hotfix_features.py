#!/usr/bin/env python3
from pathlib import Path
import re

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"

def main() -> None:
    t = P.read_text()
    # Fix broken split with literal newline inside char
    bad = re.search(r"raw\.split\(',',\s*'\n?',\s*';'\)", t)
    # More permissive: anything between split( and ).map
    m = re.search(r"val domains = raw\.split\(.*?\)\.map", t, re.S)
    if not m:
        # try broken form across lines
        m = re.search(r"val domains = raw\.split\(',',\s*'\s*',\s*';'\)\.map", t)
    if "Regex" in t and "domains = raw.split" in t:
        print("already fixed?")
        # still try replace broken
    fixed_line = 'val domains = raw.split(Regex("[,\\n;]")).map { it.trim() }.filter { it.isNotEmpty() }'
    # Replace any version of the domains = raw.split ... filter line
    t2, n = re.subn(
        r"val domains = raw\.split\(.*?filter \{ it\.isNotEmpty\(\) \}",
        fixed_line,
        t,
        count=1,
        flags=re.S,
    )
    if n == 0:
        raise SystemExit("domains split not found")
    P.write_text(t2)
    print("fixed domains split")

if __name__ == "__main__":
    main()
