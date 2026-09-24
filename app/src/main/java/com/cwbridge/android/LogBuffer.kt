package com.cwbridge.android

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-process logcat-style ring buffer. Also mirrors to Android Log. */
object LogBuffer {
    enum class Level { V, D, I, W, E }

    data class Line(
        val ts: String,
        val level: Level,
        val tag: String,
        val msg: String,
    )

    private const val MAX = 500
    private val lines = CopyOnWriteArrayList<Line>()
    private val listeners = CopyOnWriteArrayList<(Line) -> Unit>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun addListener(listener: (Line) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Line) -> Unit) {
        listeners.remove(listener)
    }

    fun snapshot(): List<Line> = lines.toList()

    fun clear() {
        lines.clear()
    }

    fun v(tag: String, msg: String) = push(Level.V, tag, msg)
    fun d(tag: String, msg: String) = push(Level.D, tag, msg)
    fun i(tag: String, msg: String) = push(Level.I, tag, msg)
    fun w(tag: String, msg: String) = push(Level.W, tag, msg)
    fun e(tag: String, msg: String) = push(Level.E, tag, msg)

    private fun push(level: Level, tag: String, msg: String) {
        val line = Line(fmt.format(Date()), level, tag, msg)
        lines.add(line)
        while (lines.size > MAX) {
            lines.removeAt(0)
        }
        when (level) {
            Level.V -> Log.v(tag, msg)
            Level.D -> Log.d(tag, msg)
            Level.I -> Log.i(tag, msg)
            Level.W -> Log.w(tag, msg)
            Level.E -> Log.e(tag, msg)
        }
        listeners.forEach { it(line) }
    }
}
