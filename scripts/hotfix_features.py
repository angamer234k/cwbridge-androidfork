#!/usr/bin/env python3
"""Patch MainActivity a11y detection for MIUI/Redmi."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"


def main() -> None:
    t = P.read_text()

    old_refuse = '''            if (!TapService.isConnected()) {
                LogBuffer.e("CWBridge", "refusing start: Accessibility service is off")
                Toast.makeText(this, "Enable CWBridge Tap first", Toast.LENGTH_SHORT).show()
                openAccessibilitySettings()
                refreshUi()
                return
            }'''

    new_refuse = '''            if (!TapService.isConnected()) {
                val listed = isAccessibilityEnabled()
                LogBuffer.e(
                    "CWBridge",
                    if (listed) "a11y listed ON but TapService not bound (MIUI often kills it)"
                    else "Accessibility service is off",
                )
                if (listed) {
                    showAccessibilityReconnectDialog()
                } else {
                    Toast.makeText(this, "Enable CWBridge Tap first", Toast.LENGTH_SHORT).show()
                    openAccessibilitySettings()
                }
                refreshUi()
                return
            }'''

    if old_refuse not in t:
        if "showAccessibilityReconnectDialog" in t:
            print("already patched refuse")
        else:
            raise SystemExit("refuse block missing")
    else:
        t = t.replace(old_refuse, new_refuse, 1)
        print("patched refuse")

    old_ui = '''        val a11y = isAccessibilityEnabled()
        binding.a11yState.text = if (a11y) "A11y ON" else "A11y OFF"
        binding.a11yState.setTextColor(ContextCompat.getColor(this, if (a11y) R.color.ok else R.color.warn))'''

    new_ui = '''        val a11yListed = isAccessibilityEnabled()
        val a11yBound = TapService.isConnected()
        binding.a11yState.text = when {
            a11yBound -> "A11y ON"
            a11yListed -> "A11y listed (not bound)"
            else -> "A11y OFF"
        }
        binding.a11yState.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    a11yBound -> R.color.ok
                    a11yListed -> R.color.warn
                    else -> R.color.warn
                },
            ),
        )'''

    if old_ui not in t:
        if "A11y listed (not bound)" in t:
            print("already patched ui")
        else:
            raise SystemExit("ui block missing")
    else:
        t = t.replace(old_ui, new_ui, 1)
        print("patched ui")

    # fix BridgeStatus line that used old `a11y` var
    t = t.replace(
        "!(TapService.isConnected() || a11y) -> BridgeStatus.set(OverlayState.ERROR, \"Accessibility off\")",
        "!TapService.isConnected() -> BridgeStatus.set(\n"
        "                OverlayState.ERROR,\n"
        "                if (a11yListed) \"A11y not bound — toggle Tap off/on\" else \"Accessibility off\",\n"
        "            )",
        1,
    )

    dialog = '''
    /** MIUI/Redmi often leave the service "enabled" in Settings while the process is dead. */
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

    if "showAccessibilityReconnectDialog" not in t:
        anchor = "    private fun openAccessibilitySettings() {"
        if anchor not in t:
            raise SystemExit("openAccessibilitySettings missing")
        t = t.replace(anchor, dialog + "\n" + anchor, 1)
        print("added dialog")
    else:
        print("dialog already present")

    P.write_text(t)
    print("done")


if __name__ == "__main__":
    main()
