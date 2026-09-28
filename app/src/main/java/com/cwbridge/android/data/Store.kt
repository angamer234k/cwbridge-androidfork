package com.cwbridge.android.data

import android.content.Context
import com.cwbridge.android.bridge.LogBuffer
import kotlin.math.pow

/**
 * Key/value store namespaced by domain (e.g. weather.rbx).
 * Persisted under Android/data/<pkg>/files/cwbridge/store.json (user storage).
 */
class Store(context: Context) {

    private val app = context.applicationContext

    init {
        UserFileStore.init(app)
    }

    fun save(domain: String, key: String, data: String): Result<Unit> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain \'$domain\' — want name.rbx only"),
        )
        if (key.isBlank()) return Result.failure(IllegalArgumentException("empty key"))
        val slot = slot(d, key)
        val existing = UserFileStore.storeGet(app, slot) ?: ""
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
        UserFileStore.storePut(app, slot, data)
        return Result.success(Unit)
    }

    fun saveDefault(key: String, data: String): Result<Unit> = save(DEFAULT_DOMAIN, key, data)

    fun load(domain: String, key: String): Result<String> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain \'$domain\' — want name.rbx only"),
        )
        if (key.isBlank()) return Result.failure(IllegalArgumentException("empty key"))
        val value = UserFileStore.storeGet(app, slot(d, key))
            ?: return Result.failure(NoSuchElementException("no value for $d/$key"))
        return Result.success(value)
    }

    fun listKeys(domain: String): Result<List<String>> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain \'$domain\'"),
        )
        val prefix = "$d::"
        val keys = UserFileStore.storeAll(app).keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
        return Result.success(keys)
    }

    fun clearDomain(domain: String): Result<Int> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain \'$domain\'"),
        )
        val n = UserFileStore.storeRemovePrefix(app, "$d::")
        return Result.success(n)
    }

    fun remove(domain: String, key: String): Result<Unit> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain \'$domain\'"),
        )
        if (key.isBlank()) return Result.failure(IllegalArgumentException("empty key"))
        UserFileStore.storeRemove(app, slot(d, key))
        return Result.success(Unit)
    }

    fun usageOf(domain: String): Long {
        val d = normalizeDomain(domain) ?: return 0L
        val prefix = "$d::"
        return UserFileStore.storeAll(app).entries
            .filter { it.key.startsWith(prefix) }
            .sumOf { it.value.toByteArray(Charsets.UTF_8).size.toLong() }
    }

    fun limitOf(domain: String): Long {
        val d = normalizeDomain(domain) ?: return DEFAULT_LIMIT_BYTES
        return UserFileStore.limitGet(app, d, DEFAULT_LIMIT_BYTES)
    }

    fun setLimit(domain: String, bytes: Long): Result<Unit> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain \'$domain\' — want name.rbx only"),
        )
        if (bytes < 0) return Result.failure(IllegalArgumentException("limit cannot be negative"))
        UserFileStore.limitPut(app, d, bytes)
        LogBuffer.i("Store", "limit $d = ${formatBytes(bytes)}")
        return Result.success(Unit)
    }

    fun domains(): List<DomainUsage> {
        val found = LinkedHashSet<String>()
        UserFileStore.storeAll(app).keys.forEach { k ->
            val i = k.indexOf("::")
            if (i > 0) found.add(k.substring(0, i))
        }
        UserFileStore.limitsAll(app).keys.forEach { found.add(it) }
        return found.filter { normalizeDomain(it) != null }
            .sorted()
            .map { d ->
                DomainUsage(
                    domain = d,
                    usedBytes = usageOf(d),
                    limitBytes = limitOf(d),
                    keyCount = UserFileStore.storeAll(app).keys.count { it.startsWith("$d::") },
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
        const val DEFAULT_DOMAIN = "local.rbx"
        const val DEFAULT_LIMIT_BYTES = 1L * 1024 * 1024

        fun normalizeDomain(raw: String): String? {
            val s = raw.trim().lowercase()
            if (!s.matches(Regex("^[a-z0-9_-]+\\.rbx$"))) return null
            return s
        }

        fun parseSize(input: String): Long? {
            val s = input.trim().lowercase().replace("_", "").replace(" ", "")
            if (s.isEmpty()) return null
            val m = Regex("^([0-9]*\\.?[0-9]+)([kmgt]?)(i?b?|bits?)$").matchEntire(s) ?: return null
            val amount = m.groupValues[1].toDoubleOrNull() ?: return null
            val unit = m.groupValues[2]
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
