#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"

def main() -> None:
    t = P.read_text()
    if "fun focusRoblox" in t and "fun outLooksOk" in t:
        print("already present")
        return

    helpers = r'''
    private fun outLooksOk(out: String): Boolean {
        if (out.isBlank()) return true
        val bad = listOf("Error", "Unknown", "not found", "No such", "Exception", "denied")
        return bad.none { out.contains(it, ignoreCase = true) }
    }

    fun focusRoblox(packageName: String = "com.roblox.client") {
        if (!isReady()) return
        val cmds = listOf(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName",
        )
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "focusRoblox $cmd exit=$code ${out.take(50)}")
            if (code == 0) return
        }
    }

'''
    # Insert before pressEnter
    marker = "    fun pressEnter(): Boolean {"
    if marker not in t:
        raise SystemExit("pressEnter missing")
    if "fun focusRoblox" not in t or "fun outLooksOk" not in t:
        # remove partials
        pass
    t = t.replace(marker, helpers + marker, 1)
    P.write_text(t)
    print("restored focusRoblox + outLooksOk")

if __name__ == "__main__":
    main()
