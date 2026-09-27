package com.cwbridge.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Actions the web server can trigger remotely, kept apart from MainActivity so
 * the server does not need an Activity reference.
 */
object BridgeControl {

    /** Hooks MainActivity installs so the server can drive the running bridge. */
    interface Hooks {
        fun restartBridge()
    }

    @Volatile
    private var hooks: Hooks? = null

    @Volatile
    private var executionEngine: ExecutionEngine? = null

    fun setHooks(h: Hooks?) {
        hooks = h
    }

    fun setExecutionEngine(engine: ExecutionEngine?) {
        executionEngine = engine
    }

    /** Run a saved service from the web UI. */
    fun runService(service: Service): String {
        val engine = executionEngine
        if (engine == null) return "execution engine not ready"
        return try {
            engine.executeService(service)
            "running ${service.name}"
        } catch (t: Throwable) {
            LogBuffer.e("Control", "runService: ${t.message}")
            "run failed: ${t.message}"
        }
    }

    /** Re-register a service's triggers after it was edited from the web UI. */
    fun reloadTriggers(service: Service) {
        executionEngine?.updateServiceTriggers(service)
    }

    fun restartBridge(): String {
        val h = hooks
        if (h == null) return "bridge hooks not ready"
        return try {
            h.restartBridge()
            "bridge restarting"
        } catch (t: Throwable) {
            LogBuffer.e("Control", "restartBridge: ${t.message}")
            "restart failed: ${t.message}"
        }
    }

    /** True when this device can take screenshots (Android 11+). */
    fun screenshotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun takeScreenshot(): Result<ByteArray> {
        val svc = TapService.instance
            ?: return Result.failure(IllegalStateException("CWBridge Tap (accessibility) is not connected"))
        if (!screenshotSupported()) {
            return Result.failure(
                UnsupportedOperationException("screenshot needs Android 11 (API 30)+"),
            )
        }
        return takeScreenshotAsync(svc)
    }

    /**
     * AccessibilityService.takeScreenshot is callback-based; block briefly on a
     * latch. The screenshot itself is small and this is off the main thread.
     */
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

    /**
     * Restart Roblox: force-stop it, then relaunch. Needs Shizuku to force-stop
     * without the app holding the kill permission; falls back to a plain launch.
     */
    fun restartRoblox(context: Context, packageName: String = "com.roblox.client"): String {
        if (packageName.isBlank()) return "no Roblox package name configured"
        return try {
            if (ShizukuShell.isReady()) {
                val (code, out) = ShizukuShell.exec("am force-stop $packageName")
                LogBuffer.i("Control", "force-stop $packageName exit=$code ${out.take(120)}")
                if (code != 0) return "force-stop failed: ${out.take(160)}"
            } else {
                LogBuffer.w("Control", "Shizuku not ready — cannot force-stop Roblox")
                return "force-stop needs Shizuku (open the Shizuku app and grant CWBridge)"
            }
            context.packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                "Roblox restarting"
            } ?: "Roblox installed but no launch intent"
        } catch (t: Throwable) {
            LogBuffer.e("Control", "restartRoblox: ${t.message}")
            "restart failed: ${t.message}"
        }
    }
}