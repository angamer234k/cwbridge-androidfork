package com.cwbridge.android.engine

import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible GET /v1/models, plus pricing when the provider sends it.
 * If the provider list has no prices, ids are matched against the public
 * OpenRouter catalog (per-token figures converted to USD per 1M tokens).
 */
object AiCatalog {

    data class Row(
        val id: String,
        val ownedBy: String,
        val inPerM: Double?,
        val outPerM: Double?,
        val priceSource: String,
    )

    private const val CATALOG_URL = "https://openrouter.ai/api/v1/models"
    private const val CATALOG_TTL_MS = 15 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    @Volatile private var catalogAt: Long = 0L
    @Volatile private var catalog: Map<String, Pair<Double?, Double?>> = emptyMap()

    fun modelsUrl(baseUrl: String): String {
        val root = baseUrl.trim().trimEnd('/')
        return when {
            root.endsWith("/models") -> root
            root.endsWith("/v1") -> "$root/models"
            root.endsWith("/v1/chat/completions") ->
                root.removeSuffix("/chat/completions") + "/models"
            root.endsWith("/v1/responses") ->
                root.removeSuffix("/responses") + "/models"
            else -> "$root/v1/models"
        }
    }

    fun fetch(baseUrl: String, token: String): List<Row> {
        if (baseUrl.isBlank()) error("configure AI URL in web panel")
        val body = httpGet(modelsUrl(baseUrl), token)
        val rows = parseModels(body)
        if (rows.isEmpty()) error("no models in /models response")
        if (rows.any { it.inPerM != null || it.outPerM != null }) {
            return rows.map { if (it.priceSource == "none" && (it.inPerM != null || it.outPerM != null)) it.copy(priceSource = "provider") else it }
        }
        val prices = openRouterPrices()
        if (prices.isEmpty()) return rows
        return rows.map { row ->
            val hit = matchPrice(row.id, prices) ?: return@map row
            row.copy(inPerM = hit.first, outPerM = hit.second, priceSource = "catalog")
        }
    }

    fun find(rows: List<Row>, model: String): Row? {
        val want = model.trim()
        if (want.isEmpty()) return null
        return rows.firstOrNull { it.id.equals(want, ignoreCase = true) }
            ?: rows.firstOrNull { it.id.endsWith("/$want", ignoreCase = true) }
    }

    fun formatPrice(row: Row): String {
        if (row.inPerM == null && row.outPerM == null) return "${row.id} no-price"
        val inn = row.inPerM?.let { money(it) } ?: "?"
        val out = row.outPerM?.let { money(it) } ?: "?"
        return "${row.id} in=$inn out=$out per1M ${row.priceSource}"
    }

    fun money(n: Double): String {
        val s = if (n >= 1.0) String.format(Locale.US, "%.2f", n)
        else String.format(Locale.US, "%.4f", n)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    private fun parseModels(body: String): List<Row> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        val out = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) continue
            val (inn, outP) = readPrices(obj)
            val source = if (inn != null || outP != null) "provider" else "none"
            out += Row(
                id = id,
                ownedBy = obj.optString("owned_by", ""),
                inPerM = inn,
                outPerM = outP,
                priceSource = source,
            )
        }
        return out
    }

    private fun readPrices(obj: JSONObject): Pair<Double?, Double?> {
        val pricing = obj.optJSONObject("pricing")
        if (pricing != null) {
            val inn = firstNum(pricing, "prompt", "input", "input_per_million", "prompt_per_million")
            val out = firstNum(pricing, "completion", "output", "output_per_million", "completion_per_million")
            if (inn != null || out != null) {
                return scale(pricing, "prompt", inn) to scale(pricing, "completion", out)
            }
        }
        val inn = firstNum(obj, "input_price", "prompt_price")
        val out = firstNum(obj, "output_price", "completion_price")
        return inn to out
    }

    private fun firstNum(obj: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val n = when (val v = obj.get(k)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (n != null && n >= 0.0) return n
        }
        return null
    }

    /** OpenRouter quotes USD per token (tiny). A figure under 0.001 is treated as per-token. */
    private fun scale(parent: JSONObject, keyHint: String, n: Double?): Double? {
        if (n == null) return null
        val perM = parent.keys().asSequence().any { k ->
            val lk = k.lowercase()
            lk.contains("million") || lk.contains("per_1m") || lk.contains("1m")
        }
        if (perM) return n
        if (keyHint.isNotEmpty() && n > 0.0 && n < 0.001) return n * 1_000_000.0
        return n
    }

    private fun openRouterPrices(): Map<String, Pair<Double?, Double?>> {
        val now = System.currentTimeMillis()
        val cached = catalog
        if (cached.isNotEmpty() && now - catalogAt < CATALOG_TTL_MS) return cached
        return try {
            val body = httpGet(CATALOG_URL, "")
            val parsed = parseModels(body)
            val map = HashMap<String, Pair<Double?, Double?>>(parsed.size * 2)
            for (row in parsed) {
                if (row.inPerM == null && row.outPerM == null) continue
                map[row.id.lowercase()] = row.inPerM to row.outPerM
            }
            catalog = map
            catalogAt = now
            map
        } catch (_: Throwable) {
            cached
        }
    }

    private fun matchPrice(id: String, prices: Map<String, Pair<Double?, Double?>>): Pair<Double?, Double?>? {
        val key = id.lowercase()
        prices[key]?.let { return it }
        val slash = prices.entries.firstOrNull { it.key.endsWith("/$key") }
        return slash?.value
    }

    private fun httpGet(url: String, token: String): String {
        val b = Request.Builder().url(url).get().header("User-Agent", "CWBridge/2.17")
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${body.take(160).replace('\n', ' ')}")
            }
            if (body.isBlank()) error("empty /models body")
            return body
        }
    }
}
