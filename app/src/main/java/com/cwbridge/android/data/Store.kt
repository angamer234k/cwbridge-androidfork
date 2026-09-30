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
        val d = normalizeDomain(domain) ?: return defaultLimitBytes()
        return UserFileStore.limitGet(app, d, defaultLimitBytes())
    }

    fun defaultLimitBytes(): Long {
        val raw = UserFileStore.getSetting(app, "store_default_limit_bytes", null)
        val parsed = raw?.let { parseSize(it) ?: it.toLongOrNull() }
        return parsed?.coerceAtLeast(0L) ?: DEFAULT_LIMIT_BYTES
    }

    fun setDefaultLimitBytes(bytes: Long): Result<Unit> {
        if (bytes < 0) return Result.failure(IllegalArgumentException("limit cannot be negative"))
        val v = if (bytes == 0L) "0" else formatBytes(bytes).replace(" ", "")
        UserFileStore.putSetting(app, "store_default_limit_bytes", if (bytes == 0L) "0" else bytes.toString())
        LogBuffer.i("Store", "default limit = ${formatBytes(bytes)}")
        return Result.success(Unit)
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
        const val DEFAULT_LIMIT_BYTES = 1L * 1024 * 1024 * 1024 // 1 GiB

        fun normalizeDomain(raw: String): String? {
            val s = raw.trim().lowercase()
            if (!s.matches(Regex("^[a-z0-9_-]+\\.rbx$"))) return null
            return s
        }

        fun parseSize(input: String): Long? {
            // Accept: 69420 | 500KB | 500 KB | 1.5mb | 2GiB | 1gb | 8kib | 100bits
            val s = input.trim().lowercase()
                .replace("_", "")
                .replace(" ", "")
                .replace(",", "")
            if (s.isEmpty()) return null

            val m = Regex(
                "^([0-9]*\\.?[0-9]+)(k|m|g|t)?(i)?(b|bit|bits)?$",
            ).matchEntire(s) ?: return null

            val amount = m.groupValues[1].toDoubleOrNull() ?: return null
            if (amount < 0) return null

            val prefix = m.groupValues[2]           // k/m/g/t or ""
            val binary = m.groupValues[3] == "i"    // KiB style
            val suffix = m.groupValues[4]           // b / bit / bits / ""

            val isBits = suffix == "bit" || suffix == "bits"
            // Bare number with no unit → bytes
            val unitPower = when (prefix) {
                "k" -> 1
                "m" -> 2
                "g" -> 3
                "t" -> 4
                else -> 0
            }
            val radix = if (binary) 1024.0 else 1024.0 // we use 1024 for both KB and KiB
            val multiplier = radix.pow(unitPower.toDouble())
            var bytes = amount * multiplier
            if (isBits) bytes /= 8.0

            if (bytes < 0 || bytes > Long.MAX_VALUE.toDouble()) return null
            return bytes.toLong()
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
