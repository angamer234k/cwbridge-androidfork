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

    /** Hooks MainActivity installs so the server can drive the running bridge. */
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
        } catch (t: Throwable) {
            LogBuffer.e("Control", "stopBridgeWithError: ${t.message}")
        }
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


    /** CatWeb: Make a Website! */
    const val CATWEB_PLACE_ID = "16855862021"
    private const val CATWEB_DEEPLINK = "roblox://placeId=$CATWEB_PLACE_ID"
    private const val CATWEB_HTTPS =
        "https://www.roblox.com/games/start?placeId=$CATWEB_PLACE_ID"

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var catwebOpenRunnable: Runnable? = null

    /** After delayMs, if CatWeb never booted, open the place via deeplink. */
    fun scheduleOpenCatWebIfNeeded(context: Context, delayMs: Long = 10_000L) {
        cancelOpenCatWeb()
        val appCtx = context.applicationContext
        val r = Runnable {
            catwebOpenRunnable = null
            if (CatWebTracker.seenBoot || CatWebTracker.ready) {
                LogBuffer.i("Control", "CatWeb already seen — skip auto-open")
                return@Runnable
            }
            LogBuffer.i("Control", "no CatWeb boot in ${delayMs}ms — opening deeplink")
            openCatWeb(appCtx)
        }
        catwebOpenRunnable = r
        mainHandler.postDelayed(r, delayMs)
        LogBuffer.i("Control", "scheduled CatWeb auto-open in ${delayMs}ms")
    }

    fun cancelOpenCatWeb() {
        catwebOpenRunnable?.let { mainHandler.removeCallbacks(it) }
        catwebOpenRunnable = null
    }

    fun openCatWeb(context: Context): String {
        return try {
            val deep = Intent(Intent.ACTION_VIEW, Uri.parse(CATWEB_DEEPLINK)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(deep)
                LogBuffer.i("Control", "opened $CATWEB_DEEPLINK")
                "opening CatWeb (deeplink)"
            } catch (t: Throwable) {
                LogBuffer.w("Control", "deeplink failed: ${t.message} — https fallback")
                val web = Intent(Intent.ACTION_VIEW, Uri.parse(CATWEB_HTTPS)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(web)
                "opening CatWeb (https fallback)"
            }
        } catch (t: Throwable) {
            LogBuffer.e("Control", "openCatWeb: ${t.message}")
            "open CatWeb failed: ${t.message}"
        }
    }

    /** True when this device can take screenshots (Android 11+). */
    fun screenshotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Prefer Shizuku `screencap` (works on many FLAG_SECURE surfaces like Roblox).
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

    /**
     * Restart Roblox: force-stop it, then relaunch. Needs Shizuku to force-stop
     * without the app holding the kill permission; falls back to a plain launch.
     */
    fun restartRoblox(context: Context, packageName: String = "com.roblox.client"): String {
        CatWebTracker.armForNextReady()

        if (packageName.isBlank()) return "no Roblox package name configured"
        return try {
            if (ShizukuShell.isReady()) {
                val (code, out) = ShizukuShell.exec("am force-stop $packageName")
                LogBuffer.i("Control", "force-stop $packageName exit=$code ${out.take(120)}")
                // Drop hung logcat --pid=old session so tail follows the new process
                LogcatReader.requestReconnect("restartRoblox")
                if (code != 0) return "force-stop failed: ${out.take(160)}"
            } else {
                LogBuffer.w("Control", "Shizuku not ready — cannot force-stop Roblox")
                return "force-stop needs Shizuku (open the Shizuku app and grant CWBridge)"
            }
            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                "Roblox restarting"
            } else {
                LogBuffer.w("Control", "no launch intent for $packageName — CatWeb deeplink")
                openCatWeb(context)
            }
        } catch (t: Throwable) {
            LogBuffer.e("Control", "restartRoblox: ${t.message}")
            "restart failed: ${t.message}"
        }
    }


    /**
     * Open domains in CatWeb: Ctrl+T, tap URL bar by percent coords, type, Enter.
     * Roblox is OpenGL so the a11y tree is empty. Ends with Ctrl+1 on first tab.
     */
    fun openDomains(
        domains: List<String>,
        urlBarXPct: Float = 50f,
        urlBarYPct: Float = 6f,
        pauseMs: Long = 1000L,
    ): String {
        if (domains.isEmpty()) return "no domains"
        val svc = TapService.instance
        val results = mutableListOf<String>()
        for ((i, raw) in domains.withIndex()) {
            val d = raw.trim()
            if (d.isEmpty()) continue
            LogBuffer.i("Control", "openDomains [${i + 1}/${domains.size}] $d")
            // CatWeb mobile: tabs-count → + (Ctrl+T is PC-only per CatDocs)
            var opened = false
            if (ShizukuShell.isReady()) {
                opened = ShizukuShell.openNewTabByPlusTap()
            }
            if (!opened && svc != null) {
                // a11y: tabs-count region then +
                for ((px, py) in listOf(88f to 7f, 90f to 8f, 86f to 9f)) {
                    if (svc.clickAtPercent(px, py)) {
                        LogBuffer.i("Control", "tabs-count a11y @$px%,$py%")
                        opened = true
                        break
                    }
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
                for ((px, py) in listOf(92f to 8f, 95f to 12f, 92f to 92f, 50f to 92f)) {
                    svc.clickAtPercent(px, py)
                }
            }
            if (!opened) {
                results += "$d: new-tab failed (tabs-count / +)"
                continue
            }
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            val tapped = svc?.clickAtPercent(urlBarXPct, urlBarYPct) == true
            if (!tapped) { results += "$d: URL-bar tap failed"; continue }
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            val typed = ShizukuShell.isReady() && ShizukuShell.inputText(d)
            if (!typed) { results += "$d: type failed (need Shizuku)"; continue }
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            val enter = when {
                ShizukuShell.isReady() -> ShizukuShell.pressEnter()
                svc != null -> svc.pressEnter()
                else -> false
            }
            results += if (enter) "$d: ok" else "$d: Enter failed"
            try { Thread.sleep(pauseMs) } catch (_: InterruptedException) {}
        }
        val ctrl1 = ShizukuShell.isReady() && ShizukuShell.pressCtrlNumber(1)
        results += if (ctrl1) "Ctrl+1 ok" else "Ctrl+1 failed"
        return results.joinToString("; ")
    }


    /** How many forced Roblox relaunches after a dead disconnect (no Reconnect). */
    @Volatile
    var disconnectFailsafe: Int = 0
        private set

    private const val MAX_FAILSAFE = 5

    fun bumpDisconnectFailsafe(): Int {
        disconnectFailsafe += 1
        LogBuffer.w("Control", "disconnect failsafe now=$disconnectFailsafe/$MAX_FAILSAFE")
        if (disconnectFailsafe >= MAX_FAILSAFE) {
            val msg =
                "Bridge stopped: Roblox disconnected $disconnectFailsafe times " +
                    "without recovery (failsafe limit $MAX_FAILSAFE). " +
                    "Open Roblox/CatWeb manually, then start the bridge again."
            stopBridgeWithError(msg)
        }
        return disconnectFailsafe
    }

    fun resetDisconnectFailsafe() {
        disconnectFailsafe = 0
    }

    /**
     * Called when CatWeb logs "finished". Opens domains saved for auto-load
     * by focusing the URL bar (no Ctrl+T — mobile CatWeb is not Chrome).
     */
    fun openDomainsOnCwLoad(context: Context): String {
        val domains = loadAutoOpenDomains(context)
        if (domains.isEmpty()) {
            LogBuffer.i("Control", "CW load: no auto-open domains configured")
            return "no auto-open domains"
        }
        val one = domains.take(1)
        LogBuffer.i("Control", "CW load: opening single domain ${one.firstOrNull()}")
        return openSingleDomainOnLoad(one.first())
    }

    fun loadAutoOpenDomains(context: Context): List<String> {
        return try {
            com.cwbridge.android.data.UserFileStore.init(context.applicationContext)
            val raw = com.cwbridge.android.data.UserFileStore.getSetting(
                context.applicationContext,
                "auto_open_domains",
                "",
            ) ?: ""
            raw.lines().flatMap { it.split(",", ";") }.map { it.trim() }.filter { it.isNotEmpty() }
        } catch (t: Throwable) {
            LogBuffer.w("Control", "loadAutoOpenDomains: ${t.message}")
            emptyList()
        }
    }

    fun saveAutoOpenDomains(context: Context, domains: List<String>) {
        com.cwbridge.android.data.UserFileStore.init(context.applicationContext)
        com.cwbridge.android.data.UserFileStore.putSetting(
            context.applicationContext,
            "auto_open_domains",
            domains.joinToString("\n"),
        )
    }


    /**
     * Navigate the current CatWeb tab to [domain] via OCR URL bar
     * (region X 5-90%, Y 0-50%). No new-tab / multi-tab logic.
     */
    fun openSingleDomainOnLoad(domain: String): String {
        val d = domain.trim()
        if (d.isEmpty()) return "empty domain"
        val svc = TapService.instance
            ?: return "$d: no accessibility (need CWBridge Tap)"
        val appCtx = svc.applicationContext
        // OCR URL bar: X 5-90%, Y 0-50% (case-insensitive)
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
        return if (enter) "$d: ok" else "$d: Enter failed"
    }

}
