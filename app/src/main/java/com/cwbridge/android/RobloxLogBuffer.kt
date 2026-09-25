package com.cwbridge.android

import java.util.ArrayDeque

/**
 * Ring buffer of Roblox console lines matched via `[FLog::CreatorOutput]`.
 */
object RobloxLogBuffer {
    private const val MAX = 40
    private val lines = ArrayDeque<String>(MAX)
    private val lock = Any()

    @Volatile
    var everReceived: Boolean = false
        private set

    fun add(rawLine: String) {
        val cleaned = clean(rawLine)
        if (cleaned.isEmpty()) return
        synchronized(lock) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(cleaned)
            everReceived = true
        }
    }

    fun last(n: Int = 10): List<String> {
        synchronized(lock) {
            if (lines.isEmpty()) return emptyList()
            val take = n.coerceAtMost(lines.size)
            return lines.toList().takeLast(take)
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            everReceived = false
        }
    }

    private fun clean(raw: String): String {
        val marker = "[FLog::CreatorOutput]"
        val idx = raw.indexOf(marker)
        val body = if (idx >= 0) {
            raw.substring(idx + marker.length).trim().trimStart(':', ' ')
        } else {
            raw.trim()
        }
        return body.take(400)
    }
}
