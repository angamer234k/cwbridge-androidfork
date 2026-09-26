package com.cwbridge.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keep-alive while the bridge is running.
 *
 * IMPORTANT: must NOT react to historical logcat dump or spam taps.
 * - Ignore all signals for GRACE_MS after start
 * - Disconnect match only on CatWeb/FLog-ish lines (not every system log)
 * - Idle keep-alive at most once per IDLE_MS, with long min interval
 * - Tap mid-screen (not status-bar / gesture edge)
 */
object AntiDisconnect {
    private const val IDLE_MS = 5 * 60 * 1000L
    private const val TICK_MS = 30_000L
    private const val GRACE_MS = 20_000L
    private const val MIN_TAP_INTERVAL_MS = 60_000L
    /** Safe-ish dead zone: right side, mid height — avoid top chrome / gesture bar. */
    private const val KEEP_ALIVE_X = 92f
    private const val KEEP_ALIVE_Y = 48f

    @Volatile private var lastActivityMs: Long = System.currentTimeMillis()
    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var enabled: Boolean = false
    @Volatile private var lastTapTime: Long = 0L
    private var job: Job? = null

    fun noteActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun onLogLine(raw: String) {
        if (!enabled) return
        if (System.currentTimeMillis() - startedAtMs < GRACE_MS) return

        val lower = raw.lowercase()
        // Only treat as live Roblox/CatWeb signal — not random system "disconnect"
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
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, "Reconnecting…")
            }
            // Do NOT tap on every disconnect line — that caused continuous edge taps
            // after logcat buffer replay. Idle watchdog still handles long silence.
        } else if (
            lower.contains("flog::") || raw.contains('\u2022') || lower.contains("invoke|")
        ) {
            noteActivity()
        }
    }

    fun start(scope: CoroutineScope) {
        stop()
        enabled = true
        startedAtMs = System.currentTimeMillis()
        noteActivity()
        job = scope.launch(Dispatchers.IO) {
            LogBuffer.i(
                "AntiDC",
                "watchdog on (grace ${GRACE_MS / 1000}s, idle ${IDLE_MS / 60000}m → tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}%)",
            )
            while (isActive && enabled) {
                delay(TICK_MS)
                if (!enabled) break
                if (System.currentTimeMillis() - startedAtMs < GRACE_MS) continue
                val idle = System.currentTimeMillis() - lastActivityMs
                if (idle >= IDLE_MS) {
                    LogBuffer.i("AntiDC", "idle ${idle / 1000}s — keep-alive tap")
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
            LogBuffer.w("AntiDC", "no accessibility — cannot tap ($reason)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTapTime < MIN_TAP_INTERVAL_MS) {
            LogBuffer.i("AntiDC", "skip tap: cooldown ${now - lastTapTime}ms")
            return
        }
        lastTapTime = now
        val ok = svc.clickAtPercent(KEEP_ALIVE_X, KEEP_ALIVE_Y)
        LogBuffer.i("AntiDC", "tap ${KEEP_ALIVE_X.toInt()}%,${KEEP_ALIVE_Y.toInt()}% ($reason) ok=$ok")
    }
}
