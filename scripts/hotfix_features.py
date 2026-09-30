#!/usr/bin/env python3
"""
After OCR reconnect / Roblox relaunch, CatWeb 'finished' must open the domain again.
Root cause: CatWebTracker.readyFired stays true forever after first ready.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_tracker():
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/CatWebTracker.kt"
    t = p.read_text()

    if "fun armForNextReady" in t:
        print("CatWebTracker: armForNextReady already present")
    else:
        old = """    fun setOnReadyOnce(cb: (() -> Unit)?) {
        readyCallback = cb
        readyFired = false
    }"""
        new = """    fun setOnReadyOnce(cb: (() -> Unit)?) {
        readyCallback = cb
        readyFired = false
    }

    /**
     * After disconnect / OCR reconnect / Roblox relaunch — allow the next
     * CatWeb "finished" line to fire [readyCallback] again (re-open domain).
     */
    fun armForNextReady() {
        ready = false
        readyFired = false
        LogBuffer.i("CatWeb", "armed for next finished (will reopen domain)")
    }"""
        if old not in t:
            raise SystemExit("setOnReadyOnce not found")
        t = t.replace(old, new, 1)
        print("CatWebTracker: armForNextReady")

    old_fin = """        if (finished) {
            ready = true
            BridgeStatus.set(OverlayState.ACTIVE, "CatWeb ready")
            if (!readyFired) {
                readyFired = true
                try {
                    readyCallback?.invoke()
                } catch (t: Throwable) {
                    LogBuffer.e("CatWeb", "onReady: ${t.message}")
                }
            }
        } else if (!ready) {"""

    new_fin = """        if (finished) {
            ready = true
            BridgeStatus.set(OverlayState.ACTIVE, "CatWeb ready")
            if (!readyFired) {
                readyFired = true
                try {
                    LogBuffer.i("CatWeb", "finished → onReady (open domain)")
                    readyCallback?.invoke()
                } catch (t: Throwable) {
                    LogBuffer.e("CatWeb", "onReady: ${t.message}")
                }
            } else {
                LogBuffer.i("CatWeb", "finished ignored (already fired — need armForNextReady after reconnect)")
            }
        } else if (
            ready && (
                lower.contains("waiting for server") ||
                    lower.contains("loading") ||
                    (lower.contains("catweb") && (lower.contains("v") || lower.contains("version")))
            )
        ) {
            // Session reloading after reconnect — arm so next finished reopens domain
            armForNextReady()
            val detail = when {
                lower.contains("waiting for server") -> "Waiting for server…"
                lower.contains("loading") -> "CatWeb loading…"
                else -> "CatWeb restarting…"
            }
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, detail)
            }
        } else if (!ready) {"""

    if old_fin in t:
        t = t.replace(old_fin, new_fin, 1)
        print("CatWebTracker: auto-arm on reload lines + finished log")
    else:
        print("CatWebTracker: finished block pattern miss — check file")

    p.write_text(t)
    print("CatWebTracker", p.stat().st_size)


def patch_ocr():
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/DisconnectOcrWatch.kt"
    t = p.read_text()

    if "armForNextReady" in t:
        print("OCR: already arms")
    else:
        old = """        if (reconnect != null) {
            LogBuffer.i("OCR-DC", "Reconnect found — tapping")
            val svc = TapService.instance
            if (svc != null) {
                svc.clickAt(reconnect.centerX, reconnect.centerY)
            } else if (ShizukuShell.isReady()) {
                ShizukuShell.exec(
                    "input tap ${reconnect.centerX.toInt()} ${reconnect.centerY.toInt()}",
                )
            }
            AntiDisconnect.noteActivity()
            return
        }"""
        new = """        if (reconnect != null) {
            LogBuffer.i("OCR-DC", "Reconnect found — tapping")
            val svc = TapService.instance
            if (svc != null) {
                svc.clickAt(reconnect.centerX, reconnect.centerY)
            } else if (ShizukuShell.isReady()) {
                ShizukuShell.exec(
                    "input tap ${reconnect.centerX.toInt()} ${reconnect.centerY.toInt()}",
                )
            }
            AntiDisconnect.noteActivity()
            CatWebTracker.armForNextReady()
            BridgeStatus.set(OverlayState.WAITING, "Reconnect tapped — waiting CatWeb…")
            return
        }"""
        if old not in t:
            raise SystemExit("OCR reconnect block not found")
        t = t.replace(old, new, 1)
        print("OCR: arm after reconnect tap")

        old2 = """        LogBuffer.w("OCR-DC", "relaunching Roblox (failsafe $n)")
        BridgeControl.restartRoblox(context)
        BridgeStatus.set(OverlayState.WAITING, "Relaunch after disconnect ($n/5)")
    }
}"""
        new2 = """        LogBuffer.w("OCR-DC", "relaunching Roblox (failsafe $n)")
        CatWebTracker.armForNextReady()
        BridgeControl.restartRoblox(context)
        BridgeStatus.set(OverlayState.WAITING, "Relaunch after disconnect ($n/5)")
    }
}"""
        if old2 in t:
            t = t.replace(old2, new2, 1)
            print("OCR: arm before relaunch")
        else:
            print("OCR: relaunch arm pattern miss")

    p.write_text(t)
    print("DisconnectOcrWatch", p.stat().st_size)


def patch_bridge_control():
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()

    idx = t.find("fun restartRoblox")
    if idx >= 0:
        chunk = t[idx:idx + 800]
        if "armForNextReady" in chunk:
            print("Control: restartRoblox already arms")
        else:
            brace = t.find("{", idx)
            t = t[:brace + 1] + "\n        CatWebTracker.armForNextReady()\n" + t[brace + 1:]
            print("Control: arm at restartRoblox")
    else:
        print("Control: no restartRoblox")

    p.write_text(t)
    print("BridgeControl", p.stat().st_size)


def patch_anti():
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/AntiDisconnect.kt"
    if not p.exists():
        print("AntiDisconnect skip")
        return
    t = p.read_text()
    if "armForNextReady" in t:
        print("AntiDC: already arms")
        return
    old = """            LogBuffer.w("AntiDC", "disconnect signal — soft recover (no spam tap)")
            noteActivity()"""
    new = """            LogBuffer.w("AntiDC", "disconnect signal — soft recover (no spam tap)")
            noteActivity()
            CatWebTracker.armForNextReady()"""
    if old in t:
        t = t.replace(old, new, 1)
        p.write_text(t)
        print("AntiDC: arm on disconnect signal")
    else:
        print("AntiDC: pattern miss")


def main():
    patch_tracker()
    patch_ocr()
    patch_bridge_control()
    patch_anti()
    print("hotfix reopen OK")


if __name__ == "__main__":
    main()
