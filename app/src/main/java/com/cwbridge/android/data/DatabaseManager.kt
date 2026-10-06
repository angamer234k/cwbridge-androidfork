package com.cwbridge.android.data

import android.content.Context
import androidx.room.Room
import com.cwbridge.android.bridge.LogBuffer
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages the Room database instance and handles migration from JSON files.
 * Provides both Room-based storage and external storage via SAF.
 */
object DatabaseManager {
    private const val DIR = "cwbridge"
    private const val SETTINGS_FILE = "settings.json"
    private const val STORE_FILE = "store.json"
    private const val LIMITS_FILE = "limits.json"

    private var database: AppDatabase? = null
    private var externalStorageScope: CoroutineScope? = null
    private var externalDirUri: String? = null

    private val lock = Any()
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return

            val appContext = context.applicationContext
            
            database = Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                AppDatabase.DATABASE_NAME
            )
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()

            externalStorageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            // Perform migration from JSON files in background
            externalStorageScope?.launch {
                migrateFromJsonFiles(appContext)
            }

            initialized = true
            LogBuffer.i("Database", "Room database initialized")
        }
    }

    fun getDatabase(context: Context): AppDatabase {
        init(context)
        return database!!
    }

    /**
     * Migrate data from old JSON files to Room database.
     * This is a one-time migration that runs in the background.
     */
    private suspend fun migrateFromJsonFiles(context: Context) {
        val base = context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir
        val dir = File(base, DIR)
        
        if (!dir.exists()) return

        val settingsFile = File(dir, SETTINGS_FILE)
        val storeFile = File(dir, STORE_FILE)
        val limitsFile = File(dir, LIMITS_FILE)

        val dao = database?.settingDao() ?: return
        val storeDao = database?.storeDao() ?: return
        val limitDao = database?.limitDao() ?: return

        // Check if migration already happened
        val migrationFlag = dao.getValueByKey("migrated_from_json")
        if (migrationFlag == "true") {
            LogBuffer.i("Database", "JSON migration already completed")
            return
        }

        LogBuffer.i("Database", "Starting JSON to Room migration...")

        // Migrate settings
        if (settingsFile.exists()) {
            try {
                val obj = JSONObject(settingsFile.readText())
                obj.keys().forEach { key ->
                    val value = obj.optString(key, "")
                    dao.insert(SettingEntity(key, value))
                }
                LogBuffer.i("Database", "Migrated ${obj.length()} settings")
            } catch (t: Throwable) {
                LogBuffer.w("Database", "Failed to migrate settings: ${t.message}")
            }
        }

        // Migrate store
        if (storeFile.exists()) {
            try {
                val obj = JSONObject(storeFile.readText())
                obj.keys().forEach { slot ->
                    val value = obj.optString(slot, "")
                    storeDao.insert(StoreEntity(slot, value))
                }
                LogBuffer.i("Database", "Migrated ${obj.length()} store entries")
            } catch (t: Throwable) {
                LogBuffer.w("Database", "Failed to migrate store: ${t.message}")
            }
        }

        // Migrate limits
        if (limitsFile.exists()) {
            try {
                val obj = JSONObject(limitsFile.readText())
                obj.keys().forEach { domain ->
                    val bytes = obj.optLong(domain, 0L)
                    limitDao.insert(LimitEntity(domain, bytes))
                }
                LogBuffer.i("Database", "Migrated ${obj.length()} limits")
            } catch (t: Throwable) {
                LogBuffer.w("Database", "Failed to migrate limits: ${t.message}")
            }
        }

        // Mark migration as complete
        dao.insert(SettingEntity("migrated_from_json", "true"))
        LogBuffer.i("Database", "JSON migration completed successfully")
    }

    /**
     * Set the external storage directory URI (from SAF).
     * This should be a directory URI string obtained from Storage Access Framework.
     */
    fun setExternalStorageUri(uri: String?) {
        externalDirUri = uri
        LogBuffer.i("Database", "External storage URI set: ${uri?.take(50)}...")
    }

    fun getExternalStorageUri(): String? = externalDirUri

    /**
     * Check if external storage is configured.
     */
    fun hasExternalStorage(): Boolean = !externalDirUri.isNullOrBlank()

    /**
     * Clear the external storage URI.
     */
    fun clearExternalStorageUri() {
        externalDirUri = null
        LogBuffer.i("Database", "External storage URI cleared")
    }

    /**
     * Export all data to external storage as JSON files.
     * Returns true if successful, false otherwise.
     */
    suspend fun exportToExternalStorage(context: Context): Boolean {
        if (!hasExternalStorage()) {
            LogBuffer.w("Database", "Cannot export: no external storage configured")
            return false
        }

        return try {
            val db = getDatabase(context)
            val settings = db.settingDao().getAll()
            val store = db.storeDao().getAll()
            val limits = db.limitDao().getAll()

            // TODO: Implement actual export using DocumentFile
            // For now, we'll use the existing UserFileStore directory as fallback
            val base = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val dir = File(base, DIR).also { it.mkdirs() }

            // Export settings
            val settingsObj = JSONObject()
            settings.forEach { settingsObj.put(it.key, it.value) }
            File(dir, "settings_export_${System.currentTimeMillis()}.json").writeText(settingsObj.toString())

            // Export store
            val storeObj = JSONObject()
            store.forEach { storeObj.put(it.slot, it.value) }
            File(dir, "store_export_${System.currentTimeMillis()}.json").writeText(storeObj.toString())

            // Export limits
            val limitsObj = JSONObject()
            limits.forEach { limitsObj.put(it.domain, it.bytes) }
            File(dir, "limits_export_${System.currentTimeMillis()}.json").writeText(limitsObj.toString())

            LogBuffer.i("Database", "Exported ${settings.size} settings, ${store.size} store entries, ${limits.size} limits")
            true
        } catch (t: Throwable) {
            LogBuffer.e("Database", "Export failed: ${t.message}")
            false
        }
    }

    /**
     * Import data from external storage JSON files.
     * Returns true if successful, false otherwise.
     */
    suspend fun importFromExternalStorage(context: Context): Boolean {
        if (!hasExternalStorage()) {
            LogBuffer.w("Database", "Cannot import: no external storage configured")
            return false
        }

        return try {
            val db = getDatabase(context)
            val dao = db.settingDao()
            val storeDao = db.storeDao()
            val limitDao = db.limitDao()

            // TODO: Implement actual import using DocumentFile
            // For now, we'll use the existing UserFileStore directory as fallback
            val base = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val dir = File(base, DIR)

            // Look for export files
            val settingsFiles = dir.listFiles { f -> f.name.startsWith("settings_export_") && f.name.endsWith(".json") }
            val storeFiles = dir.listFiles { f -> f.name.startsWith("store_export_") && f.name.endsWith(".json") }
            val limitsFiles = dir.listFiles { f -> f.name.startsWith("limits_export_") && f.name.endsWith(".json") }

            // Import settings (use latest file)
            settingsFiles?.sortedByDescending { it.lastModified() }?.firstOrNull()?.let { file ->
                val obj = JSONObject(file.readText())
                obj.keys().forEach { key ->
                    val value = obj.optString(key, "")
                    dao.insert(SettingEntity(key, value))
                }
                LogBuffer.i("Database", "Imported ${obj.length()} settings")
            }

            // Import store (use latest file)
            storeFiles?.sortedByDescending { it.lastModified() }?.firstOrNull()?.let { file ->
                val obj = JSONObject(file.readText())
                obj.keys().forEach { slot ->
                    val value = obj.optString(slot, "")
                    storeDao.insert(StoreEntity(slot, value))
                }
                LogBuffer.i("Database", "Imported ${obj.length()} store entries")
            }

            // Import limits (use latest file)
            limitsFiles?.sortedByDescending { it.lastModified() }?.firstOrNull()?.let { file ->
                val obj = JSONObject(file.readText())
                obj.keys().forEach { domain ->
                    val bytes = obj.optLong(domain, 0L)
                    limitDao.insert(LimitEntity(domain, bytes))
                }
                LogBuffer.i("Database", "Imported ${obj.length()} limits")
            }

            true
        } catch (t: Throwable) {
            LogBuffer.e("Database", "Import failed: ${t.message}")
            false
        }
    }

    /**
     * Save data to external storage (if configured) in addition to Room.
     * This provides a backup mechanism.
     */
    suspend fun saveToExternalStorage(
        context: Context,
        dataType: String,
        key: String,
        value: String
    ): Boolean {
        if (!hasExternalStorage()) return false

        return try {
            val base = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val dir = File(base, DIR).also { it.mkdirs() }
            
            val file = File(dir, "${dataType}_${key}.json")
            file.writeText(value)
            true
        } catch (t: Throwable) {
            LogBuffer.w("Database", "Failed to save to external: ${t.message}")
            false
        }
    }

    /**
     * Load data from external storage (if configured).
     */
    suspend fun loadFromExternalStorage(
        context: Context,
        dataType: String,
        key: String
    ): String? {
        if (!hasExternalStorage()) return null

        return try {
            val base = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val dir = File(base, DIR)
            
            val file = File(dir, "${dataType}_${key}.json")
            if (file.exists()) file.readText() else null
        } catch (t: Throwable) {
            LogBuffer.w("Database", "Failed to load from external: ${t.message}")
            null
        }
    }

    /**
     * Clear all data from Room database.
     */
    suspend fun clearAllData(context: Context) {
        val db = getDatabase(context)
        db.settingDao().deleteAll()
        db.storeDao().deleteAll()
        db.limitDao().deleteAll()
        LogBuffer.i("Database", "All data cleared from Room database")
    }
}
