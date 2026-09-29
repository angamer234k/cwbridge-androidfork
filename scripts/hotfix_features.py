#!/usr/bin/env python3
"""Add ML Kit OCR: URL-bar find + disconnect 750px watch. Compile-safe."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SCREEN_OCR = r'''package com.cwbridge.android.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Capture + on-device OCR (ML Kit Latin).
 * Prefer Shizuku screencap (works on many FLAG_SECURE games).
 */
object ScreenOcr {

    data class Hit(
        val text: String,
        val centerX: Float,
        val centerY: Float,
        val bounds: Rect,
    )

    fun capture(context: Context): Bitmap? {
        if (ShizukuShell.isReady()) {
            val dir = context.getExternalFilesDir(null) ?: context.cacheDir
            val file = File(dir, "cw_screencap.png")
            try {
                if (file.exists()) file.delete()
            } catch (_: Throwable) {
            }
            val path = file.absolutePath
            val (code, out) = ShizukuShell.exec("screencap -p \"$path\" && chmod 644 \"$path\"")
            LogBuffer.i(
                "OCR",
                "screencap exit=$code exists=${file.exists()} size=${file.length()} ${out.take(40)}",
            )
            if (code == 0 && file.exists() && file.length() > 100L) {
                return try {
                    BitmapFactory.decodeFile(path)
                } catch (t: Throwable) {
                    LogBuffer.w("OCR", "decode: ${t.message}")
                    null
                }
            }
        }
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
     * Find [query] (case-insensitive substring) inside a percent crop of the screen.
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
            LogBuffer.w("OCR", "no bitmap for \"$query\"")
            return null
        }
        try {
            val w = full.width
            val h = full.height
            if (w < 2 || h < 2) return null
            val left = ((x0Pct / 100f) * w).toInt().coerceIn(0, w - 1)
            val top = ((y0Pct / 100f) * h).toInt().coerceIn(0, h - 1)
            val right = ((x1Pct / 100f) * w).toInt().coerceIn(left + 1, w)
            val bottom = ((y1Pct / 100f) * h).toInt().coerceIn(top + 1, h)
            val crop = try {
                Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            } catch (t: Throwable) {
                LogBuffer.w("OCR", "crop: ${t.message}")
                full
            }
            val hits = recognizeAll(crop)
            if (crop !== full) {
                try {
                    crop.recycle()
                } catch (_: Throwable) {
                }
            }
            var best: Hit? = null
            for (raw in hits) {
                if (!raw.text.lowercase().contains(q)) continue
                val b = Rect(
                    raw.bounds.left + left,
                    raw.bounds.top + top,
                    raw.bounds.right + left,
                    raw.bounds.bottom + top,
                )
                val hit = Hit(raw.text, b.exactCenterX(), b.exactCenterY(), b)
                if (best == null || hit.text.length > best.text.length) best = hit
            }
            if (best != null) {
                LogBuffer.i(
                    "OCR",
                    "hit \"${best.text.take(40)}\" @ (${best.centerX.toInt()},${best.centerY.toInt()}) q=\"$query\"",
                )
            } else {
                LogBuffer.w("OCR", "no hit for \"$query\" X$x0Pct-$x1Pct Y$y0Pct-$y1Pct")
            }
            return best
        } finally {
            try {
                full.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun recognizeAll(bitmap: Bitmap): List<Hit> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val client = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
            val result = Tasks.await(client.process(image), 12, TimeUnit.SECONDS)
            val list = ArrayList<Hit>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    list.add(
                        Hit(
                            text = line.text,
                            centerX = box.exactCenterX(),
                            centerY = box.exactCenterY(),
                            bounds = Rect(box),
                        ),
                    )
                }
            }
            list
        } catch (t: Throwable) {
            LogBuffer.w("OCR", "recognizeAll: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    fun centerSquarePct(context: Context, sizePx: Int): FloatArray {
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
'''

DISCONNECT = r'''package com.cwbridge.android.bridge

import android.content.Context
import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Every minute, OCR a center 750x750 window for Disconnected / Reconnect.
 * - Reconnect visible → tap it
 * - Disconnected without Reconnect → bump failsafe (max 5 → stop bridge)
 */
object DisconnectOcrWatch {

    private const val INTERVAL_MS = 60_000L
    private const val SQUARE_PX = 750

    @Volatile
    private var enabled = false
    private var job: Job? = null

    fun start(scope: CoroutineScope, context: Context) {
        stop()
        enabled = true
        val app = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            LogBuffer.i("OCR-DC", "watch every ${INTERVAL_MS / 1000}s, square=${SQUARE_PX}px")
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
        val x0 = sq[0]
        val x1 = sq[1]
        val y0 = sq[2]
        val y1 = sq[3]
        LogBuffer.i("OCR-DC", "scan center ${SQUARE_PX}px")

        val reconnect = ScreenOcr.findText(context, "reconnect", x0, x1, y0, y1)
        if (reconnect != null) {
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
        }

        val disconnected = ScreenOcr.findText(context, "disconnect", x0, x1, y0, y1)
        if (disconnected == null) {
            LogBuffer.i("OCR-DC", "no disconnect UI")
            return
        }

        val n = BridgeControl.bumpDisconnectFailsafe()
        LogBuffer.w("OCR-DC", "Disconnected w/o Reconnect — failsafe=$n")
        if (n >= 5) {
            // bumpDisconnectFailsafe already stops the bridge at max
            stop()
            return
        }
        LogBuffer.w("OCR-DC", "relaunching Roblox (failsafe $n)")
        BridgeControl.restartRoblox(context)
        BridgeStatus.set(OverlayState.WAITING, "Relaunch after disconnect ($n/5)")
    }
}
'''

def patch_gradle() -> None:
    p = ROOT / "app/build.gradle.kts"
    t = p.read_text()
    dep = 'implementation("com.google.mlkit:text-recognition:16.0.1")'
    if dep in t:
        print("mlkit already")
        return
    needle = 'implementation("com.google.code.gson:gson:2.10.1")'
    if needle not in t:
        raise SystemExit("gson missing")
    t = t.replace(
        needle,
        needle + "\n    // On-device OCR (Latin) for CatWeb URL bar + disconnect UI\n    " + dep,
        1,
    )
    p.write_text(t)
    print("mlkit added")

def patch_bridge() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = p.read_text()
    old = '''        // Percent URL bar (OCR deferred — ML Kit blocked CI)
        val tapped = svc.clickAtPercent(50f, 6f)'''
    new = '''        val appCtx = svc.applicationContext
        // OCR URL bar: X 5-90%, Y 0-50% (case-insensitive)
        val hit = ScreenOcr.findText(appCtx, "search or type a url", 5f, 90f, 0f, 50f)
            ?: ScreenOcr.findText(appCtx, "type a url", 5f, 90f, 0f, 50f)
            ?: ScreenOcr.findText(appCtx, "search or type", 5f, 90f, 0f, 50f)
        val tapped = if (hit != null) {
            svc.clickAt(hit.centerX, hit.centerY)
        } else {
            LogBuffer.w("Control", "OCR URL bar miss — percent fallback 50%,6%")
            svc.clickAtPercent(50f, 6f)
        }'''
    if old in t:
        t = t.replace(old, new, 1)
        p.write_text(t)
        print("openSingleDomain uses OCR")
    elif "ScreenOcr.findText" in t:
        print("OCR URL already wired")
    else:
        print("WARN: openSingleDomain block not found")

def patch_main() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    t = p.read_text()

    if "import com.cwbridge.android.bridge.DisconnectOcrWatch" not in t:
        t = t.replace(
            "import com.cwbridge.android.bridge.CatWebTracker",
            "import com.cwbridge.android.bridge.CatWebTracker\nimport com.cwbridge.android.bridge.DisconnectOcrWatch",
            1,
        )

    # Start only in bridge ON path
    if "DisconnectOcrWatch.start" not in t:
        marker = "            AntiDisconnect.start(bridgeScope)\n            ensureLogcatRunning()"
        if marker in t:
            t = t.replace(
                marker,
                "            AntiDisconnect.start(bridgeScope)\n"
                "            ensureLogcatRunning()\n"
                "            DisconnectOcrWatch.start(bridgeScope, applicationContext)\n"
                "            BridgeControl.resetDisconnectFailsafe()",
                1,
            )
            print("OCR watch on start")
        else:
            print("WARN: start marker missing")

    # Stop in bridge OFF path
    if "DisconnectOcrWatch.stop()" not in t:
        marker = (
            "        if (bridgeRunning) {\n"
            "            bridgeRunning = false\n"
            "            BridgeControl.cancelOpenCatWeb()\n"
            "            logcatReader.stop()"
        )
        if marker in t:
            t = t.replace(
                marker,
                "        if (bridgeRunning) {\n"
                "            bridgeRunning = false\n"
                "            BridgeControl.cancelOpenCatWeb()\n"
                "            DisconnectOcrWatch.stop()\n"
                "            logcatReader.stop()",
                1,
            )
            print("OCR watch on stop")

    # Also stop in stopBridgeWithError
    if "DisconnectOcrWatch.stop()" not in t.split("stopBridgeWithError")[1].split("toggleBridge")[0]:
        t = t.replace(
            "                BridgeControl.cancelOpenCatWeb()\n                    try { logcatReader.stop()",
            "                BridgeControl.cancelOpenCatWeb()\n"
            "                DisconnectOcrWatch.stop()\n"
            "                try { logcatReader.stop()",
            1,
        )

    p.write_text(t)
    print("MainActivity done")

def main() -> None:
    patch_gradle()
    (ROOT / "app/src/main/java/com/cwbridge/android/bridge/ScreenOcr.kt").write_text(SCREEN_OCR)
    print("ScreenOcr written")
    (ROOT / "app/src/main/java/com/cwbridge/android/bridge/DisconnectOcrWatch.kt").write_text(DISCONNECT)
    print("DisconnectOcrWatch written")
    patch_bridge()
    patch_main()
    print("done")

if __name__ == "__main__":
    main()
