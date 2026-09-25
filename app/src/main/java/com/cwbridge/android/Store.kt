package com.cwbridge.android

import android.content.Context

/**
 * Key/value store namespaced by domain (e.g. weather.rbx).
 * Domain must be a single label + ".rbx" — no extra subdomains or paths.
 */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(domain: String, key: String, data: String): Result<Unit> {
        val d = normalizeDomain(domain) ?: return Result.failure(
            IllegalArgumentException("bad domain '$domain' — want name.rbx only"),
        )
        if (key.isBlank()) return Result.failure(IllegalArgumentException("empty key"))
        prefs.edit().putString(slot(d, key), data).apply()
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

    companion object {
        private const val PREFS = "cwbridge_store"
        const val DEFAULT_DOMAIN = "local.rbx"

        /** Accept only `label.rbx` — no dots in label, no path. */
        fun normalizeDomain(raw: String): String? {
            val s = raw.trim().lowercase()
            if (!s.matches(Regex("^[a-z0-9_-]+\\.rbx$"))) return null
            return s
        }

        private fun slot(domain: String, key: String) = "$domain::$key"
    }
}
