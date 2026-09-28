#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_gradle() -> None:
    p = ROOT / "app/build.gradle.kts"
    t = p.read_text()
    dep = 'implementation("com.google.mlkit:text-recognition:16.0.1")'
    if dep in t:
        print("mlkit ok")
        return
    needle = 'implementation("com.google.code.gson:gson:2.10.1")'
    if needle not in t:
        raise SystemExit("gson missing")
    t = t.replace(needle, needle + "\n    " + dep, 1)
    p.write_text(t)
    print("mlkit added")

def patch_bridge() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()

    t = t.replace(
        "interface Hooks {\n        fun restartBridge()\n    }",
        "interface Hooks {\n        fun restartBridge()\n        fun stopBridgeWithError(message: String)\n    }",
        1,
    )

    if "fun stopBridgeWithError" not in t:
        t = t.replace(
            "fun setHooks(h: Hooks?) {\n        hooks = h\n    }",
            '''fun setHooks(h: Hooks?) {
        hooks = h
    }

    fun stopBridgeWithError(message: String) {
        try {
            hooks?.stopBridgeWithError(message)
        } catch (t: Throwable) {
            LogBuffer.e("Control", "stopBridgeWithError: ${t.message}")
        }
    }''',
            1,
        )

    # single domain on load
    old = '''        LogBuffer.i("Control", "CW load: opening ${domains.size} domain(s)")
        // First domain: just navigate current tab via URL bar.
        // Further domains: try tabs-count → + then URL bar.
        return openDomains(domains)'''
    new = '''        val one = domains.firstOrNull()?.trim().orEmpty()
        if (one.isEmpty()) return "no auto-open domains"
        LogBuffer.i("Control", "CW load: opening single domain $one")
        return openSingleDomainOnLoad(one)'''
    if old in t:
        t = t.replace(old, new, 1)
        print("single domain on load")
    elif "openSingleDomainOnLoad" in t:
        print("single domain already")
    else:
        # alternate: replace return openDomains(domains) inside openDomainsOnCwLoad
        import re
        t2, n = re.subn(
            r'(fun openDomainsOnCwLoad\(context: Context\): String \{[\s\S]*?)return openDomains\(domains\)',
            r'''\1val one = domains.firstOrNull()?.trim().orEmpty()
        if (one.isEmpty()) return "no auto-open domains"
        LogBuffer.i("Control", "CW load: opening single domain $one")
        return openSingleDomainOnLoad(one)''',
            t,
            count=1,
        )
        if n:
            t = t2
            print("single domain via regex")
        else:
            print("WARN: could not patch openDomainsOnCwLoad")

    if "fun openSingleDomainOnLoad" not in t:
        fn = r'''
    /** Navigate current tab to [domain] via OCR URL bar (X 5-90%, Y 0-50%). */
    fun openSingleDomainOnLoad(domain: String): String {
        val d = domain.trim()
        if (d.isEmpty()) return "empty domain"
        val svc = TapService.instance
            ?: return "$d: no accessibility (need CWBridge Tap)"
        val appCtx = svc.applicationContext

        val hit = ScreenOcr.findText(appCtx, "search or type a url", 5f, 90f, 0f, 50f)
            ?: ScreenOcr.findText(appCtx, "type a url", 5f, 90f, 0f, 50f)
            ?: ScreenOcr.findText(appCtx, "search or type", 5f, 90f, 0f, 50f)

        val tapped = if (hit != null) {
            svc.clickAt(hit.centerX, hit.centerY)
        } else {
            LogBuffer.w("Control", "OCR URL bar miss — percent fallback 50%,6%")
            svc.clickAtPercent(50f, 6f)
        }
        if (!tapped) return "$d: URL bar tap failed"
        try { Thread.sleep(400) } catch (_: InterruptedException) {}

        val typed = if (ShizukuShell.isReady()) ShizukuShell.inputText(d) else svc.sendText(d)
        if (!typed) return "$d: type failed"
        try { Thread.sleep(200) } catch (_: InterruptedException) {}

        val enter = if (ShizukuShell.isReady()) ShizukuShell.pressEnter() else svc.pressEnter()
        return if (enter) "$d: ok (OCR URL bar)" else "$d: Enter failed"
    }

'''
        idx = t.rfind("}")
        t = t[:idx] + fn + "}\n"
        print("added openSingleDomainOnLoad")

    # ensure TapService import if needed - same package bridge uses fully qualified already
    p.write_text(t)
    print("BridgeControl done")

def patch_main() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    t = p.read_text()

    if "import com.cwbridge.android.bridge.DisconnectOcrWatch" not in t:
        t = t.replace(
            "import com.cwbridge.android.bridge.CatWebTracker",
            "import com.cwbridge.android.bridge.CatWebTracker\nimport com.cwbridge.android.bridge.DisconnectOcrWatch",
            1,
        )

    if "DisconnectOcrWatch.start" not in t:
        t = t.replace(
            "ensureLogcatRunning()",
            "ensureLogcatRunning()\n            DisconnectOcrWatch.start(bridgeScope, applicationContext)\n            BridgeControl.resetDisconnectFailsafe()",
            1,
        )

    if "DisconnectOcrWatch.stop" not in t:
        # only in stop branch — first logcatReader.stop in toggleBridge
        t = t.replace(
            """        if (bridgeRunning) {
            bridgeRunning = false
            BridgeControl.cancelOpenCatWeb()
            logcatReader.stop()""",
            """        if (bridgeRunning) {
            bridgeRunning = false
            BridgeControl.cancelOpenCatWeb()
            DisconnectOcrWatch.stop()
            logcatReader.stop()""",
            1,
        )

    if "fun stopBridgeWithError" not in t:
        method = r'''
    private fun stopBridgeWithError(message: String) {
        runOnUiThread {
            if (bridgeRunning) {
                bridgeRunning = false
                BridgeControl.cancelOpenCatWeb()
                DisconnectOcrWatch.stop()
                try { logcatReader.stop() } catch (_: Throwable) {}
                try { invokeEngine.stop() } catch (_: Throwable) {}
                try { executionEngine.stop() } catch (_: Throwable) {}
                try { AntiDisconnect.stop() } catch (_: Throwable) {}
                refreshUi()
            }
            BridgeStatus.set(OverlayState.ERROR, message.take(48))
            MaterialAlertDialogBuilder(this)
                .setTitle("Bridge stopped")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

'''
        t = t.replace("private fun toggleBridge()", method + "    private fun toggleBridge()", 1)

    # Expand existing setHooks
    old_hooks = '''        BridgeControl.setHooks(object : BridgeControl.Hooks {
            override fun restartBridge() {
                runOnUiThread {
                    toggleBridge()
                    LogBuffer.i("Control", "bridge restarted from web UI")
                }
            }
        })'''
    new_hooks = '''        BridgeControl.setHooks(object : BridgeControl.Hooks {
            override fun restartBridge() {
                runOnUiThread {
                    toggleBridge()
                    LogBuffer.i("Control", "bridge restarted from web UI")
                }
            }
            override fun stopBridgeWithError(message: String) {
                stopBridgeWithError(message)
            }
        })'''
    if old_hooks in t:
        t = t.replace(old_hooks, new_hooks, 1)
        print("hooks expanded")
    elif "stopBridgeWithError(message)" in t:
        print("hooks already have stop")
    else:
        print("WARN: hooks block not matched")

    p.write_text(t)
    print("MainActivity done")

def main() -> None:
    patch_gradle()
    patch_bridge()
    patch_main()
    print("done")

if __name__ == "__main__":
    main()
