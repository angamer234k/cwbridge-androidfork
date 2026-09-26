package com.cwbridge.android

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/** Sealed Action/Trigger need _kind — plain Gson emptied lists on load. */
class ServiceConverters {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Action::class.java, ActionAdapter())
        .registerTypeAdapter(Trigger::class.java, TriggerAdapter())
        .create()

    private val actionListType = object : TypeToken<List<Action>>() {}.type
    private val triggerListType = object : TypeToken<List<Trigger>>() {}.type

    @TypeConverter
    fun fromTriggerList(value: List<Trigger>): String = gson.toJson(value, triggerListType)

    @TypeConverter
    fun toTriggerList(value: String): List<Trigger> = try {
        gson.fromJson(value, triggerListType) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @TypeConverter
    fun fromActionList(value: List<Action>): String = gson.toJson(value, actionListType)

    @TypeConverter
    fun toActionList(value: String): List<Action> = try {
        gson.fromJson(value, actionListType) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

private class ActionAdapter : JsonSerializer<Action>, JsonDeserializer<Action> {
    override fun serialize(src: Action, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val tree = when (src) {
            is Action.TapAction -> context.serialize(src, Action.TapAction::class.java)
            is Action.GetInfoAction -> context.serialize(src, Action.GetInfoAction::class.java)
            is Action.HttpRequestAction -> context.serialize(src, Action.HttpRequestAction::class.java)
            is Action.AiAction -> context.serialize(src, Action.AiAction::class.java)
            is Action.DelayAction -> context.serialize(src, Action.DelayAction::class.java)
            is Action.RunServiceAction -> context.serialize(src, Action.RunServiceAction::class.java)
            is Action.CtrlTAction -> context.serialize(src, Action.CtrlTAction::class.java)
            is Action.PressEnterAction -> context.serialize(src, Action.PressEnterAction::class.java)
            is Action.SendTextAction -> context.serialize(src, Action.SendTextAction::class.java)
            is Action.EnterTextAction -> context.serialize(src, Action.EnterTextAction::class.java)
            is Action.TextManAction -> context.serialize(src, Action.TextManAction::class.java)
            is Action.SetVarAction -> context.serialize(src, Action.SetVarAction::class.java)
        }.asJsonObject
        tree.addProperty("_kind", src.type.name)
        return tree
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Action {
        val obj = json.asJsonObject
        val kind = (obj.get("_kind")?.asString ?: obj.get("type")?.asString ?: "").uppercase()
        return when (kind) {
            "TAP" -> context.deserialize(obj, Action.TapAction::class.java)
            "GET_INFO" -> context.deserialize(obj, Action.GetInfoAction::class.java)
            "HTTP_REQUEST" -> context.deserialize(obj, Action.HttpRequestAction::class.java)
            "AI" -> context.deserialize(obj, Action.AiAction::class.java)
            "DELAY" -> context.deserialize(obj, Action.DelayAction::class.java)
            "RUN_SERVICE" -> context.deserialize(obj, Action.RunServiceAction::class.java)
            "CTRL_T" -> context.deserialize(obj, Action.CtrlTAction::class.java)
            "PRESS_ENTER" -> context.deserialize(obj, Action.PressEnterAction::class.java)
            "SEND_TEXT" -> context.deserialize(obj, Action.SendTextAction::class.java)
            "ENTER_TEXT" -> context.deserialize(obj, Action.EnterTextAction::class.java)
            "TEXT_MAN" -> context.deserialize(obj, Action.TextManAction::class.java)
            "SET_VAR" -> context.deserialize(obj, Action.SetVarAction::class.java)
            else -> if (obj.has("milliseconds")) {
                context.deserialize(obj, Action.DelayAction::class.java)
            } else {
                throw JsonParseException("Unknown Action kind: $kind")
            }
        }
    }
}

private class TriggerAdapter : JsonSerializer<Trigger>, JsonDeserializer<Trigger> {
    override fun serialize(src: Trigger, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val tree = when (src) {
            is Trigger.LogTrigger -> context.serialize(src, Trigger.LogTrigger::class.java)
            is Trigger.TimeTrigger -> context.serialize(src, Trigger.TimeTrigger::class.java)
            is Trigger.AccessibilityTrigger -> context.serialize(src, Trigger.AccessibilityTrigger::class.java)
        }.asJsonObject
        tree.addProperty("_kind", src.type.name)
        return tree
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Trigger {
        val obj = json.asJsonObject
        val kind = (obj.get("_kind")?.asString ?: obj.get("type")?.asString ?: "").uppercase()
        return when (kind) {
            "LOG" -> context.deserialize(obj, Trigger.LogTrigger::class.java)
            "TIME" -> context.deserialize(obj, Trigger.TimeTrigger::class.java)
            "ACCESSIBILITY" -> context.deserialize(obj, Trigger.AccessibilityTrigger::class.java)
            else -> if (obj.has("pattern")) {
                context.deserialize(obj, Trigger.LogTrigger::class.java)
            } else {
                throw JsonParseException("Unknown Trigger kind: $kind")
            }
        }
    }
}
