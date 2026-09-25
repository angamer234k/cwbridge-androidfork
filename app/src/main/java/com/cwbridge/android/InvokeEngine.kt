package com.cwbridge.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches log lines for `invoke|request[.data1[.data2]]` and dispatches handlers.
 *
 * Same wire format as the MacroDroid Server macro:
 *   Info … invoke|save.mykey.hello
 *   Info … invoke|load.mykey.weather.rbx
 *   Info … invoke|status
 *   Info … invoke|tap.50.85
 *   Info … invoke|paste.hello world
 */
class InvokeEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val store = Store(context)
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /** Default focus for paste sequence (percent). */
    @Volatile var focusXPct = 50f
    @Volatile var focusYPct = 50f

    /** Default keyboard-submit tap (pixels). 0 = skip submit. */
    @Volatile var submitXPx = 0f
    @Volatile var submitYPx = 0f

    private val listener: (LogBuffer.Line) -> Unit = { line ->
        if (running.get()) handleLine(line.msg)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        LogBuffer.addListener(listener)
        LogBuffer.i("Invoke", "engine on — watching for invoke|")
        LogBuffer.i(
            "Invoke",
            "cmds: save load status tap tappx paste clip focus submit wait toast echo help",
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        LogBuffer.removeListener(listener)
        job?.cancel()
        job = null
        LogBuffer.i("Invoke", "engine off")
    }

    fun isRunning(): Boolean = running.get()

    /** Also feed raw system logcat lines (may include Roblox FLog). */
    fun onExternalLog(raw: String) {
        if (!running.get()) return
        if (raw.contains("invoke|", ignoreCase = false)) {
            handleLine(raw)
        }
    }

    private fun handleLine(raw: String) {
        val idx = raw.indexOf("invoke|")
        if (idx < 0) return
        val payload = raw.substring(idx + "invoke|".length).trim()
        if (payload.isEmpty()) return

        // Avoid re-entrancy on our own reply lines
        if (raw.contains("cwbridge|")) return

        val parts = payload.split(".", limit = 3)
        val request = parts[0].trim().lowercase()
        val data1 = parts.getOrNull(1)?.trim().orEmpty()
        val data2 = parts.getOrNull(2)?.trim().orEmpty()

        LogBuffer.i("Invoke", "→ $request data1=${data1.take(80)} data2=${data2.take(80)}")

        job = scope.launch(Dispatchers.Main) {
            try {
                dispatch(request, data1, data2)
            } catch (t: Throwable) {
                replyErr(request, t.message ?: t::class.java.simpleName)
            }
        }
    }

    private suspend fun dispatch(request: String, data1: String, data2: String) {
        when (request) {
            "save" -> {
                // save.key.data  OR  save.key.data with domain in key as domain/key
                // Wire: save.<key>.<data>  → default domain local.rbx
                //       save.<domain>.<key> not used — user asked save.key.data
                if (data1.isEmpty()) {
                    replyErr("save", "need save.key.data")
                    return
                }
                val result = store.saveDefault(data1, data2)
                result.fold(
                    onSuccess = { replyOk("save", "$data1 stored (${data2.length} chars)") },
                    onFailure = { replyErr("save", it.message ?: "fail") },
                )
            }

            "load" -> {
                // load.key.domain — domain MUST be name.rbx
                if (data1.isEmpty() || data2.isEmpty()) {
                    replyErr("load", "need load.key.domain (domain like weather.rbx)")
                    return
                }
                val result = store.load(data2, data1)
                result.fold(
                    onSuccess = { value ->
                        setClipboard(value)
                        replyOk("load", value)
                    },
                    onFailure = { replyErr("load", it.message ?: "fail") },
                )
            }

            "savedomain" -> {
                // savedomain.domain.key — data2 is key, need third part… use data2 as key=value blob
                // savedomain.<domain>.<key=value>  — simpler: savedomain.weather.rbx key via save after set domain
                // Support: savedomain.<domain>.<key> with empty value clear? Skip — use store with explicit domain:
                // save2.domain.key not in 2-data limit.
                // Alternate: save.<key>.<data> and load.<key>.<domain> as user specified.
                replyErr("savedomain", "use save.key.data + load.key.domain")
            }

            "status" -> {
                val snap = DeviceStatus.read(context)
                replyOk("status", snap.toPayload())
            }

            "tap" -> {
                val x = data1.toFloatOrNull()
                val y = data2.toFloatOrNull()
                if (x == null || y == null) {
                    replyErr("tap", "need tap.xPct.yPct")
                    return
                }
                val svc = TapService.instance
                if (svc == null) {
                    replyErr("tap", "TapService offline")
                    return
                }
                val ok = svc.clickAtPercent(x, y)
                if (ok) replyOk("tap", "$x $y") else replyErr("tap", "gesture failed")
            }

            "tappx" -> {
                val x = data1.toFloatOrNull()
                val y = data2.toFloatOrNull()
                if (x == null || y == null) {
                    replyErr("tappx", "need tappx.x.y")
                    return
                }
                val svc = TapService.instance
                if (svc == null) {
                    replyErr("tappx", "TapService offline")
                    return
                }
                val ok = svc.clickAt(x, y)
                if (ok) replyOk("tappx", "$x $y") else replyErr("tappx", "gesture failed")
            }

            "focus" -> {
                val x = data1.toFloatOrNull()
                val y = data2.toFloatOrNull()
                if (x == null || y == null) {
                    replyErr("focus", "need focus.xPct.yPct")
                    return
                }
                focusXPct = x
                focusYPct = y
                replyOk("focus", "set $x $y")
            }

            "submit" -> {
                val x = data1.toFloatOrNull()
                val y = data2.toFloatOrNull()
                if (x == null || y == null) {
                    replyErr("submit", "need submit.xPx.yPx")
                    return
                }
                submitXPx = x
                submitYPx = y
                replyOk("submit", "set $x $y")
            }

            "paste" -> {
                // paste.<text>  (data1 + optional .data2 rejoined)
                val text = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (text.isEmpty()) {
                    replyErr("paste", "need paste.text")
                    return
                }
                runPasteSequence(text)
            }

            "clip" -> {
                when (data1.lowercase()) {
                    "set" -> {
                        if (data2.isEmpty()) {
                            replyErr("clip", "need clip.set.text")
                            return
                        }
                        setClipboard(data2)
                        replyOk("clip", "set ${data2.length} chars")
                    }
                    "get" -> {
                        val t = getClipboard()
                        replyOk("clip", t ?: "")
                    }
                    else -> replyErr("clip", "clip.set.text or clip.get")
                }
            }

            "wait" -> {
                val ms = data1.toLongOrNull() ?: data2.toLongOrNull() ?: 1000L
                delay(ms.coerceIn(0L, 30_000L))
                replyOk("wait", "${ms}ms")
            }

            "toast" -> {
                val msg = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                android.widget.Toast.makeText(context, msg.ifEmpty { "cwbridge" }, android.widget.Toast.LENGTH_SHORT).show()
                replyOk("toast", msg.take(40))
            }

            "echo" -> {
                val msg = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                replyOk("echo", msg)
            }

            "help" -> {
                replyOk(
                    "help",
                    "save.key.data | load.key.domain | status | tap.x.y | tappx.x.y | " +
                        "paste.text | clip.set.text | clip.get | focus.x.y | submit.x.y | " +
                        "wait.ms | toast.msg | echo.msg | help",
                )
            }

            "ai" -> {
                // Prompt is data1 + data2 (joined). No cloud key in-app yet — stash to clipboard
                // and run paste sequence so game can receive / user can wire genai later.
                val prompt = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (prompt.isEmpty()) {
                    replyErr("ai", "need ai.prompt")
                    return
                }
                LogBuffer.w("Invoke", "ai: no in-app LLM yet — clipboard+paste prompt as passthrough")
                runPasteSequence(prompt)
                replyOk("ai", "passthrough ${prompt.length} chars (add genai key later)")
            }

            else -> replyErr(request, "unknown request — invoke|help")
        }
    }

    private suspend fun runPasteSequence(text: String) {
        val svc = TapService.instance
        if (svc == null) {
            replyErr("paste", "TapService offline")
            return
        }
        setClipboard(text)
        svc.clickAtPercent(focusXPct, focusYPct)
        delay(1000)
        svc.pasteClipboard()
        delay(1000)
        if (submitXPx > 0f || submitYPx > 0f) {
            svc.clickAt(submitXPx, submitYPx)
        }
        replyOk("paste", "done ${text.length} chars focus=$focusXPct,$focusYPct submit=$submitXPx,$submitYPx")
    }

    private fun setClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cwbridge", text))
    }

    private fun getClipboard(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount < 1) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    private fun replyOk(request: String, payload: String) {
        // Tagged so Roblox/MacroDroid can filter; also human-readable in the in-app log.
        LogBuffer.i("Invoke", "cwbridge|ok|$request|${payload.take(500)}")
    }

    private fun replyErr(request: String, payload: String) {
        LogBuffer.e("Invoke", "cwbridge|err|$request|${payload.take(500)}")
    }
}
