package com.cwbridge.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Executes Action blocks — keys via TapService, network on IO, rest via InvokeEngine. */
class ExecutionEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val invokeEngine: InvokeEngine,
    private val logcatReader: LogcatReader
) {

    private val okHttpClient = OkHttpClient()
    private var timeTriggerJobs: MutableMap<String, Job> = mutableMapOf()
    private var logTriggerJobs: MutableMap<String, Job> = mutableMapOf()
    private var running = false

    fun start() {
        if (running) return
        running = true
        startAllTriggers()
        LogBuffer.i("ExecutionEngine", "started")
    }

    fun stop() {
        if (!running) return
        running = false
        stopAllTriggers()
        LogBuffer.i("ExecutionEngine", "stopped")
    }

    fun executeService(service: Service) {
        if (!service.isEnabled) {
            LogBuffer.i("ExecutionEngine", "Service ${service.name} is disabled, skipping")
            return
        }
        scope.launch(Dispatchers.IO) { executeActions(service.actions) }
    }

    fun executeAction(action: Action) {
        scope.launch(Dispatchers.IO) { executeActionInternal(action) }
    }

    private suspend fun executeActions(actions: List<Action>) {
        for (action in actions) {
            if (!running) break
            executeActionInternal(action)
            delay(50)
        }
    }

    private suspend fun executeActionInternal(action: Action) {
        when (action) {
            is Action.CtrlTAction -> {
                val tap = TapService.instance
                if (tap == null) LogBuffer.w("ExecutionEngine", "Ctrl+T: TapService not connected")
                else LogBuffer.i("ExecutionEngine", "Ctrl+T ok=${tap.pressCtrlT()}")
            }
            is Action.PressEnterAction -> {
                val tap = TapService.instance
                if (tap == null) LogBuffer.w("ExecutionEngine", "Enter: TapService not connected")
                else LogBuffer.i("ExecutionEngine", "Enter ok=${tap.pressEnter()}")
            }
            is Action.SendTextAction -> {
                val tap = TapService.instance
                if (tap == null) LogBuffer.w("ExecutionEngine", "SendText: TapService not connected")
                else LogBuffer.i("ExecutionEngine", "SendText len=${action.text.length} ok=${tap.sendText(action.text)}")
            }
            is Action.HttpRequestAction -> executeHttpRequest(action)
            is Action.RunServiceAction -> {
                val target = ServiceRepository.getServiceById(action.serviceId)
                if (target != null) executeActions(target.actions)
            }
            else -> {
                val cmd = actionToInvokeCommand(action)
                if (cmd.isNotEmpty()) {
                    LogBuffer.i("ExecutionEngine", "Executing: $cmd")
                    invokeEngine.onExternalLog("Info · invoke|$cmd")
                }
            }
        }
    }

    private fun actionToInvokeCommand(action: Action): String = when (action) {
        is Action.TapAction -> when {
            action.xPercent != null && action.yPercent != null -> "tap.${action.xPercent}.${action.yPercent}"
            action.xPx != null && action.yPx != null -> "tappx.${action.xPx}.${action.yPx}"
            !action.text.isNullOrEmpty() -> "paste.${action.text}"
            else -> ""
        }
        is Action.GetInfoAction -> when (action.infoType) {
            InfoType.TIMESTAMP -> "echo.${System.currentTimeMillis()}"
            InfoType.CLIPBOARD -> "clip.get"
            else -> "status"
        }
        is Action.AiAction -> "ai.${action.prompt}"
        is Action.DelayAction -> "wait.${action.milliseconds}"
        else -> ""
    }

    private fun executeHttpRequest(action: Action.HttpRequestAction) {
        scope.launch(Dispatchers.IO) {
            try {
                val b = Request.Builder().url(action.url).method(action.method, action.body.toRequestBody(null))
                action.headers.forEach { (k, v) -> b.addHeader(k, v) }
                val resp = okHttpClient.newCall(b.build()).execute()
                if (!resp.isSuccessful) LogBuffer.e("ExecutionEngine", "HTTP ${resp.code}: ${resp.message}")
                else LogBuffer.i("ExecutionEngine", "HTTP ${resp.code} ${action.url}")
            } catch (e: Exception) {
                LogBuffer.e("ExecutionEngine", "HTTP error: ${e.message}")
            }
        }
    }

    private fun startAllTriggers() {
        ServiceRepository.getAllServices().filter { it.isEnabled }.forEach { startServiceTriggers(it) }
    }

    private fun startServiceTriggers(service: Service) {
        service.triggers.forEach { startTrigger(service, it) }
    }

    private fun startTrigger(service: Service, trigger: Trigger) {
        when (trigger) {
            is Trigger.LogTrigger -> startLogTrigger(service, trigger)
            is Trigger.TimeTrigger -> startTimeTrigger(service, trigger)
            is Trigger.AccessibilityTrigger ->
                LogBuffer.i("ExecutionEngine", "Accessibility trigger registered: ${trigger.textPattern}")
        }
    }

    private fun startLogTrigger(service: Service, trigger: Trigger.LogTrigger) {
        val jobId = "${service.id}-${trigger.id}"
        logTriggerJobs[jobId]?.cancel()
        logTriggerJobs[jobId] = scope.launch(Dispatchers.IO) {
            var last = 0L
            while (isActive) {
                for (line in LogBuffer.snapshot()) {
                    val matches = if (trigger.useRegex)
                        line.msg.contains(Regex(trigger.pattern))
                    else line.msg.contains(trigger.pattern, ignoreCase = !trigger.matchCase)
                    if (matches) {
                        val now = System.currentTimeMillis()
                        if (now - last > 1000) {
                            last = now
                            LogBuffer.i("ExecutionEngine", "Log trigger: ${trigger.pattern}")
                            executeService(service)
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun startTimeTrigger(service: Service, trigger: Trigger.TimeTrigger) {
        val jobId = "${service.id}-${trigger.id}"
        timeTriggerJobs[jobId]?.cancel()
        timeTriggerJobs[jobId] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(trigger.intervalMs)
                LogBuffer.i("ExecutionEngine", "Time trigger: ${trigger.intervalMs}ms")
                executeService(service)
            }
        }
    }

    private fun stopAllTriggers() {
        timeTriggerJobs.values.forEach { it.cancel() }; timeTriggerJobs.clear()
        logTriggerJobs.values.forEach { it.cancel() }; logTriggerJobs.clear()
    }

    fun updateServiceTriggers(service: Service) {
        stopServiceTriggers(service)
        if (service.isEnabled) startServiceTriggers(service)
    }

    private fun stopServiceTriggers(service: Service) {
        timeTriggerJobs.filterKeys { it.startsWith("${service.id}-") }.forEach { (k, j) -> j.cancel(); timeTriggerJobs.remove(k) }
        logTriggerJobs.filterKeys { it.startsWith("${service.id}-") }.forEach { (k, j) -> j.cancel(); logTriggerJobs.remove(k) }
    }

    fun removeServiceTriggers(serviceId: String) {
        timeTriggerJobs.filterKeys { it.startsWith("$serviceId-") }.forEach { (k, j) -> j.cancel(); timeTriggerJobs.remove(k) }
        logTriggerJobs.filterKeys { it.startsWith("$serviceId-") }.forEach { (k, j) -> j.cancel(); logTriggerJobs.remove(k) }
    }
}
