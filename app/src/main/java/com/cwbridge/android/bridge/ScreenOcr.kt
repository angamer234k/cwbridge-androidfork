package com.cwbridge.android.bridge

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
