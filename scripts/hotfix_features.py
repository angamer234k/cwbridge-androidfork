#!/usr/bin/env python3
"""Strip OCR modules that break CI; keep single-domain + failsafe-5 stop."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main() -> None:
    # Delete OCR sources
    for rel in [
        "app/src/main/java/com/cwbridge/android/bridge/ScreenOcr.kt",
        "app/src/main/java/com/cwbridge/android/bridge/DisconnectOcrWatch.kt",
    ]:
        p = ROOT / rel
        if p.exists():
            p.unlink()
            print("deleted", rel)

    # MainActivity: remove DisconnectOcrWatch references
    ma = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    t = ma.read_text()
    t = t.replace("import com.cwbridge.android.bridge.DisconnectOcrWatch\n", "")
    t = t.replace("            DisconnectOcrWatch.start(bridgeScope, applicationContext)\n", "")
    t = t.replace("            DisconnectOcrWatch.stop()\n", "")
    t = t.replace("            DisconnectOcrWatch.stop()\n", "")  # again if duplicated
    t = t.replace("                DisconnectOcrWatch.stop()\n", "")
    # stopBridgeWithError body may still reference it
    t = t.replace("DisconnectOcrWatch.stop()\n", "")
    ma.write_text(t)
    print("MainActivity cleaned")

    # BridgeControl: openSingleDomain without ScreenOcr
    bc = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = bc.read_text()
    # Replace openSingleDomainOnLoad body to percent-only
    start = t.find("    fun openSingleDomainOnLoad")
    if start >= 0:
        end = t.find("\n    fun ", start + 5)
        if end < 0:
            end = t.rfind("}")
        fn = r'''    fun openSingleDomainOnLoad(domain: String): String {
        val d = domain.trim()
        if (d.isEmpty()) return "empty domain"
        val svc = TapService.instance
            ?: return "$d: no accessibility (need CWBridge Tap)"
        // Percent URL bar (OCR deferred — ML Kit blocked CI)
        val tapped = svc.clickAtPercent(50f, 6f)
        if (!tapped) return "$d: URL bar tap failed"
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        val typed = if (ShizukuShell.isReady()) ShizukuShell.inputText(d) else svc.sendText(d)
        if (!typed) return "$d: type failed"
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        val enter = if (ShizukuShell.isReady()) ShizukuShell.pressEnter() else svc.pressEnter()
        return if (enter) "$d: ok" else "$d: Enter failed"
    }

'''
        t = t[:start] + fn + t[end:]
        bc.write_text(t)
        print("openSingleDomain percent-only")

    # Cap failsafe at 5 in bump + stop bridge
    if "MAX_FAILSAFE" not in t and "fun bumpDisconnectFailsafe" in bc.read_text():
        t = bc.read_text()
        old = '''    fun bumpDisconnectFailsafe(): Int {
        disconnectFailsafe += 1
        LogBuffer.w("Control", "disconnect failsafe now=$disconnectFailsafe")
        return disconnectFailsafe
    }'''
        new = '''    private const val MAX_FAILSAFE = 5

    fun bumpDisconnectFailsafe(): Int {
        disconnectFailsafe += 1
        LogBuffer.w("Control", "disconnect failsafe now=$disconnectFailsafe/$MAX_FAILSAFE")
        if (disconnectFailsafe >= MAX_FAILSAFE) {
            val msg =
                "Bridge stopped: Roblox disconnected $disconnectFailsafe times " +
                    "without recovery (failsafe limit $MAX_FAILSAFE). " +
                    "Open Roblox/CatWeb manually, then start the bridge again."
            stopBridgeWithError(msg)
        }
        return disconnectFailsafe
    }'''
        if old in t:
            t = t.replace(old, new, 1)
            bc.write_text(t)
            print("failsafe max 5 + stop")

    # AntiDisconnect should not reference OCR
    print("done")

if __name__ == "__main__":
    main()
