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
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private var job: Job? = null

    @Volatile private var lastInvokePayload: String = ""
    @Volatile private var lastInvokeAtMs: Long = 0L
    private val invokeDedupMs: Long = 3000L

    
    @Volatile var focusXPct = 50f
    @Volatile var focusYPct = 50f

    
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
            "cmds: save load storeinfo setlimit weather exists alive keys status tap paste qr totp totpcheck help",
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

        val now = System.currentTimeMillis()
        if (payload == lastInvokePayload && now - lastInvokeAtMs < invokeDedupMs) {
            LogBuffer.i("Invoke", "dedup skip (${now - lastInvokeAtMs}ms) ${payload.take(60)}")
            return
        }
        lastInvokePayload = payload
        lastInvokeAtMs = now

        val parts = payload.split(".", limit = 3)
        val request = parts[0].trim().lowercase()
        val data1 = parts.getOrNull(1)?.trim().orEmpty()
        val data2 = parts.getOrNull(2)?.trim().orEmpty()

        LogBuffer.i("Invoke", "→ $request data1=${data1.take(80)} data2=${data2.take(80)}")

        job?.cancel()
        job = scope.launch(Dispatchers.Main) {
            try {
                dispatch(request, data1, data2)
            } catch (t: Throwable) {
                replyErr(request, t.message ?: t::class.java.simpleName)
            }
        }
    }


    /** If raw ends with .name.rbx, peel; otherwise return */
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

            
            "enter", "return" -> {
                val svc = TapService.instance
                val ok = when {
                    svc != null -> svc.pressEnter()
                    ShizukuShell.isReady() -> ShizukuShell.pressEnter()
                    else -> false
                }
                if (ok) replyOk("enter", "Enter sent")
                else replyErr("enter", "Enter failed (need a11y or Shizuku)")
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
                    pasteIntoGame(text)
                }
            }

            "qr" -> {
                // qr.<text> — auto-size QR → paste "size(1-5).colormap" (0/1 modules, dark=1)
                val text = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (text.isEmpty()) {
                    replyErr("qr", "need qr.text")
                    return
                }
                try {
                    val encoded = withContext(Dispatchers.Default) { QrMap.encode(text) }
                    val payload = encoded.wire()
                    pasteIntoGame(payload)
                    replyOk(
                        "qr",
                        "size=${encoded.size} modules=${encoded.modules} bits=${encoded.colorMap.length}",
                    )
                } catch (t: Throwable) {
                    replyErr("qr", t.message ?: "encode failed")
                }
            }

            "totp" -> {
                // totp.<base32secret>  → paste current 6-digit code
                val secret = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (secret.isEmpty()) {
                    replyErr("totp", "need totp.base32secret")
                    return
                }
                try {
                    val code = withContext(Dispatchers.Default) { Totp.code(secret) }
                    pasteIntoGame(code)
                    replyOk("totp", code)
                } catch (t: Throwable) {
                    replyErr("totp", t.message ?: "totp failed")
                }
            }

            "totpcheck" -> {
                // totpcheck.<base32secret>.<user_code>  → paste 1 or 0
                val secret = data1
                val code = data2
                if (secret.isEmpty() || code.isEmpty()) {
                    replyErr("totpcheck", "need totpcheck.base32secret.code")
                    return
                }
                try {
                    val ok = withContext(Dispatchers.Default) { Totp.verify(secret, code) }
                    val payload = if (ok) "1" else "0"
                    pasteIntoGame(payload)
                    replyOk("totpcheck", payload)
                } catch (t: Throwable) {
                    replyErr("totpcheck", t.message ?: "check failed")
                }
            }

            "help" -> {
                replyOk(
                    "help",
                    "save.key.data | load.key.domain | storeinfo.domain | setlimit.domain.type.val | " +
                        "status | tap.x.y | paste.text | qr.text | totp.secret | totpcheck.secret.code | " +
                        "enter | clip | focus | submit | wait | toast | echo | help",
                )
            }

            else -> {
                replyErr(request, "unknown cmd — try help")
            }
        }
    }

    // ---- remaining helpers truncated for this restore; full file continues with pasteIntoGame, replyOk, etc. from previous good version ----
}
