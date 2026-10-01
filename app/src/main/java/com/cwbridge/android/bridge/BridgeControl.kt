package com.cwbridge.android.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import com.cwbridge.android.data.Service
import com.cwbridge.android.engine.ExecutionEngine
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Actions the web server can trigger remotely, kept apart from MainActivity so
 * the server does not need an Activity reference.
 */
object BridgeControl {

    interface Hooks {
        fun restartBridge()
        fun stopBridgeWithError(message: String)
    }

    @Volatile
    private var hooks: Hooks? = null

    @Volatile
    private var executionEngine: ExecutionEngine? = null

    fun setHooks(h: Hooks?) {
        hooks = h
    }

    fun stopBridgeWithError(message: String) {
        try {
            hooks?.stopBridgeWithError(message)
        } catch (_: Throwable) {
        }
    }

    fun screenshotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Prefer Shizuku screencap (works on many FLAG_SECURE surfaces like Roblox).
     * Fall back to AccessibilityService.takeScreenshot (Android 11+).
     */
    fun takeScreenshot(context: Context? = null): Result<ByteArray> {
        if (context != null && ShizukuShell.isReady()) {
            val bytes = screencapPngBytes(context)
            if (bytes != null) {
                LogBuffer.i("Control", "screenshot via Shizuku screencap (${bytes.size} bytes)")
                return Result.success(bytes)
            }
            LogBuffer.w("Control", "screencap failed — trying a11y")
        }
        val svc = TapService.instance
        if (svc != null && screenshotSupported()) {
            return takeScreenshotAsync(svc)
        }
        if (svc == null) {
            return Result.failure(
                IllegalStateException(
                    "screenshot failed: need Shizuku (screencap) or CWBridge Tap accessibility",
                ),
            )
        }
        return Result.failure(
            UnsupportedOperationException("screenshot needs Android 11 (API 30)+ or Shizuku"),
        )
    }

    private fun screencapPngBytes(context: Context): ByteArray? {
        val attempts = listOf(
            "screencap -p 2>/dev/null | base64",
            "screencap -p /data/local/tmp/cwbridge_cap.png && base64 /data/local/tmp/cwbridge_cap.png && rm -f /data/local/tmp/cwbridge_cap.png",
            "screencap -p /sdcard/cwbridge_cap.png && base64 /sdcard/cwbridge_cap.png && rm -f /sdcard/cwbridge_cap.png",
        )
        for (cmd in attempts) {
            try {
                val (code, out) = ShizukuShell.exec(cmd)
                LogBuffer.i("Control", "screencap try exit=$code outLen=${out.length} cmd=${cmd.take(40)}")
                if (code != 0 || out.isBlank()) continue
                val cleaned = out.replace("\n", "").replace("\r", "").replace(" ", "")
                val filtered = cleaned.filter {
                    it.isLetterOrDigit() || it == '+' || it == '/' || it == '='
                }
                if (filtered.length < 200) continue
                val bytes = Base64.decode(filtered, Base64.DEFAULT)
                if (bytes.size > 100 && isPng(bytes)) {
                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes via shell")
                    return bytes
                }
                if (bytes.size > 500) {
                    LogBuffer.i("Control", "screencap ok ${bytes.size} bytes (no png magic)")
                    return bytes
                }
            } catch (t: Throwable) {
                LogBuffer.w("Control", "screencap attempt: ${t.message}")
            }
        }
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.cacheDir
            val file = File(dir, "cw_screencap_web.png")
            if (file.exists()) file.delete()
            val path = file.absolutePath
            val (code, out) = ShizukuShell.exec("screencap -p \"$path\" && chmod 644 \"$path\"")
            LogBuffer.i(
                "Control",
                "screencap appdir exit=$code exists=${file.exists()} size=${file.length()} ${out.take(60)}",
            )
            if (code != 0 || !file.exists() || file.length() < 100L) null
            else file.readBytes()
        } catch (t: Throwable) {
            LogBuffer.w("Control", "screencap appdir: ${t.message}")
            null
        }
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
    }

    private fun takeScreenshotAsync(svc: TapService): Result<ByteArray> {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Result<ByteArray> = Result.failure(IllegalStateException("screenshot timed out"))
        try {
            svc.screenshot { bitmap ->
                result = if (bitmap == null) {
                    Result.failure(IllegalStateException("screenshot unavailable"))
                } else {
                    encodePng(bitmap)
                }
                latch.countDown()
            }
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: Throwable) {
            result = Result.failure(IllegalStateException(t.message ?: t::class.java.simpleName))
        }
        return result
    }

    private fun encodePng(bitmap: Bitmap): Result<ByteArray> = try {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        Result.success(out.toByteArray())
    } catch (t: Throwable) {
        Result.failure(IllegalStateException("png encode failed: ${t.message}"))
    }

    // NOTE: remaining BridgeControl methods live in the previous full file;
    // this push is intentionally focused on screenshot path only — if truncated,
    // bot must not be used; use full file.
}
