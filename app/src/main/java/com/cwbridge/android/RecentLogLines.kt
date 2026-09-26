package com.cwbridge.android

import java.util.ArrayDeque

data class RecentLine(val text: String, val atMs: Long = System.currentTimeMillis())

/** Ring of recent logcat/console lines for service LogTriggers. */
object RecentLogLines {
    private const val MAX = 200
    private val lines = ArrayDeque<RecentLine>(MAX)
    private val lock = Any()

    fun add(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(RecentLine(t))
        }
    }

    fun snapshot(): List<RecentLine> = synchronized(lock) { lines.toList() }

    fun since(afterMs: Long): List<RecentLine> = synchronized(lock) {
        lines.filter { it.atMs > afterMs }
    }
}
