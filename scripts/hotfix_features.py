#!/usr/bin/env python3
"""Move settings + domain data from SharedPreferences to user-visible JSON files."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

FILE_STORE = r'''package com.cwbridge.android.data

import android.content.Context
import android.content.SharedPreferences
import com.cwbridge.android.bridge.LogBuffer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings + domain data on *user storage* (app external files dir),
 * not internal SharedPreferences.
 *
 * Path: Android/data/<package>/files/cwbridge/
 *   settings.json  — server password, remote enabled, first-run flags
 *   store.json     — domain::key -> value
 *   limits.json    — domain -> byte limit
 *
 * Visible in a file manager under the app folder; survives clear-cache
 * (not clear-data). Migrates once from the old SharedPreferences files.
 */
object UserFileStore {

    private const val DIR = "cwbridge"
    private const val SETTINGS = "settings.json"
    private const val STORE = "store.json"
    private const val LIMITS = "limits.json"

    private val lock = Any()
    private var root: File? = null
    private val settingsCache = ConcurrentHashMap<String, String>()
    private val storeCache = ConcurrentHashMap<String, String>()
    private val limitsCache = ConcurrentHashMap<String, Long>()
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val base = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val dir = File(base, DIR).also { it.mkdirs() }
            root = dir
            loadJson(File(dir, SETTINGS), settingsCache)
            loadJson(File(dir, STORE), storeCache)
            loadLimits(File(dir, LIMITS), limitsCache)
            migrateFromPrefs(context.applicationContext)
            ready = true
            LogBuffer.i("Files", "data dir: ${dir.absolutePath}")
        }
    }

    fun dataDir(context: Context): File {
        init(context)
        return root!!
    }

    // ---- settings (string/bool) ----

    fun getSetting(context: Context, key: String, default: String? = null): String? {
        init(context)
        return settingsCache[key] ?: default
    }

    fun getBool(context: Context, key: String, default: Boolean): Boolean {
        val v = getSetting(context, key) ?: return default
        return v.equals("true", ignoreCase = true) || v == "1"
    }

    fun putSetting(context: Context, key: String, value: String) {
        init(context)
        synchronized(lock) {
            settingsCache[key] = value
            flushSettings()
        }
    }

    fun putBool(context: Context, key: String, value: Boolean) {
        putSetting(context, key, if (value) "true" else "false")
    }

    // ---- domain key/value store ----

    fun storeGet(context: Context, slot: String): String? {
        init(context)
        return storeCache[slot]
    }

    fun storePut(context: Context, slot: String, value: String) {
        init(context)
        synchronized(lock) {
            storeCache[slot] = value
            flushStore()
        }
    }

    fun storeRemove(context: Context, slot: String) {
        init(context)
        synchronized(lock) {
            storeCache.remove(slot)
            flushStore()
        }
    }

    fun storeRemovePrefix(context: Context, prefix: String): Int {
        init(context)
        synchronized(lock) {
            val keys = storeCache.keys.filter { it.startsWith(prefix) }
            keys.forEach { storeCache.remove(it) }
            if (keys.isNotEmpty()) flushStore()
            return keys.size
        }
    }

    fun storeAll(context: Context): Map<String, String> {
        init(context)
        return storeCache.toMap()
    }

    // ---- limits ----

    fun limitGet(context: Context, domain: String, default: Long): Long {
        init(context)
        return limitsCache[domain] ?: default
    }

    fun limitPut(context: Context, domain: String, bytes: Long) {
        init(context)
        synchronized(lock) {
            limitsCache[domain] = bytes
            flushLimits()
        }
    }

    fun limitsAll(context: Context): Map<String, Long> {
        init(context)
        return limitsCache.toMap()
    }

    // ---- persistence ----

    private fun flushSettings() {
        val dir = root ?: return
        writeJson(File(dir, SETTINGS), settingsCache)
    }

    private fun flushStore() {
        val dir = root ?: return
        writeJson(File(dir, STORE), storeCache)
    }

    private fun flushLimits() {
        val dir = root ?: return
        val obj = JSONObject()
        limitsCache.forEach { (k, v) -> obj.put(k, v) }
        File(dir, LIMITS).writeText(obj.toString())
    }

    private fun loadJson(file: File, into: ConcurrentHashMap<String, String>) {
        if (!file.exists()) return
        try {
            val obj = JSONObject(file.readText())
            obj.keys().forEach { k -> into[k] = obj.optString(k, "") }
        } catch (t: Throwable) {
            LogBuffer.w("Files", "load ${file.name}: ${t.message}")
        }
    }

    private fun loadLimits(file: File, into: ConcurrentHashMap<String, Long>) {
        if (!file.exists()) return
        try {
            val obj = JSONObject(file.readText())
            obj.keys().forEach { k -> into[k] = obj.optLong(k, 0L) }
        } catch (t: Throwable) {
            LogBuffer.w("Files", "load limits: ${t.message}")
        }
    }

    private fun writeJson(file: File, map: Map<String, String>) {
        try {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(obj.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            LogBuffer.w("Files", "write ${file.name}: ${t.message}")
        }
    }

    /** One-shot migration from old SharedPreferences. */
    private fun migrateFromPrefs(ctx: Context) {
        val flag = "migrated_from_prefs"
        if (settingsCache[flag] == "true") return

        fun copyPrefs(name: String, target: ConcurrentHashMap<String, String>, asLong: Boolean = false) {
            val p: SharedPreferences = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            p.all.forEach { (k, v) ->
                when (v) {
                    is String -> target.putIfAbsent(k, v)
                    is Boolean -> target.putIfAbsent(k, if (v) "true" else "false")
                    is Number -> {
                        if (asLong) {
                            // limits handled separately
                        } else target.putIfAbsent(k, v.toString())
                    }
                }
            }
        }

        // Server auth
        val auth = ctx.getSharedPreferences("cwbridge_server_auth", Context.MODE_PRIVATE)
        auth.getString("password", null)?.let { settingsCache.putIfAbsent("password", it) }
        if (auth.contains("enabled")) {
            settingsCache.putIfAbsent("enabled", if (auth.getBoolean("enabled", true)) "true" else "false")
        }

        // First-run
        val fr = ctx.getSharedPreferences("cwbridge_first_run", Context.MODE_PRIVATE)
        if (fr.getBoolean("compatibility_notice_shown", false)) {
            settingsCache.putIfAbsent("compatibility_notice_shown", "true")
        }

        // Domain store
        val storePrefs = ctx.getSharedPreferences("cwbridge_store", Context.MODE_PRIVATE)
        storePrefs.all.forEach { (k, v) ->
            if (v is String) storeCache.putIfAbsent(k, v)
        }

        // Limits
        val limitPrefs = ctx.getSharedPreferences("cwbridge_store_limits", Context.MODE_PRIVATE)
        limitPrefs.all.forEach { (k, v) ->
            when (v) {
                is Long -> limitsCache.putIfAbsent(k, v)
                is Int -> limitsCache.putIfAbsent(k, v.toLong())
                is Number -> limitsCache.putIfAbsent(k, v.toLong())
            }
        }

        settingsCache[flag] = "true"
        flushSettings()
        flushStore()
        flushLimits()
        LogBuffer.i("Files", "migrated SharedPreferences → ${root?.absolutePath}")
    }
}
'''

def write_user_file_store() -> None:
    path = ROOT / "app/src/main/java/com/cwbridge/android/data/UserFileStore.kt"
    path.write_text(FILE_STORE)
    print("UserFileStore.kt written", path.stat().st_size)

def patch_store() -> None:
    path = ROOT / "app/src/main/java/com/cwbridge/android/data/Store.kt"
    # Rewrite Store to use UserFileStore while keeping public API
    path.write_text(r'''package com.cwbridge.android.data

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
''')
    print("Store.kt rewritten for UserFileStore")

def patch_server_auth() -> None:
    path = ROOT / "app/src/main/java/com/cwbridge/android/server/ServerAuth.kt"
    t = path.read_text()
    # Replace SharedPreferences usage with UserFileStore
    t = t.replace(
        "import android.content.Context\nimport com.cwbridge.android.bridge.LogBuffer",
        "import android.content.Context\nimport com.cwbridge.android.bridge.LogBuffer\nimport com.cwbridge.android.data.UserFileStore",
    )
    # Remove prefs field and init body
    old_init = '''    private lateinit var prefs: android.content.SharedPreferences
    private val sessions = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }'''
    new_init = '''    private val sessions = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        UserFileStore.init(context)
    }'''
    if old_init not in t:
        raise SystemExit("ServerAuth init block missing")
    t = t.replace(old_init, new_init, 1)

    t = t.replace(
        "return prefs.getBoolean(KEY_ENABLED, true)",
        "return UserFileStore.getBool(context, KEY_ENABLED, true)",
    )
    t = t.replace(
        "prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()",
        "UserFileStore.putBool(context, KEY_ENABLED, enabled)",
    )
    t = t.replace(
        "val existing = prefs.getString(KEY_PASSWORD, null)",
        "val existing = UserFileStore.getSetting(context, KEY_PASSWORD)",
    )
    t = t.replace(
        "prefs.edit().putString(KEY_PASSWORD, generated).apply()",
        "UserFileStore.putSetting(context, KEY_PASSWORD, generated)",
    )
    # remove unused PREFS const if still there — leave KEY_* 
    t = t.replace('    private const val PREFS = "cwbridge_server_auth"\n', "")
    path.write_text(t)
    print("ServerAuth → UserFileStore")

def patch_first_run() -> None:
    path = ROOT / "app/src/main/java/com/cwbridge/android/FirstRun.kt"
    path.write_text(r'''package com.cwbridge.android

import android.content.Context
import com.cwbridge.android.data.UserFileStore

/**
 * One-time post-install notice. Flag lives in settings.json on user storage.
 */
object FirstRun {

    private const val KEY_NOTICE_SHOWN = "compatibility_notice_shown"

    /** @return true the first time only; marks the notice as shown. */
    fun consumeCompatibilityNotice(context: Context): Boolean {
        if (UserFileStore.getBool(context, KEY_NOTICE_SHOWN, false)) return false
        UserFileStore.putBool(context, KEY_NOTICE_SHOWN, true)
        return true
    }
}
''')
    print("FirstRun → UserFileStore")

def main() -> None:
    write_user_file_store()
    patch_store()
    patch_server_auth()
    patch_first_run()
    print("done")

if __name__ == "__main__":
    main()
