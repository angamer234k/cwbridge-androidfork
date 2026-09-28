#!/usr/bin/env python3
from pathlib import Path

P = Path(__file__).resolve().parents[1] / "app/src/main/java/com/cwbridge/android/MainActivity.kt"

def main() -> None:
    t = P.read_text()

    # Fix bad onResume injection
    bad = '''        ensureLogcatRunning()
            DisconnectOcrWatch.start(bridgeScope, applicationContext)
            BridgeControl.resetDisconnectFailsafe()
        if (OverlayService.canDrawOverlays(this)) OverlayService.start(this)'''
    good = '''        ensureLogcatRunning()
        if (OverlayService.canDrawOverlays(this)) OverlayService.start(this)'''
    if bad in t:
        t = t.replace(bad, good, 1)
        print("fixed onResume")

    # Fix onPause - only stop OCR if we wrongly started it; keep logcat stop when !bridgeRunning
    # Ensure DisconnectOcrWatch starts on bridge start
    if "DisconnectOcrWatch.start(bridgeScope" not in t.split("private fun toggleBridge")[-1]:
        # insert after ensureLogcatRunning in start branch of toggleBridge
        start_marker = '''            AntiDisconnect.start(bridgeScope)
            ensureLogcatRunning()
            BridgeControl.scheduleOpenCatWebIfNeeded'''
        start_repl = '''            AntiDisconnect.start(bridgeScope)
            ensureLogcatRunning()
            DisconnectOcrWatch.start(bridgeScope, applicationContext)
            BridgeControl.resetDisconnectFailsafe()
            BridgeControl.scheduleOpenCatWebIfNeeded'''
        if start_marker in t:
            t = t.replace(start_marker, start_repl, 1)
            print("OCR watch on bridge start")
        else:
            print("WARN: start marker missing")

    # stop branch
    if "DisconnectOcrWatch.stop()" not in t.split("private fun toggleBridge")[-1].split("else")[0]:
        t = t.replace(
            '''        if (bridgeRunning) {
            bridgeRunning = false
            BridgeControl.cancelOpenCatWeb()
            logcatReader.stop()''',
            '''        if (bridgeRunning) {
            bridgeRunning = false
            BridgeControl.cancelOpenCatWeb()
            DisconnectOcrWatch.stop()
            logcatReader.stop()''',
            1,
        )
        print("OCR stop on bridge stop")

    t = t.replace("refreshBridgeUi()", "refreshUi()")

    # stopBridgeWithError should also stop executionEngine
    t = t.replace(
        '''                try { invokeEngine.stop() } catch (_: Throwable) {}
                try { AntiDisconnect.stop() } catch (_: Throwable) {}''',
        '''                try { invokeEngine.stop() } catch (_: Throwable) {}
                try { executionEngine.stop() } catch (_: Throwable) {}
                try { AntiDisconnect.stop() } catch (_: Throwable) {}''',
        1,
    )

    P.write_text(t)
    print("done")

if __name__ == "__main__":
    main()
