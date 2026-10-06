package com.cwbridge.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "limits")
data class LimitEntity(
    @PrimaryKey val domain: String,
    val bytes: Long
)
