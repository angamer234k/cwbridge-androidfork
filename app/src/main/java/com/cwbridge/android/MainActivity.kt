package com.cwbridge.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
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

        binding.btnAddService.setOnClickListener { showAddServiceDialog() }
        setupServicesRecyclerView()

        LogBuffer.addListener(logListener)
        LogBuffer.snapshot().forEach { appendLog(it) }
        LogBuffer.i("CWBridge", "session start version=2.9.0-android")
        showCategory("bridge")
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
        val items = arrayOf("Add Action", "Reorder Actions", "Add Trigger")
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
            "Tap", "Get Info", "HTTP Request", "AI Action", "Delay", "Run Service"
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
            val action = Action.AiAction(prompt = promptEdit.text.toString())
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
        val others = ServiceRepository.getAllServices().filter { it.id != service.id }
        if (others.isEmpty()) {
            Toast.makeText(this, "No other services available", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Run Service Action")
            .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                val action = Action.RunServiceAction(serviceId = others[which].id)
                ServiceRepository.addActionToService(service.id, action)
                refreshServices()
                Toast.makeText(this, "Run Service action added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReorderActionsDialog(service: Service) {
        if (service.actions.isEmpty()) {
            Toast.makeText(this, "No actions to reorder", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Reorder Actions")
            .setItems(service.actions.map { it.name }.toTypedArray()) { _, which ->
                showMoveActionDialog(service, which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveActionDialog(service: Service, currentIndex: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Action: ${service.actions[currentIndex].name}")
            .setItems(arrayOf("Move Up", "Move Down", "Remove")) { _, which ->
                when (which) {
                    0 -> if (currentIndex > 0) {
                        val a = service.actions.removeAt(currentIndex)
                        service.actions.add(currentIndex - 1, a)
                        ServiceRepository.updateService(service)
                        refreshServices()
                    }
                    1 -> if (currentIndex < service.actions.size - 1) {
                        val a = service.actions.removeAt(currentIndex)
                        service.actions.add(currentIndex + 1, a)
                        ServiceRepository.updateService(service)
                        refreshServices()
                    }
                    2 -> {
                        ServiceRepository.removeActionFromService(
                            service.id, service.actions[currentIndex].id
                        )
                        refreshServices()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTriggerDialog(service: Service) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Trigger")
            .setItems(arrayOf("Log Trigger", "Time Trigger", "Accessibility Trigger")) { _, which ->
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
        val view = layoutInflater.inflate(R.layout.dialog_trigger_log, null)
        val patternEdit = view.findViewById<android.widget.EditText>(R.id.editLogPattern)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Log Trigger")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                service.triggers.add(Trigger.LogTrigger(pattern = patternEdit.text.toString()))
                ServiceRepository.updateService(service)
                refreshServices()
                Toast.makeText(this, "Log trigger added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTimeTrigger(service: Service) {
        val view = layoutInflater.inflate(R.layout.dialog_trigger_time, null)
        val intervalEdit = view.findViewById<android.widget.EditText>(R.id.editTimeInterval)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Time Trigger")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                service.triggers.add(
                    Trigger.TimeTrigger(
                        intervalMs = intervalEdit.text.toString().toLongOrNull() ?: 1000
                    )
                )
                ServiceRepository.updateService(service)
                refreshServices()
                Toast.makeText(this, "Time trigger added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAccessibilityTrigger(service: Service) {
        val view = layoutInflater.inflate(R.layout.dialog_trigger_accessibility, null)
        // layout id is editAccTextPattern
        val patternEdit = view.findViewById<android.widget.EditText>(R.id.editAccTextPattern)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Accessibility Trigger")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                service.triggers.add(
                    Trigger.AccessibilityTrigger(textPattern = patternEdit.text.toString())
                )
                ServiceRepository.updateService(service)
                refreshServices()
                Toast.makeText(this, "Accessibility trigger added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteService(service: Service) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Service")
            .setMessage("Delete ${service.name}?")
            .setPositiveButton("Delete") { _, _ ->
                ServiceRepository.deleteService(service.id)
                refreshServices()
                Toast.makeText(this, "Service deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleService(service: Service) {
        ServiceRepository.updateService(service.copy(isEnabled = !service.isEnabled))
        refreshServices()
    }

    override fun onDestroy() {
        LogBuffer.removeListener(logListener)
        if (bridgeRunning) logcatReader.stop()
        super.onDestroy()
    }

    private fun toggleBridge() {
        if (bridgeRunning) {
            logcatReader.stop()
            bridgeRunning = false
            LogBuffer.i("CWBridge", "bridge stopped")
        } else {
            if (!logcatReader.start()) {
                Toast.makeText(this, "logcat failed — grant READ_LOGS via helper/ADB", Toast.LENGTH_LONG).show()
                return
            }
            bridgeRunning = true
            LogBuffer.i("CWBridge", "bridge started")
        }
        refreshUi()
    }

    private fun refreshUi() {
        binding.btnStartStop.text = if (bridgeRunning) "Stop bridge" else "Start bridge"
        val a11y = isAccessibilityEnabled()
        binding.chipA11y.text = if (a11y) "A11y ON" else "A11y OFF"
        binding.chipA11y.setTextColor(ContextCompat.getColor(this, if (a11y) R.color.ok else R.color.warn))
        binding.chipBridge.text = if (bridgeRunning) "Bridge ON" else "Bridge OFF"
        binding.chipBridge.setTextColor(ContextCompat.getColor(this, if (bridgeRunning) R.color.ok else R.color.warn))
    }

    private fun isAccessibilityEnabled(): Boolean {
        val cn = ComponentName(this, TapService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (ComponentName.unflattenFromString(splitter.next()) == cn) return true
        }
        return false
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showAdbGrantHint() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Grant READ_LOGS")
            .setMessage("adb shell pm grant ${packageName} android.permission.READ_LOGS\n\nOr use CWBridge Helper over OTG.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun performTapByText() {
        val text = binding.editTapText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter text to tap", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = TapService.instance?.tapText(text) == true
            Toast.makeText(this@MainActivity, if (ok) "Tapped $text" else "Tap failed / a11y off", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performTapPercent() {
        val x = binding.editTapXPercent.text?.toString()?.toFloatOrNull()
        val y = binding.editTapYPercent.text?.toString()?.toFloatOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Enter X% and Y%", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = TapService.instance?.tapPercent(x, y) == true
            Toast.makeText(this@MainActivity, if (ok) "Tapped $x% $y%" else "Tap failed / a11y off", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performTapPx() {
        val x = binding.editTapXPx.text?.toString()?.toIntOrNull()
        val y = binding.editTapYPx.text?.toString()?.toIntOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Enter Xpx and Ypx", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = TapService.instance?.tapPx(x, y) == true
            Toast.makeText(this@MainActivity, if (ok) "Tapped $x,$y" else "Tap failed / a11y off", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendLog(line: LogBuffer.Line) {
        binding.logView.append("${line.tag}: ${line.message}\n")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
