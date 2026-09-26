package com.cwbridge.android

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime variables for services (textMan, enterText templates, etc.).
 * Built-in: lastMatch (set by log triggers).
 */
object VarStore {
    private val map = ConcurrentHashMap<String, String>()

    fun set(key: String, value: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        map[k] = value
        LogBuffer.i("Vars", "set $k = ${value.take(120)}${if (value.length > 120) "…" else ""}")
    }

    fun get(key: String): String? = map[key.trim()]

    fun getOrEmpty(key: String): String = get(key).orEmpty()

    fun clear() {
        map.clear()
        LogBuffer.i("Vars", "cleared")
    }

    fun snapshot(): Map<String, String> = map.toMap()

    /** Expand ${name} and $name placeholders. */
    fun expand(template: String): String {
        if (template.isEmpty() || !template.contains('$')) return template
        var out = template
        val brace = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
        out = brace.replace(out) { m -> getOrEmpty(m.groupValues[1]) }
        val plain = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")
        out = plain.replace(out) { m -> getOrEmpty(m.groupValues[1]) }
        return out
    }
}
