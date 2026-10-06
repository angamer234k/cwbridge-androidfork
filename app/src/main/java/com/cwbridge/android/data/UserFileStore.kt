package com.cwbridge.android.data

import android.content.Context
import android.content.SharedPreferences
import com.cwbridge.android.bridge.LogBuffer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings + domain data storage.
 * 
 * Primary storage: Room database (SQLite)
 * Secondary storage: External folder via SAF (optional, for backups)
 * 
 * Path: Android/data/<package>/files/cwbridge/ (for legacy JSON files)
 *   settings.json   server password, remote enabled, first-run flags
 *   store.json      domain::key -> value
 *   limits.json     domain -> byte limit
 *
 * Visible in a file manager under the app folder; survives clear-cache
 * (not clear-data). Migrates once from the old SharedPreferences files and JSON files.
 */
object UserFileStore {

    private const val DIR = "cwbridge"
    private const val SETTINGS = "settings.json"
    private const val STORE = "store.json"
    private const val LIMITS = "limits.json"

    private val lock = Any()
    private var root: File? = null
    private var useLegacyStorage = false

    // Caches for backward compatibility
    private val settingsCache = ConcurrentHashMap<String, String>()
    private val storeCache = ConcurrentHashMap<String, String>()
    private val limitsCache = ConcurrentHashMap<String, Long>()
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            
            // Initialize database manager
            DatabaseManager.init(context)
            
            val base = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val dir = File(base, DIR).also { it.mkdirs() }
            root = dir
            
            // Check if we should use legacy storage (for migration purposes)
            useLegacyStorage = !DatabaseManager.hasExternalStorage()
            
            // Load from Room database
            try {
                val db = DatabaseManager.getDatabase(context)
                runBlocking {
                    db.settingDao().getAll().forEach { 
                        settingsCache[it.key] = it.value 
                    }
                    db.storeDao().getAll().forEach { 
                        storeCache[it.slot] = it.value 
                    }
                    db.limitDao().getAll().forEach { 
                        limitsCache[it.domain] = it.bytes 
                    }
                }
            } catch (t: Throwable) {
                LogBuffer.w("Files", "Failed to load from Room: ${t.message}")
                // Fallback to legacy JSON files
                loadJson(File(dir, SETTINGS), settingsCache)
                loadJson(File(dir, STORE), storeCache)
                loadLimits(File(dir, LIMITS), limitsCache)
            }
            
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
        
        // Try Room database first
        try {
            val db = DatabaseManager.getDatabase(context)
            val value = runBlocking { db.settingDao().getValueByKey(key) }
            if (value != null) {
                settingsCache[key] = value
                return value
            }
        } catch (t: Throwable) {
            LogBuffer.w("Files", "Room getSetting failed: ${t.message}")
        }
        
        // Fallback to cache
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
            
