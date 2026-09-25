package com.cwbridge.android

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

@Entity(tableName = "services")
@TypeConverters(ServiceConverters::class)
data class ServiceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val triggers: List<Trigger> = emptyList(),
    val actions: List<Action> = emptyList()
) {
    fun toService(): Service = Service(
        id = id,
        name = name,
        description = description,
        isEnabled = isEnabled,
        triggers = triggers.toMutableList(),
        actions = actions.toMutableList()
    )

    companion object {
        fun fromService(service: Service): ServiceEntity = ServiceEntity(
            id = service.id,
            name = service.name,
            description = service.description,
            isEnabled = service.isEnabled,
            triggers = service.triggers,
            actions = service.actions
        )
    }
}
