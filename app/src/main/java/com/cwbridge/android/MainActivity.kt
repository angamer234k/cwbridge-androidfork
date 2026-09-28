package com.cwbridge.android

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cwbridge.android.bridge.AntiDisconnect
import com.cwbridge.android.bridge.BridgeControl
import com.cwbridge.android.bridge.BridgeStatus
import com.cwbridge.android.bridge.CatWebTracker
import com.cwbridge.android.bridge.DisconnectOcrWatch
import com.cwbridge.android.bridge.LogBuffer
import com.cwbridge.android.bridge.LogcatReader
import com.cwbridge.android.bridge.OverlayState
import com.cwbridge.android.data.Action
import com.cwbridge.android.data.InfoType
import com.cwbridge.android.data.Service
import com.cwbridge.android.data.ServiceRepository
import com.cwbridge.android.data.Store
import com.cwbridge.android.data.Trigger
import com.cwbridge.android.databinding.ActivityMainBinding
import com.cwbridge.android.engine.ExecutionEngine
import com.cwbridge.android.engine.InvokeEngine
import com.cwbridge.android.engine.ServiceAdapter
import com.cwbridge.android.server.LocalHttpServer
import com.cwbridge.android.server.ServerAuth
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logcatReader: LogcatReader
    private lateinit var invokeEngine: InvokeEngine
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private var bridgeRunning = false
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        logcatReader = LogcatReader(applicationContext) { raw -> invokeEngine.onExternalLog(raw) }
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
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates() }
        setupServerUi()
        maybeShowCompatibilityNotice()
        setupServicesRecyclerView()
        LogBuffer.addListener(logListener)
        LogBuffer.snapshot().forEach { appendLog(it) }
        LogBuffer.i("CWBridge", "session start version=2.10.7-android textman-vars")
        showCategory("bridge")
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        ensureLogcatRunning()
            DisconnectOcrWatch.start(bridgeScope, applicationContext)
            BridgeControl.resetDisconnectFailsafe()
        if (OverlayService.canDrawOverlays(this)) OverlayService.start(this)
        AntiDisconnect.noteActivity()
    }

    override fun onPause() {
        if (!bridgeRunning) {
            DisconnectOcrWatch.stop()
            logcatReader.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (!bridgeRunning) {
            logcatReader.stop()
        }
        AntiDisconnect.stop()
        localServer?.stop()
        invokeEngine.stop()
        executionEngine.stop()
        LogBuffer.removeListener(logListener)
        super.onDestroy()
    }

    private fun ensureLogcatRunning() {
        if (!logcatReader.hasPermission()) {
            LogBuffer.w("Logcat", "READ_LOGS not granted")
            return
        }
        val scope = if (bridgeRunning) bridgeScope else lifecycleScope
        logcatReader.start(scope)
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
        binding.categoryServer.visibility = if (category == "server") View.VISIBLE else View.GONE
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
            .setItems(
                arrayOf(
                    "Tap",
                    "Get Info",
                    "HTTP Request",
                    "AI Action",
                    "Delay",
                    "Run Service",
                    "Ctrl+T",
                    "Press Enter",
                    "Send Text (no Enter)",
                    "Enter Text (type + Enter)",
                    "TextMan (extract to var)",
                    "Set variable",
                ),
            ) { _, which ->
                when (which) {
                    0 -> addTapAction(service)
                    1 -> addGetInfoAction(service)
                    2 -> addHttpRequestAction(service)
                    3 -> addAiAction(service)
                    4 -> addDelayAction(service)
                    5 -> addRunServiceAction(service)
                    6 -> {
                        ServiceRepository.addActionToService(service.id, Action.CtrlTAction())
                        refreshServices()
                    }
                    7 -> {
                        ServiceRepository.addActionToService(service.id, Action.PressEnterAction())
                        refreshServices()
                    }
                    8 -> addSendTextAction(service)
                    9 -> addEnterTextAction(service)
                    10 -> addTextManAction(service)
                    11 -> addSetVarAction(service)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun addSendTextAction(service: Service) {
        val input = android.widget.EditText(this).apply {
            hint = "Text (supports \$var / \${var})"
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Send Text")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id,
                    Action.SendTextAction(text = input.text?.toString().orEmpty()),
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addEnterTextAction(service: Service) {
        val input = android.widget.EditText(this).apply {
            hint = "Type this, then Enter (\$var ok)"
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Enter Text")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id,
                    Action.EnterTextAction(text = input.text?.toString().orEmpty()),
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTextManAction(service: Service) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun field(hint: String, single: Boolean = true) = android.widget.EditText(this).also {
            it.hint = hint
            it.setSingleLine(single)
            layout.addView(it)
        }
        val source = field("Source (\$lastMatch or \$var or text)")
        source.setText("\$lastMatch")
        val mode = field("Mode: full | regex | after | before | replace | trim")
        mode.setText("regex")
        val pattern = field("Pattern / regex")
        val group = field("Regex group (default 1)")
        group.setText("1")
        val replaceWith = field("Replace with (replace mode)")
        val saveTo = field("Save to variable name")
        saveTo.setText("result")
        MaterialAlertDialogBuilder(this)
            .setTitle("TextMan")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id,
                    Action.TextManAction(
                        source = source.text?.toString().orEmpty().ifBlank { "\$lastMatch" },
                        mode = mode.text?.toString().orEmpty().ifBlank { "regex" },
                        pattern = pattern.text?.toString().orEmpty(),
                        group = group.text?.toString()?.toIntOrNull() ?: 1,
                        replaceWith = replaceWith.text?.toString().orEmpty(),
                        saveTo = saveTo.text?.toString().orEmpty().ifBlank { "result" },
                    ),
                )
                refreshServices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSetVarAction(service: Service) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val key = android.widget.EditText(this).apply { hint = "Variable name" }
        val value = android.widget.EditText(this).apply {
            hint = "Value (can use \$other)"
            minLines = 2
        }
        layout.addView(key)
        layout.addView(value)
        MaterialAlertDialogBuilder(this)
            .setTitle("Set variable")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                ServiceRepository.addActionToService(
                    service.id,
                    Action.SetVarAction(
                        key = key.text?.toString().orEmpty(),
                        value = value.text?.toString().orEmpty(),
                    ),
                )
                refreshServices()
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

    
    private fun stopBridgeWithError(message: String) {
        runOnUiThread {
            if (bridgeRunning) {
                // mirror stop branch of toggleBridge
                bridgeRunning = false
                BridgeControl.cancelOpenCatWeb()
                DisconnectOcrWatch.stop()
                try { logcatReader.stop() } catch (_: Throwable) {}
                try { invokeEngine.stop() } catch (_: Throwable) {}
                try { AntiDisconnect.stop() } catch (_: Throwable) {}
                refreshBridgeUi()
            }
            BridgeStatus.set(OverlayState.ERROR, message.take(48))
            MaterialAlertDialogBuilder(this)
                .setTitle("Bridge stopped")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toggleBridge() {
        if (bridgeRunning) {
            bridgeRunning = false
            BridgeControl.cancelOpenCatWeb()
            logcatReader.stop()
            invokeEngine.stop()
            executionEngine.stop()
            AntiDisconnect.stop()
            LogBuffer.i("CWBridge", "bridge stopped")
        } else {
            if (!TapService.isConnected()) {
                val listed = isAccessibilityEnabled()
                LogBuffer.e(
                    "CWBridge",
                    if (listed) "a11y listed ON but TapService not bound (MIUI often kills it)"
                    else "Accessibility service is off",
                )
                if (listed) {
                    showAccessibilityReconnectDialog()
                } else {
                    Toast.makeText(this, "Enable CWBridge Tap first", Toast.LENGTH_SHORT).show()
                    openAccessibilitySettings()
                }
                refreshUi()
                return
            }
            if (!logcatReader.hasPermission()) {
                LogBuffer.e("CWBridge", "refusing start: READ_LOGS not granted")
                Toast.makeText(this, "Grant READ_LOGS via Helper first", Toast.LENGTH_LONG).show()
                showAdbGrantHint()
                refreshUi()
                return
            }
            bridgeRunning = true
            CatWebTracker.reset()
            CatWebTracker.setOnReadyOnce {
                Thread {
                    try {
                        // Small settle delay after "finished"
                        Thread.sleep(800)
                        val msg = BridgeControl.openDomainsOnCwLoad(applicationContext)
                        LogBuffer.i("CWBridge", "domains on CW load: $msg")
                    } catch (t: Throwable) {
                        LogBuffer.e("CWBridge", "domains on CW load: ${t.message}")
                    }
                }.start()
            }
            AntiDisconnect.start(bridgeScope)
            ensureLogcatRunning()
            BridgeControl.scheduleOpenCatWebIfNeeded(applicationContext, 10_000L)
            invokeEngine.start()
            invokeEngine.focusXPct = 50f
            invokeEngine.focusYPct = 50f
            invokeEngine.submitXPx = 730f
            invokeEngine.submitYPx = 1028f
            executionEngine.start()
            LogBuffer.i("CWBridge", "bridge running — logcat stays on while you use Roblox")
        }
        refreshUi()
    }

    private fun refreshUi() {
        binding.btnStartStop.text = if (bridgeRunning) "Stop bridge" else "Start bridge"
        val a11yListed = isAccessibilityEnabled()
        val a11yBound = TapService.isConnected()
        binding.a11yState.text = when {
            a11yBound -> "A11y ON"
            a11yListed -> "A11y listed (not bound)"
            else -> "A11y OFF"
        }
        binding.a11yState.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    a11yBound -> R.color.ok
                    a11yListed -> R.color.warn
                    else -> R.color.warn
                },
            ),
        )
        binding.statusPill.text = if (bridgeRunning) "Bridge ON" else "Bridge OFF"
        binding.statusPill.setTextColor(ContextCompat.getColor(this, if (bridgeRunning) R.color.ok else R.color.warn))
        binding.logcatState.text = if (logcatReader.hasPermission()) "logcat OK" else "logcat needs grant"
        binding.statusDetail.text =
            if (bridgeRunning) "Watching invoke| in logcat" else "Idle — start after enabling Tap"
        binding.versionText.text = "Version ${UpdateChecker(applicationContext).getCurrentVersion()}"
        refreshServerUi()
        when {
            !bridgeRunning -> BridgeStatus.set(OverlayState.IDLE, "Bridge off")
            !TapService.isConnected() -> BridgeStatus.set(
                OverlayState.ERROR,
                if (a11yListed) "A11y not bound — toggle Tap off/on" else "Accessibility off",
            )
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


    /** MIUI/Redmi often leave the service enabled in Settings while the process is dead. */
    private fun showAccessibilityReconnectDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("CWBridge Tap not running")
            .setMessage(
                """
                Settings says Tap is enabled, but the service is not connected.

                This is common on Xiaomi / Redmi / MIUI:
                1. Open Accessibility settings
                2. Turn CWBridge Tap OFF, wait 2s, turn ON
                3. Disable battery restrictions for CWBridge
                4. Force-stop CWBridge, then reopen the app
                """.trimIndent(),
            )
            .setPositiveButton("Open settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Later", null)
            .show()
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

    // ---- first-run compatibility notice -----------------------------------

    /**
     * Shown once, right after install. Lists only the things that may genuinely
     * not work on this device, so it is short and specific rather than a wall of
     * maybes. Everything listed here is hidden or reported cleanly at the point
     * of use regardless.
     */
    private fun maybeShowCompatibilityNotice() {
        if (!FirstRun.consumeCompatibilityNotice(applicationContext)) return

        val notes = buildList {
            if (!BridgeControl.screenshotSupported()) {
                add("• Take screenshot needs Android 11+ — the button is hidden in the web panel on this device.")
            }
            if (!ShizukuShell.isReady()) {
                add("• Ctrl+T, Enter and Restart Roblox need Shizuku running with CWBridge allowed. " +
                    "Without it, Restart Roblox cannot force-stop Roblox.")
            }
            add("• The bridge needs Accessibility (CWBridge Tap) and READ_LOGS to see console output.")
        }

        if (notes.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Some features may not be supported")
            .setMessage(
                notes.joinToString("\n\n") +
                    "\n\nYou can fix most of this under Permissions and Server in the app.",
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    // ---- web server -------------------------------------------------------

    private fun setupServerUi() {
        ServerAuth.init(applicationContext)
        binding.btnServerToggle.setOnClickListener { toggleLocalServer() }
        binding.btnShowPassword.setOnClickListener { showServerPassword() }
        binding.btnRegenPassword.setOnClickListener { regenerateServerPassword() }
        binding.btnServerQuota.setOnClickListener { setDefaultDomainQuota() }
        refreshServerUi()
    }

    private fun refreshServerUi() {
        val running = localServer?.isRunning() == true
        val port = localServer?.port() ?: 8080
        binding.btnServerToggle.text = if (running) "Stop server" else "Start server"
        binding.serverState.text = if (running) {
            "Listening on port $port — open http://<this-device-ip>:$port"
        } else {
            "Server off"
        }
    }

    private fun toggleLocalServer() {
        val existing = localServer
        if (existing != null && existing.isRunning()) {
            existing.stop()
            localServer = null
            LogBuffer.i("Server", "stopped from UI")
            Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show()
            refreshServerUi()
            return
        }

        val server = LocalHttpServer(
            context = applicationContext,
            preferredPort = 8080,
            onInvoke = { raw -> invokeEngine.onExternalLog(raw) },
            onToggleBridge = {
                // toggleBridge() can refuse to start (no a11y / no READ_LOGS),
                // so report the state that actually resulted, not a guess.
                runOnUiThread { toggleBridge() }
                Thread.sleep(150)
                bridgeRunning
            },
        )
        localServer = server
        BridgeControl.setExecutionEngine(executionEngine)
        BridgeControl.setHooks(object : BridgeControl.Hooks {
            override fun restartBridge() {
                runOnUiThread {
                    toggleBridge()
                    LogBuffer.i("Control", "bridge restarted from web UI")
                }
            }
            override fun stopBridgeWithError(message: String) {
                stopBridgeWithError(message)
            }
        })
        server.start()
        Thread {
            repeat(20) {
                Thread.sleep(50)
                if (server.isRunning() && server.port() > 0) {
                    runOnUiThread {
                        Toast.makeText(this, "Server on http://…:${server.port()}", Toast.LENGTH_LONG).show()
                        refreshServerUi()
                    }
                    return@Thread
                }
            }
            runOnUiThread {
                Toast.makeText(this, "Server failed to bind (8080/8765/80)", Toast.LENGTH_LONG).show()
                refreshServerUi()
            }
        }.start()
        refreshServerUi()
    }

    private fun showServerPassword() {
        val pw = ServerAuth.password(applicationContext)
        MaterialAlertDialogBuilder(this)
            .setTitle("Server password")
            .setMessage(
                "Use this to unlock the web panel from outside your local network.\n\n" +
                    "$pw\n\nLocal-network addresses (192.168.x.x etc.) do not need it.",
            )
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("cwbridge", pw))
                Toast.makeText(this, "Password copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun regenerateServerPassword() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Regenerate password?")
            .setMessage("Remote sessions will be signed out.")
            .setPositiveButton("Regenerate") { _, _ ->
                ServerAuth.regeneratePassword(applicationContext)
                Toast.makeText(this, "New password generated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setDefaultDomainQuota() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. 2MB (bits/bytes/KB/MB/GB, 0 = unlimited)"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Default domain limit")
            .setMessage("Applies to domains that have no custom limit.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val bytes = Store.parseSize(input.text.toString())
                if (bytes == null) {
                    Toast.makeText(this, "Could not parse size", Toast.LENGTH_LONG).show()
                } else {
                    Store(applicationContext).setLimit(Store.DEFAULT_DOMAIN, bytes)
                    Toast.makeText(this, "Default limit saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- updates ----------------------------------------------------------

    private fun checkForUpdates() {
        val button = binding.btnCheckUpdate
        button.isEnabled = false
        button.text = "Checking…"
        val checker = UpdateChecker(applicationContext)
        lifecycleScope.launch {
            val result = checker.check()
            when (result) {
                is UpdateChecker.Result.Available -> {
                    val asset = checker.findApkAsset(result.release)
                    if (asset == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "${result.latestVersion} is out, but the release has no APK asset",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        checker.showUpdateDialog(result.release, asset)
                    }
                }
                is UpdateChecker.Result.UpToDate -> Toast.makeText(
                    this@MainActivity,
                    "You're up to date (${checker.getCurrentVersion()})",
                    Toast.LENGTH_LONG,
                ).show()
                is UpdateChecker.Result.Failed -> Toast.makeText(
                    this@MainActivity,
                    "Update check failed: ${result.reason}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            button.isEnabled = true
            button.text = "Check for updates"
        }
    }
}
