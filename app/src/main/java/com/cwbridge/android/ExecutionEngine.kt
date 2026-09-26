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

/** Executes Action blocks — keys via TapService/Shizuku, TextMan → vars, rest via InvokeEngine. */
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
                val ok = when {
                    ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                    TapService.instance != null -> TapService.instance!!.pressCtrlT()
                    else -> false
                }
                LogBuffer.i("ExecutionEngine", "Ctrl+T ok=$ok")
            }
            is Action.PressEnterAction -> {
                val ok = when {
                    ShizukuShell.isReady() -> ShizukuShell.pressEnter()
                    TapService.instance != null -> TapService.instance!!.pressEnter()
                    else -> false
                }
                LogBuffer.i("ExecutionEngine", "Enter ok=$ok")
            }
            is Action.SendTextAction -> {
                val body = VarStore.expand(action.text)
                val tap = TapService.instance
                val ok = when {
                    tap != null -> tap.sendText(body)
                    ShizukuShell.isReady() -> ShizukuShell.inputText(body)
                    else -> false
                }
                LogBuffer.i("ExecutionEngine", "SendText len=${body.length} ok=$ok")
            }
            is Action.EnterTextAction -> {
                val body = VarStore.expand(action.text)
                LogBuffer.i("ExecutionEngine", "EnterText len=${body.length}")
                val tap = TapService.instance
                var okPaste = false
                var okEnter = false
                if (tap != null) {
                    okPaste = tap.sendText(body)
                    delay(120)
                    okEnter = tap.pressEnter()
                } else if (ShizukuShell.isReady()) {
                    okPaste = ShizukuShell.inputText(body)
                    delay(120)
                    okEnter = ShizukuShell.pressEnter()
                } else {
                    LogBuffer.w("ExecutionEngine", "EnterText: need TapService or Shizuku")
                }
                LogBuffer.i("ExecutionEngine", "EnterText paste=$okPaste enter=$okEnter")
            }
            is Action.TextManAction -> runTextMan(action)
            is Action.SetVarAction -> {
                val key = VarStore.expand(action.key).ifBlank { action.key }
                val value = VarStore.expand(action.value)
                VarStore.set(key, value)
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

    private fun runTextMan(action: Action.TextManAction) {
        val srcRaw = action.source.trim().ifEmpty { "\$lastMatch" }
        val sourceText = when {
            srcRaw.equals("\$lastMatch", ignoreCase = true) ||
                srcRaw.equals("lastMatch", ignoreCase = true) ->
                VarStore.getOrEmpty("lastMatch")
            srcRaw.startsWith("$") -> VarStore.expand(srcRaw)
            else -> VarStore.expand(srcRaw)
        }
        val mode = action.mode.lowercase().trim()
        val result = try {
            when (mode) {
                "full", "copy", "" -> sourceText
                "trim" -> sourceText.trim()
                "after" -> {
                    val p = action.pattern
                    val i = sourceText.indexOf(p)
                    if (i >= 0) sourceText.substring(i + p.length) else ""
                }
                "before" -> {
                    val p = action.pattern
                    val i = sourceText.indexOf(p)
                    if (i >= 0) sourceText.substring(0, i) else sourceText
                }
                "replace" -> sourceText.replace(action.pattern, action.replaceWith)
                "regex" -> {
                    val re = Regex(action.pattern)
                    val m = re.find(sourceText)
                    if (m == null) ""
                    else {
                        val g = action.group
                        if (g >= 0 && g <= m.groupValues.lastIndex) m.groupValues[g]
                        else m.value
                    }
                }
                else -> sourceText
            }
        } catch (t: Throwable) {
            LogBuffer.w("ExecutionEngine", "TextMan error: ${t.message}")
            ""
        }
        val dest = action.saveTo.ifBlank { "result" }
        VarStore.set(dest, result)
        LogBuffer.i("ExecutionEngine", "TextMan mode=$mode → \$$dest (${result.length} chars)")
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
            var afterMs = System.currentTimeMillis()
            while (isActive) {
                val batch = RecentLogLines.since(afterMs)
                for (line in batch) {
                    if (line.atMs > afterMs) afterMs = line.atMs
                    val matches = try {
                        if (trigger.useRegex) line.text.contains(Regex(trigger.pattern))
                        else line.text.contains(trigger.pattern, ignoreCase = !trigger.matchCase)
                    } catch (_: Throwable) {
                        false
                    }
                    if (matches) {
                        VarStore.set("lastMatch", line.text)
                        LogBuffer.i("ExecutionEngine", "Log trigger '${trigger.pattern}' → ${service.name}")
                        executeService(service)
                    }
                }
                delay(250)
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
