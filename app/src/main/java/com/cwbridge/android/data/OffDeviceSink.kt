package com.cwbridge.android.data

import android.content.Context
import com.cwbridge.android.bridge.LogBuffer
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Copies request data off the device.
 * Webhook: any HTTP(S) POST. Discord webhook URLs get a short message.
 * Google Sheet: an Apps Script web app URL (…/exec), not the spreadsheet link.
 */
object OffDeviceSink {

    const val SCOPE_SAVE = "save"
    const val SCOPE_ALL = "all"

    private const val VALUE_CAP = 24_000

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cw-sink").apply { isDaemon = true }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun enabled(context: Context): Boolean =
        UserFileStore.getSetting(context, "sink_on", "0") == "1"

    fun scope(context: Context): String {
        val s = UserFileStore.getSetting(context, "sink_scope", SCOPE_SAVE)
            ?.trim()?.lowercase().orEmpty()
        return if (s == SCOPE_ALL) SCOPE_ALL else SCOPE_SAVE
    }

    fun webhook(context: Context): String =
        UserFileStore.getSetting(context, "sink_webhook", "")?.trim().orEmpty()

    fun sheet(context: Context): String =
        UserFileStore.getSetting(context, "sink_sheet", "")?.trim().orEmpty()

    /** Datastore write. No-op when the sink is off. */
    fun onSave(context: Context, domain: String, key: String, value: String) {
        val app = context.applicationContext
        if (!enabled(app)) return
        io.execute {
            try {
                if (!enabled(app)) return@execute
                deliver(
                    app,
                    "save",
                    mapOf("domain" to domain, "key" to key, "value" to value),
                )
            } catch (t: Throwable) {
                LogBuffer.w("Sink", "save mirror: ${t.message ?: "failed"}")
            }
        }
    }

    /** Non-save invoke. Only when scope is "all" (saves go through [onSave]). */
    fun onInvoke(context: Context, request: String, arg1: String, arg2: String) {
        val app = context.applicationContext
        if (!enabled(app) || scope(app) != SCOPE_ALL) return
        if (request == "save" || request == "sink") return
        io.execute {
            try {
                if (!enabled(app) || scope(app) != SCOPE_ALL) return@execute
                deliver(app, request, mapOf("arg1" to arg1, "arg2" to arg2))
            } catch (t: Throwable) {
                LogBuffer.w("Sink", "invoke mirror: ${t.message ?: "failed"}")
            }
        }
    }

    /** Blocking test post. Returns a short status, or throws. */
    fun test(context: Context): String =
        deliver(context.applicationContext, "test", mapOf("note" to "cwbridge sink test"))

    private fun deliver(context: Context, event: String, fields: Map<String, String>): String {
        val hook = checkUrl(webhook(context), "webhook")
        val sheetUrl = checkUrl(sheet(context), "sheet")
        if (hook.isEmpty() && sheetUrl.isEmpty()) error("set a webhook or sheet URL")
        val secret = UserFileStore.getSetting(context, "sink_secret", "")?.trim().orEmpty()
        val body = payload(event, fields)
        val parts = ArrayList<String>(2)
        if (hook.isNotEmpty()) {
            val raw = if (isDiscord(hook)) discordBody(body) else body.toString()
            parts += "webhook " + postJson(hook, raw, secret)
        }
        if (sheetUrl.isNotEmpty()) {
            parts += "sheet " + postJson(sheetUrl, body.toString(), secret)
        }
        val msg = parts.joinToString(" · ")
        LogBuffer.i("Sink", "$event $msg")
        return msg
    }

    private fun payload(event: String, fields: Map<String, String>): JSONObject {
        val value = fields["value"].orEmpty()
        val clipped = if (value.length > VALUE_CAP) value.take(VALUE_CAP) else value
        return JSONObject()
            .put("source", "cwbridge")
            .put("event", event)
            .put("time", Instant.now().toString())
            .put("domain", fields["domain"].orEmpty())
            .put("key", fields["key"].orEmpty())
            .put("value", clipped)
            .put("truncated", value.length > VALUE_CAP)
            .put("arg1", fields["arg1"].orEmpty())
            .put("arg2", fields["arg2"].orEmpty())
            .put("note", fields["note"].orEmpty())
    }

    private fun discordBody(p: JSONObject): String {
        val line = buildString {
            append("CWBridge ").append(p.optString("event"))
            val key = p.optString("key")
            val domain = p.optString("domain")
            if (key.isNotEmpty()) append(" ").append(key)
            if (domain.isNotEmpty()) append(" @ ").append(domain)
            val value = p.optString("value")
            val note = p.optString("note")
            if (value.isNotEmpty()) append("\n").append(value.take(1500))
            if (note.isNotEmpty()) append("\n").append(note.take(200))
        }.take(1900)
        return JSONObject().put("content", line).toString()
    }

    private fun postJson(url: String, body: String, secret: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "CWBridge/2.17")
            .header("Content-Type", "application/json")
            .apply {
                if (secret.isNotEmpty()) header("X-CWBridge-Token", secret)
            }
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code !in 200..299) {
                val brief = resp.body?.string().orEmpty().take(120).replace('\n', ' ')
                error("HTTP $code $brief")
            }
            return "HTTP $code"
        }
    }

    private fun checkUrl(raw: String, kind: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return ""
        val lower = url.lowercase()
        if (kind == "sheet" && lower.contains("docs.google.com/spreadsheet")) {
            error("sheet URL must be an Apps Script /exec link, not the spreadsheet")
        }
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            error("$kind URL must start with http:// or https://")
        }
        return url
    }

    private fun isDiscord(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("discord.com/api/webhooks/") || u.contains("discordapp.com/api/webhooks/")
    }
}
