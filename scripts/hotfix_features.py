#!/usr/bin/env python3
"""CatWeb mobile: tabs-count button opens tab UI, then + for new tab. Ctrl is PC-only."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_shizuku() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = p.read_text()
    start = t.find("    fun openNewTabByPlusTap(): Boolean {")
    if start < 0:
        raise SystemExit("openNewTabByPlusTap missing")
    end = t.find("\n    fun pressEnter", start)
    if end < 0:
        end = t.find("\n    private fun newProcess", start)
    if end < 0:
        raise SystemExit("end marker missing")

    # Replace openNewTab + reveal with mobile-correct flow
    replacement = r'''    /**
     * CatWeb mobile new-tab flow (NOT Chrome):
     *  1) Tap the tabs-count button (square with a number, right of the URL/star)
     *  2) Wait for the tab overview
     *  3) Tap "+" in the overview
     *
     * Ctrl+T is PC-only per CatDocs — never rely on it on phones.
     * Coords are % of screen; landscape is assumed (CatWeb is landscape-only).
     */
    fun openNewTabByPlusTap(): Boolean {
        focusRoblox()
        try { Thread.sleep(200) } catch (_: InterruptedException) {}

        val (szCode, szOut) = exec("wm size")
        val sizeMatch = Regex("""(\d+)x(\d+)""").find(szOut)
        val w = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val h = sizeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        LogBuffer.i("Shizuku", "screen ${w}x$h (wm size exit=$szCode)")
        if (w <= 0 || h <= 0) return false

        // Step 1: tabs-count button (the "1" / "2" square right of address bar)
        // From user photo: roughly right side of top chrome, left of screen edge.
        val tabsCountSpots = listOf(
            88f to 7f, 90f to 7f, 86f to 7f,
            88f to 9f, 90f to 9f, 85f to 8f,
            92f to 7f, 84f to 10f,
        )
        var hitTabs = false
        for ((xp, yp) in tabsCountSpots) {
            val x = ((xp / 100f) * w).toInt()
            val y = ((yp / 100f) * h).toInt()
            val (code, out) = exec("input tap $x $y")
            LogBuffer.i("Shizuku", "tabs-count tap ${xp}% ${yp}% -> ($x,$y) exit=$code ${out.take(30)}")
            if (code == 0) hitTabs = true
            try { Thread.sleep(80) } catch (_: InterruptedException) {}
        }
        if (!hitTabs) {
            LogBuffer.w("Shizuku", "tabs-count taps all failed")
            return false
        }
        // Let tab overview animate in
        try { Thread.sleep(600) } catch (_: InterruptedException) {}

        // Step 2: "+" inside the tab overview (usually top-right or bottom-right)
        val plusSpots = listOf(
            92f to 8f, 95f to 8f, 88f to 8f,
            92f to 12f, 95f to 12f,
            92f to 92f, 95f to 92f, 88f to 90f,  // bottom variants
            50f to 92f,  // some UIs center a big +
            92f to 50f,
        )
        var hitPlus = false
        for ((xp, yp) in plusSpots) {
            val x = ((xp / 100f) * w).toInt()
            val y = ((yp / 100f) * h).toInt()
            val (code, out) = exec("input tap $x $y")
            LogBuffer.i("Shizuku", "tab-overview + tap ${xp}% ${yp}% -> ($x,$y) exit=$code ${out.take(30)}")
            if (code == 0) hitPlus = true
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        try { Thread.sleep(500) } catch (_: InterruptedException) {}
        LogBuffer.i("Shizuku", "openNewTab mobile flow done tabs=$hitTabs plus=$hitPlus")
        return hitTabs // overview opened; + may still have landed on one of the taps
    }

'''
    t = t[:start] + replacement + t[end:]
    # Drop dead revealCatWebChrome if still present
    if "fun revealCatWebChrome" in t:
        r0 = t.find("    private fun revealCatWebChrome")
        if r0 < 0:
            r0 = t.find("    fun revealCatWebChrome")
        if r0 > 0:
            r1 = t.find("\n    fun ", r0 + 5)
            if r1 < 0:
                r1 = t.find("\n    private fun ", r0 + 5)
            if r1 > 0:
                t = t[:r0] + t[r1 + 1 :]
                print("removed revealCatWebChrome")
    p.write_text(t)
    print("openNewTabByPlusTap = tabs-count then +")

def patch_bridge() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()
    # Prefer mobile tab flow; skip Ctrl+T on the happy path
    old = '''            // Prefer + button taps (Roblox mobile rarely accepts synthetic Ctrl)
            var opened = false
            if (ShizukuShell.isReady()) {
                opened = ShizukuShell.openNewTabByPlusTap()
                if (!opened) opened = ShizukuShell.pressCtrlT()
            }
            if (!opened && svc != null) {
                // a11y multi-spot +
                for ((px, py) in listOf(
                    92f to 4f, 96f to 4f, 88f to 5f, 94f to 6f, 50f to 4f
                )) {
                    if (svc.clickAtPercent(px, py)) {
                        LogBuffer.i("Control", "+ a11y tap @$px%,$py%")
                        opened = true
                        break
                    }
                }
                if (!opened) opened = svc.pressCtrlT()
            }
            if (!opened) {
                results += "$d: new-tab failed (keys + + button)"
                continue
            }'''
    new = '''            // CatWeb mobile: tabs-count → + (Ctrl+T is PC-only per CatDocs)
            var opened = false
            if (ShizukuShell.isReady()) {
                opened = ShizukuShell.openNewTabByPlusTap()
            }
            if (!opened && svc != null) {
                // a11y: tabs-count region then +
                for ((px, py) in listOf(88f to 7f, 90f to 8f, 86f to 9f)) {
                    if (svc.clickAtPercent(px, py)) {
                        LogBuffer.i("Control", "tabs-count a11y @$px%,$py%")
                        opened = true
                        break
                    }
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
                for ((px, py) in listOf(92f to 8f, 95f to 12f, 92f to 92f, 50f to 92f)) {
                    svc.clickAtPercent(px, py)
                }
            }
            if (!opened) {
                results += "$d: new-tab failed (tabs-count / +)"
                continue
            }'''
    if old not in t:
        print("WARN: bridge openDomains block not exact — trying loose")
        if "openNewTabByPlusTap" in t:
            print("already has openNewTab path")
        else:
            raise SystemExit("bridge block missing")
    else:
        t = t.replace(old, new, 1)
        p.write_text(t)
        print("BridgeControl mobile tab flow")

def patch_api() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    import re
    t2, n = re.subn(
        r'"ctrl-t" -> \{.*?\n            \}',
        '''"ctrl-t" -> {
                // On mobile CatWeb, Ctrl+T does nothing (PC-only). Use tabs-count → +.
                if (ShizukuShell.isReady() && ShizukuShell.openNewTabByPlusTap())
                    "Opened new tab via CatWeb tabs-count → + (mobile flow)"
                else if (svc != null && svc.pressCtrlT())
                    "Ctrl+T sent (key path — PC CatWeb only)"
                else if (ShizukuShell.isReady() && ShizukuShell.pressCtrlT())
                    "Ctrl+T sent (key path — PC CatWeb only)"
                else
                    "ERROR: mobile new-tab failed — tap the tabs-count button manually once and retry"
            }''',
        t,
        count=1,
        flags=re.S,
    )
    if n:
        p.write_text(t2)
        print("ctrl-t API uses mobile tab flow first")
    else:
        print("WARN: ctrl-t API not updated")

def main() -> None:
    patch_shizuku()
    patch_bridge()
    patch_api()
    print("done")

if __name__ == "__main__":
    main()
