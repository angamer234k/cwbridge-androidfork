package com.cwbridge.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cwbridge.android.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logcatReader: LogcatReader
    private lateinit var invokeEngine: InvokeEngine
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private var bridgeRunning = false
    private lateinit var serviceAdapter: ServiceAdapter

    private val logListener: (LogBuffer.Line) -> Unit = { line ->
        runOnUiThread { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup drawer
        setupDrawer()

        invokeEngine = InvokeEngine(applicationContext, lifecycleScope)
        logcatReader = LogcatReader(this) { raw -> invokeEngine.onExternalLog(raw) }

        binding.btnStartStop.setOnClickListener { toggleBridge() }
        binding.btnA11y.setOnClickListener { openAccessibilitySettings() }
        binding.btnLogcatHint.setOnClickListener { showAdbGrantHint() }
        binding.btnTap.setOnClickListener { performTapByText() }
        binding.btnTapPercent.setOnClickListener { performTapPercent() }
        binding.btnTapPx.setOnClickListener { performTapPx() }
        binding.btnClearLogs.setOnClickListener {
            LogBuffer.clear()
            binding.logView.text = ""
        }

        // Services setup
        binding.btnAddService.setOnClickListener { showAddServiceDialog() }
        setupServicesRecyclerView()

        LogBuffer.addListener(logListener)
        LogBuffer.snapshot().forEach { appendLog(it) }
        LogBuffer.i("CWBridge", "session start version=2.8.0-android")
        refreshUi()
    }

    private fun setupDrawer() {
        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            R.string.app_name,
            R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            onNavigationItemSelected(menuItem)
            true
        }
    }

    private fun onNavigationItemSelected(menuItem: MenuItem) {
        when (menuItem.itemId) {
            R.id.nav_bridge -> showCategory("bridge")
            R.id.nav_permissions -> showCategory("permissions")
            R.id.nav_actions -> showCategory("actions")
            R.id.nav_services -> showCategory("services")
            R.id.nav_logs -> showCategory("logs")
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun showCategory(category: String) {
        binding.categoryBridge.visibility = if (category == "bridge") View.VISIBLE else View.GONE
        binding.categoryPermissions.visibility = if (category == "permissions") View.VISIBLE else View.GONE
        binding.categoryActions.visibility = if (category == "actions") View.VISIBLE else View.GONE
        binding.categoryServices.visibility = if (category == "services") View.VISIBLE else View.GONE
        binding.categoryLogs.visibility = if (category == "logs") View.VISIBLE else View.GONE
    }

    private fun setupServicesRecyclerView() {
        serviceAdapter = ServiceAdapter(
            services = ServiceRepository.getAllServices(),
            onEdit = { service -> showEditServiceDialog(service) },
            onDelete = { service -> deleteService(service) },
            onToggle = { service -> toggleService(service) }
        )
        binding.servicesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.servicesRecyclerView.adapter = serviceAdapter
    }

    private fun refreshServices() {
        serviceAdapter = ServiceAdapter(
            services = ServiceRepository.getAllServices(),
            onEdit = { service -> showEditServiceDialog(service) },
            onDelete = { service -> deleteService(service) },
            onToggle = { service -> toggleService(service) }
        )
        binding.servicesRecyclerView.adapter = serviceAdapter
    }

    private fun showAddServiceDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add New Service")

        val view = layoutInflater.inflate(R.layout.dialog_service_edit, null)
        val nameEdit = view.findViewById<android.widget.EditText>(R.id.editServiceName)
        val descEdit = view.findViewById<android.widget.EditText>(R.id.editServiceDescription)

        builder.setView(view)
        builder.setPositiveButton("Create") { _, _ ->
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Service name cannot be empty", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val service = Service(
                name = name,
                description = descEdit.text.toString().trim(),
                isEnabled = true
            )
            ServiceRepository.addService(service)
            refreshServices()
            Toast.makeText(this, "Service created", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showEditServiceDialog(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Edit Service")

        val view = layoutInflater.inflate(R.layout.dialog_service_edit, null)
        val nameEdit = view.findViewById<android.widget.EditText>(R.id.editServiceName)
        val descEdit = view.findViewById<android.widget.EditText>(R.id.editServiceDescription)

        nameEdit.setText(service.name)
        descEdit.setText(service.description)

        builder.setView(view)
        builder.setPositiveButton("Save") { _, _ ->
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Service name cannot be empty", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val updatedService = service.copy(
                name = name,
                description = descEdit.text.toString().trim()
            )
            ServiceRepository.updateService(updatedService)
            refreshServices()
            Toast.makeText(this, "Service updated", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.setNeutralButton("Actions") { _, _ ->
            showServiceActionsDialog(service)
        }
        builder.show()
    }

    private fun showServiceActionsDialog(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Manage Actions for ${service.name}")

        val items = arrayOf(
            "Add Action",
            "Reorder Actions",
            "Add Trigger"
        )

        builder.setItems(items) { _, which ->
            when (which) {
                0 -> showAddActionDialog(service)
                1 -> showReorderActionsDialog(service)
                2 -> showAddTriggerDialog(service)
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showAddActionDialog(service: Service) {
        val actionTypes = arrayOf(
            "Tap",
            "Get Info",
            "HTTP Request",
            "AI Action",
            "Delay",
            "Run Service"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Action")
            .setItems(actionTypes) { _, which ->
                when (which) {
                    0 -> addTapAction(service)
                    1 -> addGetInfoAction(service)
                    2 -> addHttpRequestAction(service)
                    3 -> addAiAction(service)
                    4 -> addDelayAction(service)
                    5 -> addRunServiceAction(service)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTapAction(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add Tap Action")

        val view = layoutInflater.inflate(R.layout.dialog_action_tap, null)
        val xPercentEdit = view.findViewById<android.widget.EditText>(R.id.editTapXPercent)
        val yPercentEdit = view.findViewById<android.widget.EditText>(R.id.editTapYPercent)
        val xPxEdit = view.findViewById<android.widget.EditText>(R.id.editTapXPx)
        val yPxEdit = view.findViewById<android.widget.EditText>(R.id.editTapYPx)
        val textEdit = view.findViewById<android.widget.EditText>(R.id.editTapText)

        builder.setView(view)
        builder.setPositiveButton("Add") { _, _ ->
            val action = Action.TapAction(
                xPercent = xPercentEdit.text.toString().toFloatOrNull(),
                yPercent = yPercentEdit.text.toString().toFloatOrNull(),
                xPx = xPxEdit.text.toString().toIntOrNull(),
                yPx = yPxEdit.text.toString().toIntOrNull(),
                text = textEdit.text.toString().ifEmpty { null }
            )
            ServiceRepository.addActionToService(service.id, action)
            refreshServices()
            Toast.makeText(this, "Tap action added", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addGetInfoAction(service: Service) {
        val infoTypes = InfoType.values().map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Get Info Action")
            .setItems(infoTypes) { _, which ->
                val action = Action.GetInfoAction(
                    infoType = InfoType.values()[which],
                    storeInVariable = "var_${System.currentTimeMillis()}"
                )
                ServiceRepository.addActionToService(service.id, action)
                refreshServices()
                Toast.makeText(this, "Get Info action added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addHttpRequestAction(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add HTTP Request Action")

        val view = layoutInflater.inflate(R.layout.dialog_action_http, null)
        val urlEdit = view.findViewById<android.widget.EditText>(R.id.editHttpUrl)
        val methodSpinner = view.findViewById<android.widget.Spinner>(R.id.spinnerHttpMethod)

        builder.setView(view)
        builder.setPositiveButton("Add") { _, _ ->
            val action = Action.HttpRequestAction(
                url = urlEdit.text.toString(),
                method = methodSpinner.selectedItem.toString()
            )
            ServiceRepository.addActionToService(service.id, action)
            refreshServices()
            Toast.makeText(this, "HTTP action added", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addAiAction(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add AI Action")

        val view = layoutInflater.inflate(R.layout.dialog_action_ai, null)
        val promptEdit = view.findViewById<android.widget.EditText>(R.id.editAiPrompt)

        builder.setView(view)
        builder.setPositiveButton("Add") { _, _ ->
            val action = Action.AiAction(
                prompt = promptEdit.text.toString()
            )
            ServiceRepository.addActionToService(service.id, action)
            refreshServices()
            Toast.makeText(this, "AI action added", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addDelayAction(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add Delay Action")

        val view = layoutInflater.inflate(R.layout.dialog_action_delay, null)
        val delayEdit = view.findViewById<android.widget.EditText>(R.id.editDelayMs)

        builder.setView(view)
        builder.setPositiveButton("Add") { _, _ ->
            val action = Action.DelayAction(
                milliseconds = delayEdit.text.toString().toLongOrNull() ?: 1000
            )
            ServiceRepository.addActionToService(service.id, action)
            refreshServices()
            Toast.makeText(this, "Delay action added", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addRunServiceAction(service: Service) {
        val availableServices = ServiceRepository.getAllServices()
            .filter { it.id != service.id }
            .map { it.name }
            .toTypedArray()

        if (availableServices.isEmpty()) {
            Toast.makeText(this, "No other services available", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Run Service Action")
            .setItems(availableServices) { _, which ->
                val targetService = ServiceRepository.getAllServices()
                    .filter { it.id != service.id }[which]
                val action = Action.RunServiceAction(
                    serviceId = targetService.id
                )
                ServiceRepository.addActionToService(service.id, action)
                refreshServices()
                Toast.makeText(this, "Run Service action added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReorderActionsDialog(service: Service) {
        val actionNames = service.actions.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Reorder Actions")
            .setMessage("Select an action to move up or down")
            .setItems(actionNames) { _, which ->
                showMoveActionDialog(service, which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveActionDialog(service: Service, currentIndex: Int) {
        val items = arrayOf("Move Up", "Move Down", "Remove")

        MaterialAlertDialogBuilder(this)
            .setTitle("Action: ${service.actions[currentIndex].name}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        // Move up
                        if (currentIndex > 0) {
                            val action = service.actions[currentIndex]
                            service.actions.removeAt(currentIndex)
                            service.actions.add(currentIndex - 1, action)
                            ServiceRepository.updateService(service)
                            refreshServices()
                        }
                    }
                    1 -> {
                        // Move down
                        if (currentIndex < service.actions.size - 1) {
                            val action = service.actions[currentIndex]
                            service.actions.removeAt(currentIndex)
                            service.actions.add(currentIndex + 1, action)
                            ServiceRepository.updateService(service)
                            refreshServices()
                        }
                    }
                    2 -> {
                        // Remove
                        ServiceRepository.removeActionFromService(service.id, service.actions[currentIndex].id)
                        refreshServices()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTriggerDialog(service: Service) {
        val triggerTypes = arrayOf(
            "Log Trigger",
            "Time Trigger",
            "Accessibility Trigger"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Trigger")
            .setItems(triggerTypes) { _, which ->
                when (which) {
                    0 -> addLogTrigger(service)
                    1 -> addTimeTrigger(service)
                    2 -> addAccessibilityTrigger(service)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addLogTrigger(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add Log Trigger")

        val view = layoutInflater.inflate(R.layout.dialog_trigger_log, null)
        val patternEdit = view.findViewById<android.widget.EditText>(R.id.editLogPattern)

        builder.setView(view)
        builder.setPositiveButton("Add") { _, _ ->
            val trigger = Trigger.LogTrigger(
                pattern = patternEdit.text.toString()
            )
            service.triggers.add(trigger)
            ServiceRepository.updateService(service)
            refreshServices()
            Toast.makeText(this, "Log trigger added", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addTimeTrigger(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add Time Trigger")

        val view = layoutInflater.inflate(R.layout.dialog_trigger_time, null)
        val intervalEdit = view.findViewById<android.widget.EditText>(R.id.editTimeInterval)

        builder.setView(view)
        builder.setPositiveButton("Add") { _, _ ->
            val trigger = Trigger.TimeTrigger(
                intervalMs = intervalEdit.text.toString().toLongOrNull() ?: 1000
            )
            service.triggers.add(trigger)
            ServiceRepository.updateService(service)
            refreshServices()
            Toast.makeText(this, "Time trigger added", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addAccessibilityTrigger(service: Service) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Add Accessibility Trigger")

        val view = layoutInflater.inflate(R.layout.dialog_trigger_accessibility, null)
        val textEdit = view.findViewById<android.widget.EditText>(R.id.editAccTextPattern)

        builder.setView(view)
        builder.setPositiveButton("Add") { _, _ ->
            val trigger = Trigger.AccessibilityTrigger(
                textPattern = textEdit.text.toString()
            )
            service.triggers.add(trigger)
            ServiceRepository.updateService(service)
            refreshServices()
            Toast.makeText(this, "Accessibility trigger added", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun deleteService(service: Service) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Service")
            .setMessage("Are you sure you want to delete '${service.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                ServiceRepository.deleteService(service.id)
                refreshServices()
                Toast.makeText(this, "Service deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleService(service: Service) {
        val updatedService = service.copy(isEnabled = !service.isEnabled)
        ServiceRepository.updateService(updatedService)
        refreshServices()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        logcatReader.start(lifecycleScope)
    }

    override fun onPause() {
        logcatReader.stop()
        super.onPause()
    }

    override fun onDestroy() {
        invokeEngine.stop()
        LogBuffer.removeListener(logListener)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun toggleBridge() {
        if (bridgeRunning) {
            bridgeRunning = false
            invokeEngine.stop()
            LogBuffer.i("CWBridge", "bridge stopped")
        } else {
            if (!TapService.isConnected()) {
                LogBuffer.e("CWBridge", "refusing start: Accessibility service is off")
                Toast.makeText(this, "Enable CWBridge Tap first", Toast.LENGTH_SHORT).show()
                openAccessibilitySettings()
                refreshUi()
                return
            }
            bridgeRunning = true
            invokeEngine.start()
            invokeEngine.focusXPct = 50f
            invokeEngine.focusYPct = 50f
            invokeEngine.submitXPx = 730f
            invokeEngine.submitYPx = 1028f
            LogBuffer.i(
                "CWBridge",
                "bridge running invoke=on logcat=${logcatReader.hasPermission()}",
            )
            LogBuffer.i("CWBridge", "stays idle while Roblox is closed; watches invoke| in logcat")
            LogBuffer.i("CWBridge", "paste defaults focus=50,50 submit=730,1028 - change with invoke|focus / submit")
            if (!logcatReader.hasPermission()) {
                LogBuffer.w("CWBridge", "without READ_LOGS, only in-app test invokes work - grant via ADB")
            }
        }
        refreshUi()
    }

    private fun requireService(): TapService? {
        val service = TapService.instance
        if (service == null) {
            Toast.makeText(this, "CWBridge Tap is not connected", Toast.LENGTH_SHORT).show()
            LogBuffer.w("A11y", "tap requested but service offline")
        }
        return service
    }

    private fun performTapByText() {
        val query = binding.tapQuery.text?.toString().orEmpty()
        val service = requireService() ?: return
        val ok = service.clickByText(query)
        Toast.makeText(
            this,
            if (ok) "Tapped \"$query\"" else "No match for \"$query\" (use %/px for Roblox)",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun performTapPercent() {
        val service = requireService() ?: return
        val x = binding.tapXPercent.text?.toString()?.toFloatOrNull()
        val y = binding.tapYPercent.text?.toString()?.toFloatOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Enter X% and Y% (0-100)", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = service.clickAtPercent(x, y)
        Toast.makeText(
            this,
            if (ok) "Tapped ${x}% ${y}%" else "Gesture failed",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun performTapPx() {
        val service = requireService() ?: return
        val x = binding.tapXPx.text?.toString()?.toFloatOrNull()
        val y = binding.tapYPx.text?.toString()?.toFloatOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Enter X and Y pixels", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = service.clickAt(x, y)
        Toast.makeText(
            this,
            if (ok) "Tapped px ($x, $y)" else "Gesture failed",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showAdbGrantHint() {
        val pkg = packageName
        MaterialAlertDialogBuilder(this)
            .setTitle("Grant READ_LOGS")
            .setMessage(
                "Required to see Roblox FLog invoke| lines.\n\n" +
                    "adb shell pm grant $pkg android.permission.READ_LOGS\n\n" +
                    "Without it, only the process-local buffer works.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshUi() {
        val a11yOn = isAccessibilityEnabled()
        binding.a11yState.text = if (a11yOn) "on" else "off"
        binding.a11yState.setTextColor(
            ContextCompat.getColor(this, if (a11yOn) R.color.ok else R.color.danger),
        )

        val logsOn = logcatReader.hasPermission()
        binding.logcatState.text = if (logsOn) "granted" else "not granted (ADB)"
        binding.logcatState.setTextColor(
            ContextCompat.getColor(this, if (logsOn) R.color.ok else R.color.warn),
        )

        when {
            !bridgeRunning -> {
                binding.statusPill.text = getString(R.string.status_stopped)
                binding.statusPill.setTextColor(ContextCompat.getColor(this, R.color.muted))
                binding.statusDetail.text = "Enable CWBridge Tap, then start the bridge."
                binding.btnStartStop.text = "Start"
            }
            else -> {
                binding.statusPill.text = getString(R.string.status_running)
                binding.statusPill.setTextColor(ContextCompat.getColor(this, R.color.ok))
                binding.statusDetail.text =
                    "invoke| engine on. Roblox needs READ_LOGS. Try invoke|help"
                binding.btnStartStop.text = "Stop"
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (TapService.isConnected()) return true
        val expected = ComponentName(this, TapService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun appendLog(line: LogBuffer.Line) {
        val row = "${line.ts} ${line.level} ${line.tag}: ${line.msg}\n"
        binding.logView.append(row)
    }
}
