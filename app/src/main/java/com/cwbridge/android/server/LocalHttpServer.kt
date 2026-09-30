package com.cwbridge.android.server

import android.content.Context
import com.cwbridge.android.ShizukuShell
import com.cwbridge.android.TapService
import com.cwbridge.android.bridge.BridgeControl
import com.cwbridge.android.bridge.BridgeStatus
import com.cwbridge.android.bridge.CatWebTracker
import com.cwbridge.android.bridge.LogBuffer
import com.cwbridge.android.bridge.RobloxLogBuffer
import com.cwbridge.android.data.Service
import com.cwbridge.android.data.ServiceConverters
import com.cwbridge.android.data.ServiceRepository
import com.cwbridge.android.data.DatastoreRateLimit
import com.cwbridge.android.data.Store
import com.cwbridge.android.data.VarStore
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.text.Charsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Built-in web server: serves a control panel at / plus a JSON API.
 *
 * Auth rule (see ServerAuth): a client on the local network skips the password,
 * anything else must log in first. Every connection gets its own thread and is
 * wrapped, so a bad request can never take the listener down.
 */
class LocalHttpServer(
    private val context: Context,
    private val preferredPort: Int = 8080,
    private val onInvoke: (String) -> Unit,
    private val onToggleBridge: () -> Boolean,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    @Volatile private var boundPort: Int = 0
    private val gson = Gson()
    private val store by lazy { Store(context) }
    // Must match Room's parsing or sealed Trigger/Action lists come back empty.
    private val serviceGson by lazy { ServiceConverters().gson }

    fun isRunning(): Boolean = running.get()
    fun port(): Int = if (boundPort > 0) boundPort else preferredPort

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "cwbridge-http", isDaemon = true) {
            // Prefer 8080, then legacy 8765, then 80 (often needs priv).
            val candidates = linkedSetOf(preferredPort, 8080, 8765, 80).filter { it in 1..65535 }
            var lastErr: Throwable? = null
            var started = false
            for (tryPort in candidates) {
                try {
                    val ss = ServerSocket(tryPort)
                    server = ss
                    boundPort = tryPort
                    LogBuffer.i("Server", "listening on port $tryPort (tried ${candidates.joinToString()})")
                    started = true
                    try {
                        while (running.get()) {
                            try {
                                val socket = ss.accept()
                                thread(name = "cwbridge-http-conn", isDaemon = true) {
                                    try {
                                        handle(socket)
                                    } catch (t: Throwable) {
                                        LogBuffer.w("Server", "connection failed: ${t.message}")
                                    }
                                }
                            } catch (_: Exception) {
                                if (!running.get()) break
                            }
                        }
                    } finally {
                        try { ss.close() } catch (_: Exception) {}
                    }
                    break
                } catch (t: Throwable) {
                    lastErr = t
                    LogBuffer.w("Server", "port $tryPort failed: ${t.message}")
                }
            }
            if (!started) {
                LogBuffer.e("Server", "all ports failed: ${lastErr?.message}")
                running.set(false)
            }
            server = null
            boundPort = 0
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { server?.close() } catch (_: Exception) {}
        server = null
        LogBuffer.i("Server", "stopped")
    }

    // ---- request plumbing -------------------------------------------------

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = s.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val target = parts.getOrNull(1) ?: "/"

            val headers = HashMap<String, String>()
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0) {
                    headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) readFully(reader, length) else ""

            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val remote = s.inetAddress?.hostAddress ?: "unknown"

            route(out, method, path, query, body, headers, remote)
        }
    }

    private fun readFully(reader: BufferedReader, length: Int): String {
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val i = pair.indexOf('=')
            val k = urlDecode(if (i < 0) pair else pair.substring(0, i))
            val v = urlDecode(if (i < 0) "" else pair.substring(i + 1))
            k to v
        }.toMap()
    }

    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    // ---- routing ----------------------------------------------------------

    private fun route(
        out: OutputStream,
        method: String,
        path: String,
        query: Map<String, String>,
        body: String,
        headers: Map<String, String>,
        remote: String,
    ) {
        // Login is the one endpoint that must work while locked.
        if (path == "/api/login" && method == "POST") {
            val token = ServerAuth.login(context, jsonString(body, "password"))
            return if (token != null) {
                respond(
                    out, 200, json(mapOf("ok" to true)),
                    cookie = "CWBridge-Session=$token; Path=/; HttpOnly; SameSite=Strict",
                )
            } else {
                respond(out, 401, json(mapOf("error" to "wrong password")))
            }
        }

        val session = cookie(headers, "CWBridge-Session")
        val presented = query["password"] ?: headers["x-cwbridge-password"]
            ?: jsonString(body, "password").ifBlank { null }
        val denial = ServerAuth.check(context, remote, session, presented)
        val authed = denial == null

        // Same path `/`: login shell OR full dashboard — never both in one response.
        // Unauthenticated clients only ever receive the lock page (no dash markup).
        if (path == "/" || path == "/index.html") {
            if (authed) {
                return respond(
                    out, 200,
                    WebUi.page(BridgeControl.screenshotSupported()),
                    "text/html; charset=utf-8",
                )
            }
            return respond(out, 200, WebUi.loginPage(), "text/html; charset=utf-8")
        }

        if (!authed) {
            return respond(out, 401, json(mapOf("error" to denial, "needsPassword" to true)))
        }

        if (path == "/api/logout" && method == "POST") {
            ServerAuth.logout(session)
            return respond(
                out, 200, json(mapOf("ok" to true)),
                cookie = "CWBridge-Session=; Path=/; Max-Age=0",
            )
        }

        try {
            dispatch(out, method, path, query, body)
        } catch (t: Throwable) {
            // Never let one bad request kill anything.
            LogBuffer.e("Server", "$method $path -> ${t.message}")
            respond(out, 500, json(mapOf("error" to (t.message ?: t::class.java.simpleName))))
        }
    }

    private fun dispatch(out: OutputStream, method: String, path: String, query: Map<String, String>, body: String) {
        when {
            path == "/favicon.ico" -> respond(out, 204, "", "image/x-icon")

            path == "/api/status" -> respond(out, 200, statusJson())
            path == "/api/diagnose" -> respond(out, 200, diagnoseJson())
            path == "/api/domains" && method == "POST" -> respond(out, 200, openDomainsJson(body))
            path == "/api/auto-domain" && method == "GET" -> respond(out, 200, getAutoDomainJson())
            path == "/api/auto-domain" && method == "POST" -> respond(out, 200, setAutoDomainJson(body))

            path == "/api/logs" -> respond(out, 200, logsJson())

            path == "/api/vars" -> respond(out, 200, json(mapOf("vars" to VarStore.snapshot())))

            path == "/api/screenshot" -> {
                val shot = BridgeControl.takeScreenshot()
                shot.fold(
                    onSuccess = { bytes ->
                        respondBytes(out, 200, bytes, "image/png")
                    },
                    onFailure = {
                        val code = if (it is UnsupportedOperationException) 501 else 503
                        respond(out, code, json(mapOf("error" to (it.message ?: "failed"))))
                    },
                )
            }

            path == "/api/bridge/toggle" && method == "POST" -> {
                val nowRunning = onToggleBridge()
                respond(
                    out, 200,
                    json(mapOf("message" to if (nowRunning) "bridge started" else "bridge stopped")),
                )
            }

            path == "/api/invoke" && method == "POST" -> {
                val cmd = jsonString(body, "command")
                if (cmd.isBlank()) {
                    respond(out, 400, json(mapOf("error" to "command required")))
                } else {
                    onInvoke(if (cmd.contains("invoke|")) cmd else "invoke|$cmd")
                    respond(out, 200, json(mapOf("message" to "sent: $cmd")))
                }
            }

            path == "/api/tap" && method == "POST" -> respond(out, 200, tapJson(body))

            path.startsWith("/api/control/") ->
                respond(out, 200, controlJson(path.removePrefix("/api/control/")))

            path == "/api/services" && method == "GET" -> respond(out, 200, servicesListJson())

            path == "/api/services" && method == "POST" -> respond(out, 200, createService(body))

            path == "/api/store" && method == "GET" -> respond(out, 200, storeJson())

            path == "/api/store/keys" && method == "GET" -> respond(out, 200, storeKeysJson(query))

            path == "/api/store" && method == "POST" -> respond(out, 200, saveKey(body))

            path == "/api/store/delete" && method == "POST" -> respond(out, 200, deleteKey(body))

            path == "/api/store/clear" && method == "POST" -> respond(out, 200, clearDomain(body))

            path == "/api/limits" && method == "POST" -> respond(out, 200, setLimit(body))

            path == "/api/rate-limits" && method == "GET" -> respond(out, 200, rateLimitsJson(query))
            path == "/api/rate-limits" && method == "POST" -> respond(out, 200, setRateLimitsJson(body))

            path == "/api/admin-domain" && method == "GET" -> respond(out, 200, adminDomainJson())
            path == "/api/admin-domain" && method == "POST" -> respond(out, 200, setAdminDomainJson(body))


            path.startsWith("/api/services/") -> {
                val rest = path.removePrefix("/api/services/")
                val id = urlDecode(rest.substringBefore('/'))
                val sub = rest.substringAfter('/', "")
                respond(out, 200, serviceByIdJson(id, sub, method, body))
            }

            else -> respond(out, 404, json(mapOf("error" to "not found: $path")))
        }
    }

    // ---- handlers ---------------------------------------------------------


    private fun diagnoseJson(): String {
        val a11yBound = TapService.isConnected()
        val a11yListed = try {
            val cn = android.content.ComponentName(context, TapService::class.java)
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it.trim()) == cn
            }
        } catch (_: Throwable) { false }
        val shot = when {
            !BridgeControl.screenshotSupported() -> "unsupported (need Android 11+)"
            !a11yBound -> "needs CWBridge Tap connected"
            else -> "supported (FLAG_SECURE games still fail)"
        }
        val issues = mutableListOf<String>()
        if (!a11yBound) {
            issues += if (a11yListed) "A11y listed but not bound — toggle Tap off/on"
            else "Enable CWBridge Tap"
        }
        if (!ShizukuShell.isReady()) issues += "Shizuku not ready — open Shizuku and grant CWBridge"
        if (!BridgeControl.screenshotSupported()) issues += "Screenshot needs Android 11+"
        return json(
            mapOf(
                "a11yBound" to a11yBound,
                "a11yListed" to a11yListed,
                "shizuku" to ShizukuShell.statusLine(),
                "shizukuReady" to ShizukuShell.isReady(),
                "screenshot" to shot,
                "androidSdk" to android.os.Build.VERSION.SDK_INT,
                "issues" to issues,
                "ok" to issues.isEmpty(),
            ),
        )
    }


    private fun getAutoDomainJson(): String {
        val domains = BridgeControl.loadAutoOpenDomains(context)
        val one = domains.firstOrNull().orEmpty()
        return json(
            mapOf(
                "domain" to one,
                "domains" to domains,
            ),
        )
    }

    private fun setAutoDomainJson(body: String): String {
        val raw = jsonString(body, "domain").ifBlank {
            jsonString(body, "domains")
        }
        val one = raw.lines()
            .flatMap { it.split(",", ";") }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        BridgeControl.saveAutoOpenDomains(
            context,
            if (one.isEmpty()) emptyList() else listOf(one),
        )
        val label = if (one.isEmpty()) "(cleared)" else one
        LogBuffer.i("Server", "auto-open domain set to '$label'")
        return json(
            mapOf(
                "ok" to true,
                "domain" to one,
                "message" to if (one.isEmpty()) "auto-open cleared" else "auto-open set to $one",
            ),
        )
    }

    private fun openDomainsJson(body: String): String {
        val raw = jsonString(body, "domains")
        val domains = raw.lines().flatMap { it.split(",", ";") }.map { it.trim() }.filter { it.isNotEmpty() }
        if (domains.isEmpty()) return json(mapOf("error" to "domains required"))
        val x = (jsonDouble(body, "urlBarX") ?: 50.0).toFloat()
        val y = (jsonDouble(body, "urlBarY") ?: 6.0).toFloat()
        return json(mapOf("message" to BridgeControl.openDomains(domains, x, y)))
    }

    private fun statusJson(): String = json(
        mapOf(
            "state" to BridgeStatus.state.name,
            "detail" to BridgeStatus.detail,
            "catwebReady" to CatWebTracker.ready,
            "accessibility" to TapService.isConnected(),
            "shizuku" to ShizukuShell.isReady(),
        ),
    )

    private fun logsJson(): String =
        json(mapOf("lines" to RobloxLogBuffer.last(40).map { it.text }))

    private fun tapJson(body: String): String {
        val svc = TapService.instance
            ?: return json(mapOf("error" to "CWBridge Tap (accessibility) not connected"))
        val ok = when (jsonString(body, "mode")) {
            "percent" -> {
                val x = jsonDouble(body, "x") ?: return json(mapOf("error" to "x and y required"))
                val y = jsonDouble(body, "y") ?: return json(mapOf("error" to "x and y required"))
                svc.clickAtPercent(x.toFloat(), y.toFloat())
            }
            "px" -> {
                val x = jsonDouble(body, "x") ?: return json(mapOf("error" to "x and y required"))
                val y = jsonDouble(body, "y") ?: return json(mapOf("error" to "x and y required"))
                svc.clickAt(x.toFloat(), y.toFloat())
            }
            "text" -> {
                val t = jsonString(body, "text")
                if (t.isBlank()) return json(mapOf("error" to "text required"))
                svc.clickByText(t)
            }
            else -> return json(mapOf("error" to "mode must be percent, px or text"))
        }
        return json(mapOf("message" to if (ok) "tap ok" else "tap failed"))
    }

    private fun controlJson(action: String): String {
        val svc = TapService.instance
        val message = when (action) {
            "restart-bridge" -> BridgeControl.restartBridge()
            "restart-roblox" -> BridgeControl.restartRoblox(context)
            "ctrl-t" -> {
                // On mobile CatWeb, Ctrl+T does nothing (PC-only). Use tabs-count → +.
                if (ShizukuShell.isReady() && ShizukuShell.openNewTabByPlusTap())
                    "Opened new tab via CatWeb tabs-count → + (mobile flow)"
                else if (svc != null && svc.pressCtrlT())
                    "Ctrl+T sent (key path — PC CatWeb only)"
                else if (ShizukuShell.isReady() && ShizukuShell.pressCtrlT())
                    "Ctrl+T sent (key path — PC CatWeb only)"
                else
                    "ERROR: mobile new-tab failed — tap the tabs-count button manually once and retry"
            }
            "enter" -> {
                val ok = when {
                    ShizukuShell.isReady() -> ShizukuShell.pressEnter()
                    svc != null -> svc.pressEnter()
                    else -> false
                }
                if (ok) "Enter sent" else "Enter failed"
            }
            "clipboard" -> readClipboard()
            else -> return json(mapOf("error" to "unknown control: $action"))
        }
        return json(mapOf("message" to message))
    }

    private fun readClipboard(): String = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty().take(200)
        } else {
            "(clipboard empty)"
        }
    } catch (t: Throwable) {
        "(clipboard unavailable: ${t.message})"
    }

    // ---- services ---------------------------------------------------------

    private fun servicesListJson(): String {
        val list = ServiceRepository.getAllServices().map { s ->
            mapOf(
                "id" to s.id,
                "name" to s.name,
                "description" to s.description,
                "enabled" to s.isEnabled,
                "triggers" to s.triggers.size,
                "actions" to s.actions.size,
            )
        }
        return json(mapOf("services" to list))
    }

    private fun createService(body: String): String {
        val name = jsonString(body, "name")
        if (name.isBlank()) return json(mapOf("error" to "name required"))
        val created = Service(name = name)
        ServiceRepository.addService(created)
        LogBuffer.i("Server", "web created service '${created.name}' (${created.id})")
        return json(mapOf("message" to "created ${created.name}", "id" to created.id))
    }

    private fun serviceByIdJson(id: String, sub: String, method: String, body: String): String {
        val existing = ServiceRepository.getServiceById(id)
            ?: return json(mapOf("error" to "no such service: $id"))

        if (method == "GET" && sub.isEmpty()) {
            // serviceGson emits _kind on triggers/actions for round-trip edit
            return """{"service":${serviceGson.toJson(existing)}}"""
        }

        if (method == "DELETE" && sub.isEmpty()) {
            ServiceRepository.deleteService(existing.id)
            LogBuffer.i("Server", "web deleted service ${existing.name}")
            return json(mapOf("message" to "deleted ${existing.name}"))
        }

        if (method == "POST" && sub == "run") {
            BridgeControl.runService(existing)
            return json(mapOf("message" to "running ${existing.name}"))
        }

        if (method == "POST" && sub.isEmpty()) {
            val raw = jsonString(body, "json")
            if (raw.isNotBlank()) {
                val parsed = try {
                    serviceGson.fromJson(raw, Service::class.java)
                } catch (t: Throwable) {
                    null
                } ?: return json(mapOf("error" to "bad service JSON"))
                val merged = parsed.copy(id = existing.id)
                ServiceRepository.updateService(merged)
                BridgeControl.reloadTriggers(merged)
                return json(mapOf("message" to "saved ${merged.name}"))
            }

            val enabledField = bodyField(body, "enabled")
            if (enabledField != null && !enabledField.isJsonNull) {
                val updated = existing.copy(isEnabled = enabledField.asBoolean)
                ServiceRepository.updateService(updated)
                BridgeControl.reloadTriggers(updated)
                return json(mapOf("message" to if (updated.isEnabled) "enabled" else "disabled"))
            }
            return json(mapOf("error" to "send {enabled:bool} or {json:'...'}"))
        }

        return json(mapOf("error" to "unknown service route"))
    }

    // ---- storage + quota --------------------------------------------------


    private fun storeKeysJson(query: Map<String, String>): String {
        val domain = query["domain"].orEmpty()
        if (domain.isBlank()) {
            return json(mapOf("error" to "domain required (?domain=name.rbx)"))
        }
        val d = Store.normalizeDomain(domain)
            ?: return json(mapOf("error" to "bad domain '$domain'"))
        val prefix = "$d::"
        val all = com.cwbridge.android.data.UserFileStore.storeAll(context)
        val pairs = all.entries
            .filter { it.key.startsWith(prefix) }
            .map { e -> e.key.removePrefix(prefix) to e.value }
            .sortedBy { it.first }
        val keys = pairs.map { (key, value) ->
            mapOf(
                "key" to key,
                "value" to value,
                "bytes" to value.toByteArray(Charsets.UTF_8).size,
            )
        }
        return json(
            mapOf(
                "domain" to d,
                "keys" to keys,
                "used" to Store.formatBytes(store.usageOf(d)),
                "limit" to Store.formatBytes(store.limitOf(d)),
                "limitBytes" to store.limitOf(d),
            ),
        )
    }

    private fun deleteKey(body: String): String {
        val domain = jsonString(body, "domain")
        val key = jsonString(body, "key")
        return store.remove(domain, key).fold(
            onSuccess = { json(mapOf("message" to "deleted $key from $domain")) },
            onFailure = { json(mapOf("error" to (it.message ?: "delete failed"))) },
        )
    }

    private fun storeJson(): String {
        val domains = store.domains().map { d ->
            mapOf(
                "domain" to d.domain,
                "keys" to d.keyCount,
                "used" to Store.formatBytes(d.usedBytes),
                "usedBytes" to d.usedBytes,
                "limit" to if (d.unlimited) "unlimited" else Store.formatBytes(d.limitBytes),
                "limitBytes" to d.limitBytes,
                "unlimited" to d.unlimited,
            )
        }
        return json(mapOf("domains" to domains))
    }

    private fun saveKey(body: String): String {
        val domain = jsonString(body, "domain")
        val key = jsonString(body, "key")
        val value = bodyField(body, "value")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return store.save(domain, key, value).fold(
            onSuccess = {
                json(
                    mapOf(
                        "message" to "saved $key in $domain (" +
                            Store.formatBytes(store.usageOf(domain)) + " of " +
                            Store.formatBytes(store.limitOf(domain)) + ")",
                    ),
                )
            },
            onFailure = { json(mapOf("error" to (it.message ?: "save failed"))) },
        )
    }

    private fun clearDomain(body: String): String {
        val domain = jsonString(body, "domain")
        return store.clearDomain(domain).fold(
            onSuccess = { json(mapOf("message" to "cleared $it keys from $domain")) },
            onFailure = { json(mapOf("error" to (it.message ?: "clear failed"))) },
        )
    }



    private fun adminDomainJson(): String {
        val d = com.cwbridge.android.data.UserFileStore.getSetting(context, "admin_domain", "") ?: ""
        return json(mapOf("adminDomain" to d))
    }

    private fun setAdminDomainJson(body: String): String {
        val raw = jsonString(body, "adminDomain").ifBlank { jsonString(body, "domain") }.trim().lowercase()
        if (raw.isBlank()) {
            com.cwbridge.android.data.UserFileStore.putSetting(context, "admin_domain", "")
            return json(mapOf("ok" to true, "adminDomain" to "", "message" to "cleared"))
        }
        val norm = Store.normalizeDomain(raw)
            ?: return json(mapOf("error" to "bad domain — want name.rbx"))
        com.cwbridge.android.data.UserFileStore.putSetting(context, "admin_domain", norm)
        return json(mapOf("ok" to true, "adminDomain" to norm))
    }

    private fun rateLimitsJson(query: Map<String, String>): String {
        val domain = query["domain"]
        val snap = DatastoreRateLimit.snapshot(context, domain).toMutableMap()
        snap["defaultLimitBytes"] = store.defaultLimitBytes()
        snap["defaultLimit"] = Store.formatBytes(store.defaultLimitBytes())
        return json(snap)
    }

    private fun setRateLimitsJson(body: String): String {
        val g = jsonString(body, "globalPerDay").toIntOrNull()
        val d = jsonString(body, "domainPerDay").toIntOrNull()
        val defRaw = jsonString(body, "defaultLimit")
        var touched = false
        if (g != null || d != null) {
            DatastoreRateLimit.setLimits(context, g, d)
            touched = true
        }
        if (defRaw.isNotBlank()) {
            val bytes = if (defRaw.equals("unlimited", true) || defRaw == "0") 0L
            else Store.parseSize(defRaw)
                ?: return json(mapOf("error" to "bad defaultLimit '$defRaw' — try 1GB, 500MB"))
            store.setDefaultLimitBytes(bytes).getOrElse {
                return json(mapOf("error" to (it.message ?: "failed")))
            }
            touched = true
        }
        if (!touched) {
            return json(mapOf("error" to "globalPerDay, domainPerDay, and/or defaultLimit required"))
        }
        return json(
            mapOf(
                "ok" to true,
                "globalPerDay" to DatastoreRateLimit.globalLimit(context),
                "domainPerDay" to DatastoreRateLimit.domainLimit(context),
                "defaultLimitBytes" to store.defaultLimitBytes(),
                "defaultLimit" to Store.formatBytes(store.defaultLimitBytes()),
            ),
        )
    }

    private fun setLimit(body: String): String {
        val domain = jsonString(body, "domain")
        val raw = jsonString(body, "limit")
        if (raw.equals("unlimited", ignoreCase = true) || raw == "0") {
            return store.setLimit(domain, 0L).fold(
                onSuccess = { json(mapOf("message" to "$domain is now unlimited")) },
                onFailure = { json(mapOf("error" to (it.message ?: "failed"))) },
            )
        }
        val bytes = Store.parseSize(raw)
            ?: return json(mapOf("error" to "bad limit '$raw' — try 500KB, 2MB or 1GB"))
        return store.setLimit(domain, bytes).fold(
            onSuccess = { json(mapOf("message" to "$domain limited to ${Store.formatBytes(bytes)}")) },
            onFailure = { json(mapOf("error" to (it.message ?: "failed"))) },
        )
    }

    // ---- json helpers -----------------------------------------------------

    private fun json(map: Map<String, Any?>): String = gson.toJson(map)

    private fun parseBody(body: String): JsonObject? = try {
        if (body.isBlank()) null else JsonParser.parseString(body).asJsonObject
    } catch (_: Throwable) {
        null
    }

    private fun bodyField(body: String, name: String): JsonElement? = parseBody(body)?.get(name)

    private fun jsonString(body: String, name: String): String =
        bodyField(body, name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun jsonDouble(body: String, name: String): Double? =
        bodyField(body, name)?.takeIf { !it.isJsonNull }?.asDouble

    private fun cookie(headers: Map<String, String>, name: String): String? {
        val raw = headers["cookie"] ?: return null
        return raw.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    // ---- writing ----------------------------------------------------------

    private fun respond(
        out: OutputStream,
        code: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        cookie: String? = null,
    ) {
        respondBytes(out, code, body.toByteArray(Charsets.UTF_8), contentType, cookie)
    }

    /** Raw bytes, used for the PNG screenshot endpoint. */
    private fun respondBytes(
        out: OutputStream,
        code: Int,
        bytes: ByteArray,
        contentType: String,
        cookie: String? = null,
    ) {
        try {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("Content-Length: ").append(bytes.size).append("\r\n")
            sb.append("Cache-Control: no-store\r\n")
            sb.append("X-Content-Type-Options: nosniff\r\n")
            if (cookie != null) sb.append("Set-Cookie: ").append(cookie).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
        } catch (_: Throwable) {
            // Client vanished mid-response; nothing useful to do.
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
