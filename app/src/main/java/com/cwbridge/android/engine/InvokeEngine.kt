package com.cwbridge.android.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.cwbridge.android.DeviceStatus
import com.cwbridge.android.TapService
import com.cwbridge.android.bridge.LogBuffer
import com.cwbridge.android.data.DatastoreRateLimit
import com.cwbridge.android.data.Store
import com.cwbridge.android.data.UserFileStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.cwbridge.android.ShizukuShell

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
    private val httpClient = OkHttpClient()

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
            "cmds: save load storeinfo setlimit weather exists alive keys status tap paste help",
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


    /** If raw ends with .name.rbx, peel domain; else default local.rbx. */
    private fun splitOptionalDomain(raw: String): Pair<String, String> {
        val m = Regex("""^(.*)\.([a-z0-9_-]+\.rbx)$""", RegexOption.IGNORE_CASE).matchEntire(raw.trim())
        return if (m != null) {
            m.groupValues[1] to m.groupValues[2].lowercase()
        } else {
            raw to Store.DEFAULT_DOMAIN
        }
    }

    private suspend fun dispatch(request: String, data1: String, data2: String) {
        when (request) {
            "save" -> {
                // save.<key>.<value>[.<domain.rbx>]
                if (data1.isEmpty()) {
                    replyErr("save", "need save.key.value[.domain.rbx]")
                    return
                }
                val (value, domain) = splitOptionalDomain(data2)
                val rl = DatastoreRateLimit.checkAndConsume(context, domain)
                if (rl != null) {
                    replyErr("save", rl)
                    return
                }
                val result = store.save(domain, data1, value)
                result.fold(
                    onSuccess = { replyOk("save", "$data1 → $domain (${value.length} chars)") },
                    onFailure = { replyErr("save", it.message ?: "fail") },
                )
            }

            "load" -> {
                // load.<key>.<domain.rbx> — pastes the actual key value into the game
                if (data1.isEmpty() || data2.isEmpty()) {
                    replyErr("load", "need load.key.domain (domain like weather.rbx)")
                    return
                }
                val domain = data2.trim()
                val rl = DatastoreRateLimit.checkAndConsume(context, domain)
                if (rl != null) {
                    replyErr("load", rl)
                    return
                }
                val result = store.load(domain, data1)
                result.fold(
                    onSuccess = { value ->
                        // Paste real content into focused field (not just clipboard)
                        pasteIntoGame(value)
                        replyOk("load", value.take(500))
                    },
                    onFailure = { e ->
                        // Tell the game via log channel (CatWeb can read cwbridge|err|load|…)
                        val msg = when (e) {
                            is NoSuchElementException -> "NOTFOUND ${e.message}"
                            else -> e.message ?: "fail"
                        }
                        replyErr("load", msg)
                    },
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


            "storeinfo" -> {
                // storeinfo.<domain.rbx> → paste: 5.STORED_BITS.LIMIT_BITS.KEYS.REQ_USED.REQ_MAX.REQ_LEFT
                val domainRaw = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                val domain = Store.normalizeDomain(domainRaw)
                    ?: run {
                        replyErr("storeinfo", "need storeinfo.domain.rbx")
                        return
                    }
                val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 1)
                if (rl != null) {
                    replyErr("storeinfo", rl)
                    return
                }
                val usedBytes = store.usageOf(domain)
                val limBytes = store.limitOf(domain)
                val keys = store.listKeys(domain).getOrElse { emptyList() }.size
                val snap = DatastoreRateLimit.snapshot(context, domain)
                val reqUsed = (snap["domainUsed"] as? Number)?.toInt() ?: 0
                val reqMax = (snap["domainLimit"] as? Number)?.toInt()
                    ?: DatastoreRateLimit.domainLimit(context, domain)
                val reqLeft = if (reqMax <= 0) -1 else (reqMax - reqUsed).coerceAtLeast(0)
                val storedBits = usedBytes * 8L
                val limitBits = if (limBytes <= 0L) 0L else limBytes * 8L
                val payload = "5.$storedBits.$limitBits.$keys.$reqUsed.$reqMax.$reqLeft"
                pasteIntoGame(payload)
                replyOk("storeinfo", payload)
            }

            "setlimit" -> {
                // setlimit.<domain.rbx>.<type>.<value>
                // type 0 = requests/day for domain, type 1 = data limit (bits)
                val admin = UserFileStore.getSetting(context, "admin_domain", "")
                    ?.trim()?.lowercase().orEmpty()
                if (admin.isEmpty()) {
                    replyErr("setlimit", "admin domain not configured")
                    return
                }
                val full = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                val m = Regex("""^([a-z0-9_-]+\.rbx)\.([01])\.(.+)$""", RegexOption.IGNORE_CASE)
                    .matchEntire(full.trim())
                if (m == null) {
                    replyErr("setlimit", "need setlimit.domain.rbx.type.value (type 0=reqs 1=data bits)")
                    return
                }
                val target = Store.normalizeDomain(m.groupValues[1])
                    ?: run {
                        replyErr("setlimit", "bad domain")
                        return
                    }
                val type = m.groupValues[2].toInt()
                val valueRaw = m.groupValues[3].trim()
                when (type) {
                    0 -> {
                        val n = valueRaw.toIntOrNull()
                            ?: run {
                                replyErr("setlimit", "type 0 value must be int reqs/day")
                                return
                            }
                        DatastoreRateLimit.setDomainRequestLimit(context, target, n)
                        replyOk("setlimit", "reqs $target = $n/day")
                    }
                    1 -> {
                        val bits = valueRaw.toLongOrNull()
                            ?: run {
                                replyErr("setlimit", "type 1 value must be bits (integer)")
                                return
                            }
                        val bytes = if (bits <= 0L) 0L else (bits / 8L)
                        store.setLimit(target, bytes).fold(
                            onSuccess = {
                                replyOk("setlimit", "data $target = ${bits}b (${Store.formatBytes(bytes)})")
                            },
                            onFailure = { replyErr("setlimit", it.message ?: "fail") },
                        )
                    }
                    else -> replyErr("setlimit", "type must be 0 or 1")
                }
            }


            "weather" -> {
                // weather.<lat>.<lon>  OR  weather.<City>
                // costs 2 against default domain budget
                val domain = Store.DEFAULT_DOMAIN
                val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 2)
                if (rl != null) {
                    replyErr("weather", rl)
                    return
                }
                val q = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (q.isEmpty()) {
                    replyErr("weather", "need weather.lat.lon or weather.City")
                    return
                }
                try {
                    val payload = withContext(Dispatchers.IO) {
                        val (lat, lon) = resolveWeatherPoint(q)
                        val url =
                            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                                "&current=temperature_2m,relative_humidity_2m,weather_code"
                        val body = httpGet(url)
                        val cur = JSONObject(body).getJSONObject("current")
                        val temp = cur.getDouble("temperature_2m")
                        val hum = cur.optInt("relative_humidity_2m", 0)
                        val code = cur.optInt("weather_code", 0)
                        "${temp.toInt()}.$hum.$code"
                    }
                    pasteIntoGame(payload)
                    replyOk("weather", payload)
                } catch (t: Throwable) {
                    replyErr("weather", t.message ?: "fetch failed")
                }
            }

            "exists" -> {
                // exists.<key>.<domain.rbx> — free, paste 1 or 0
                if (data1.isEmpty() || data2.isEmpty()) {
                    replyErr("exists", "need exists.key.domain.rbx")
                    return
                }
                val domain = data2.trim()
                val ok = store.load(domain, data1).isSuccess
                val payload = if (ok) "1" else "0"
                pasteIntoGame(payload)
                replyOk("exists", payload)
            }

            "alive" -> {
                // free — paste 1 if TapService up, else 0
                val a11y = TapService.isConnected() || TapService.instance != null
                val payload = if (a11y) "1" else "0"
                pasteIntoGame(payload)
                replyOk("alive", payload)
            }

            "keys" -> {
                // keys.<domain.rbx> — cost 1, paste "n.key1.key2..." or just count if empty
                val domainRaw = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                val domain = Store.normalizeDomain(domainRaw)
                    ?: run {
                        replyErr("keys", "need keys.domain.rbx")
                        return
                    }
                val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 1)
                if (rl != null) {
                    replyErr("keys", rl)
                    return
                }
                val list = store.listKeys(domain).getOrElse { emptyList() }
                val payload = if (list.isEmpty()) {
                    "0"
                } else {
                    list.size.toString() + "." + list.joinToString(".")
                }
                pasteIntoGame(payload)
                replyOk("keys", payload.take(500))
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
                // paste with no arg -> paste whatever is already on the clipboard.
                val text = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (text.isEmpty()) {
                    val existing = getClipboard()
                    if (existing.isNullOrEmpty()) {
                        replyErr("paste", "no text arg and clipboard is empty")
                        return
                    }
                    pasteExisting(existing)
                } else {
                    runPasteSequence(text)
                }
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
                    "save.key.data | load.key.domain | storeinfo.domain | setlimit.domain.type.val | " +
                        "status | tap.x.y | paste.text | clip | focus | submit | wait | toast | echo | help",
                )
            }

            "ai" -> {
                // ai.<prompt> — uses web AI settings; pastes model text; costs 2
                val prompt = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (prompt.isEmpty()) {
                    replyErr("ai", "need ai.prompt")
                    return
                }
                val domain = Store.DEFAULT_DOMAIN
                val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 2)
                if (rl != null) {
                    replyErr("ai", rl)
                    return
                }
                val url = UserFileStore.getSetting(context, "ai_url", "")?.trim().orEmpty()
                val token = UserFileStore.getSetting(context, "ai_token", "")?.trim().orEmpty()
                val model = UserFileStore.getSetting(context, "ai_model", "gpt-4o-mini")?.trim().orEmpty()
                    .ifBlank { "gpt-4o-mini" }
                val style = UserFileStore.getSetting(context, "ai_style", "chat")?.trim()?.lowercase().orEmpty()
                if (url.isEmpty()) {
                    replyErr("ai", "configure AI URL in web panel")
                    return
                }
                if (token.isEmpty()) {
                    replyErr("ai", "configure AI token in web panel")
                    return
                }
                try {
                    val text = withContext(Dispatchers.IO) {
                        callLlm(baseUrl = url, token = token, model = model, style = style, prompt = prompt)
                    }
                    if (text.isBlank()) {
                        replyErr("ai", "empty model reply")
                        return
                    }
                    pasteIntoGame(text)
                    replyOk("ai", text.take(500))
                } catch (t: Throwable) {
                    replyErr("ai", t.message ?: "llm failed")
                }
            }

            else -> replyErr(request, "unknown request — invoke|help")
        }
    }


    /** Focus → paste [text] into game. Does not emit replyOk (caller does). */
    private suspend fun pasteIntoGame(text: String) {
        val svc = TapService.instance ?: return
        setClipboard(text)
        svc.clickAtPercent(focusXPct, focusYPct)
        delay(1000)
        svc.pasteClipboard()
        delay(400)
        if (submitXPx > 0f || submitYPx > 0f) {
            svc.clickAt(submitXPx, submitYPx)
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

    /**
     * `paste` with no text arg: the clipboard already holds the content, so only
     * the focus-tap -> paste -> submit sequence runs. We deliberately do NOT
     * rewrite the clipboard here.
     */
    private suspend fun pasteExisting(existing: String) {
        val svc = TapService.instance
        if (svc == null) {
            replyErr("paste", "TapService offline")
            return
        }
        svc.clickAtPercent(focusXPct, focusYPct)
        delay(1000)
        svc.pasteClipboard()
        delay(1000)
        if (submitXPx > 0f || submitYPx > 0f) {
            svc.clickAt(submitXPx, submitYPx)
        }
        replyOk("paste", "pasted clipboard (${existing.length} chars) focus=$focusXPct,$focusYPct")
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


    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).get().header("User-Agent", "CWBridge/1.0").build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("empty body")
        }
    }

    /** lat,lon from "48.8.2.3" / "48.8,2.3" or city name via Open-Meteo geocoding. */
    private fun resolveWeatherPoint(q: String): Pair<Double, Double> {
        val cleaned = q.trim().replace(",", ".")
        val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(cleaned).map { it.value.toDouble() }.toList()
        if (nums.size >= 2) {
            return nums[0] to nums[1]
        }
        val geoUrl =
            "https://geocoding-api.open-meteo.com/v1/search?name=" +
                java.net.URLEncoder.encode(q, Charsets.UTF_8.name()) +
                "&count=1&language=en&format=json"
        val body = httpGet(geoUrl)
        val results = JSONObject(body).optJSONArray("results")
            ?: error("city not found: $q")
        if (results.length() == 0) error("city not found: $q")
        val first = results.getJSONObject(0)
        return first.getDouble("latitude") to first.getDouble("longitude")
    }


    /**
     * OpenAI-compatible call.
     * style "chat" → POST {base}/v1/chat/completions
     * style "responses" → POST {base}/v1/responses
     */
    private fun callLlm(
        baseUrl: String,
        token: String,
        model: String,
        style: String,
        prompt: String,
    ): String {
        val root = baseUrl.trim().trimEnd('/')
        val useResponses = style == "responses" || style == "v2" || style == "response"
        val endpoint = when {
            useResponses && root.endsWith("/v1/responses") -> root
            useResponses && root.endsWith("/v1") -> "$root/responses"
            useResponses -> "$root/v1/responses"
            root.endsWith("/v1/chat/completions") -> root
            root.endsWith("/v1") -> "$root/chat/completions"
            else -> "$root/v1/chat/completions"
        }
        val jsonBody = if (useResponses) {
            JSONObject()
                .put("model", model)
                .put("input", prompt)
                .toString()
        } else {
            val messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt),
            )
            JSONObject()
                .put("model", model)
                .put("messages", messages)
                .toString()
        }
        val media = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(media))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val brief = body.take(180).replace('\n', ' ')
                error("HTTP ${resp.code}: $brief")
            }
            val obj = JSONObject(body)
            return if (useResponses) {
                extractResponsesText(obj)
            } else {
                obj.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }
    }

    private fun extractResponsesText(obj: JSONObject): String {
        if (obj.has("output_text")) {
            val ot = obj.optString("output_text", "")
            if (ot.isNotBlank()) return ot.trim()
        }
        val output = obj.optJSONArray("output") ?: return obj.toString().take(500)
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        }
        val out = sb.toString().trim()
        if (out.isNotEmpty()) return out
        error("no text in responses payload")
    }


    private fun replyOk(request: String, payload: String) {
        // Tagged so Roblox/MacroDroid can filter; also human-readable in the in-app log.
        LogBuffer.i("Invoke", "cwbridge|ok|$request|${payload.take(500)}")
    }

    private fun replyErr(request: String, payload: String) {
        LogBuffer.e("Invoke", "cwbridge|err|$request|${payload.take(500)}")
    }
}
