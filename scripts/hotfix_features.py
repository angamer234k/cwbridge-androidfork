#!/usr/bin/env python3
"""Remove ML Kit dep; ScreenOcr stubs until CI can compile ML Kit cleanly."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

STUB = r'''package com.cwbridge.android.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Screen capture helper. OCR (ML Kit) temporarily stubbed so releases build;
 * URL-bar open falls back to percent coords. Disconnect OCR logs a skip.
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
            LogBuffer.i("OCR", "screencap exit=$code exists=${file.exists()} size=${file.length()} ${out.take(40)}")
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

    fun findText(
        context: Context,
        query: String,
        x0Pct: Float = 0f,
        x1Pct: Float = 100f,
        y0Pct: Float = 0f,
        y1Pct: Float = 100f,
    ): Hit? {
        // ML Kit not linked in this build — force coordinate fallbacks
        LogBuffer.w("OCR", "stub: no ML Kit — miss for \"$query\" (region X$x0Pct-$x1Pct Y$y0Pct-$y1Pct)")
        // Still exercise capture path so logs show if screencap works
        try {
            capture(context)?.recycle()
        } catch (_: Throwable) {
        }
        return null
    }

    fun regionContains(
        context: Context,
        needle: String,
        x0Pct: Float,
        x1Pct: Float,
        y0Pct: Float,
        y1Pct: Float,
    ): Boolean = findText(context, needle, x0Pct, x1Pct, y0Pct, y1Pct) != null

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

def main() -> None:
    (ROOT / "app/src/main/java/com/cwbridge/android/bridge/ScreenOcr.kt").write_text(STUB)
    print("ScreenOcr stubbed")

    g = ROOT / "app/build.gradle.kts"
    t = g.read_text()
    t2 = re.sub(r"\n\s*// On-device OCR.*?\n\s*implementation\(\"com\.google\.mlkit:text-recognition:[^\"]+\"\)", "\n", t)
    t2 = re.sub(r"\n\s*implementation\(\"com\.google\.mlkit:text-recognition:[^\"]+\"\)", "\n", t2)
    t2 = re.sub(r"\n\s*implementation\(\"com\.google\.android\.gms:play-services-tasks:[^\"]+\"\)", "\n", t2)
    if t2 != t:
        g.write_text(t2)
        print("removed mlkit deps")
    else:
        print("no mlkit lines or already gone")

if __name__ == "__main__":
    main()
