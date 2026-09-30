#!/usr/bin/env python3
from pathlib import Path
p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/bridge/AntiDisconnect.kt"
t = p.read_text()
t2 = t.replace(
    'val out = ShizukuShell.exec("wm size")',
    'val (_, sizeOut) = ShizukuShell.exec("wm size")',
)
t2 = t2.replace('find(out ?: "")', 'find(sizeOut)')
t2 = t2.replace(
    'val r = ShizukuShell.exec("input tap $x $y")',
    'val (code, r) = ShizukuShell.exec("input tap $x $y")',
)
for a in ["\u2192 $r", "-> $r", "\\u2192 $r"]:
    t2 = t2.replace(f"shizuku $x,$y {a}", "shizuku $x,$y code=$code $r")
if t2 == t and "sizeOut" not in t:
    raise SystemExit("no changes applied - unexpected file")
p.write_text(t2)
print("patched", p.stat().st_size, "sizeOut" in t2)
