package com.cwbridge.android

/**
 * Watches CatWeb console-style lines:
 *   Catweb v*
 *   • loading …
 *   • waiting for server
 *   • finished   ← loading done → overlay goes green
 */
object CatWebTracker {
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