            // Save to Room database
            try {
                val db = DatabaseManager.getDatabase(context)
                runBlocking { 
                    db.settingDao().insert(SettingEntity(key, value))
                }
                // Also save to external storage if configured
                if (DatabaseManager.hasExternalStorage()) {
                    runBlocking {
                        DatabaseManager.saveToExternalStorage(context, "settings", key, value)
                    }
                }
            } catch (t: Throwable) {
                LogBuffer.w("Files", "Room putSetting failed: ${t.message}")
                // Fallback to JSON file
                flushSettings()
            }
        }
    }

    fun putBool(context: Context, key: String, value: Boolean) {
        putSetting(context, key, if (value) "true" else "false")
    }

    // ---- domain key/value store ----

    fun storeGet(context: Context, slot: String): String? {
        init(context)
        
        // Try Room database first
        try {
            val db = DatabaseManager.getDatabase(context)
            val value = runBlocking { db.storeDao().getValueBySlot(slot) }
            if (value != null) {
                storeCache[slot] = value
                return value
            }
        } catch (t: Throwable) {
            LogBuffer.w("Files", "Room storeGet failed: ${t.message}")
        }
        
        // Fallback to cache
        return storeCache[slot]
    }

    fun storePut(context: Context, slot: String, value: String) {
        init(context)
        synchronized(lock) {
            storeCache[slot] = value
            
            // Save to Room database
            try {
                val db = DatabaseManager.getDatabase(context)
                runBlocking { 
                    db.storeDao().insert(StoreEntity(slot, value))
                }
                // Also save to external storage if configured
                if (DatabaseManager.hasExternalStorage()) {
                    runBlocking {
                        DatabaseManager.saveToExternalStorage(context, "store", slot, value)
                    }
                }
            } catch (t: Throwable) {
                LogBuffer.w("Files", "Room storePut failed: ${t.message}")
                // Fallback to JSON file
                flushStore()
            }
        }
    }

    fun storeRemove(context: Context, slot: String) {
        init(context)
        synchronized(lock) {
            storeCache.remove(slot)
            
            // Delete from Room database
            try {
                val db = DatabaseManager.getDatabase(context)
                runBlocking { 
                    db.storeDao().deleteBySlot(slot)
                }
            } catch (t: Throwable) {
                LogBuffer.w("Files", "Room storeRemove failed: ${t.message}")
                // Fallback to JSON file
                flushStore()
            }
        }
    }

    fun storeRemovePrefix(context: Context, prefix: String): Int {
        init(context)
        synchronized(lock) {
            val keys = storeCache.keys.filter { it.startsWith(prefix) }
            keys.forEach { storeCache.remove(it) }
            
            // Delete from Room database
            try {
                val db = DatabaseManager.getDatabase(context)
                runBlocking { 
                    db.storeDao().deleteAllWithPrefix(prefix)
                }
            } catch (t: Throwable) {
                LogBuffer.w("Files", "Room storeRemovePrefix failed: ${t.message}")
                // Fallback to JSON file
                if (keys.isNotEmpty()) flushStore()
            }
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
        
        // Try Room database first
        try {
            val db = DatabaseManager.getDatabase(context)
            val bytes = runBlocking { db.limitDao().getBytesByDomain(domain) }
            if (bytes != null) {
                limitsCache[domain] = bytes
                return bytes
            }
        } catch (t: Throwable) {
            LogBuffer.w("Files", "Room limitGet failed: ${t.message}")
        }
        
        // Fallback to cache
        return limitsCache[domain] ?: default
    }

    fun limitPut(context: Context, domain: String, bytes: Long) {
        init(context)
        synchronized(lock) {
            limitsCache[domain] = bytes
            
            // Save to Room database
            try {
                val db = DatabaseManager.getDatabase(context)
                runBlocking { 
                    db.limitDao().insert(LimitEntity(domain, bytes))
                }
                // Also save to external storage if configured
                if (DatabaseManager.hasExternalStorage()) {
                    runBlocking {
                        DatabaseManager.saveToExternalStorage(context, "limits", domain, bytes.toString())
                    }
                }
            } catch (t: Throwable) {
                LogBuffer.w("Files", "Room limitPut failed: ${t.message}")
                // Fallback to JSON file
                flushLimits()
            }
        }
    }

    fun limitsAll(context: Context): Map<String, Long> {
        init(context)
        return limitsCache.toMap()
    }

    // ---- persistence (legacy fallback) ----

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
        
        // Save to Room database
        try {
            val db = DatabaseManager.getDatabase(ctx)
            runBlocking {
                settingsCache.forEach { (k, v) ->
                    db.settingDao().insert(SettingEntity(k, v))
                }
                storeCache.forEach { (k, v) ->
                    db.storeDao().insert(StoreEntity(k, v))
                }
                limitsCache.forEach { (k, v) ->
                    db.limitDao().insert(LimitEntity(k, v))
                }
            }
        } catch (t: Throwable) {
            LogBuffer.w("Files", "Failed to save migration to Room: ${t.message}")
        }
        
        flushSettings()
        flushStore()
        flushLimits()
        LogBuffer.i("Files", "migrated SharedPreferences  ${root?.absolutePath}")
    }
}
