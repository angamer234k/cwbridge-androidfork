package com.cwbridge.android.bridge

/**
 * Watches CatWeb console-style lines:
 *   Catweb v*
 *   • loading …
 *   • waiting for server
 *   • finished   ← loading done → overlay goes green
 */
object CatWebTracker {

    /** Fired once when CatWeb prints finished — used to open domains on load. */
    @Volatile
    private var readyCallback: (() -> Unit)? = null

    @Volatile
    private var readyFired: Boolean = false

    fun setOnReadyOnce(cb: (() -> Unit)?) {
        readyCallback = cb
        readyFired = false
    }

    /**
     * After disconnect / OCR reconnect / Roblox relaunch — allow the next
     * CatWeb "finished" line to fire [readyCallback] again (re-open domain).
     */
    fun armForNextReady() {
        ready = false
        readyFired = false
        LogBuffer.i("CatWeb", "armed for next finished (will reopen domain)")
    }

    @Volatile
    var seenBoot: Boolean = false
        private set

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastLine: String = ""
        private set

    fun reset() {
        seenBoot = false
        ready = false
        readyFired = false
        lastLine = ""
    }

    /** @return true if this line was a CatWeb-related status line */
    fun onLogLine(raw: String): Boolean {
        val line = raw.trim()
        if (line.isEmpty()) return false
        val lower = line.lowercase()

        val isCatweb =
            lower.contains("catweb") ||
                lower.contains("waiting for server") ||
                (lower.contains("loading") && (lower.contains("•") || lower.contains("catweb") || lower.contains("·"))) ||
                (lower.contains("finished") && (seenBoot || lower.contains("•") || lower.contains("·") || lower.contains("catweb")))

        if (!isCatweb) return false

        lastLine = line.take(200)
        seenBoot = true
        RobloxLogBuffer.add(line)

        val finished = lower.contains("finished") && !lower.contains("unfinished")

        if (finished) {
            ready = true
            BridgeStatus.set(OverlayState.ACTIVE, "CatWeb ready")
            if (!readyFired) {
                readyFired = true
                try {
                    LogBuffer.i("CatWeb", "finished → onReady (open domain)")
                    readyCallback?.invoke()
                } catch (t: Throwable) {
                    LogBuffer.e("CatWeb", "onReady: ${t.message}")
                }
            } else {
                LogBuffer.i("CatWeb", "finished ignored (already fired — need armForNextReady after reconnect)")
            }
        } else if (
            ready && (
                lower.contains("waiting for server") ||
                    lower.contains("loading") ||
                    (lower.contains("catweb") && (lower.contains("v") || lower.contains("version")))
            )
        ) {
            // Session reloading after reconnect — arm so next finished reopens domain
            armForNextReady()
            val detail = when {
                lower.contains("waiting for server") -> "Waiting for server…"
                lower.contains("loading") -> "CatWeb loading…"
                else -> "CatWeb restarting…"
            }
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, detail)
            }
        } else if (!ready) {
            val detail = when {
                lower.contains("waiting for server") -> "Waiting for server…"
                lower.contains("loading") -> "CatWeb loading…"
                lower.contains("catweb") && (lower.contains("v") || lower.contains("version")) -> "CatWeb starting…"
                else -> "Waiting for CatWeb…"
            }
            if (BridgeStatus.state != OverlayState.ERROR) {
                BridgeStatus.set(OverlayState.WAITING, detail)
            }
        }
        return true
    }
}
