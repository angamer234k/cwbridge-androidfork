package com.cwbridge.android.data

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
