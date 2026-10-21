package com.cwbridge.helper

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thin client for a CWBridge device's local HTTP API (same-LAN, no password). */
class BridgeClient(baseUrl: String) {
    private val root = baseUrl.trimEnd('/') + "/"
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Status(
        val state: String,
        val detail: String,
        val catwebReady: Boolean,
        val accessibility: Boolean,
        val shizuku: Boolean,
        val versionName: String,
    )

    data class ServiceItem(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val triggers: Int,
        val actions: Int,
    )

    fun status(): Status {
        val o = getJson("api/status")
        return Status(
            state = o.optString("state", "?"),
            detail = o.optString("detail", ""),
            catwebReady = o.optBoolean("catwebReady"),
            accessibility = o.optBoolean("accessibility"),
            shizuku = o.optBoolean("shizuku"),
            versionName = o.optString("versionName", "?"),
        )
    }

    fun logs(limit: Int = 40): List<String> {
        val arr = getJson("api/logs").optJSONArray("lines") ?: JSONArray()
        val out = ArrayList<String>(arr.length())
        val start = (arr.length() - limit).coerceAtLeast(0)
        for (i in start until arr.length()) out.add(arr.optString(i))
        return out
    }

    fun services(): List<ServiceItem> {
        val arr = getJson("api/services").optJSONArray("services") ?: JSONArray()
        val out = ArrayList<ServiceItem>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            out.add(
                ServiceItem(
                    id = s.optString("id"),
                    name = s.optString("name"),
                    enabled = s.optBoolean("enabled"),
                    triggers = s.optInt("triggers"),
                    actions = s.optInt("actions"),
                ),
            )
        }
        return out
    }

    fun toggleBridge(): String =
        postJson("api/bridge/toggle").optString("message", "ok")

    fun control(action: String): String {
        val o = getJson("api/control/$action")
        return o.optString("message").ifBlank { o.optString("error", "ok") }
    }

    fun invoke(command: String): String {
        val body = JSONObject().put("command", command).toString()
        val o = postJson("api/invoke", body)
        return o.optString("message").ifBlank { o.optString("error", "ok") }
    }

    fun runService(id: String): String =
        postJson("api/services/${id}/run").optString("message", "ok")

    fun screenshotJpeg(): ByteArray {
        val req = Request.Builder()
            .url(root + "api/screenshot?fmt=jpeg&q=50")
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("screenshot ${res.code}")
            return res.body?.bytes() ?: error("empty screenshot")
        }
    }

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder().url(root + path).get().build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("${res.code}: $text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun postJson(path: String, jsonBody: String = "{}"): JSONObject {
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(root + path).post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("${res.code}: $text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
