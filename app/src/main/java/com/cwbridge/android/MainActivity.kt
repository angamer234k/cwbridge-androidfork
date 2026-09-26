package com.cwbridge.android

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cwbridge.android.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logcatReader: LogcatReader
    private lateinit var invokeEngine: InvokeEngine
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private var bridgeRunning = false
    private lateinit var serviceAdapter: ServiceAdapter
    private var localServer: LocalHttpServer? = null

    private val logListener: (LogBuffer.Line) -> Unit = { line ->
        runOnUiThread {
            appendLog(line)
            if (line.level == LogBuffer.Level.E) {
                BridgeStatus.set(OverlayState.ERROR, line.msg.take(80))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupDrawer()
        ServiceRepository.init(applicationContext, lifecycleScope)
        invokeEngine = InvokeEngine(applicationContext, lifecycleScope)
        logcatReader = LogcatReader(this) { raw -> invokeEngine.onExternalLog(raw) }
        ensureOverlayPermission()
        OverlayService.start(this)
        executionEngine = ExecutionEngine(applicationContext, lifecycleScope, invokeEngine, logcatReader)
        executionEngine.start()
        binding.btnStartStop.setOnClickListener { toggleBridge() }
        binding.btnA11y.setOnClickListener { openAccessibilitySettings() }
        binding.btnLogcatHint.setOnClickListener { showAdbGrantHint() }
        binding.btnTap.setOnClickListener { performTapByText() }
        binding.btnTapPercent.setOnClickListener { performTapPercent() }
        binding.btnTapPx.setOnClickListener { performTapPx() }
        binding.btnCtrlT.setOnClickListener { performCtrlT() }
        binding.btnClearLogs.setOnClickListener {
            LogBuffer.clear()
            binding.logView.text = ""
        }
        binding.btnAddService.setOnClickListener { showAddServiceDialog() }
        try {
            binding.root.findViewById<View>(R.id.btnServerToggle)?.setOnClickListener { toggleLocalServer() }
        } catch (_: Exception) {}
        setupServicesRecyclerView()
        LogBuffer.addListener(logListener)
        LogBuffer.snapshot().forEach { appendLog(it) }
        LogBuffer.i("CWBridge", "session start version=2.9.4-android ctrl-t-test")
        showCategory("bridge")
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        logcatReader.start(lifecycleScope)
        if (OverlayService.canDrawOverlays(this)) OverlayService.start(this)
    }

    override fun onPause() {
        logcatReader.stop()
        super.onPause()
    }

    override fun onDestroy() {
        AntiDisconnect.stop()
        localServer?.stop()
        invokeEngine.stop()
        executionEngine.stop()
        LogBuffer.removeListener(logListener)
        super.onDestroy()
    }

    private fun setupDrawer() {
        drawerToggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.app_name, R.string.app_name
        )
        drawerToggle.isDrawerIndicatorEnabled = true
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
        binding.navigationView.setNavigationItemSelectedListener { item ->
            onNavigationItemSelected(item)
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
            R.id.nav_server -> showCategory("server")
            else -> showCategory("bridge")
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun showCategory(category: String) {
        binding.categoryBridge.visibility = if (category == "bridge") View.VISIBLE else View.GONE
        binding.categoryPermissions.visibility = if (category == "permissions") View.VISIBLE else View.GONE
        binding.categoryActions.visibility = if (category == "actions") View.VISIBLE else View.GONE
        binding.categoryServices.visibility = if (category == "services") View.VISIBLE else View.GONE
        binding.categoryLogs.visibility = if (category == "logs") View.VISIBLE else View.GONE
        try {
            binding.root.findViewById<View>(R.id.categoryServer)?.visibility =
                if (category == "server") View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    private fun setupServicesRecyclerView() {
        serviceAdapter = ServiceAdapter(
            services = ServiceRepository.getAllServices(),
            onEdit = { showEditServiceDialog(it) },
            onDelete = { deleteService(it) },
            onToggle = { toggleService(it) },
        )
        binding.servicesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.servicesRecyclerView.adapter = serviceAdapter
    }

    private fun refreshServices() {
        serviceAdapter = ServiceAdapter(
            services = ServiceRepository.getAllServices(),
            onEdit = { showEditServiceDialog(it) },
            onDelete = { deleteService(it) },
            onToggle = { toggleService(it) },
        )
        binding.servicesRecyclerView.adapter = serviceAdapter
        executionEngine.stop()
        executionEngine.start()
    }

    private fun showAddServiceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_service_edit, null)
        val nameEdit = view.findViewById<android.widget.EditText>(R.id.editServiceName)
        val descEdit = view.findViewById<android.widget.EditText>(R.id.editServiceDescription)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add New Service")
            .setView(view)
            .setPositiveButton("Create") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Service name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ServiceRepository.addService(
                    Service(name = name, description = descEdit.text.toString().trim(), isEnabled = true)
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditServiceDialog(service: Service) {
        val view = layoutInflater.inflate(R.layout.dialog_service_edit, null)
        val nameEdit = view.findViewById<android.widget.EditText>(R.id.editServiceName)
        val descEdit = view.findViewById<android.widget.EditText>(R.id.editServiceDescription)
        nameEdit.setText(service.name)
        descEdit.setText(service.description)
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Service")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val updated = service.copy(name = name, description = descEdit.text.toString().trim())
                ServiceRepository.updateService(updated)
                executionEngine.updateServiceTriggers(updated)
                refreshServices()
            }
            .setNeutralButton("Actions") { _, _ -> showServiceActionsDialog(service) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showServiceActionsDialog(service: Service) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Manage Actions for ${service.name}")
            .setItems(arrayOf("Add Action", "Reorder Actions", "Add Trigger")) { _, which ->
                when (which) {
                    0 -> showAddActionDialog(service)
                    1 -> showReorderActionsDialog(service)
                    2 -> showAddTriggerDialog(service)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddActionDialog(service: Service) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Action")
            .setItems(arrayOf("Tap", "Get Info", "HTTP Request", "AI Action", "Delay", "Run Service")) { _, which ->
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
        val view = layoutInflater.inflate(R.layout.dialog_action_tap, null)
        val xPercent = view.findViewById<android.widget.EditText>(R.id.editTapXPercent)
        val yPercent = view.findViewById<android.widget.EditText>(R.id.editTapYPercent)
        val xPx = view.findViewById<android.widget.EditText>(R.id.editTapXPx)
        val yPx = view.findViewById<android.widget.EditText>(R.id.editTapYPx)
        val text = view.findViewById<android.widget.EditText>(R.id.editTapText)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Tap Action")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id,
                    Action.TapAction(
                        xPercent = xPercent.text.toString().toFloatOrNull(),
                        yPercent = yPercent.text.toString().toFloatOrNull(),
                        xPx = xPx.text.toString().toIntOrNull(),
                        yPx = yPx.text.toString().toIntOrNull(),
                        text = text.text.toString().ifEmpty { null },
                    ),
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addGetInfoAction(service: Service) {
        val types = InfoType.values().map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Get Info Action")
            .setItems(types) { _, which ->
                ServiceRepository.addActionToService(
                    service.id, Action.GetInfoAction(infoType = InfoType.values()[which])
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addHttpRequestAction(service: Service) {
        val view = layoutInflater.inflate(R.layout.dialog_action_http, null)
        val urlEdit = view.findViewById<android.widget.EditText>(R.id.editHttpUrl)
        val methodSpinner = view.findViewById<android.widget.Spinner>(R.id.spinnerHttpMethod)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add HTTP Request Action")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id,
                    Action.HttpRequestAction(
                        url = urlEdit.text.toString(),
                        method = methodSpinner.selectedItem?.toString() ?: "GET",
                    ),
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAiAction(service: Service) {
        val view = layoutInflater.inflate(R.layout.dialog_action_ai, null)
        val promptEdit = view.findViewById<android.widget.EditText>(R.id.editAiPrompt)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add AI Action")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id, Action.AiAction(prompt = promptEdit.text.toString())
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addDelayAction(service: Service) {
        val view = layoutInflater.inflate(R.layout.dialog_action_delay, null)
        val delayEdit = view.findViewById<android.widget.EditText>(R.id.editDelayMs)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Delay Action")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id,
                    Action.DelayAction(milliseconds = delayEdit.text.toString().toLongOrNull() ?: 1000),
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                ServiceRepository.addActionToService(
                    service.id, Action.RunServiceAction(serviceId = others[which].id)
                )
                refreshServices()
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
                        ServiceRepository.removeActionFromService(service.id, service.actions[currentIndex].id)
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
                executionEngine.updateServiceTriggers(service)
                refreshServices()
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
                    Trigger.TimeTrigger(intervalMs = intervalEdit.text.toString().toLongOrNull() ?: 1000)
                )
                ServiceRepository.updateService(service)
                executionEngine.updateServiceTriggers(service)
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAccessibilityTrigger(service: Service) {
        val view = layoutInflater.inflate(R.layout.dialog_trigger_accessibility, null)
        val patternEdit = view.findViewById<android.widget.EditText>(R.id.editAccTextPattern)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Accessibility Trigger")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                service.triggers.add(
                    Trigger.AccessibilityTrigger(textPattern = patternEdit.text.toString())
                )
                ServiceRepository.updateService(service)
                executionEngine.updateServiceTriggers(service)
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteService(service: Service) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Service")
            .setMessage("Delete ${service.name}?")
            .setPositiveButton("Delete") { _, _ ->
                executionEngine.removeServiceTriggers(service.id)
                ServiceRepository.deleteService(service.id)
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleService(service: Service) {
        ServiceRepository.updateService(service.copy(isEnabled = !service.isEnabled))
        refreshServices()
    }

    private fun toggleBridge() {
        if (bridgeRunning) {
            bridgeRunning = false
            invokeEngine.stop()
            executionEngine.stop()
            AntiDisconnect.stop()
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
            CatWebTracker.reset()
            AntiDisconnect.start(lifecycleScope)
            invokeEngine.start()
            invokeEngine.focusXPct = 50f
            invokeEngine.focusYPct = 50f
            invokeEngine.submitXPx = 730f
            invokeEngine.submitYPx = 1028f
            executionEngine.start()
            LogBuffer.i("CWBridge", "bridge running")
        }
        refreshUi()
    }

    private fun refreshUi() {
        binding.btnStartStop.text = if (bridgeRunning) "Stop bridge" else "Start bridge"
        val a11y = isAccessibilityEnabled()
        binding.a11yState.text = if (a11y) "A11y ON" else "A11y OFF"
        binding.a11yState.setTextColor(ContextCompat.getColor(this, if (a11y) R.color.ok else R.color.warn))
        binding.statusPill.text = if (bridgeRunning) "Bridge ON" else "Bridge OFF"
        binding.statusPill.setTextColor(ContextCompat.getColor(this, if (bridgeRunning) R.color.ok else R.color.warn))
        binding.logcatState.text = if (logcatReader.hasPermission()) "logcat OK" else "logcat needs grant"
        binding.statusDetail.text =
            if (bridgeRunning) "Watching invoke| in logcat" else "Idle — start after enabling Tap"
        when {
            !bridgeRunning -> BridgeStatus.set(OverlayState.IDLE, "Bridge off")
            !(TapService.isConnected() || a11y) -> BridgeStatus.set(OverlayState.ERROR, "Accessibility off")
            !logcatReader.hasPermission() -> BridgeStatus.set(OverlayState.WAITING, "Need READ_LOGS")
            CatWebTracker.ready -> BridgeStatus.set(OverlayState.ACTIVE, "CatWeb ready")
            CatWebTracker.seenBoot -> BridgeStatus.set(
                OverlayState.WAITING,
                CatWebTracker.lastLine.ifBlank { "CatWeb loading\u2026" }.take(48),
            )
            else -> BridgeStatus.set(OverlayState.WAITING, "Waiting for CatWeb\u2026")
        }
        OverlayService.refresh(this)
    }

    private fun ensureOverlayPermission() {
        if (OverlayService.canDrawOverlays(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Floating status overlay")
            .setMessage("Allow draw over other apps for the status dot.")
            .setPositiveButton("Grant") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .show()
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
            .setMessage("adb shell pm grant ${packageName} android.permission.READ_LOGS")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requireService(): TapService? {
        val service = TapService.instance
        if (service == null) Toast.makeText(this, "CWBridge Tap is not connected", Toast.LENGTH_SHORT).show()
        return service
    }

    private fun performTapByText() {
        val query = binding.tapQuery.text?.toString().orEmpty()
        val service = requireService() ?: return
        val ok = service.clickByText(query)
        Toast.makeText(this, if (ok) "Tapped \"$query\"" else "No match", Toast.LENGTH_SHORT).show()
    }

    private fun performTapPercent() {
        val x = binding.tapXPercent.text?.toString()?.toFloatOrNull()
        val y = binding.tapYPercent.text?.toString()?.toFloatOrNull()
        if (x == null || y == null) { Toast.makeText(this, "Enter X% and Y%", Toast.LENGTH_SHORT).show(); return }
        val service = requireService() ?: return
        val ok = service.clickAtPercent(x, y)
        Toast.makeText(this, if (ok) "Tapped $x% $y%" else "Tap failed", Toast.LENGTH_SHORT).show()
    }

    private fun performCtrlT() {
        val service = requireService() ?: return
        val ok = service.pressCtrlT()
        Toast.makeText(
            this,
            if (ok) "Ctrl+T injected" else "Ctrl+T inject failed (OEM may block key injection)",
            Toast.LENGTH_LONG,
        ).show()
        LogBuffer.i("Test", "Ctrl+T ok=$ok")
    }

    private fun performTapPx() {
        val x = binding.tapXPx.text?.toString()?.toFloatOrNull()
        val y = binding.tapYPx.text?.toString()?.toFloatOrNull()
        if (x == null || y == null) { Toast.makeText(this, "Enter Xpx and Ypx", Toast.LENGTH_SHORT).show(); return }
        val service = requireService() ?: return
        val ok = service.clickAt(x, y)
        Toast.makeText(this, if (ok) "Tapped $x,$y" else "Tap failed", Toast.LENGTH_SHORT).show()
    }

    private fun appendLog(line: LogBuffer.Line) {
        binding.logView.append("${line.tag}: ${line.msg}\n")
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

    private fun toggleLocalServer() {
        val srv = localServer
        if (srv != null && srv.isRunning()) {
            srv.stop()
            localServer = null
            try {
                binding.root.findViewById<android.widget.Button>(R.id.btnServerToggle)?.text = "Start server"
                binding.root.findViewById<android.widget.TextView>(R.id.serverState)?.text = "Server off"
            } catch (_: Exception) {}
            LogBuffer.i("Server", "stopped")
            Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show()
        } else {
            val server = LocalHttpServer(8765) { raw -> invokeEngine.onExternalLog(raw) }
            localServer = server
            server.start()
            try {
                binding.root.findViewById<android.widget.Button>(R.id.btnServerToggle)?.text = "Stop server"
                binding.root.findViewById<android.widget.TextView>(R.id.serverState)?.text =
                    "Listening on http://127.0.0.1:8765"
            } catch (_: Exception) {}
            Toast.makeText(this, "Server on :8765", Toast.LENGTH_SHORT).show()
        }
    }
}
