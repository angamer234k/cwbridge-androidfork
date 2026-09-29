#!/usr/bin/env python3
import base64, gzip
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def w(path, b64):
    Path(path).write_bytes(gzip.decompress(base64.b64decode(b64)))
print('loading stage2')
