package com.cwbridge.android.bridge

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
