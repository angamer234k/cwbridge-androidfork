package com.cwbridge.android

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Represents an automation service with triggers and actions.
 * In-memory only (no Parcelable needed).
 */
data class Service(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val triggers: MutableList<Trigger> = mutableListOf(),
    val actions: MutableList<Action> = mutableListOf()
)

sealed class Trigger {
    abstract val id: String
    abstract val name: String
    abstract val type: TriggerType

    data class LogTrigger(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Log Trigger",
        val pattern: String = "",
        val matchCase: Boolean = false,
        val useRegex: Boolean = false
    ) : Trigger() {
        override val type: TriggerType = TriggerType.LOG
    }

    data class TimeTrigger(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Time Trigger",
        val intervalMs: Long = 1000,
        val repeat: Boolean = true
    ) : Trigger() {
        override val type: TriggerType = TriggerType.TIME
    }

    data class AccessibilityTrigger(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Accessibility Trigger",
        val textPattern: String = "",
        val nodeId: String = "",
        val eventType: String = "click"
    ) : Trigger() {
        override val type: TriggerType = TriggerType.ACCESSIBILITY
    }
}

sealed class Action {
    abstract val id: String
    abstract val name: String
    abstract val type: ActionType

    data class TapAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Tap",
        val xPercent: Float? = null,
        val yPercent: Float? = null,
        val xPx: Int? = null,
        val yPx: Int? = null,
        val text: String? = null
    ) : Action() {
        override val type: ActionType = ActionType.TAP
    }

    data class GetInfoAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Get Info",
        val infoType: InfoType = InfoType.SCREEN_TEXT,
        val storeInVariable: String = ""
    ) : Action() {
        override val type: ActionType = ActionType.GET_INFO
    }

    data class HttpRequestAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "HTTP Request",
        val url: String = "",
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
        val storeResponseIn: String = ""
    ) : Action() {
        override val type: ActionType = ActionType.HTTP_REQUEST
    }

    data class AiAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "AI Action",
        val prompt: String = "",
        val model: String = "default",
        val storeResponseIn: String = ""
    ) : Action() {
        override val type: ActionType = ActionType.AI
    }

    data class DelayAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Delay",
        val milliseconds: Long = 1000
    ) : Action() {
        override val type: ActionType = ActionType.DELAY
    }

    data class RunServiceAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Run Service",
        val serviceId: String = ""
    ) : Action() {
        override val type: ActionType = ActionType.RUN_SERVICE
    }
}

enum class TriggerType {
    LOG,
    TIME,
    ACCESSIBILITY
}

enum class ActionType {
    TAP,
    GET_INFO,
    HTTP_REQUEST,
    AI,
    DELAY,
    RUN_SERVICE
}

enum class InfoType {
    SCREEN_TEXT,
    NODE_TEXT,
    NODE_BOUNDS,
    CURRENT_APP,
    TIMESTAMP,
    CLIPBOARD
}

object ServiceRepository {
    private var database: ServiceDatabase? = null
    private val inMemoryServices: MutableList<Service> = mutableListOf()
    private var useDatabase = false

    fun init(context: Context, scope: CoroutineScope) {
        if (database == null) {
            database = Room.databaseBuilder(
                context,
                ServiceDatabase::class.java,
                ServiceDatabase.DATABASE_NAME
            ).build()
            useDatabase = true
            scope.launch(Dispatchers.IO) {
                loadFromDatabase()
            }
        }
    }

    private suspend fun loadFromDatabase() {
        database?.serviceDao()?.getAll()?.forEach { entity ->
            val service = entity.toService()
            if (!inMemoryServices.any { it.id == service.id }) {
                inMemoryServices.add(service)
            }
        }
    }

    private fun saveToDatabase(service: Service) {
        if (useDatabase) {
            CoroutineScope(Dispatchers.IO).launch {
                database?.serviceDao()?.insert(ServiceEntity.fromService(service))
            }
        }
    }

    private fun deleteFromDatabase(id: String) {
        if (useDatabase) {
            CoroutineScope(Dispatchers.IO).launch {
                database?.serviceDao()?.deleteById(id)
            }
        }
    }

    fun getAllServices(): List<Service> = inMemoryServices.toList()

    fun getServiceById(id: String): Service? = inMemoryServices.find { it.id == id }

    fun addService(service: Service) {
        inMemoryServices.add(service)
        saveToDatabase(service)
    }

    fun updateService(updatedService: Service) {
        val index = inMemoryServices.indexOfFirst { it.id == updatedService.id }
        if (index != -1) {
            inMemoryServices[index] = updatedService
        }
        saveToDatabase(updatedService)
    }

    fun deleteService(id: String): Boolean {
        val service = inMemoryServices.find { it.id == id }
        return if (service != null) {
            inMemoryServices.remove(service)
            deleteFromDatabase(id)
            true
        } else {
            false
        }
    }

    fun reorderActions(serviceId: String, newOrder: List<String>) {
        val service = inMemoryServices.find { it.id == serviceId }
        service?.let { s ->
            val orderedActions = s.actions.sortedBy { action ->
                newOrder.indexOf(action.id)
            }.toMutableList()
            s.actions.clear()
            s.actions.addAll(orderedActions)
            saveToDatabase(s)
        }
    }

    fun addActionToService(serviceId: String, action: Action): Boolean {
        val service = inMemoryServices.find { it.id == serviceId }
        return if (service != null) {
            service.actions.add(action)
            saveToDatabase(service)
            true
        } else {
            false
        }
    }

    fun removeActionFromService(serviceId: String, actionId: String): Boolean {
        val service = inMemoryServices.find { it.id == serviceId }
        return if (service != null) {
            service.actions.removeIf { it.id == actionId }
            saveToDatabase(service)
            true
        } else {
            false
        }
    }

    fun addTriggerToService(serviceId: String, trigger: Trigger): Boolean {
        val service = inMemoryServices.find { it.id == serviceId }
        return if (service != null) {
            service.triggers.add(trigger)
            saveToDatabase(service)
            true
        } else {
            false
        }
    }

    fun removeTriggerFromService(serviceId: String, triggerId: String): Boolean {
        val service = inMemoryServices.find { it.id == serviceId }
        return if (service != null) {
            service.triggers.removeIf { it.id == triggerId }
            saveToDatabase(service)
            true
        } else {
            false
        }
    }
}
