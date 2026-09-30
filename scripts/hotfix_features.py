#!/usr/bin/env python3
"""Fix ShizukuShell.exec Pair handling in AntiDisconnect."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/AntiDisconnect.kt"
t = p.read_text()

old = '''        if (ShizukuShell.isReady()) {
            val out = ShizukuShell.exec("wm size")
            var w = 1080
            var h = 2400
            val m = Regex("""(\\d+)x(\\d+)""").find(out ?: "")
            if (m != null) {
                w = m.groupValues[1].toIntOrNull() ?: w
                h = m.groupValues[2].toIntOrNull() ?: h
            }
            val x = (w * KEEP_ALIVE_X / 100f).toInt()
            val y = (h * KEEP_ALIVE_Y / 100f).toInt()
            val r = ShizukuShell.exec("input tap $x $y")
            LogBuffer.i("AntiDC", "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) shizuku $x,$y \\u2192 $r")
            return
        }'''

# try several variants of the broken block
fixed = '''        if (ShizukuShell.isReady()) {
            val (_, sizeOut) = ShizukuShell.exec("wm size")
            var w = 1080
            var h = 2400
            val m = Regex("""(\\d+)x(\\d+)""").find(sizeOut)
            if (m != null) {
                w = m.groupValues[1].toIntOrNull() ?: w
                h = m.groupValues[2].toIntOrNull() ?: h
            }
            val x = (w * KEEP_ALIVE_X / 100f).toInt()
            val y = (h * KEEP_ALIVE_Y / 100f).toInt()
            val (code, r) = ShizukuShell.exec("input tap $x $y")
            LogBuffer.i(
                "AntiDC",
                "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) shizuku $x,$y code=$code $r",
            )
            return
        }'''

if "val (_, sizeOut)" in t:
    print("already fixed")
elif "val out = ShizukuShell.exec(\"wm size\")" in t:
    # flexible replace
    import re
    t2, n = re.subn(
        r"if \(ShizukuShell\.isReady\(\)\) \{[\s\S]*?LogBuffer\.w\(\"AntiDC\", "no accessibility",
        fixed + "\n        LogBuffer.w(\"AntiDC\", "no accessibility",
        t,
        count=1,
    )
    if n != 1:
        # simpler line-based
        t = t.replace(
            "val out = ShizukuShell.exec(\"wm size\")",
            "val (_, sizeOut) = ShizukuShell.exec(\"wm size\")",
            1,
        )
        t = t.replace("find(out ?: \"\")", "find(sizeOut)", 1)
        t = t.replace(
            "val r = ShizukuShell.exec(\"input tap $x $y\")",
            "val (code, r) = ShizukuShell.exec(\"input tap $x $y\")",
            1,
        )
        t = t.replace("shizuku $x,$y \\u2192 $r", "shizuku $x,$y code=$code $r", 1)
        t = t.replace("shizuku $x,$y \u2192 $r", "shizuku $x,$y code=$code $r", 1)
        t = t.replace("shizuku $x,$y → $r", "shizuku $x,$y code=$code $r", 1)
        print("fixed via line replaces")
    else:
        t = t2
        print("fixed via regex")
else:
    raise SystemExit("shizuku block not found")

p.write_text(t)
print("AntiDisconnect", p.stat().st_size)
print("hotfix pair OK")
