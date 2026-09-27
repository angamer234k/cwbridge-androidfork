package com.cwbridge.android

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global bridge health for the floating overlay.
 *
 * RED    = error in app / custom service
 * YELLOW = waiting for CatWeb / Roblox FLog init
 * GREEN  = active, listening
 * IDLE   = bridge stopped (dim)
 */
enum class OverlayState {
    IDLE,
    WAITING,
    ACTIVE,
    ERROR,
}

object BridgeStatus {
    @Volatile
    var state: OverlayState = OverlayState.IDLE
        private set

    @Volatile
    var detail: String = "Bridge off"
        private set

    /** Sticky error — set by real faults, survives recompute(). */
    @Volatile
    var hasStickyError: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(OverlayState, String) -> Unit>()

    fun addListener(l: (OverlayState, String) -> Unit) {
        listeners.add(l)
        l(state, detail)
    }

    fun removeListener(l: (OverlayState, String) -> Unit) {
        listeners.remove(l)
    }

    /**
     * Report a fault for the overlay.
     * @param remember true = sticky (cleared only by clearError), used for real
     *   errors like "accessibility off". false = transient, e.g. one failed
     *   service action, which should not permanently pin the dot red.
     */
    fun reportError(message: String, remember: Boolean = true) {
        if (remember) hasStickyError = true
        set(OverlayState.ERROR, message)
    }

    fun clearError() {
        hasStickyError = false
    }

    fun set(state: OverlayState, detail: String = "") {
        this.state = state
        this.detail = detail.ifBlank {
            when (state) {
                OverlayState.IDLE -> "Bridge off"
                OverlayState.WAITING -> "Waiting for CatWeb…"
                OverlayState.ACTIVE -> "Listening"
                OverlayState.ERROR -> "Error"
            }
        }
        // One bad listener must not break status updates for the overlay.
        listeners.forEach {
            try {
                it(this.state, this.detail)
            } catch (_: Throwable) {
            }
        }
    }

    /** Recompute from live signals (call after bridge toggle / logcat / FLog). */
    fun recompute(
        bridgeRunning: Boolean,
        hasLogcat: Boolean,
        a11yConnected: Boolean,
        sawRobloxFlog: Boolean,
        hasError: Boolean,
    ) {
        when {
            hasError || hasStickyError -> set(OverlayState.ERROR, "Error — check logs")
            !bridgeRunning -> set(OverlayState.IDLE, "Bridge off")
            !a11yConnected -> set(OverlayState.ERROR, "Accessibility off")
            !hasLogcat -> set(OverlayState.WAITING, "Need READ_LOGS")
            !sawRobloxFlog -> set(OverlayState.WAITING, "Waiting for CatWeb…")
            else -> set(OverlayState.ACTIVE, "Listening")
        }
    }
}
