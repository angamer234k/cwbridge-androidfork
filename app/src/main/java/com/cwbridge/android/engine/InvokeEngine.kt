package com.cwbridge.android.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.cwbridge.android.DeviceStatus
import com.cwbridge.android.TapService
import com.cwbridge.android.bridge.LogBuffer
import com.cwbridge.android.data.DatastoreRateLimit
import com.cwbridge.android.data.OffDeviceSink
import com.cwbridge.android.data.Store
import com.cwbridge.android.data.UserFileStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.PriorityQueue
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.cwbridge.android.ShizukuShell

/**
 * Watches log lines for `invoke|request[.data1[.data2]]||reqId` and dispatches handlers.
 * Requests without a valid REQID are ignored before they can enter the queue.
 *
 * Same wire format as the MacroDroid Server macro:
 *   Info … invoke|save.mykey.hello||req_1
 *   Info … invoke|load.mykey.weather.rbx||req_2
 *   Info … invoke|status||req_3
 *   Info … invoke|tap.50.85||req_4
 *   Info … invoke|paste.hello world||req_5
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

    private data class QueuedRequest(
        val command: String,
        val data1: String,
        val data2: String,
        val requestId: String,
        val priority: Int,
        val sequence: Long,
        val requiresAck: Boolean,
    )

    private data class RequestReply(val ok: Boolean, val body: String)

    private class ActiveRequest(val queued: QueuedRequest) {
        @Volatile var reply: RequestReply? = null
        @Volatile var delivered: Boolean = false
        @Volatile var expectedAckPrefix: String? = null
        @Volatile var pendingAck: CompletableDeferred<Boolean>? = null
    }

    private val running = AtomicBoolean(false)
    private val queueLock = Any()
    private val requestQueue = PriorityQueue<QueuedRequest>(
        compareBy<QueuedRequest> { it.priority }.thenBy { it.sequence },
    )
    private val seenRequestIds = LinkedHashMap<String, Unit>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private var nextSequence = 0L
    private var workerJob: Job? = null
    @Volatile private var activeRequest: ActiveRequest? = null

    private val maxQueuedRequests = 64
    private val maxRememberedRequestIds = 512
    private val ackTimeoutMs = 5_000L
    private val maxDeliveryAttempts = 3

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
        workerJob = scope.launch(Dispatchers.Main) { runQueue() }
        LogBuffer.i("Invoke", "engine on — watching for invoke| requests with REQID")
        LogBuffer.i(
            "Invoke",
            "queue: datastore → admin/control → weather → AI; one request at a time",
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        LogBuffer.removeListener(listener)
        activeRequest?.pendingAck?.complete(false)
        workerJob?.cancel()
        workerJob = null
        val dropped = synchronized(queueLock) {
            val count = requestQueue.size
            requestQueue.clear()
            seenRequestIds.clear()
            count
        }
        activeRequest = null
        LogBuffer.i("Invoke", "engine off; dropped $dropped queued request(s)")
    }

    fun isRunning(): Boolean = running.get()

    /** Local control-panel requests already receive a generated local_ REQID. */
    fun onLocalInvoke(raw: String) {
        if (running.get()) handleLine(raw, requiresAckOverride = false)
    }

    /** App-owned automation requests get an ID but do not wait for a CatWeb page ACK. */
    fun onInternalInvoke(raw: String) {
        if (running.get()) handleLine(raw, requiresAckOverride = false)
    }

    /** Also feed raw system logcat lines (may include Roblox FLog). */
    fun onExternalLog(raw: String) {
        if (!running.get()) return
        if (InvokeProtocol.isAck(raw)) {
            handleAck(raw)
            return
        }
        if (raw.contains("invoke|", ignoreCase = false)) {
            handleLine(raw)
        }
    }

    private fun handleLine(raw: String, requiresAckOverride: Boolean? = null) {
        if (!running.get() || InvokeProtocol.isAck(raw)) return
        val parsed = InvokeProtocol.parseRequest(raw) ?: return

        val domainGuess = listOf(parsed.data1, parsed.data2)
            .flatMap { it.split(".") }
            .map { it.trim().lowercase() }
            .firstOrNull { it.endsWith(".rbx") }
            ?: listOf(parsed.data1, parsed.data2).firstOrNull { it.contains(".rbx") }
                ?.let { blob -> Regex("[a-z0-9_-]+\\.rbx").find(blob.lowercase())?.value }

        val requiresAck = requiresAckOverride ?: true

        val result = synchronized(queueLock) {
            when {
                seenRequestIds.containsKey(parsed.requestId) -> "duplicate"
                requestQueue.size >= maxQueuedRequests -> "full"
                else -> {
                    val queued = QueuedRequest(
                        command = parsed.command,
                        data1 = parsed.data1,
                        data2 = parsed.data2,
                        requestId = parsed.requestId,
                        priority = InvokeProtocol.priorityFor(parsed.command),
                        sequence = nextSequence++,
                        requiresAck = requiresAck,
                    )
                    requestQueue.add(queued)
                    seenRequestIds[parsed.requestId] = Unit
                    while (seenRequestIds.size > maxRememberedRequestIds) {
                        val oldest = seenRequestIds.entries.iterator()
                        if (oldest.hasNext()) {
                            oldest.next()
                            oldest.remove()
                        }
                    }
                    "queued"
                }
            }
        }

        when (result) {
            "duplicate" -> {
                LogBuffer.i("Invoke", "duplicate request ignored id=${parsed.requestId}")
                return
            }
            "full" -> {
                LogBuffer.e("Invoke", "request queue full; ignored id=${parsed.requestId}")
                return
            }
        }

        LogBuffer.i(
            "BRIDGE",
            "queued (${parsed.command}) priority=${InvokeProtocol.priorityFor(parsed.command)}" +
                " from ${domainGuess ?: "local"} id=${parsed.requestId}" +
                " ${parsed.data1.take(40)}${if (parsed.data2.isNotEmpty()) ".${parsed.data2.take(40)}" else ""}",
        )
        queueSignal.trySend(Unit)
    }

    private fun handleAck(raw: String) {
        val active = activeRequest ?: return
        val expected = active.expectedAckPrefix ?: return
        if (!InvokeProtocol.ackMatches(raw, active.queued.requestId, expected)) return
        val pending = active.pendingAck ?: return
        if (pending.complete(true)) {
            LogBuffer.i("Invoke", "ACK received id=${active.queued.requestId}")
        }
    }

    private suspend fun runQueue() {
        while (running.get()) {
            val queued = synchronized(queueLock) { requestQueue.poll() }
            if (queued == null) {
                queueSignal.receive()
                continue
            }

            val active = ActiveRequest(queued)
            activeRequest = active
            LogBuffer.i("Invoke", "starting id=${queued.requestId} command=${queued.command}")
            try {
                try {
                    dispatch(queued.command, queued.data1, queued.data2)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    replyErr(queued.command, t.message ?: t::class.java.simpleName)
                }

                if (queued.requiresAck && !active.delivered) {
                    val reply = active.reply ?: RequestReply(true, "ok")
                    pasteIntoGame(reply.body, success = reply.ok)
                }
                LogBuffer.i("Invoke", "request released id=${queued.requestId}")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                LogBuffer.e(
                    "Invoke",
                    "request failed id=${queued.requestId}: ${t.message ?: t::class.java.simpleName}",
                )
            } finally {
                activeRequest = null
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
        try {
            OffDeviceSink.onInvoke(context, request, data1, data2)
        } catch (_: Throwable) {
        }
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
                        if (value.isEmpty()) {
                            replyErr("load", "empty")
                        } else {
                            pasteIntoGame(value)
                            replyOk("load", value.take(500))
                        }
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
                // paste.<text>  (data1 + optional data2 rejoined)
                // With no argument, return the existing clipboard contents.
                val text = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                val value = if (text.isEmpty()) getClipboard().orEmpty() else text
                if (value.isEmpty()) {
                    replyErr("paste", "no text arg and clipboard is empty")
                    return
                }
                pasteIntoGame(value)
                replyOk("paste", "pasted ${value.length} chars")
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
              "ai.prompt | ai.models | ai.pricing | sink | sink.test | " +
              "enter | clip | focus | submit | wait | toast | echo | help",
      )
  }

            "ai" -> {
                val head = data1.lowercase()
                if (head == "models" || head == "pricing" || head == "price") {
                    dispatchAiMeta(head, data2)
                    return
                }
                // ai.<prompt> — uses web AI settings; pastes model text; costs 2
                val prompt = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                if (prompt.isEmpty()) {
                    replyErr("ai", "need ai.prompt (or ai.models / ai.pricing)")
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
                val system = UserFileStore.getSetting(context, "ai_system", "")?.trim().orEmpty()
                if (url.isEmpty()) {
                    replyErr("ai", "configure AI URL in web panel")
                    return
                }
                if (token.isEmpty()) {
                    replyErr("ai", "configure AI token in web panel")
                    return
                }
                try {
                    val timeoutSec = UserFileStore.getSetting(context, "ai_timeout_sec", "90")
                        ?.trim()?.toIntOrNull()?.coerceIn(15, 300) ?: 90
                    val temperature = UserFileStore.getSetting(context, "ai_temperature", "0.7")
                        ?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.7
                    val maxTokens = UserFileStore.getSetting(context, "ai_max_tokens", "1024")
                        ?.trim()?.toIntOrNull()?.coerceIn(1, 16384) ?: 1024
                    val text = withContext(Dispatchers.IO) {
                        callLlm(
                            baseUrl = url,
                            token = token,
                            model = model,
                            style = style,
                            prompt = prompt,
                            timeoutSec = timeoutSec,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            system = system,
                        )
                    }
                    if (text.isBlank()) {
                        replyErr("ai", "empty model reply")
                        return
                    }
                    pasteIntoGame(text, pressEnter = true)
                    replyOk("ai", text.take(500))
                } catch (t: Throwable) {
                    replyErr("ai", t.message ?: "llm failed")
                }
            }

            "sink" -> {
                val sub = data1.lowercase()
                when (sub) {
                    "", "status" -> {
                        val on = OffDeviceSink.enabled(context) &&
                            (OffDeviceSink.webhook(context).isNotEmpty() || OffDeviceSink.sheet(context).isNotEmpty())
                        val bit = if (on) "1" else "0"
                        pasteIntoGame(bit)
                        replyOk("sink", bit)
                    }
                    "test" -> {
                        val domain = Store.DEFAULT_DOMAIN
                        val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 1)
                        if (rl != null) {
                            replyErr("sink", rl)
                            return
                        }
                        try {
                            val msg = withContext(Dispatchers.IO) { OffDeviceSink.test(context) }
                            pasteIntoGame("1")
                            replyOk("sink", msg)
                        } catch (t: Throwable) {
                            replyErr("sink", t.message ?: "sink failed")
                        }
                    }
                    else -> replyErr("sink", "need sink or sink.test")
                }
            }

            else -> replyErr(request, "unknown request — invoke|help")
        }
    }


    /**
     * Deliver one response through the focused CatWeb inbox.
     * CatWeb requests are wrapped in the 0./1. protocol and hold the worker
     * until the matching invoke||ACK||REQID||PREFIX is observed.
     */
    private suspend fun pasteIntoGame(
        text: String,
        pressEnter: Boolean = false,
        success: Boolean = true,
    ) {
        val active = activeRequest
            ?: throw IllegalStateException("paste attempted outside an active request")
        if (active.delivered) return

        val restoreClipboard = when {
            active.queued.command == "paste" -> {
                if (active.queued.data1.isEmpty() && active.queued.data2.isEmpty()) {
                    getClipboard()
                } else {
                    listOf(active.queued.data1, active.queued.data2)
                        .filter { it.isNotEmpty() }
                        .joinToString(".")
                }
            }
            success && active.queued.command == "clip" && active.queued.data1.equals("set", ignoreCase = true) ->
                active.queued.data2
            else -> null
        }
        fun markDelivered() {
            active.delivered = true
            active.pendingAck = null
            if (restoreClipboard != null) {
                try {
                    setClipboard(restoreClipboard)
                } catch (t: Throwable) {
                    LogBuffer.w("Invoke", "could not restore clipboard: ${t.message}")
                }
            }
        }

        val safe = text.replace("\n", " ").replace("\r", " ").replace("||", "|").take(900)
        val responseBody = if (active.queued.requiresAck) {
            "${if (success) "0." else "1."}$safe"
        } else {
            text
        }
        val wire = if (active.queued.requiresAck) {
            "$responseBody||${active.queued.requestId}"
        } else {
            responseBody
        }

        val svc = TapService.instance
        if (!active.queued.requiresAck) {
            setClipboard(wire)
            if (svc != null) {
                svc.clickAtPercent(focusXPct, focusYPct)
                delay(1000)
                svc.pasteClipboard()
                delay(400)
                if (submitXPx > 0f || submitYPx > 0f) {
                    svc.clickAt(submitXPx, submitYPx)
                    delay(200)
                }
                if (pressEnter) {
                    val ok = svc.pressEnter()
                    LogBuffer.i("Invoke", "pasteEnter a11y=$ok")
                }
            } else if (ShizukuShell.isReady()) {
                ShizukuShell.exec("input keyevent KEYCODE_PASTE")
                delay(400)
                if (pressEnter) {
                    val ok = ShizukuShell.pressEnter()
                    LogBuffer.i("Invoke", "pasteEnter shizuku=$ok")
                }
            } else {
                LogBuffer.w("Invoke", "pasteIntoGame: no TapService/Shizuku")
            }
            active.delivered = true
            return
        }

        val expectedPrefix = responseBody.take(10)
        val ack = CompletableDeferred<Boolean>()
        active.expectedAckPrefix = expectedPrefix
        active.pendingAck = ack

        if (svc == null && !ShizukuShell.isReady()) {
            LogBuffer.e("Invoke", "cannot deliver id=${active.queued.requestId}: no TapService/Shizuku; queue paused")
            ack.await()
            markDelivered()
            return
        }

        for (attempt in 1..maxDeliveryAttempts) {
            if (ack.isCompleted) break
            if (attempt > 1) {
                // Focus first, then select all: tapping after Ctrl+A would collapse the selection.
                if (!ShizukuShell.isReady()) break
                if (svc != null) {
                    svc.clickAtPercent(focusXPct, focusYPct)
                    delay(100)
                }
                if (!ShizukuShell.injectCtrlChordShell(android.view.KeyEvent.KEYCODE_A)) {
                    LogBuffer.w("Invoke", "could not select inbox before retry; pausing delivery")
                    break
                }
                delay(100)
                if (ack.isCompleted) break
            }

            try {
                setClipboard(wire)
                if (svc != null) {
                    if (attempt == 1) svc.clickAtPercent(focusXPct, focusYPct)
                    delay(1000)
                    svc.pasteClipboard()
                    delay(400)
                    if (submitXPx > 0f || submitYPx > 0f) {
                        svc.clickAt(submitXPx, submitYPx)
                        delay(200)
                    }
                    if (pressEnter && attempt == 1) {
                        val ok = svc.pressEnter()
                        LogBuffer.i("Invoke", "pasteEnter a11y=$ok")
                    }
                } else {
                    ShizukuShell.exec("input keyevent KEYCODE_PASTE")
                    delay(400)
                    if (pressEnter && attempt == 1) {
                        val ok = ShizukuShell.pressEnter()
                        LogBuffer.i("Invoke", "pasteEnter shizuku=$ok")
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                LogBuffer.w("Invoke", "delivery attempt $attempt failed: ${t.message}")
            }

            val acknowledged = withTimeoutOrNull(ackTimeoutMs) { ack.await() } == true
            if (acknowledged) {
                markDelivered()
                return
            }
            if (attempt < maxDeliveryAttempts && ShizukuShell.isReady()) {
                LogBuffer.w("Invoke", "ACK timeout for id=${active.queued.requestId}; resending")
            } else {
                break
            }
        }

        if (ack.isCompleted && ack.await()) {
            markDelivered()
            return
        }
        LogBuffer.e(
            "Invoke",
            "UNCONFIRMED id=${active.queued.requestId}; queue paused until a matching ACK arrives",
        )
        // A late ACK still releases the queue; otherwise the operator can stop the bridge.
        ack.await()
        markDelivered()
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


    /** ai.models / ai.pricing[.<model>] — list ids or paste USD per 1M tokens. Cost 1. */
    private suspend fun dispatchAiMeta(head: String, data2: String) {
        val domain = Store.DEFAULT_DOMAIN
        val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 1)
        if (rl != null) {
            replyErr("ai", rl)
            return
        }
        val url = UserFileStore.getSetting(context, "ai_url", "")?.trim().orEmpty()
        val token = UserFileStore.getSetting(context, "ai_token", "")?.trim().orEmpty()
        if (url.isEmpty()) {
            replyErr("ai", "configure AI URL in web panel")
            return
        }
        try {
            val rows = withContext(Dispatchers.IO) { AiCatalog.fetch(url, token) }
            if (head == "models") {
                val ids = rows.joinToString(",") { it.id }
                val clip = if (ids.length > 700) ids.take(700) + "…" else ids
                pasteIntoGame(clip)
                replyOk("ai", "${rows.size} models")
                return
            }
            val configured = UserFileStore.getSetting(context, "ai_model", "gpt-4o-mini")
                ?.trim().orEmpty().ifBlank { "gpt-4o-mini" }
            val want = data2.ifBlank { configured }
            val row = AiCatalog.find(rows, want)
            if (row == null) {
                replyErr("ai", "model not in /models: $want")
                return
            }
            val line = AiCatalog.formatPrice(row)
            pasteIntoGame(line)
            replyOk("ai", line)
        } catch (t: Throwable) {
            replyErr("ai", t.message ?: "models failed")
        }
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
        timeoutSec: Int = 90,
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
        system: String = "",
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
        val temp = temperature.coerceIn(0.0, 2.0)
        val maxTok = maxTokens.coerceIn(1, 16384)
        val sys = system.trim()
        val jsonBody = if (useResponses) {
            val o = JSONObject()
                .put("model", model)
                .put("input", prompt)
                .put("temperature", temp)
                .put("max_output_tokens", maxTok)
            if (sys.isNotEmpty()) o.put("instructions", sys)
            o.toString()
        } else {
            val messages = JSONArray()
            if (sys.isNotEmpty()) {
                messages.put(JSONObject().put("role", "system").put("content", sys))
            }
            messages.put(JSONObject().put("role", "user").put("content", prompt))
            JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temp)
                .put("max_tokens", maxTok)
                .toString()
        }
        val media = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(media))
            .build()
        val sec = timeoutSec.coerceIn(15, 300)
        val client = httpClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(sec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout((sec + 15).toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
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
        } catch (e: java.net.SocketTimeoutException) {
            error("AI timeout after ${sec}s — raise ai_timeout_sec (15-300) in settings")
        } catch (e: java.io.InterruptedIOException) {
            error("AI timeout after ${sec}s — raise ai_timeout_sec (15-300) in settings")
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
        activeRequest?.reply = RequestReply(true, payload)
        val id = activeRequest?.queued?.requestId
        val tag = if (id != null) "|$id" else ""
        LogBuffer.i("Invoke", "cwbridge|ok|$request|${payload.take(500)}$tag")
    }

    private fun replyErr(request: String, payload: String) {
        activeRequest?.reply = RequestReply(false, payload)
        val id = activeRequest?.queued?.requestId
        val tag = if (id != null) "|$id" else ""
        LogBuffer.e("Invoke", "cwbridge|err|$request|${payload.take(500)}$tag")
    }
}
