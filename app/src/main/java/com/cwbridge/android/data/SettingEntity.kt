package com.cwbridge.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
) {
    companion object {
        fun fromPair(pair: Pair<String, String>): SettingEntity = SettingEntity(pair.first, pair.second)
    }
}
