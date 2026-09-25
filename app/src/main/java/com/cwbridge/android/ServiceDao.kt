package com.cwbridge.android

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services ORDER BY name ASC")
    suspend fun getAll(): List<ServiceEntity>

    @Query("SELECT * FROM services WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(service: ServiceEntity)

    @Update
    suspend fun update(service: ServiceEntity)

    @Delete
    suspend fun delete(service: ServiceEntity)

    @Query("DELETE FROM services WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM services")
    suspend fun deleteAll()
}
