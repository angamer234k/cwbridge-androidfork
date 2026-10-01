package com.cwbridge.android.bridge

import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keep-alive while the bridge is running.
 * Periodic taps at fixed 500px, 2px (top edge — outside CatWeb input/keyboard zone).
 */
object AntiDisconnect {
    private const val KEEP_ALIVE_INTERVAL_MS = 2 * 60 * 1000L
    private const val TICK_MS = 15_000L
    private const val GRACE_MS = 25_000L
    private const val MIN_TAP_INTERVAL_MS = 90_000L
    private const val KEEP_ALIVE_X_PX = 500f
    private const val KEEP_ALIVE_Y_PX = 2f

    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var enabled: Boolean = false
    @Volatile private var lastTapTime: Long = 0L
    private var job: Job? = null

    fun noteActivity() {
        // Kept for callers; keep-alive is periodic only.
    }

    fun onLogLine(raw: String) {
        if (!enabled) return
        if (System.currentTimeMillis() - startedAtMs < GRACE_MS) return

        val lower = raw.lowercase()
        val isConsole =
            lower.contains("flog::") ||
                raw.contains('\u2022') ||
                raw.contains('\u00B7') ||
                lower.contains("catweb") ||
                lower.contains("invoke|")
        if (!isConsole) return

        if (
            lower.contains("disconnect") || lower.contains("disconnected") ||
            lower.contains("connection lost")
        ) {
            LogBuffer.w("AntiDC", "disconnect signal - soft recover (no spam tap)")
            noteActivity()
            CatWebTracker.armForNextReady()
            val hasReconnect = lower.contains("reconnect")
            if (!hasReconnect) {
                val n = BridgeControl.bumpDisconnectFailsafe()
                LogBuffer.w("AntiDC", "dead disconnect hint - failsafe=$n")
            }
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, "Reconnecting...")
            }
        }
    }

    fun start(scope: CoroutineScope) {
        stop()
        enabled = true
        startedAtMs = System.currentTimeMillis()
        lastTapTime = 0L
        job = scope.launch(Dispatchers.IO) {
            LogBuffer.i(
                "AntiDC",
                "periodic keep-alive every ${KEEP_ALIVE_INTERVAL_MS / 1000}s " +
                    "-> tap ${KEEP_ALIVE_X_PX.toInt()}px,${KEEP_ALIVE_Y_PX.toInt()}px " +
                    "(grace ${GRACE_MS / 1000}s)",
            )
            while (isActive && enabled) {
                delay(TICK_MS)
                if (!enabled) break
                val now = System.currentTimeMillis()
                if (now - startedAtMs < GRACE_MS) continue
                if (lastTapTime == 0L || now - lastTapTime >= KEEP_ALIVE_INTERVAL_MS) {
                    tryKeepAliveTap("periodic")
                }
            }
        }
    }

    fun stop() {
        enabled = false
        job?.cancel()
        job = null
        lastTapTime = 0L
    }

    private fun tryKeepAliveTap(reason: String) {
        val now = System.currentTimeMillis()
        if (lastTapTime > 0L && now - lastTapTime < MIN_TAP_INTERVAL_MS) {
            LogBuffer.i("AntiDC", "skip tap: cooldown ${now - lastTapTime}ms")
            return
        }
        lastTapTime = now

        val x = KEEP_ALIVE_X_PX
        val y = KEEP_ALIVE_Y_PX
        val svc = TapService.instance
        if (svc != null) {
            val ok = svc.clickAt(x, y)
            LogBuffer.i("AntiDC", "tap ${x.toInt()}px,${y.toInt()}px ($reason) a11y ok=$ok")
            return
        }
        if (ShizukuShell.isReady()) {
            val xi = x.toInt()
            val yi = y.toInt()
            val (code, r) = ShizukuShell.exec("input tap $xi $yi")
            LogBuffer.i("AntiDC", "tap ${xi}px,${yi}px ($reason) shizuku code=$code $r")
            return
        }
        LogBuffer.w("AntiDC", "no accessibility/Shizuku - cannot tap ($reason)")
    }
}
