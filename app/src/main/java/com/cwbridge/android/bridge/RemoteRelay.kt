package com.cwbridge.android.bridge

import android.content.Context
import android.util.Base64
import com.cwbridge.android.TapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud pair-code relay client (Vercel + Upstash).
 * Phone creates a 6-digit code, browser claims it, phone polls for commands.
 */
object RemoteRelay {
    const val DEFAULT_BASE = "https://cw-control.vercel.app"

    @Volatile var baseUrl: String = DEFAULT_BASE
        private set
    @Volatile var code: String? = null
        private set
    @Volatile var status: String = "off"
        private set
    @Volatile private var deviceToken: String? = null
    @Volatile private var sessionId: String? = null

    private var job: Job? = null
    private var onUpdate: ((String) -> Unit)? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun setBaseUrl(url: String) {
        val u = url.trim().trimEnd('/')
        if (u.isNotEmpty()) baseUrl = u
    }

    fun start(scope: CoroutineScope, context: Context, onUi: (String) -> Unit = {}) {
        stop()
        onUpdate = onUi
        status = "starting"
        code = null
        deviceToken = null
        sessionId = null
        notify("Starting remote…")
        job = scope.launch(Dispatchers.IO) {
            try {
                val created = postJson(
                    "/api/pair-create",
                    JSONObject().put("deviceName", android.os.Build.MODEL ?: "CWBridge"),
                )
                val c = created.optString("code", "")
                val tok = created.optString("deviceToken", "")
                if (c.length != 6 || tok.isBlank()) {
                    status = "error"
                    notify("pair-create failed: ${created.optString("error", created.toString())}")
                    return@launch
                }
                code = c
                deviceToken = tok
                status = "waiting"
                notify("Code $c — open $baseUrl and enter it")
                LogBuffer.i("Relay", "pair code=$c storage=${created.optString("storage")}")

                while (isActive) {
                    val st = getJson("/api/device-status?deviceToken=${urlEnc(tok)}")
                    val s = st.optString("status", "waiting")
                    if (s == "paired") {
                        sessionId = st.optString("sessionId", null)
                        status = "paired"
                        notify("Paired — polling commands")
                        LogBuffer.i("Relay", "paired session=$sessionId")
                        break
                    }
                    status = "waiting"
                    delay(1500)
                }

                while (isActive) {
                    try {
                        val poll = getJson("/api/device-poll?deviceToken=${urlEnc(tok)}")
                        val cmds = poll.optJSONArray("commands") ?: JSONArray()
                        for (i in 0 until cmds.length()) {
                            val cmd = cmds.getJSONObject(i)
                            handleCommand(context, tok, cmd)
                        }
                    } catch (t: Throwable) {
                        LogBuffer.w("Relay", "poll: ${t.message}")
                    }
                    delay(1200)
                }
            } catch (t: Throwable) {
                status = "error"
                notify("Remote error: ${t.message}")
                LogBuffer.e("Relay", "start: ${t.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        status = "off"
        code = null
        deviceToken = null
        sessionId = null
        notify("Remote off")
    }

    private fun notify(msg: String) {
        try {
            onUpdate?.invoke(msg)
        } catch (_: Throwable) {
        }
    }

    private suspend fun handleCommand(context: Context, token: String, cmd: JSONObject) {
        val id = cmd.optString("id", "")
        val type = cmd.optString("type", "").lowercase()
        val payload = cmd.optJSONObject("payload") ?: JSONObject()
        LogBuffer.i("Relay", "cmd $type id=$id")
        var ok = false
        var error: String? = null
        var data = JSONObject()
        try {
            when (type) {
                "screenshot" -> {
                    val shot = BridgeControl.takeScreenshot(context.applicationContext)
                    if (shot.isSuccess) {
                        val bytes = shot.getOrThrow()
                        data.put("pngBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        data.put("bytes", bytes.size)
                        ok = true
                    } else {
                        error = shot.exceptionOrNull()?.message ?: "screenshot failed"
                    }
                }
                "tap" -> {
                    val mode = payload.optString("mode", "percent")
                    val x = payload.optDouble("x", 50.0).toFloat()
                    val y = payload.optDouble("y", 50.0).toFloat()
                    val svc = TapService.instance
                    ok = if (svc == null) {
                        error = "accessibility not connected"
                        false
                    } else if (mode == "px") {
                        svc.clickAt(x, y)
                    } else {
                        svc.clickAtPercent(x, y)
                    }
                    data.put("message", if (ok) "tapped" else "tap failed")
                }
                "invoke" -> {
                    val line = payload.optString("line", payload.optString("text", ""))
                    // Local server uses onInvoke; expose via LogBuffer path — paste invoke for now
                    if (line.isBlank()) {
                        error = "empty invoke"
                    } else {
                        // Best-effort: write to log buffer so user sees it; actual engine may need hook
                        LogBuffer.i("Relay", "invoke|$line")
                        data.put("message", "queued invoke (see device logs)")
                        ok = true
                    }
                }
                "restart-roblox" -> {
                    val msg = BridgeControl.restartRoblox(context.applicationContext)
                    data.put("message", msg)
                    ok = !msg.contains("failed", ignoreCase = true)
                    if (!ok) error = msg
                }
                "restart-bridge" -> {
                    val msg = BridgeControl.restartBridge()
                    data.put("message", msg)
                    ok = msg.contains("restart", ignoreCase = true)
                    if (!ok) error = msg
                }
                "clipboard" -> {
                    val svc = TapService.instance
                    val clip = try {
                        // Soft: report via accessibility if available
                        "use local web clipboard"
                    } catch (_: Throwable) {
                        null
                    }
                    data.put("message", clip ?: "n/a")
                    ok = true
                }
                "status" -> {
                    data.put("message", "relay=$status code=${code ?: "-"} a11y=${TapService.instance != null}")
                    ok = true
                }
                else -> error = "unknown type $type"
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            ok = false
        }
        try {
            postJson(
                "/api/device-result",
                JSONObject()
                    .put("deviceToken", token)
                    .put("commandId", id)
                    .put("ok", ok)
                    .put("error", error)
                    .put("data", data),
            )
        } catch (t: Throwable) {
            LogBuffer.w("Relay", "result post: ${t.message}")
        }
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(baseUrl + path)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: "{}"
            conn.disconnect()
            JSONObject(if (text.isBlank()) "{}" else text)
        }

    private suspend fun getJson(path: String): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(baseUrl + path)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: "{}"
            conn.disconnect()
            JSONObject(if (text.isBlank()) "{}" else text)
        }

    private fun urlEnc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
