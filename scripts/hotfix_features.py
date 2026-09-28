#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"

def main() -> None:
    t = P.read_text()
    # Broken script uses unescaped $devs/$dev which is invalid Kotlin string template
    bad = '''        val script = """
devs=$(ls /dev/input/event* 2>/dev/null)
ok=0
for dev in $devs; do
  if sendevent "$dev" 1 29 1 2>/dev/null && \\
     sendevent "$dev" 0 0 0 2>/dev/null && \\
     sendevent "$dev" 1 $linuxKey 1 2>/dev/null && \\
     sendevent "$dev" 0 0 0 2>/dev/null && \\
     sendevent "$dev" 1 $linuxKey 0 2>/dev/null && \\
     sendevent "$dev" 0 0 0 2>/dev/null && \\
     sendevent "$dev" 1 29 0 2>/dev/null && \\
     sendevent "$dev" 0 0 0 2>/dev/null; then
    echo "OK $dev"
    ok=1
  fi
done
exit $((1-ok))
""".trimIndent()'''

    # Use a simpler script built with concatenation to avoid $ issues
    good = '''        // Build shell script without Kotlin $ interpolation traps
        val lk = linuxKey
        val script = StringBuilder()
        script.append("ok=0; ")
        script.append("for dev in /dev/input/event*; do ")
        script.append("[ -e \\"\$dev\\" ] || continue; ")
        script.append("sendevent \\"\$dev\\" 1 29 1 2>/dev/null || continue; ")
        script.append("sendevent \\"\$dev\\" 0 0 0 2>/dev/null; ")
        script.append("sendevent \\"\$dev\\" 1 ").append(lk).append(" 1 2>/dev/null || continue; ")
        script.append("sendevent \\"\$dev\\" 0 0 0 2>/dev/null; ")
        script.append("sendevent \\"\$dev\\" 1 ").append(lk).append(" 0 2>/dev/null; ")
        script.append("sendevent \\"\$dev\\" 0 0 0 2>/dev/null; ")
        script.append("sendevent \\"\$dev\\" 1 29 0 2>/dev/null; ")
        script.append("sendevent \\"\$dev\\" 0 0 0 2>/dev/null; ")
        script.append("echo OK \$dev; ok=1; break; ")
        script.append("done; ")
        script.append("exit \$((1-ok))")
'''

    # Simpler approach: find the function and replace the script assignment
    start = t.find("    private fun sendeventCtrlChord")
    if start < 0:
        raise SystemExit("sendeventCtrlChord missing")
    end = t.find("    /**\n     * Open a CatWeb new tab", start)
    if end < 0:
        end = t.find("    fun openNewTabByPlusTap", start)
    if end < 0:
        raise SystemExit("end marker missing")

    replacement = '''    private fun sendeventCtrlChord(linuxKey: Int, label: String): Boolean {
        // Hardware-level EV_KEY via sendevent. Escape shell vars carefully for Kotlin.
        val lk = linuxKey
        val script = (
            "ok=0; " +
            "for dev in /dev/input/event*; do " +
            "[ -e \"\$dev\" ] || continue; " +
            "sendevent \"\$dev\" 1 29 1 2>/dev/null || continue; " +
            "sendevent \"\$dev\" 0 0 0 2>/dev/null; " +
            "sendevent \"\$dev\" 1 " + lk + " 1 2>/dev/null || continue; " +
            "sendevent \"\$dev\" 0 0 0 2>/dev/null; " +
            "sendevent \"\$dev\" 1 " + lk + " 0 2>/dev/null; " +
            "sendevent \"\$dev\" 0 0 0 2>/dev/null; " +
            "sendevent \"\$dev\" 1 29 0 2>/dev/null; " +
            "sendevent \"\$dev\" 0 0 0 2>/dev/null; " +
            "echo OK \$dev; ok=1; break; " +
            "done; " +
            "[ \$ok -eq 1 ]"
        )
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
    print("sendevent $ escaping fixed")

if __name__ == "__main__":
    main()
