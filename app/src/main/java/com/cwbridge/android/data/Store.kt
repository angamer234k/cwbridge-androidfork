package com.cwbridge.android.data

import android.content.Context
import com.cwbridge.android.bridge.LogBuffer
import kotlin.math.pow

/**
 * Key/value store namespaced by domain (e.g. weather.rbx).
 * Domain must be a single label + ".rbx" — no extra subdomains or paths.
 */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val limitPrefs = context.applicationContext.getSharedPreferences(LIMITS, Context.MODE_PRIVATE)

    fun save(domain: String, key: String, data: String): Result<Unit> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain '$domain' — want name.rbx only"),
        )
        if (key.isBlank()) return Result.failure(IllegalArgumentException("empty key"))
        // Reject only if this write is the one that pushes the domain over its cap.
        val slot = slot(d, key)
        val existing = prefs.all[slot] as? String ?: ""
        val projected = usageOf(d) - existing.toByteArray(Charsets.UTF_8).size +
            data.toByteArray(Charsets.UTF_8).size
        val cap = limitOf(d)
        if (projected > cap) {
            return Result.failure(
                IllegalStateException(
                    "domain $d would store ${formatBytes(projected)} but the limit is " +
                        "${formatBytes(cap)} — raise the limit or clear keys first",
                ),
            )
        }
        prefs.edit().putString(slot, data).apply()
        return Result.success(Unit)
    }

    /** save.key.data with default domain local.rbx */
    fun saveDefault(key: String, data: String): Result<Unit> = save(DEFAULT_DOMAIN, key, data)

    fun load(domain: String, key: String): Result<String> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain '$domain' — want name.rbx only"),
        )
        if (key.isBlank()) return Result.failure(IllegalArgumentException("empty key"))
        val value = prefs.getString(slot(d, key), null)
            ?: return Result.failure(NoSuchElementException("no value for $d/$key"))
        return Result.success(value)
    }

    fun listKeys(domain: String): Result<List<String>> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain '$domain'"),
        )
        val prefix = "$d::"
        val keys = prefs.all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
        return Result.success(keys)
    }

    fun clearDomain(domain: String): Result<Int> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain '$domain'"),
        )
        val prefix = "$d::"
        val ed = prefs.edit()
        var n = 0
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach {
            ed.remove(it)
            n++
        }
        ed.apply()
        return Result.success(n)
    }

    fun remove(domain: String, key: String): Result<Unit> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain '$domain'"),
        )
        if (key.isBlank()) return Result.failure(IllegalArgumentException("empty key"))
        prefs.edit().remove(slot(d, key)).apply()
        return Result.success(Unit)
    }

    // ---- per-domain quota -------------------------------------------------

    /** Bytes currently stored under [domain]. */
    fun usageOf(domain: String): Long {
        val d = normalizeDomain(domain) ?: return 0L
        val prefix = "$d::"
        return prefs.all.entries
            .filter { it.key.startsWith(prefix) }
            .sumOf { (it.value as? String).orEmpty().toByteArray(Charsets.UTF_8).size.toLong() }
    }

    /** Byte cap for [domain]; falls back to [DEFAULT_LIMIT_BYTES]. */
    fun limitOf(domain: String): Long {
        val d = normalizeDomain(domain) ?: return DEFAULT_LIMIT_BYTES
        return limitPrefs.getLong(d, DEFAULT_LIMIT_BYTES)
    }

    /** Set the byte cap for [domain]. 0 = unlimited. */
    fun setLimit(domain: String, bytes: Long): Result<Unit> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain '$domain' — want name.rbx only"),
        )
        if (bytes < 0) return Result.failure(IllegalArgumentException("limit cannot be negative"))
        limitPrefs.edit().putLong(d, bytes).apply()
        LogBuffer.i("Store", "limit $d = ${formatBytes(bytes)}")
        return Result.success(Unit)
    }

    /** Every domain that has keys or a custom limit, with usage + cap. */
    fun domains(): List<DomainUsage> {
        val found = LinkedHashSet<String>()
        prefs.all.keys.forEach { k ->
            val i = k.indexOf("::")
            if (i > 0) found.add(k.substring(0, i))
        }
        limitPrefs.all.keys.forEach { found.add(it) }
        return found.filter { normalizeDomain(it) != null }
            .sorted()
            .map { d ->
                DomainUsage(
                    domain = d,
                    usedBytes = usageOf(d),
                    limitBytes = limitOf(d),
                    keyCount = prefs.all.keys.count { it.startsWith("$d::") },
                )
            }
    }

    data class DomainUsage(
        val domain: String,
        val usedBytes: Long,
        val limitBytes: Long,
        val keyCount: Int,
    ) {
        val unlimited: Boolean get() = limitBytes <= 0L
    }

    companion object {
        private const val PREFS = "cwbridge_store"
        private const val LIMITS = "cwbridge_store_limits"
        const val DEFAULT_DOMAIN = "local.rbx"

        /** 1 MiB per domain unless the user changes it. */
        const val DEFAULT_LIMIT_BYTES = 1L * 1024 * 1024

        /** Accept only `label.rbx` — no dots in label, no path. */
        fun normalizeDomain(raw: String): String? {
            val s = raw.trim().lowercase()
            if (!s.matches(Regex("^[a-z0-9_-]+\\.rbx$"))) return null
            return s
        }

        /**
         * Parse a human size ("512", "10kb", "2 MB", "1gb") into bytes.
         * Recognises bit/byte units; bare numbers are bytes. Returns null if unparseable.
         */
        fun parseSize(input: String): Long? {
            val s = input.trim().lowercase().replace("_", "").replace(" ", "")
            if (s.isEmpty()) return null
            val m = Regex("^([0-9]*\\.?[0-9]+)([kmgt]?)(i?b?|bits?)$").matchEntire(s) ?: return null
            val amount = m.groupValues[1].toDoubleOrNull() ?: return null
            val unit = m.groupValues[2]
            // "mb" is the common way people mean megabytes; treat bare m/b as bytes-based
            // except for an explicit "bit"/"bits" suffix.
            val isBits = m.groupValues[3].startsWith("bit")
            val power = when (unit) {
                "k" -> 10
                "m" -> 20
                "g" -> 30
                "t" -> 40
                else -> 0
            }
            val base = 1024.0.pow(power)
            val bytes = amount * base
            if (bytes < 0 || bytes > Long.MAX_VALUE) return null
            return (if (isBits) bytes / 8 else bytes).toLong()
        }

        /** Human-readable byte count, e.g. "1.4 MB". */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var idx = 0
            while (value >= 1024 && idx < units.lastIndex) {
                value /= 1024
                idx++
            }
            return String.format(java.util.Locale.US, "%.1f %s", value, units[idx])
        }

        private fun slot(domain: String, key: String) = "$domain::$key"
    }
}
