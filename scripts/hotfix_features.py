#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/MainActivity.kt"

def main() -> None:
    t = P.read_text()
    if "private fun showAccessibilityReconnectDialog()" in t:
        print("already has dialog fn")
        return
    dialog = '''
    /** MIUI/Redmi often leave the service enabled in Settings while the process is dead. */
    private fun showAccessibilityReconnectDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("CWBridge Tap not running")
            .setMessage(
                "Settings says Tap is enabled, but the service is not connected.\n\n" +
                    "This is common on Xiaomi / Redmi / MIUI:\n" +
                    "1. Open Accessibility settings\n" +
                    "2. Turn CWBridge Tap OFF, wait 2s, turn ON\n" +
                    "3. Disable battery restrictions for CWBridge\n" +
                    "4. Force-stop CWBridge, then reopen the app",
            )
            .setPositiveButton("Open settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Later", null)
            .show()
    }

'''
    anchor = "    private fun openAccessibilitySettings() {"
    if anchor not in t:
        raise SystemExit("anchor missing")
    t = t.replace(anchor, dialog + anchor, 1)
    P.write_text(t)
    print("dialog fn added")

if __name__ == "__main__":
    main()
