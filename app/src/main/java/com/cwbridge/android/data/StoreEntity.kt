package com.cwbridge.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "store")
data class StoreEntity(
    @PrimaryKey val slot: String,
    val value: String
) {
    companion object {
        fun fromPair(pair: Pair<String, String>): StoreEntity = StoreEntity(pair.first, pair.second)
    }
}
