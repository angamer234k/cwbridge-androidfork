#!/usr/bin/env python3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
t = p.read_text()
n = 0
if "$lastMatch" in t:
    t = t.replace("$lastMatch", "${'$'}lastMatch")
    n += 1
    print("escaped $lastMatch")
if "$var" in t:
    t = t.replace("$var", "${'$'}var")
    n += 1
    print("escaped $var")
if n == 0:
    print("already escaped or absent")
p.write_text(t)
print("done", p.stat().st_size)
