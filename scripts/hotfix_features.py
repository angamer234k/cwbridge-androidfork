#!/usr/bin/env python3
"""
Unified 250/day/domain request budget (editable via web + admin setlimit).
weather (cost 2), exists/alive (free), keys (cost 1).
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_rate_limit():
    p = ROOT / "app/src/main/java/com/cwbridge/android/data/DatastoreRateLimit.kt"
    t = p.read_text()

    t2 = t.replace(
        "const val DEFAULT_GLOBAL_PER_DAY = 500",
        "const val DEFAULT_GLOBAL_PER_DAY = 0 // 0 = unlimited global; domain budget is the real cap",
    )
    t2 = t2.replace(
        "const val DEFAULT_DOMAIN_PER_DAY = 200",
        "const val DEFAULT_DOMAIN_PER_DAY = 250",
    )
    if t2 != t:
        t = t2
        print("RateLimit: defaults global=0 domain=250")
    else:
        print("RateLimit: defaults already patched or different")

    old_sig = "    fun checkAndConsume(ctx: Context, domain: String): String? {"
    if "cost: Int" in t and "fun checkAndConsume" in t:
        print("RateLimit: checkAndConsume already has cost")
    elif old_sig in t:
        start = t.find(old_sig)
        rest = t[start + len(old_sig):]
        end_rel = rest.find("\n    fun snapshot")
        if end_rel < 0:
            end_rel = rest.find("\n    fun ")
        if end_rel < 0:
            raise SystemExit("cannot find end of checkAndConsume")
        new_fn = '''    /**
     * @param cost request weight (weather=2, save/load/keys=1, exists/alive=0).
     * @return null if allowed; "RATELIMIT" if blocked.
     * Domain budget only (default 250/day). Global limit optional (0 = off).
     * limit 0 = unlimited for that scope. Editable via web + admin setlimit type 0.
     */
    fun checkAndConsume(ctx: Context, domain: String, cost: Int = 1): String? {
        if (cost <= 0) return null
        UserFileStore.init(ctx)
        val day = dayKey()
        val gLim = globalLimit(ctx)
        val dNorm = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
        val dLim = domainLimit(ctx, dNorm)

        val gKey = "rate_count_global_$day"
        val dKey = "rate_count_domain_${dNorm}_$day"

        val gUsed = UserFileStore.getSetting(ctx, gKey, "0")?.toIntOrNull() ?: 0
        val dUsed = UserFileStore.getSetting(ctx, dKey, "0")?.toIntOrNull() ?: 0

        if (gLim > 0 && gUsed + cost > gLim) {
            return "RATELIMIT global $gUsed/$gLim per day"
        }
        if (dLim > 0 && dUsed + cost > dLim) {
            return "RATELIMIT domain $dNorm $dUsed/$dLim per day"
        }

        UserFileStore.putSetting(ctx, gKey, (gUsed + cost).toString())
        UserFileStore.putSetting(ctx, dKey, (dUsed + cost).toString())
        return null
    }
'''
        t = t[:start] + new_fn + rest[end_rel:]
        print("RateLimit: checkAndConsume(cost)")
    else:
        print("RateLimit: checkAndConsume pattern miss")

    t = t.replace(
        "Daily caps on datastore save/load actions.",
        "Daily request budget per domain (default 250). Weighted costs; editable via web/admin.",
    )

    p.write_text(t)
    print("DatastoreRateLimit", p.stat().st_size)


def patch_invoke():
    p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
    t = p.read_text()

    if "import okhttp3.OkHttpClient" not in t:
        t = t.replace(
            "import kotlinx.coroutines.launch",
            "import kotlinx.coroutines.launch\n"
            "import kotlinx.coroutines.withContext\n"
            "import okhttp3.OkHttpClient\n"
            "import okhttp3.Request\n"
            "import org.json.JSONObject\n"
            "import com.cwbridge.android.TapService\n"
            "import com.cwbridge.android.ShizukuShell",
            1,
        )
        lines = t.splitlines(True)
        seen = set()
        out = []
        for line in lines:
            s = line.strip()
            if s.startswith("import ") and s in seen:
                continue
            if s.startswith("import "):
                seen.add(s)
            out.append(line)
        t = "".join(out)
        print("Invoke: http/json imports")

    if "private val httpClient" not in t:
        t = t.replace(
            "private val store = Store(context)",
            "private val store = Store(context)\n"
            "    private val httpClient = OkHttpClient()\n",
            1,
        )
        print("Invoke: httpClient field")

    for old, new in [
        (
            '"cmds: save load storeinfo setlimit status tap tappx paste clip focus submit wait toast echo help"',
            '"cmds: save load storeinfo setlimit weather exists alive keys status tap paste help"',
        ),
        (
            '"cmds: save load status tap tappx paste clip focus submit wait toast echo help"',
            '"cmds: save load storeinfo setlimit weather exists alive keys status tap paste help"',
        ),
    ]:
        if old in t:
            t = t.replace(old, new, 1)
            print("Invoke: cmds banner")
            break

    store_block_start = t.find('"storeinfo"')
    if store_block_start > 0:
        chunk = t[store_block_start:store_block_start + 900]
        if "checkAndConsume" not in chunk:
            old_si = """                val domain = Store.normalizeDomain(domainRaw)
                    ?: run {
                        replyErr("storeinfo", "need storeinfo.domain.rbx")
                        return
                    }
                val usedBytes = store.usageOf(domain)"""
            new_si = """                val domain = Store.normalizeDomain(domainRaw)
                    ?: run {
                        replyErr("storeinfo", "need storeinfo.domain.rbx")
                        return
                    }
                val rl = DatastoreRateLimit.checkAndConsume(context, domain, cost = 1)
                if (rl != null) {
                    replyErr("storeinfo", rl)
                    return
                }
                val usedBytes = store.usageOf(domain)"""
            if old_si in t:
                t = t.replace(old_si, new_si, 1)
                print("Invoke: storeinfo costs 1")
            else:
                print("Invoke: storeinfo inject miss")

    if '"weather"' in t:
        print("Invoke: weather already present")
    else:
        marker = '            "status" -> {'
        if marker not in t:
            marker = '            "savedomain" -> {'
        handlers = r'''
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

'''
        t = t.replace(marker, handlers + marker, 1)
        print("Invoke: weather exists alive keys")

    if "fun resolveWeatherPoint" not in t:
        helpers = r'''
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

'''
        anchor = "    private fun replyOk"
        if anchor not in t:
            anchor = "    private fun replyErr"
        if anchor not in t:
            raise SystemExit("replyOk not found")
        t = t.replace(anchor, helpers + anchor, 1)
        print("Invoke: weather helpers")

    p.write_text(t)
    print("InvokeEngine", p.stat().st_size)


def patch_web():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()
    n = 0
    if 'placeholder="200"' in t:
        t = t.replace('placeholder="200"', 'placeholder="250"', 1)
        n += 1
        print("WebUi: domain req placeholder 250")
    if "Request caps count save/load" in t:
        t = t.replace(
            "Request caps count save/load actions (0 = unlimited).",
            "Request budget per domain (default 250/day; weather costs 2). Editable here or admin setlimit type 0. 0 = unlimited.",
            1,
        )
        n += 1
        print("WebUi: limits hint")
    p.write_text(t)
    print("WebUi", p.stat().st_size, "edits", n)


def main():
    patch_rate_limit()
    patch_invoke()
    patch_web()
    print("hotfix weather/budget OK")


if __name__ == "__main__":
    main()
