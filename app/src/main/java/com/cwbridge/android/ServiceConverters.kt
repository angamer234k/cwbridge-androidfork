package com.cwbridge.android

import androidx.room.TypeConverter
import com.google.gson.Gson

class ServiceConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromTriggerList(value: List<Trigger>): String = gson.toJson(value)

    @TypeConverter
    fun toTriggerList(value: String): List<Trigger> = 
        try { gson.fromJson(value, Array<Trigger>::class.java)?.toList() ?: emptyList() }
        catch (e: Exception) { emptyList() }

    @TypeConverter
    fun fromActionList(value: List<Action>): String = gson.toJson(value)

    @TypeConverter
    fun toActionList(value: String): List<Action> = 
        try { gson.fromJson(value, Array<Action>::class.java)?.toList() ?: emptyList() }
        catch (e: Exception) { emptyList() }
}
