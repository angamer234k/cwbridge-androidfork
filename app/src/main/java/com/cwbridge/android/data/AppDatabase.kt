package com.cwbridge.android.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SettingEntity::class,
        StoreEntity::class,
        LimitEntity::class,
        ServiceEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(ServiceConverters::class, DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingDao(): SettingDao
    abstract fun storeDao(): StoreDao
    abstract fun limitDao(): LimitDao
    abstract fun serviceDao(): ServiceDao

    companion object {
        const val DATABASE_NAME = "cwbridge-app"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS settings (
                        key TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS store (
                        slot TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS limits (
                        domain TEXT PRIMARY KEY NOT NULL,
                        bytes INTEGER NOT NULL
                    )
                """)
            }
        }
    }
}
