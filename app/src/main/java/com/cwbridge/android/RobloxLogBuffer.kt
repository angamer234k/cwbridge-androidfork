package com.cwbridge.android

import java.util.ArrayDeque

enum class ConsoleLevel {
    CATWEB,
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
 * Ring buffer of console lines (max 25).
 * After FLog strip, lines that start with • are CatWeb → [CATWEB] tag + blueish-white.
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
        val display = if (level == ConsoleLevel.CATWEB && !cleaned.startsWith("[CATWEB]")) {
            "[CATWEB] $cleaned"
        } else {
            cleaned
        }
        synchronized(lock) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(ConsoleLine(display, level))
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
        val trimmed = text.trimStart()
        if (trimmed.startsWith("\u2022") || trimmed.startsWith("\u00B7") ||
            trimmed.startsWith("[CATWEB]")
        ) {
            return ConsoleLevel.CATWEB
        }
        when {
            text.contains("\u274C") || text.contains("\u2716") || text.contains("\u2A2F") ->
                return ConsoleLevel.ERROR
            text.contains("\u26A0") -> return ConsoleLevel.WARN
            text.contains("\u2139") -> return ConsoleLevel.INFO
        }
        val lower = text.lowercase()
        return when {
            lower.contains("error") || lower.contains("invalid") ||
                lower.contains("exception") || lower.contains("failed") -> ConsoleLevel.ERROR
            lower.contains("warn") || lower.contains("warning") -> ConsoleLevel.WARN
            else -> ConsoleLevel.INFO
        }
    }

    /** Strip FLog::CreatorOutput (any case). If result starts with • → CatWeb. */
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
        return body.take(500)
    }
}
