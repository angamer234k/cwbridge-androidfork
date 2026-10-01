#!/usr/bin/env python3
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"

# Prefer local good copy if already fixed
if p.exists() and "toggleStream" in p.read_text() and p.stat().st_size > 10000:
    print("WebUi already has stream", p.stat().st_size)
else:
    # restore from last known good commit then leave for second patch
    url = "https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/7655da3f/app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = urllib.request.urlopen(url, timeout=90).read().decode()
    if len(t) < 1000 or t.strip() == "PLACEHOLDER":
        raise SystemExit("could not restore WebUi base")
    p.write_text(t)
    print("WebUi restored base", p.stat().st_size)
