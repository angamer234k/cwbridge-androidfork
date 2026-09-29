#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main() -> None:
    # gradle
    g = ROOT / "app/build.gradle.kts"
    gt = g.read_text()
    dep = 'implementation("com.google.mlkit:text-recognition:16.0.1")'
    if dep not in gt:
        gt = gt.replace(
            'implementation("com.google.code.gson:gson:2.10.1")',
            'implementation("com.google.code.gson:gson:2.10.1")\n    ' + dep,
            1,
        )
        g.write_text(gt)
        print("mlkit dep")
    else:
        print("mlkit ok")

    # BridgeControl openSingleDomain
    bc = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = bc.read_text()
    if "ScreenOcr.findText" not in t:
        old = (
            "        val svc = TapService.instance\n"
            "            ?: return \"$d: no accessibility (need CWBridge Tap)\"\n"
            "        // Percent URL bar (OCR deferred — ML Kit blocked CI)\n"
            "        val tapped = svc.clickAtPercent(50f, 6f)"
        )
        new = (
            "        val svc = TapService.instance\n"
            "            ?: return \"$d: no accessibility (need CWBridge Tap)\"\n"
            "        val appCtx = svc.applicationContext\n"
            "        val hit = ScreenOcr.findText(appCtx, \"search or type a url\", 5f, 90f, 0f, 50f)\n"
            "            ?: ScreenOcr.findText(appCtx, \"type a url\", 5f, 90f, 0f, 50f)\n"
            "            ?: ScreenOcr.findText(appCtx, \"search or type\", 5f, 90f, 0f, 50f)\n"
            "        val tapped = if (hit != null) {\n"
            "            svc.clickAt(hit.centerX, hit.centerY)\n"
            "        } else {\n"
            "            LogBuffer.w(\"Control\", \"OCR URL bar miss — percent fallback 50%,6%\")\n"
            "            svc.clickAtPercent(50f, 6f)\n"
            "        }"
        )
        if old not in t:
            # try simpler replace
            old2 = "        // Percent URL bar (OCR deferred — ML Kit blocked CI)\n        val tapped = svc.clickAtPercent(50f, 6f)"
            new2 = (
                "        val appCtx = svc.applicationContext\n"
                "        val hit = ScreenOcr.findText(appCtx, \"search or type a url\", 5f, 90f, 0f, 50f)\n"
                "            ?: ScreenOcr.findText(appCtx, \"type a url\", 5f, 90f, 0f, 50f)\n"
                "            ?: ScreenOcr.findText(appCtx, \"search or type\", 5f, 90f, 0f, 50f)\n"
                "        val tapped = if (hit != null) {\n"
                "            svc.clickAt(hit.centerX, hit.centerY)\n"
                "        } else {\n"
                "            LogBuffer.w(\"Control\", \"OCR URL bar miss — percent fallback 50%,6%\")\n"
                "            svc.clickAtPercent(50f, 6f)\n"
                "        }"
            )
            if old2 in t:
                t = t.replace(old2, new2, 1)
                print("OCR URL via simple")
            else:
                print("WARN openSingle not matched")
                print(repr(t[t.find("openSingleDomain"):t.find("openSingleDomain")+400]))
        else:
            t = t.replace(old, new, 1)
            print("OCR URL wired")
        bc.write_text(t)
    else:
        print("OCR URL already")

    # MainActivity
    ma = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    mt = ma.read_text()
    if "DisconnectOcrWatch" not in mt:
        mt = mt.replace(
            "import com.cwbridge.android.bridge.CatWebTracker",
            "import com.cwbridge.android.bridge.CatWebTracker\nimport com.cwbridge.android.bridge.DisconnectOcrWatch",
            1,
        )
    if "DisconnectOcrWatch.start" not in mt:
        mt = mt.replace(
            "            AntiDisconnect.start(bridgeScope)\n            ensureLogcatRunning()",
            "            AntiDisconnect.start(bridgeScope)\n"
            "            ensureLogcatRunning()\n"
            "            DisconnectOcrWatch.start(bridgeScope, applicationContext)\n"
            "            BridgeControl.resetDisconnectFailsafe()",
            1,
        )
        print("start wired")
    if mt.count("DisconnectOcrWatch.stop()") < 1:
        mt = mt.replace(
            "            BridgeControl.cancelOpenCatWeb()\n            logcatReader.stop()",
            "            BridgeControl.cancelOpenCatWeb()\n"
            "            DisconnectOcrWatch.stop()\n"
            "            logcatReader.stop()",
            1,
        )
        print("stop wired")
    # stopBridgeWithError path
    if "DisconnectOcrWatch.stop()" not in mt[mt.find("stopBridgeWithError"):mt.find("private fun toggleBridge")]:
        mt = mt.replace(
            "                BridgeControl.cancelOpenCatWeb()\n                    try { logcatReader.stop()",
            "                BridgeControl.cancelOpenCatWeb()\n"
            "                DisconnectOcrWatch.stop()\n"
            "                try { logcatReader.stop()",
            1,
        )
        print("error-stop wired")
    ma.write_text(mt)
    print("main done")

if __name__ == "__main__":
    main()
