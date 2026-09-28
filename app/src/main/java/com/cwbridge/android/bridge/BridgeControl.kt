package com.cwbridge.android.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.os.Build
import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import com.cwbridge.android.data.Service
import com.cwbridge.android.engine.ExecutionEngine
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

    fun bumpDisconnectFailsafe(): Int {
        disconnectFailsafe += 1
        LogBuffer.w("Control", "disconnect failsafe now=$disconnectFailsafe")
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
        LogBuffer.i("Control", "CW load: opening ${domains.size} domain(s)")
        // First domain: just navigate current tab via URL bar.
        // Further domains: try tabs-count → + then URL bar.
        return openDomains(domains)
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

}
