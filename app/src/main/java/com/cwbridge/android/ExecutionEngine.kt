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

/**
 * Executes Action blocks by translating them to invoke| commands
 * and passing them to the existing InvokeEngine parser.
 * 
 * Runs all network/IO operations on Dispatchers.IO to prevent UI freezing.
 */
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

    /**
     * Start the execution engine and all active triggers
     */
    fun start() {
        if (running) return
        running = true
        startAllTriggers()
        LogBuffer.i("ExecutionEngine", "started")
    }

    /**
     * Stop the execution engine and all triggers
     */
    fun stop() {
        if (!running) return
        running = false
        stopAllTriggers()
        LogBuffer.i("ExecutionEngine", "stopped")
    }

    /**
     * Execute all actions for a service
     */
    fun executeService(service: Service) {
        if (!service.isEnabled) {
            LogBuffer.i("ExecutionEngine", "Service ${service.name} is disabled, skipping")
            return
        }
        scope.launch(Dispatchers.IO) {
            executeActions(service.actions)
        }
    }

    /**
     * Execute a single action
     */
    fun executeAction(action: Action) {
        scope.launch(Dispatchers.IO) {
            executeActionInternal(action)
        }
    }

    /**
     * Execute a list of actions sequentially
     */
    private suspend fun executeActions(actions: List<Action>) {
        for (action in actions) {
            if (!running) break
            executeActionInternal(action)
            // Small delay between actions to prevent overwhelming the system
            delay(50)
        }
    }

    /**
     * Internal execution of a single action with translation to invoke| commands
     */
    private suspend fun executeActionInternal(action: Action) {
        val invokeCommand = actionToInvokeCommand(action)
        if (invokeCommand.isNotEmpty()) {
            LogBuffer.i("ExecutionEngine", "Executing: $invokeCommand")
            // Feed to InvokeEngine via its onExternalLog method
            // The InvokeEngine will parse and execute the command
            invokeEngine.onExternalLog("Info \u00b7 invoke|$invokeCommand")
        }
    }

    /**
     * Translate an Action to an invoke| command string
     */
    private fun actionToInvokeCommand(action: Action): String {
        return when (action) {
            is Action.TapAction -> {
                when {
                    action.xPercent != null && action.yPercent != null ->
                        "tap.${action.xPercent}.${action.yPercent}"
                    action.xPx != null && action.yPx != null ->
                        "tappx.${action.xPx}.${action.yPx}"
                    !action.text.isNullOrEmpty() ->
                        "paste.${action.text}"
                    else -> ""
                }
            }
            is Action.GetInfoAction -> {
                when (action.infoType) {
                    InfoType.SCREEN_TEXT -> "status"
                    InfoType.NODE_TEXT -> "status"
                    InfoType.NODE_BOUNDS -> "status"
                    InfoType.CURRENT_APP -> "status"
                    InfoType.TIMESTAMP -> "echo.${System.currentTimeMillis()}"
                    InfoType.CLIPBOARD -> "clip.get"
                }
            }
            is Action.HttpRequestAction -> {
                // Execute HTTP request directly, then we can optionally emit results
                executeHttpRequest(action)
                // Return empty as we handle the action directly
                ""
            }
            is Action.AiAction -> {
                "ai.${action.prompt}"
            }
            is Action.DelayAction -> {
                "wait.${action.milliseconds}"
            }
            is Action.RunServiceAction -> {
                // Find and execute the target service
                val targetService = ServiceRepository.getServiceById(action.serviceId)
                if (targetService != null) {
                    scope.launch(Dispatchers.IO) {
                        executeActions(targetService.actions)
                    }
                }
                ""
            }
        }
    }

    /**
     * Execute HTTP request on IO dispatcher
     */
    private fun executeHttpRequest(action: Action.HttpRequestAction) {
        scope.launch(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder()
                    .url(action.url)
                    .method(action.method, action.body.toRequestBody(null))
                
                action.headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }
                
                val request = requestBuilder.build()
                val response = okHttpClient.newCall(request).execute()
                
                val responseBody = response.body?.string() ?: ""
                if (action.storeResponseIn.isNotEmpty()) {
                    // Store response for later use
                    LogBuffer.i("ExecutionEngine", "HTTP ${response.code} for ${action.url}")
                }
                
                if (!response.isSuccessful) {
                    LogBuffer.e("ExecutionEngine", "HTTP error ${response.code}: ${response.message}")
                }
            } catch (e: IOException) {
                LogBuffer.e("ExecutionEngine", "HTTP request failed: ${e.message}")
            } catch (e: Exception) {
                LogBuffer.e("ExecutionEngine", "HTTP error: ${e.message}")
            }
        }
    }

    /**
     * Start all triggers for all enabled services
     */
    private fun startAllTriggers() {
        val services = ServiceRepository.getAllServices()
        for (service in services) {
            if (!service.isEnabled) continue
            startServiceTriggers(service)
        }
    }

    /**
     * Start triggers for a specific service
     */
    private fun startServiceTriggers(service: Service) {
        for (trigger in service.triggers) {
            startTrigger(service, trigger)
        }
    }

    /**
     * Start a specific trigger
     */
    private fun startTrigger(service: Service, trigger: Trigger) {
        when (trigger) {
            is Trigger.LogTrigger -> {
                startLogTrigger(service, trigger)
            }
            is Trigger.TimeTrigger -> {
                startTimeTrigger(service, trigger)
            }
            is Trigger.AccessibilityTrigger -> {
                startAccessibilityTrigger(service, trigger)
            }
        }
    }

    /**
     * Start a log trigger that monitors logcat for pattern matches
     */
    private fun startLogTrigger(service: Service, trigger: Trigger.LogTrigger) {
        val jobId = "${service.id}-${trigger.id}"
        // Stop any existing job for this trigger
        logTriggerJobs[jobId]?.cancel()
        
        val job = scope.launch(Dispatchers.IO) {
            var lastMatchTime = 0L
            while (isActive) {
                // Check logcat via LogBuffer for the pattern
                val logs = LogBuffer.snapshot()
                for (line in logs) {
                    val matches = if (trigger.useRegex) {
                        line.msg.contains(Regex(trigger.pattern, if (trigger.matchCase) setOf(RegexOption.IGNORE_CASE) else emptySet()))
                    } else {
                        line.msg.contains(trigger.pattern, ignoreCase = !trigger.matchCase)
                    }
                    
                    if (matches) {
                        val currentTime = System.currentTimeMillis()
                        // Debounce: only trigger once per second for the same pattern
                        if (currentTime - lastMatchTime > 1000) {
                            lastMatchTime = currentTime
                            LogBuffer.i("ExecutionEngine", "Log trigger fired: ${trigger.pattern}")
                            executeService(service)
                        }
                    }
                }
                delay(500) // Check every 500ms
            }
        }
        logTriggerJobs[jobId] = job
    }

    /**
     * Start a time trigger that fires at intervals
     */
    private fun startTimeTrigger(service: Service, trigger: Trigger.TimeTrigger) {
        val jobId = "${service.id}-${trigger.id}"
        // Stop any existing job for this trigger
        timeTriggerJobs[jobId]?.cancel()
        
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(trigger.intervalMs)
                if (trigger.repeat || isActive) {
                    LogBuffer.i("ExecutionEngine", "Time trigger fired: ${trigger.intervalMs}ms")
                    executeService(service)
                }
            }
        }
        timeTriggerJobs[jobId] = job
    }

    /**
     * Start an accessibility trigger (stub - actual implementation would integrate with TapService)
     */
    private fun startAccessibilityTrigger(service: Service, trigger: Trigger.AccessibilityTrigger) {
        val jobId = "${service.id}-${trigger.id}"
        // Stop any existing job for this trigger
        timeTriggerJobs[jobId]?.cancel()
        
        // For now, just log - actual implementation would require
        // integration with accessibility event callbacks in TapService
        LogBuffer.i("ExecutionEngine", "Accessibility trigger registered: ${trigger.textPattern}")
    }

    /**
     * Stop all active triggers
     */
    private fun stopAllTriggers() {
        timeTriggerJobs.values.forEach { it.cancel() }
        timeTriggerJobs.clear()
        logTriggerJobs.values.forEach { it.cancel() }
        logTriggerJobs.clear()
    }

    /**
     * Update triggers for a service (e.g., when service is edited)
     */
    fun updateServiceTriggers(service: Service) {
        stopServiceTriggers(service)
        if (service.isEnabled) {
            startServiceTriggers(service)
        }
    }

    /**
     * Stop all triggers for a specific service
     */
    private fun stopServiceTriggers(service: Service) {
        // Stop time triggers for this service
        timeTriggerJobs.filterKeys { it.startsWith("${service.id}-") }
            .forEach { (key, job) ->
                job.cancel()
                timeTriggerJobs.remove(key)
            }
        // Stop log triggers for this service
        logTriggerJobs.filterKeys { it.startsWith("${service.id}-") }
            .forEach { (key, job) ->
                job.cancel()
                logTriggerJobs.remove(key)
            }
    }

    /**
     * Remove triggers for a deleted service
     */
    fun removeServiceTriggers(serviceId: String) {
        timeTriggerJobs.filterKeys { it.startsWith("$serviceId-") }
            .forEach { (key, job) ->
                job.cancel()
                timeTriggerJobs.remove(key)
            }
        logTriggerJobs.filterKeys { it.startsWith("$serviceId-") }
            .forEach { (key, job) ->
                job.cancel()
                logTriggerJobs.remove(key)
            }
    }
}
