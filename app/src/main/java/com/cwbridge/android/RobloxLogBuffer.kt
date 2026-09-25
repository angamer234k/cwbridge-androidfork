package com.cwbridge.android

import java.util.ArrayDeque

enum class ConsoleLevel {
    INFO,
    WARN,
    ERROR,
}

data class ConsoleLine(
    val text: String,
    val level: ConsoleLevel,
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * Ring buffer of game/CatWeb console lines (after FLog::CreatorOutput / bullet lines).
 * Max 25. Levels inferred from info / warn / error markers.
 */
object RobloxLogBuffer {
    private const val MAX = 25
    private val lines = ArrayDeque<ConsoleLine>(MAX)
    private val lock = Any()

    @Volatile
    var everReceived: Boolean = false
        private set

    fun add(rawLine: String) {
        val cleaned = clean(rawLine)
        if (cleaned.isEmpty()) return
        val level = detectLevel(cleaned)
        synchronized(lock) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(ConsoleLine(cleaned, level))
            everReceived = true
        }
        AntiDisconnect.noteActivity()
    }

    fun last(n: Int = 25): List<ConsoleLine> {
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

    fun detectLevel(text: String): ConsoleLevel {
        val t = text
        when {
            t.contains("\u274C") || t.contains("\u2716") || t.contains("\u2A2F") -> return ConsoleLevel.ERROR
            t.contains("\u26A0") -> return ConsoleLevel.WARN
            t.contains("\u2139") -> return ConsoleLevel.INFO
        }
        val lower = t.lowercase()
        return when {
            lower.contains("error") || lower.contains("invalid") ||
                lower.contains("exception") || lower.contains("failed") -> ConsoleLevel.ERROR
            lower.contains("warn") || lower.contains("warning") -> ConsoleLevel.WARN
            else -> ConsoleLevel.INFO
        }
    }

    /** Prefer body after FLog::CreatorOutput (any case), then from first bullet. */
    fun clean(raw: String): String {
        var body = raw.trim()
        val lower = body.lowercase()

        val markers = listOf(
            "[flog::creatoroutput]",
            "flog::creatoroutput",
            "[flog::output]",
        )
        for (m in markers) {
            val idx = lower.indexOf(m)
            if (idx >= 0) {
                body = body.substring(idx + m.length).trim().trimStart(':', ' ', '-', ']')
                break
            }
        }

        val bulletIdx = body.indexOf('\u2022').let { if (it >= 0) it else body.indexOf('\u00B7') }
        if (bulletIdx >= 0) {
            body = body.substring(bulletIdx).trim()
        }

        return body.take(500)
    }
}
