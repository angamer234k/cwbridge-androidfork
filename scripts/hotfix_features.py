#!/usr/bin/env python3
from pathlib import Path
import re

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/MainActivity.kt"

def main() -> None:
    t = P.read_text()
    # Replace the broken dialog (literal newlines inside string literals)
    broken = re.search(
        r'private fun showAccessibilityReconnectDialog\(\) \{.*?\.show\(\)\n    \}',
        t,
        re.S,
    )
    if not broken:
        raise SystemExit('dialog block not found')
    fixed = '''private fun showAccessibilityReconnectDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("CWBridge Tap not running")
            .setMessage(
                "Settings says Tap is enabled, but the service is not connected." +
                    "\n\nThis is common on Xiaomi / Redmi / MIUI:" +
                    "\n1. Open Accessibility settings" +
                    "\n2. Turn CWBridge Tap OFF, wait 2s, turn ON" +
                    "\n3. Disable battery restrictions for CWBridge" +
                    "\n4. Force-stop CWBridge, then reopen the app",
            )
            .setPositiveButton("Open settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Later", null)
            .show()
    }'''
    t = t[:broken.start()] + fixed + t[broken.end():]
    P.write_text(t)
    print('dialog strings fixed')

if __name__ == '__main__':
    main()
