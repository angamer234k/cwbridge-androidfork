package com.cwbridge.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keep-alive + disconnect recovery while the bridge is running.
 * - Log line mentions disconnect \u2192 soft recover + keep-alive tap
 * - No console/invoke activity for 5 minutes \u2192 tap at ~90% x, 1% y
 */
object AntiDisconnect {
    private const val IDLE_MS = 5 * 60 * 1000L
    private const val TICK_MS = 15_000L
    private const val KEEP_ALIVE_X = 90f
    private const val KEEP_ALIVE_Y = 1f
    private var lastTapTime: Long = 0L
    private const val MIN_TAP_INTERVAL_MS = 3000L

    @Volatile
    private var lastActivityMs: Long = System.currentTimeMillis()

    @Volatile
    private var enabled: Boolean = false

    private var job: Job? = null

    fun noteActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun onLogLine(raw: String) {
        val lower = raw.lowercase()
        if (lower.contains("disconnect") || lower.contains("disconnected") ||
            lower.contains("connection lost") || lower.contains("reconnecting")
        ) {
            LogBuffer.w("AntiDC", "disconnect signal \u2014 soft recover")
            noteActivity()
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, "Reconnecting\u2026")
            }
            tryKeepAliveTap("disconnect")
        }
    }

    fun start(scope: CoroutineScope) {
        stop()
        enabled = true
        noteActivity()
        job = scope.launch(Dispatchers.IO) {
            LogBuffer.i(
                "AntiDC",
                "watchdog on (idle ${IDLE_MS / 60000}m \u2192 tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}%)",
            )
            while (isActive && enabled) {
                delay(TICK_MS)
                if (!enabled) break
                val idle = System.currentTimeMillis() - lastActivityMs
                if (idle >= IDLE_MS) {
                    LogBuffer.i("AntiDC", "idle ${idle / 1000}s \u2014 keep-alive tap")
                    tryKeepAliveTap("idle")
                    noteActivity()
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
        val svc = TapService.instance
        if (svc == null) {
            LogBuffer.w("AntiDC", "no accessibility \u2014 cannot tap ($reason)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTapTime < MIN_TAP_INTERVAL_MS) {
            LogBuffer.i("AntiDC", "skipping keep-alive tap: too soon (${now - lastTapTime}ms < ${MIN_TAP_INTERVAL_MS}ms)")
            return
        }
        lastTapTime = now
        val ok = svc.clickAtPercent(KEEP_ALIVE_X, KEEP_ALIVE_Y)
        LogBuffer.i("AntiDC", "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) ok=$ok")
    }
}
