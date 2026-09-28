#!/usr/bin/env python3
from pathlib import Path
import re

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/MainActivity.kt"

FIXED = r'''    private fun showAccessibilityReconnectDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("CWBridge Tap not running")
            .setMessage(
                """
                Settings says Tap is enabled, but the service is not connected.

                This is common on Xiaomi / Redmi / MIUI:
                1. Open Accessibility settings
                2. Turn CWBridge Tap OFF, wait 2s, turn ON
                3. Disable battery restrictions for CWBridge
                4. Force-stop CWBridge, then reopen the app
                """.trimIndent(),
            )
            .setPositiveButton("Open settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Later", null)
            .show()
    }'''

def main() -> None:
    t = P.read_text()
    m = re.search(
        r'[ \t]*private fun showAccessibilityReconnectDialog\(\) \{.*?\.show\(\)\s*\}',
        t,
        re.S,
    )
    if not m:
        raise SystemExit('dialog not found')
    t = t[:m.start()] + FIXED + t[m.end():]
    P.write_text(t)
    print('rewrote dialog with trimIndent')

if __name__ == '__main__':
    main()
