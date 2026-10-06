package com.cwbridge.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LimitDao {
    @Query("SELECT * FROM limits WHERE domain = :domain LIMIT 1")
    suspend fun getByDomain(domain: String): LimitEntity?

    @Query("SELECT bytes FROM limits WHERE domain = :domain LIMIT 1")
    suspend fun getBytesByDomain(domain: String): Long?

    @Query("SELECT * FROM limits")
    suspend fun getAll(): List<LimitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LimitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<LimitEntity>)

    @Query("DELETE FROM limits WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)

    @Query("DELETE FROM limits")
    suspend fun deleteAll()
}
