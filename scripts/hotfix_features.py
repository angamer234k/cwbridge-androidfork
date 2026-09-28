#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"

def main() -> None:
    t = P.read_text()
    start = t.find("    private fun sendeventCtrlChord")
    if start < 0:
        raise SystemExit("missing sendeventCtrlChord")
    end = t.find("    fun openNewTabByPlusTap", start)
    if end < 0:
        # maybe still has doc comment
        end = t.find("    /**\n     * Open a CatWeb new tab", start)
    if end < 0:
        raise SystemExit("missing openNewTab marker")

    # No nested quotes — event paths have no spaces
    replacement = r'''    private fun sendeventCtrlChord(linuxKey: Int, label: String): Boolean {
        val lk = linuxKey
        // Shell vars written as ${'$'}name so Kotlin does not interpolate them.
        val script =
            "ok=0; for dev in /dev/input/event*; do " +
            "[ -e ${'$'}dev ] || continue; " +
            "sendevent ${'$'}dev 1 29 1 2>/dev/null || continue; " +
            "sendevent ${'$'}dev 0 0 0 2>/dev/null; " +
            "sendevent ${'$'}dev 1 " + lk + " 1 2>/dev/null || continue; " +
            "sendevent ${'$'}dev 0 0 0 2>/dev/null; " +
            "sendevent ${'$'}dev 1 " + lk + " 0 2>/dev/null; " +
            "sendevent ${'$'}dev 0 0 0 2>/dev/null; " +
            "sendevent ${'$'}dev 1 29 0 2>/dev/null; " +
            "sendevent ${'$'}dev 0 0 0 2>/dev/null; " +
            "echo OK ${'$'}dev; ok=1; break; done; " +
            "[ ${'$'}ok -eq 1 ]"
        val (code, out) = exec(script)
        LogBuffer.i("Shizuku", "sendevent Ctrl+$label exit=$code ${out.take(120)}")
        if (code == 0 && out.contains("OK")) {
            LogBuffer.i("Shizuku", "Ctrl+$label OK via sendevent")
            return true
        }
        return false
    }

'''
    t = t[:start] + replacement + t[end:]
    P.write_text(t)
    print("sendevent fixed with ${'$'} escapes")

if __name__ == "__main__":
    main()
