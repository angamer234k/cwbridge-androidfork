package com.cwbridge.android

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents an automation service with triggers and actions.
 */
@Parcelize
data class Service(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val triggers: MutableList<Trigger> = mutableListOf(),
    val actions: MutableList<Action> = mutableListOf()
) : Parcelable

/**
 * Base class for all trigger types.
 */
@Parcelize
sealed class Trigger : Parcelable {
    abstract val id: String
    abstract val name: String
    abstract val type: TriggerType

    @Parcelize
    data class LogTrigger(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Log Trigger",
        val pattern: String = "",
        val matchCase: Boolean = false,
        val useRegex: Boolean = false
    ) : Trigger() {
        override val type: TriggerType = TriggerType.LOG
    }

    @Parcelize
    data class TimeTrigger(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Time Trigger",
        val intervalMs: Long = 1000,
        val repeat: Boolean = true
    ) : Trigger() {
        override val type: TriggerType = TriggerType.TIME
    }

    @Parcelize
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

/**
 * Base class for all action types.
 */
@Parcelize
sealed class Action : Parcelable {
    abstract val id: String
    abstract val name: String
    abstract val type: ActionType

    @Parcelize
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

    @Parcelize
    data class GetInfoAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Get Info",
        val infoType: InfoType = InfoType.SCREEN_TEXT,
        val storeInVariable: String = ""
    ) : Action() {
        override val type: ActionType = ActionType.GET_INFO
    }

    @Parcelize
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

    @Parcelize
    data class AiAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "AI Action",
        val prompt: String = "",
        val model: String = "default",
        val storeResponseIn: String = ""
    ) : Action() {
        override val type: ActionType = ActionType.AI
    }

    @Parcelize
    data class DelayAction(
        override val id: String = System.currentTimeMillis().toString(),
        override val name: String = "Delay",
        val milliseconds: Long = 1000
    ) : Action() {
        override val type: ActionType = ActionType.DELAY
    }

    @Parcelize
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

/**
 * Repository for managing services.
 */
object ServiceRepository {
    private val services: MutableList<Service> = mutableListOf()

    fun getAllServices(): List<Service> = services.toList()

    fun getServiceById(id: String): Service? = services.find { it.id == id }

    fun addService(service: Service) {
        services.add(service)
    }

    fun updateService(updatedService: Service) {
        val index = services.indexOfFirst { it.id == updatedService.id }
        if (index != -1) {
            services[index] = updatedService
        }
    }

    fun deleteService(id: String): Boolean {
        val service = services.find { it.id == id }
        return if (service != null) {
            services.remove(service)
            true
        } else {
            false
        }
    }

    fun reorderActions(serviceId: String, newOrder: List<String>) {
        val service = services.find { it.id == serviceId }
        service?.let { s ->
            val orderedActions = s.actions.sortedBy { action ->
                newOrder.indexOf(action.id)
            }.toMutableList()
            s.actions.clear()
            s.actions.addAll(orderedActions)
        }
    }

    fun addActionToService(serviceId: String, action: Action): Boolean {
        val service = services.find { it.id == serviceId }
        return if (service != null) {
            service.actions.add(action)
            true
        } else {
            false
        }
    }

    fun removeActionFromService(serviceId: String, actionId: String): Boolean {
        val service = services.find { it.id == serviceId }
        return if (service != null) {
            service.actions.removeIf { it.id == actionId }
            true
        } else {
            false
        }
    }
}
