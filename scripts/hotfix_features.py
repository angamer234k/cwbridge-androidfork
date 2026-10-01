#!/usr/bin/env python3
import zlib, base64
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
# payload written by agent - load from sibling if present
payload = ROOT / 'scripts' / 'webuipayload.b64'
if not payload.exists():
    raise SystemExit('missing webuipayload.b64')
raw = zlib.decompress(base64.b64decode(payload.read_text().strip()))
out = ROOT / 'app/src/main/java/com/cwbridge/android/server/WebUi.kt'
out.write_bytes(raw)
print('WebUi Stage3', out.stat().st_size)
