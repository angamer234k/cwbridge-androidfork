#!/usr/bin/env python3
"""On short phones CatWeb hides the tab bar — reveal it before tapping +."""
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"

def main() -> None:
    t = P.read_text()
    start = t.find("    fun openNewTabByPlusTap(): Boolean {")
    if start < 0:
        raise SystemExit("openNewTabByPlusTap missing")
    # end at next top-level fun after this one, or private fun
    # Find matching closing — next "    fun " or "    private fun " at same indent after body
    # Safer: find focusRoblox or pressEnter after openNewTab
    end = t.find("\n    fun pressEnter", start)
    if end < 0:
        end = t.find("\n    private fun newProcess", start)
    if end < 0:
        raise SystemExit("end marker missing")

    replacement = r'''    fun openNewTabByPlusTap(): Boolean {
        focusRoblox()
        try { Thread.sleep(200) } catch (_: InterruptedException) {}

        val (szCode, szOut) = exec("wm size")
        val sizeMatch = Regex("""(\d+)x(\d+)""").find(szOut)
        val w = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val h = sizeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        LogBuffer.i("Shizuku", "screen ${w}x$h (wm size exit=$szCode)")
        if (w <= 0 || h <= 0) {
            LogBuffer.w("Shizuku", "cannot resolve screen size — skip + taps")
            return false
        }

        // Short phones: CatWeb auto-hides the tab bar. Pull it back into view.
        revealCatWebChrome(w, h)

        // (x%, y%) candidates for "+" once chrome is visible
        val spots = listOf(
            92f to 4f, 96f to 4f, 88f to 4f,
            92f to 6f, 94f to 5f, 90f to 3f,
            92f to 8f, 96f to 8f, 88f to 8f,  // a bit lower after reveal
            50f to 4f,
            85f to 8f, 97f to 8f,
            92f to 10f, 94f to 12f,
        )
        var anyOk = false
        for ((xp, yp) in spots) {
            val x = ((xp / 100f) * w).toInt()
            val y = ((yp / 100f) * h).toInt()
            val (code, out) = exec("input tap $x $y")
            LogBuffer.i("Shizuku", "+ tap ${xp}% ${yp}% -> ($x,$y) exit=$code ${out.take(40)}")
            if (code == 0) anyOk = true
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        LogBuffer.i("Shizuku", "openNewTabByPlusTap done anyOk=$anyOk")
        return anyOk
    }

    /**
     * CatWeb on small-Y phones hides the tab strip. Common reveals:
     *  1) swipe down from the top edge (pull chrome)
     *  2) short tap near the top center (focus UI)
     *  3) second slower swipe a bit deeper
     */
    private fun revealCatWebChrome(w: Int, h: Int) {
        val midX = w / 2
        val topY = maxOf(2, (h * 0.01f).toInt())
        val pullY = maxOf(80, (h * 0.12f).toInt())
        val pullY2 = maxOf(120, (h * 0.18f).toInt())

        // Swipe down from top (gesture to expand browser chrome)
        val swipes = listOf(
            "input swipe $midX $topY $midX $pullY 180",
            "input swipe $midX $topY $midX $pullY2 280",
            // slight diagonal in case of edge-gesture conflict
            "input swipe ${midX - 40} $topY ${midX - 40} $pullY 200",
        )
        for (cmd in swipes) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "reveal chrome: $cmd exit=$code ${out.take(40)}")
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }

        // Tap top center — some layouts expand on tap instead of swipe
        val tapY = maxOf(4, (h * 0.02f).toInt())
        val (tc, to) = exec("input tap $midX $tapY")
        LogBuffer.i("Shizuku", "reveal tap top-center ($midX,$tapY) exit=$tc ${to.take(40)}")
        try { Thread.sleep(350) } catch (_: InterruptedException) {}
    }

'''
    t = t[:start] + replacement + t[end:]
    P.write_text(t)
    print("openNewTabByPlusTap reveals chrome first")

if __name__ == "__main__":
    main()
