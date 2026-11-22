package com.cwbridge.android.engine

internal data class ParsedInvokeRequest(
    val command: String,
    val data1: String,
    val data2: String,
    val requestId: String,
)

/** Parsing and scheduling rules shared by the invoke engine and its unit tests. */
internal object InvokeProtocol {
    private const val REQUEST_MARKER = "invoke|"
    private const val ACK_MARKER = "invoke||ACK||"
    private val requestIdPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * Parse only requests carrying a valid trailing ||REQID.
     * Missing, malformed, and reserved ID 0 requests are intentionally ignored.
     */
    fun parseRequest(raw: String): ParsedInvokeRequest? {
        if (raw.contains("cwbridge|")) return null
        val marker = raw.indexOf(REQUEST_MARKER)
        if (marker < 0) return null

        val rawPayload = raw.substring(marker + REQUEST_MARKER.length).trim()
        val separator = rawPayload.lastIndexOf("||")
        if (separator <= 0) return null

        val requestId = rawPayload.substring(separator + 2).trim()
        if (requestId == "0" || !requestIdPattern.matches(requestId)) return null

        val payload = rawPayload.substring(0, separator).trimEnd()
        if (payload.isBlank()) return null

        val parts = payload.split(".", limit = 3)
        val command = parts.firstOrNull()?.trim()?.lowercase().orEmpty()
        if (command.isEmpty()) return null

        return ParsedInvokeRequest(
            command = command,
            data1 = parts.getOrNull(1)?.trim().orEmpty(),
            data2 = parts.getOrNull(2)?.trim().orEmpty(),
            requestId = requestId,
        )
    }

    /** Lower numbers run first; the sequence number preserves FIFO within a priority. */
    fun priorityFor(command: String): Int = when (command.lowercase()) {
        // P0 — datastore
        "save", "load", "savedomain", "storeinfo", "exists", "keys" -> 0
        // P1 — administration and device/control commands
        "setlimit" -> 1
        // P2 — weather
        "weather" -> 2
        // P3 — AI and its metadata endpoints
        "ai" -> 3
        // Keep ordinary control/utility commands ahead of weather and AI.
        else -> 1
    }

    /** ACKs must match both the active request ID and the first ten response characters. */
    fun ackMatches(raw: String, requestId: String, expectedPrefix: String): Boolean {
        val marker = raw.indexOf(ACK_MARKER)
        if (marker < 0) return false
        val payload = raw.substring(marker + ACK_MARKER.length).removeSuffix("\r").removeSuffix("\n")
        val fields = payload.split("||", limit = 2)
        if (fields.size != 2) return false
        return fields[0].trim() == requestId && fields[1].take(10) == expectedPrefix
    }

    fun isAck(raw: String): Boolean = raw.contains(ACK_MARKER)
}
