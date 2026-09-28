#!/usr/bin/env python3
"""OCR (ML Kit + Shizuku screencap), single-domain CW load, failsafe max 5."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_gradle() -> None:
    p = ROOT / "app/build.gradle.kts"
    t = p.read_text()
    dep = 'implementation("com.google.mlkit:text-recognition:16.0.1")'
    if dep in t:
        print("mlkit already")
        return
    # after gson line
    needle = 'implementation("com.google.code.gson:gson:2.10.1")'
    if needle not in t:
        raise SystemExit("gson dep missing")
    t = t.replace(
        needle,
        needle + "\n    // On-device OCR for CatWeb URL bar + disconnect dialog\n    " + dep,
        1,
    )
    p.write_text(t)
    print("gradle: mlkit text-recognition")

def write_screen_ocr() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/ScreenOcr.kt"
    p.write_text(r'''package com.cwbridge.android.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Screen capture + on-device OCR.
 * Prefer Shizuku `screencap` (works on many FLAG_SECURE games); fall back to a11y.
 */
object ScreenOcr {

    data class Hit(
        val text: String,
        /** Absolute screen px of the bounding-box center. */
        val centerX: Float,
        val centerY: Float,
        val bounds: Rect,
    )

    fun capture(context: Context): Bitmap? {
        // 1) Shizuku screencap into app external files (shell can usually write here)
        if (ShizukuShell.isReady()) {
            val dir = context.getExternalFilesDir(null) ?: context.cacheDir
            val file = File(dir, "cw_screencap.png")
            try {
                if (file.exists()) file.delete()
            } catch (_: Throwable) {}
            val path = file.absolutePath
            val (code, out) = ShizukuShell.exec("screencap -p \"$path\" && chmod 644 \"$path\"")
            LogBuffer.i("OCR", "screencap exit=$code exists=${file.exists()} ${out.take(60)}")
            if (code == 0 && file.exists() && file.length() > 100) {
                return try {
                    BitmapFactory.decodeFile(path)
                } catch (t: Throwable) {
                    LogBuffer.w("OCR", "decode screencap: ${t.message}")
                    null
                }
            }
        }
        // 2) Accessibility screenshot (often blocked by FLAG_SECURE on Roblox)
        val svc = TapService.instance ?: return null
        val latch = CountDownLatch(1)
        val box = AtomicReference<Bitmap?>(null)
        svc.screenshot { bmp ->
            box.set(bmp)
            latch.countDown()
        }
        try {
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return box.get()
    }

    /**
     * Find [query] (case-insensitive, substring) inside an optional percent crop of the screen.
     * Returns the best hit (longest match) or null.
     *
     * @param x0Pct left edge % (0-100)
     * @param x1Pct right edge %
     * @param y0Pct top edge %
     * @param y1Pct bottom edge %
     */
    fun findText(
        context: Context,
        query: String,
        x0Pct: Float = 0f,
        x1Pct: Float = 100f,
        y0Pct: Float = 0f,
        y1Pct: Float = 100f,
    ): Hit? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val full = capture(context) ?: run {
            LogBuffer.w("OCR", "no bitmap — cannot OCR \"$query\"")
            return null
        }
        val w = full.width
        val h = full.height
        val left = ((x0Pct / 100f) * w).toInt().coerceIn(0, w - 1)
        val top = ((y0Pct / 100f) * h).toInt().coerceIn(0, h - 1)
        val right = ((x1Pct / 100f) * w).toInt().coerceIn(left + 1, w)
        val bottom = ((y1Pct / 100f) * h).toInt().coerceIn(top + 1, h)
        val cropW = right - left
        val cropH = bottom - top
        val crop = try {
            Bitmap.createBitmap(full, left, top, cropW, cropH)
        } catch (t: Throwable) {
            LogBuffer.w("OCR", "crop failed: ${t.message}")
            full
        }.also {
            if (it !== full) {
                // keep full for now; recycle later
            }
        }
        val hits = recognizeAll(crop)
        var best: Hit? = null
        for (raw in hits) {
            if (!raw.text.lowercase().contains(q)) continue
            // map crop-local bounds → full screen
            val b = Rect(
                raw.bounds.left + left,
                raw.bounds.top + top,
                raw.bounds.right + left,
                raw.bounds.bottom + top,
            )
            val hit = Hit(
                text = raw.text,
                centerX = b.exactCenterX(),
                centerY = b.exactCenterY(),
                bounds = b,
            )
            if (best == null || hit.text.length > best.text.length) best = hit
        }
        if (crop !== full) {
            try { crop.recycle() } catch (_: Throwable) {}
        }
        try { full.recycle() } catch (_: Throwable) {}
        if (best != null) {
            LogBuffer.i(
                "OCR",
                "hit \"${best.text.take(40)}\" @ (${best.centerX.toInt()},${best.centerY.toInt()}) for q=\"$query\"",
            )
        } else {
            LogBuffer.w("OCR", "no hit for \"$query\" in region X$x0Pct-$x1Pct Y$y0Pct-$y1Pct")
        }
        return best
    }

    /** True if any OCR line in the region contains [needle] (case-insensitive). */
    fun regionContains(
        context: Context,
        needle: String,
        x0Pct: Float,
        x1Pct: Float,
        y0Pct: Float,
        y1Pct: Float,
    ): Boolean = findText(context, needle, x0Pct, x1Pct, y0Pct, y1Pct) != null

    /** OCR the whole image; return line-level hits in image-local coords. */
    private fun recognizeAll(bitmap: Bitmap): List<Hit> {
        val latch = CountDownLatch(1)
        val out = AtomicReference<List<Hit>>(emptyList())
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val list = mutableListOf<Hit>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            list += Hit(
                                text = line.text,
                                centerX = box.exactCenterX(),
                                centerY = box.exactCenterY(),
                                bounds = Rect(box),
                            )
                        }
                    }
                    out.set(list)
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    LogBuffer.w("OCR", "mlkit failed: ${e.message}")
                    latch.countDown()
                }
            latch.await(12, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            LogBuffer.w("OCR", "recognizeAll: ${t.message}")
            latch.countDown()
        }
        return out.get()
    }

    /** Center crop percent for a fixed pixel square (e.g. 750x750) on current screen. */
    fun centerSquarePct(context: Context, sizePx: Int): FloatArray {
        // returns floatArrayOf(x0, x1, y0, y1) in percent
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        val side = sizePx.coerceAtMost(minOf(w, h))
        val x0 = ((w - side) / 2f) / w * 100f
        val y0 = ((h - side) / 2f) / h * 100f
        val x1 = x0 + side.toFloat() / w * 100f
        val y1 = y0 + side.toFloat() / h * 100f
        return floatArrayOf(x0, x1, y0, y1)
    }
}
''')
    print("wrote ScreenOcr.kt")

def write_disconnect_watch() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/DisconnectOcrWatch.kt"
    p.write_text(r'''package com.cwbridge.android.bridge

import android.content.Context
import com.cwbridge.android.TapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Every [INTERVAL_MS], OCR a center 750x750 window for Disconnected / Reconnect.
 * - Reconnect visible → tap it
 * - Disconnected without Reconnect → bump failsafe, relaunch Roblox
 * - failsafe > 5 → stop bridge + dismissable error
 */
object DisconnectOcrWatch {

    private const val INTERVAL_MS = 60_000L
    private const val SQUARE_PX = 750
    private const val MAX_FAILSAFE = 5

    @Volatile private var enabled = false
    private var job: Job? = null

    fun start(scope: CoroutineScope, context: Context) {
        stop()
        enabled = true
        val app = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            LogBuffer.i("OCR-DC", "watch on every ${INTERVAL_MS / 1000}s, square=${SQUARE_PX}px, maxFail=$MAX_FAILSAFE")
            while (isActive && enabled) {
                delay(INTERVAL_MS)
                if (!enabled) break
                try {
                    tick(app)
                } catch (t: Throwable) {
                    LogBuffer.w("OCR-DC", "tick: ${t.message}")
                }
            }
        }
    }

    fun stop() {
        enabled = false
        job?.cancel()
        job = null
    }

    private fun tick(context: Context) {
        val sq = ScreenOcr.centerSquarePct(context, SQUARE_PX)
        val x0 = sq[0]; val x1 = sq[1]; val y0 = sq[2]; val y1 = sq[3]
        LogBuffer.i("OCR-DC", "scan center ${SQUARE_PX}px X$x0-$x1 Y$y0-$y1")

        val reconnect = ScreenOcr.findText(context, "reconnect", x0, x1, y0, y1)
        if (reconnect != null) {
            LogBuffer.i("OCR-DC", "Reconnect found — tapping")
            val svc = TapService.instance
            if (svc != null) {
                svc.clickAt(reconnect.centerX, reconnect.centerY)
            } else if (com.cwbridge.android.ShizukuShell.isReady()) {
                com.cwbridge.android.ShizukuShell.exec(
                    "input tap ${reconnect.centerX.toInt()} ${reconnect.centerY.toInt()}",
                )
            }
            AntiDisconnect.noteActivity()
            return
        }

        val disconnected = ScreenOcr.findText(context, "disconnect", x0, x1, y0, y1)
        if (disconnected == null) {
            LogBuffer.i("OCR-DC", "no disconnect UI")
            return
        }

        // Dead disconnect — no Reconnect button
        val n = BridgeControl.bumpDisconnectFailsafe()
        LogBuffer.w("OCR-DC", "Disconnected w/o Reconnect — failsafe=$n/$MAX_FAILSAFE")
        if (n > MAX_FAILSAFE) {
            val msg =
                "Bridge stopped: Roblox disconnected $n times without a Reconnect button " +
                    "(failsafe limit $MAX_FAILSAFE). Open Roblox/CatWeb manually, then start the bridge again."
            LogBuffer.e("OCR-DC", msg)
            BridgeControl.stopBridgeWithError(msg)
            stop()
            return
        }
        // Force relaunch Roblox / CatWeb
        LogBuffer.w("OCR-DC", "relaunching Roblox (failsafe $n)")
        BridgeControl.restartRoblox(context)
        BridgeStatus.set(OverlayState.WAITING, "Relaunch after disconnect ($n/$MAX_FAILSAFE)")
    }
}
''')
    print("wrote DisconnectOcrWatch.kt")

def patch_bridge_control() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()

    # Expand Hooks
    if "stopBridgeWithError" not in t:
        t = t.replace(
            "interface Hooks {\n        fun restartBridge()\n    }",
            "interface Hooks {\n        fun restartBridge()\n        fun stopBridgeWithError(message: String)\n    }",
            1,
        )

    if "fun stopBridgeWithError" not in t:
        inject = '''
    fun stopBridgeWithError(message: String) {
        try {
            hooks?.stopBridgeWithError(message)
        } catch (t: Throwable) {
            LogBuffer.e("Control", "stopBridgeWithError: ${t.message}")
        }
    }

'''
        idx = t.rfind("}")
        t = t[:idx] + inject + "}\n"

    # openDomainsOnCwLoad: only first domain
    t = t.replace(
        "LogBuffer.i(\"Control\", \"CW load: opening ${domains.size} domain(s)\")\n        // First domain: just navigate current tab via URL bar.\n        // Further domains: try tabs-count → + then URL bar.\n        return openDomains(domains)",
        "val one = domains.take(1)\n        LogBuffer.i(\"Control\", \"CW load: opening single domain ${one.firstOrNull()}\")\n        return openSingleDomainOnLoad(one.first())",
    )

    if "fun openSingleDomainOnLoad" not in t:
        single = r'''
    /**
     * Navigate the current CatWeb tab to [domain] via OCR URL bar
     * (region X 5-90%, Y 0-50%). No new-tab / multi-tab logic.
     */
    fun openSingleDomainOnLoad(domain: String): String {
        val d = domain.trim()
        if (d.isEmpty()) return "empty domain"
        val ctx = try {
            // TapService is an Application-context-ish service; prefer its context
            TapService.instance ?: return "$d: no accessibility (need CWBridge Tap)"
        } catch (_: Throwable) {
            return "$d: no accessibility"
        }
        val appCtx = ctx.applicationContext

        // OCR: "Search or type a URL" (case-insensitive substring)
        val hit = ScreenOcr.findText(
            appCtx,
            "search or type a url",
            x0Pct = 5f,
            x1Pct = 90f,
            y0Pct = 0f,
            y1Pct = 50f,
        ) ?: ScreenOcr.findText(
            appCtx,
            "type a url",
            x0Pct = 5f,
            x1Pct = 90f,
            y0Pct = 0f,
            y1Pct = 50f,
        ) ?: ScreenOcr.findText(
            appCtx,
            "search or type",
            x0Pct = 5f,
            x1Pct = 90f,
            y0Pct = 0f,
            y1Pct = 50f,
        )

        val tapped = if (hit != null) {
            ctx.clickAt(hit.centerX, hit.centerY)
        } else {
            // Fallback: classic percent URL bar
            LogBuffer.w("Control", "OCR URL bar miss — percent fallback 50%,6%")
            ctx.clickAtPercent(50f, 6f)
        }
        if (!tapped) return "$d: URL bar tap failed"
        try { Thread.sleep(400) } catch (_: InterruptedException) {}

        val typed = if (ShizukuShell.isReady()) {
            ShizukuShell.inputText(d)
        } else {
            ctx.sendText(d)
        }
        if (!typed) return "$d: type failed"
        try { Thread.sleep(200) } catch (_: InterruptedException) {}

        val enter = when {
            ShizukuShell.isReady() -> ShizukuShell.pressEnter()
            else -> ctx.pressEnter()
        }
        return if (enter) "$d: ok (OCR URL bar)" else "$d: Enter failed"
    }

'''
        idx = t.rfind("}")
        t = t[:idx] + single + "}\n"

    p.write_text(t)
    print("BridgeControl patched")

def patch_main() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    t = p.read_text()

    # Wire DisconnectOcrWatch on bridge start / stop
    if "DisconnectOcrWatch" not in t:
        # import
        if "import com.cwbridge.android.bridge.CatWebTracker" in t:
            t = t.replace(
                "import com.cwbridge.android.bridge.CatWebTracker",
                "import com.cwbridge.android.bridge.CatWebTracker\nimport com.cwbridge.android.bridge.DisconnectOcrWatch",
                1,
            )
        # on start after ensureLogcatRunning
        if "ensureLogcatRunning()" in t and "DisconnectOcrWatch.start" not in t:
            t = t.replace(
                "ensureLogcatRunning()",
                "ensureLogcatRunning()\n            DisconnectOcrWatch.start(bridgeScope, applicationContext)\n            BridgeControl.resetDisconnectFailsafe()",
                1,
            )
        # on stop
        if "logcatReader.stop()" in t and "DisconnectOcrWatch.stop" not in t:
            t = t.replace(
                "logcatReader.stop()",
                "DisconnectOcrWatch.stop()\n            logcatReader.stop()",
                1,
            )

    # Hooks: stopBridgeWithError
    if "stopBridgeWithError" not in t:
        # Find setHooks or BridgeControl.setHooks
        if "BridgeControl.setHooks" in t:
            # may already be a lambda — expand carefully
            pass
        # Inject method and register hooks near onCreate end or where hooks set
        if "fun stopBridgeWithError" not in t:
            method = r'''
    private fun stopBridgeWithError(message: String) {
        runOnUiThread {
            if (bridgeRunning) {
                // mirror stop branch of toggleBridge
                bridgeRunning = false
                BridgeControl.cancelOpenCatWeb()
                DisconnectOcrWatch.stop()
                try { logcatReader.stop() } catch (_: Throwable) {}
                try { invokeEngine.stop() } catch (_: Throwable) {}
                try { AntiDisconnect.stop() } catch (_: Throwable) {}
                refreshBridgeUi()
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
            # before toggleBridge
            if "private fun toggleBridge()" in t:
                t = t.replace("private fun toggleBridge()", method + "    private fun toggleBridge()", 1)

        # Register hooks — search for existing setHooks
        if "BridgeControl.setHooks" not in t:
            # after setExecutionEngine if present
            if "BridgeControl.setExecutionEngine" in t:
                t = t.replace(
                    "BridgeControl.setExecutionEngine(executionEngine)",
                    "BridgeControl.setExecutionEngine(executionEngine)\n        BridgeControl.setHooks(object : BridgeControl.Hooks {\n            override fun restartBridge() { runOnUiThread { if (bridgeRunning) { toggleBridge(); toggleBridge() } } }\n            override fun stopBridgeWithError(message: String) { stopBridgeWithError(message) }\n        })",
                    1,
                )
        else:
            # hooks already exist — try to leave as is; user may need manual
            print("WARN: setHooks already present — ensure stopBridgeWithError is implemented")

    p.write_text(t)
    print("MainActivity patched")

def main() -> None:
    patch_gradle()
    write_screen_ocr()
    write_disconnect_watch()
    patch_bridge_control()
    patch_main()
    print("done")

if __name__ == "__main__":
    main()
