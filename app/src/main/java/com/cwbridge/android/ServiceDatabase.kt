package com.cwbridge.android

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ServiceEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(ServiceConverters::class)
abstract class ServiceDatabase : RoomDatabase() {
    abstract fun serviceDao(): ServiceDao

    companion object {
        const val DATABASE_NAME = "cwbridge-services"
    }
}
