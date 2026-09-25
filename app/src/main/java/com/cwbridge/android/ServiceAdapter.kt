package com.cwbridge.android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ServiceAdapter(
    private val services: List<Service>,
    private val onEdit: (Service) -> Unit,
    private val onDelete: (Service) -> Unit,
    private val onToggle: (Service) -> Unit
) : RecyclerView.Adapter<ServiceAdapter.ServiceViewHolder>() {

    class ServiceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: CardView = itemView.findViewById(R.id.serviceCard)
        val name: TextView = itemView.findViewById(R.id.serviceName)
        val description: TextView = itemView.findViewById(R.id.serviceDescription)
        val status: TextView = itemView.findViewById(R.id.serviceStatus)
        val triggersCount: TextView = itemView.findViewById(R.id.serviceTriggersCount)
        val actionsCount: TextView = itemView.findViewById(R.id.serviceActionsCount)
        val btnEdit: View = itemView.findViewById(R.id.btnEditService)
        val btnDelete: View = itemView.findViewById(R.id.btnDeleteService)
        val btnToggle: View = itemView.findViewById(R.id.btnToggleService)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_service, parent, false)
        return ServiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServiceViewHolder, position: Int) {
        val service = services[position]
        holder.name.text = service.name
        holder.description.text = service.description.ifEmpty { "No description" }
        holder.triggersCount.text = "Triggers: ${service.triggers.size}"
        holder.actionsCount.text = "Actions: ${service.actions.size}"

        // Status
        if (service.isEnabled) {
            holder.status.text = "Enabled"
            holder.status.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.ok))
        } else {
            holder.status.text = "Disabled"
            holder.status.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.danger))
        }

        // Toggle button
        holder.btnToggle.setOnClickListener { onToggle(service) }

        // Edit button
        holder.btnEdit.setOnClickListener { onEdit(service) }

        // Delete button
        holder.btnDelete.setOnClickListener { onDelete(service) }
    }

    override fun getItemCount(): Int = services.size
}
