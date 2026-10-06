package com.cwbridge.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StoreDao {
    @Query("SELECT * FROM store WHERE `slot` = :slot LIMIT 1")
    suspend fun getBySlot(slot: String): StoreEntity?

    @Query("SELECT value FROM store WHERE `slot` = :slot LIMIT 1")
    suspend fun getValueBySlot(slot: String): String?

    @Query("SELECT * FROM store")
    suspend fun getAll(): List<StoreEntity>

    @Query("SELECT * FROM store WHERE `slot` LIKE :prefix || '%'")
    suspend fun getAllWithPrefix(prefix: String): List<StoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StoreEntity>)

    @Query("DELETE FROM store WHERE `slot` = :slot")
    suspend fun deleteBySlot(slot: String)

    @Query("DELETE FROM store WHERE `slot` LIKE :prefix || '%'")
    suspend fun deleteAllWithPrefix(prefix: String): Int

    @Query("DELETE FROM store")
    suspend fun deleteAll()
}
