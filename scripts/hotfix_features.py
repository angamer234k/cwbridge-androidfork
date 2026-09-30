#!/usr/bin/env python3
import base64, gzip
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
parts = []
for i in range(5):
    t = (ROOT / "scripts" / f"stage2_part{i}.b64").read_text()
    parts.append("".join(t.split()))
b64 = "".join(parts)
p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
p.write_bytes(gzip.decompress(base64.b64decode(b64)))
print("WebUi.kt stage2 written", p.stat().st_size)
