#!/usr/bin/env python3
"""Anti-AFK keep-alive: fixed 500px, 2px (not percent in input zone)."""
from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/bridge/AntiDisconnect.kt"
t = p.read_text()

# constants
t = t.replace(
    """    /** Right side, upper-mid \u2014 away from top chrome (~5%) and bottom gesture bar. */
    private const val KEEP_ALIVE_X = 88f
    private const val KEEP_ALIVE_Y = 40f""",
    """    /** Fixed px near top edge \u2014 away from CatWeb input / keyboard zone. */
    private const val KEEP_ALIVE_X_PX = 500f
    private const val KEEP_ALIVE_Y_PX = 2f""",
)
t = t.replace(
    """    private const val KEEP_ALIVE_X = 88f
    private const val KEEP_ALIVE_Y = 40f""",
    """    private const val KEEP_ALIVE_X_PX = 500f
    private const val KEEP_ALIVE_Y_PX = 2f""",
)

# log line in start()
t = t.replace(
    '"\\u2192 tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% " +
',
    '"-> tap ${KEEP_ALIVE_X_PX.toInt()}px,${KEEP_ALIVE_Y_PX.toInt()}px " +
',
)
t = t.replace(
    '"\u2192 tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% " +
',
    '"-> tap ${KEEP_ALIVE_X_PX.toInt()}px,${KEEP_ALIVE_Y_PX.toInt()}px " +
',
)
# also if already arrow char
if "KEEP_ALIVE_X.toInt()}%" in t:
    t = t.replace(
        "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}%",
        "tap ${KEEP_ALIVE_X_PX.toInt()}px,${KEEP_ALIVE_Y_PX.toInt()}px",
    )

# a11y path
old_a11y = """        if (svc != null) {
            val ok = svc.clickAtPercent(KEEP_ALIVE_X, KEEP_ALIVE_Y)
            LogBuffer.i(
                "AntiDC",
                "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) a11y ok=$ok",
            )
            return
        }"""
new_a11y = """        if (svc != null) {
            val ok = svc.clickAt(KEEP_ALIVE_X_PX, KEEP_ALIVE_Y_PX)
            LogBuffer.i(
                "AntiDC",
                "tap ${KEEP_ALIVE_X_PX.toInt()}px,${KEEP_ALIVE_Y_PX.toInt()}px ($reason) a11y ok=$ok",
            )
            return
        }"""
if old_a11y in t:
    t = t.replace(old_a11y, new_a11y, 1)
    print("a11y path")
else:
    t = t.replace("svc.clickAtPercent(KEEP_ALIVE_X, KEEP_ALIVE_Y)", "svc.clickAt(KEEP_ALIVE_X_PX, KEEP_ALIVE_Y_PX)")
    t = t.replace(
        "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) a11y ok=$ok",
        "tap ${KEEP_ALIVE_X_PX.toInt()}px,${KEEP_ALIVE_Y_PX.toInt()}px ($reason) a11y ok=$ok",
    )
    print("a11y path (loose)")

# shizuku path: skip wm size, just fixed px
old_sh = """        if (ShizukuShell.isReady()) {
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
            LogBuffer.i("AntiDC", "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) shizuku $x,$y code=$code $r")
            return
        }"""
new_sh = """        if (ShizukuShell.isReady()) {
            val x = KEEP_ALIVE_X_PX.toInt()
            val y = KEEP_ALIVE_Y_PX.toInt()
            val (code, r) = ShizukuShell.exec("input tap $x $y")
            LogBuffer.i(
                "AntiDC",
                "tap ${x}px,${y}px ($reason) shizuku code=$code $r",
            )
            return
        }"""

if "KEEP_ALIVE_X / 100f" in t or "KEEP_ALIVE_X_PX" not in t or "wm size" in t:
    import re
    t2, n = re.subn(
        r"if \(ShizukuShell\.isReady\(\)\) \{[\s\S]*?return\n        \}",
        new_sh.strip(),
        t,
        count=1,
    )
    if n == 1:
        t = t2
        print("shizuku path regex")
    else:
        # minimal
        t = t.replace(
            "val x = (w * KEEP_ALIVE_X / 100f).toInt()\n            val y = (h * KEEP_ALIVE_Y / 100f).toInt()",
            "val x = KEEP_ALIVE_X_PX.toInt()\n            val y = KEEP_ALIVE_Y_PX.toInt()",
        )
        print("shizuku path loose")

if "KEEP_ALIVE_X_PX" not in t:
    raise SystemExit("KEEP_ALIVE_X_PX missing after patch")

p.write_text(t)
print("AntiDisconnect", p.stat().st_size)
print("hotfix coords OK")
