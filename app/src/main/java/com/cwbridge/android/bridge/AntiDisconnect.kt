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
 *
 * Periodic taps every [KEEP_ALIVE_INTERVAL_MS] (not "idle after 5min"),
 * because CatWeb/FLog lines used to reset the idle clock and taps never fired.
 * Tap is mid-right (avoids status bar / gesture edge). Shizuku fallback if a11y down.
 */
object AntiDisconnect {
    /** How often to poke the screen while bridge is on. */
    private const val KEEP_ALIVE_INTERVAL_MS = 2 * 60 * 1000L
    private const val TICK_MS = 15_000L
    private const val GRACE_MS = 25_000L
    private const val MIN_TAP_INTERVAL_MS = 90_000L
    /** Right side, upper-mid — away from top chrome (~5%) and bottom gesture bar. */
    private const val KEEP_ALIVE_X = 88f
    private const val KEEP_ALIVE_Y = 40f

    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var enabled: Boolean = false
    @Volatile private var lastTapTime: Long = 0L
    private var job: Job? = null

    fun noteActivity() {
        // Kept for callers; no longer gates keep-alive (periodic only).
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

        if (lower.contains("disconnect") || lower.contains("disconnected") ||
            lower.contains("connection lost")
        ) {
            LogBuffer.w("AntiDC", "disconnect signal — soft recover (no spam tap)")
            noteActivity()
            CatWebTracker.armForNextReady()
            val hasReconnect = lower.contains("reconnect")
            if (!hasReconnect) {
                val n = BridgeControl.bumpDisconnectFailsafe()
                LogBuffer.w("AntiDC", "dead disconnect hint — failsafe=$n")
            }
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, "Reconnecting\u2026")
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
                    "\u2192 tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% " +
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

        val svc = TapService.instance
        if (svc != null) {
            val ok = svc.clickAtPercent(KEEP_ALIVE_X, KEEP_ALIVE_Y)
            LogBuffer.i(
                "AntiDC",
                "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) a11y ok=$ok",
            )
            return
        }
        if (ShizukuShell.isReady()) {
            val (_, sizeOut) = ShizukuShell.exec("wm size")
            var w = 1080
            var h = 2400
            val m = Regex("""(\d+)x(\d+)""").find(sizeOut)
            if (m != null) {
                w = m.groupValues[1].toIntOrNull() ?: w
                h = m.groupValues[2].toIntOrNull() ?: h
            }
            val x = (w * KEEP_ALIVE_X / 100f).toInt()
            val y = (h * KEEP_ALIVE_Y / 100f).toInt()
            val (code, r) = ShizukuShell.exec("input tap $x $y")
            LogBuffer.i("AntiDC", "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) shizuku $x,$y code=$code $r")
            return
        }
        LogBuffer.w("AntiDC", "no accessibility/Shizuku — cannot tap ($reason)")
    }
}
