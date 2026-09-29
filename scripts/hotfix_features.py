#!/usr/bin/env python3
"""load pastes actual key value; save/load daily rate limits → RATELIMIT."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RATE_KT = r'''package com.cwbridge.android.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Daily caps on datastore save/load actions.
 * Exceeding → callers should surface "RATELIMIT".
 */
object DatastoreRateLimit {
    const val DEFAULT_GLOBAL_PER_DAY = 500
    const val DEFAULT_DOMAIN_PER_DAY = 200

    private fun dayKey(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }

    fun globalLimit(ctx: Context): Int =
        UserFileStore.getSetting(ctx, "rate_limit_global_day", DEFAULT_GLOBAL_PER_DAY.toString())
            ?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_GLOBAL_PER_DAY

    fun domainLimit(ctx: Context): Int =
        UserFileStore.getSetting(ctx, "rate_limit_domain_day", DEFAULT_DOMAIN_PER_DAY.toString())
            ?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_DOMAIN_PER_DAY

    fun setLimits(ctx: Context, globalPerDay: Int?, domainPerDay: Int?) {
        globalPerDay?.let {
            UserFileStore.putSetting(ctx, "rate_limit_global_day", it.coerceAtLeast(0).toString())
        }
        domainPerDay?.let {
            UserFileStore.putSetting(ctx, "rate_limit_domain_day", it.coerceAtLeast(0).toString())
        }
    }

    /**
     * @return null if allowed (and count incremented); "RATELIMIT" message if blocked.
     * limit 0 = unlimited for that scope.
     */
    fun checkAndConsume(ctx: Context, domain: String): String? {
        UserFileStore.init(ctx)
        val day = dayKey()
        val gLim = globalLimit(ctx)
        val dLim = domainLimit(ctx)

        val gKey = "rate_count_global_$day"
        val dNorm = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
        val dKey = "rate_count_domain_${dNorm}_$day"

        val gUsed = UserFileStore.getSetting(ctx, gKey, "0")?.toIntOrNull() ?: 0
        val dUsed = UserFileStore.getSetting(ctx, dKey, "0")?.toIntOrNull() ?: 0

        if (gLim > 0 && gUsed >= gLim) {
            return "RATELIMIT global $gUsed/$gLim per day"
        }
        if (dLim > 0 && dUsed >= dLim) {
            return "RATELIMIT domain $dNorm $dUsed/$dLim per day"
        }

        UserFileStore.putSetting(ctx, gKey, (gUsed + 1).toString())
        UserFileStore.putSetting(ctx, dKey, (dUsed + 1).toString())
        return null
    }

    fun snapshot(ctx: Context, domain: String? = null): Map<String, Any> {
        val day = dayKey()
        val gLim = globalLimit(ctx)
        val dLim = domainLimit(ctx)
        val gUsed = UserFileStore.getSetting(ctx, "rate_count_global_$day", "0")?.toIntOrNull() ?: 0
        val out = mutableMapOf<String, Any>(
            "day" to day,
            "globalUsed" to gUsed,
            "globalLimit" to gLim,
            "domainLimit" to dLim,
        )
        if (domain != null) {
            val d = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
            val dUsed = UserFileStore.getSetting(ctx, "rate_count_domain_${d}_$day", "0")?.toIntOrNull() ?: 0
            out["domain"] = d
            out["domainUsed"] = dUsed
        }
        return out
    }
}
'''

def write_rate() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/data/DatastoreRateLimit.kt"
    p.write_text(RATE_KT)
    print("DatastoreRateLimit.kt written")

def patch_invoke() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
    t = p.read_text()

    if "import com.cwbridge.android.data.DatastoreRateLimit" not in t:
        t = t.replace(
            "import com.cwbridge.android.data.Store",
            "import com.cwbridge.android.data.DatastoreRateLimit\nimport com.cwbridge.android.data.Store",
            1,
        )

    # Replace save block to rate-limit
    old_save = '''            "save" -> {
                // save.<key>.<value>
                // save.<key>.<value>.<domain.rbx>  (domain optional → local.rbx)
                if (data1.isEmpty()) {
                    replyErr("save", "need save.key.value[.domain.rbx]")
                    return
                }
                val (value, domain) = splitOptionalDomain(data2)
                val result = store.save(domain, data1, value)
                result.fold(
                    onSuccess = { replyOk("save", "$data1 → $domain (${value.length} chars)") },
                    onFailure = { replyErr("save", it.message ?: "fail") },
                )
            }'''
    new_save = '''            "save" -> {
                // save.<key>.<value>
                // save.<key>.<value>.<domain.rbx>  (domain optional → local.rbx)
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
            }'''
    if old_save in t:
        t = t.replace(old_save, new_save, 1)
        print("save rate-limited")
    elif "DatastoreRateLimit.checkAndConsume" in t and '"save"' in t:
        print("save already has rate limit?")
    else:
        print("WARN: save block not matched")

    # Replace load: paste actual value; errors to game; rate limit
    old_load = '''            "load" -> {
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
            }'''
    new_load = '''            "load" -> {
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
                            is NoSuchElementException -> "NOTFOUND ${'$'}{e.message}"
                            else -> e.message ?: "fail"
                        }
                        replyErr("load", msg)
                    },
                )
            }'''
    # Fix accidental python-style dollar in the template — use real kotlin
    new_load = new_load.replace("${'$'}{e.message}", "${e.message}")

    if old_load in t:
        t = t.replace(old_load, new_load, 1)
        print("load pastes + rate limit")
    else:
        # try softer match
        if "pasteIntoGame" in t:
            print("load already patched")
        else:
            print("WARN: load block not matched")
            # show nearby
            i = t.find('"load" ->')
            if i > 0:
                print(repr(t[i:i+400]))

    if "private suspend fun pasteIntoGame" not in t:
        helper = '''
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

'''
        if "private suspend fun runPasteSequence" in t:
            t = t.replace(
                "    private suspend fun runPasteSequence",
                helper + "    private suspend fun runPasteSequence",
                1,
            )
            print("pasteIntoGame added")
        else:
            print("WARN: runPasteSequence missing")

    p.write_text(t)

def patch_server_rate_api() -> None:
    """Optional GET/POST /api/rate-limits for web panel."""
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    if "/api/rate-limits" in t:
        print("rate API already")
        return

    if "import com.cwbridge.android.data.DatastoreRateLimit" not in t:
        t = t.replace(
            "import com.cwbridge.android.data.Store",
            "import com.cwbridge.android.data.DatastoreRateLimit\nimport com.cwbridge.android.data.Store",
            1,
        )

    # add routes near store
    needle = 'path == "/api/limits" && method == "POST" -> respond(out, 200, setLimit(body))'
    if needle in t:
        t = t.replace(
            needle,
            needle + "\n\n"
            '            path == "/api/rate-limits" && method == "GET" -> respond(out, 200, rateLimitsJson(query))\n'
            '            path == "/api/rate-limits" && method == "POST" -> respond(out, 200, setRateLimitsJson(body))',
            1,
        )
        print("rate routes")

    if "fun rateLimitsJson" not in t:
        helpers = '''
    private fun rateLimitsJson(query: Map<String, String>): String {
        val domain = query["domain"]
        return json(DatastoreRateLimit.snapshot(context, domain))
    }

    private fun setRateLimitsJson(body: String): String {
        val g = jsonString(body, "globalPerDay").toIntOrNull()
        val d = jsonString(body, "domainPerDay").toIntOrNull()
        if (g == null && d == null) {
            return json(mapOf("error" to "globalPerDay and/or domainPerDay required"))
        }
        DatastoreRateLimit.setLimits(context, g, d)
        return json(
            mapOf(
                "ok" to true,
                "globalPerDay" to DatastoreRateLimit.globalLimit(context),
                "domainPerDay" to DatastoreRateLimit.domainLimit(context),
            ),
        )
    }

'''
        if "private fun setLimit(body: String)" in t:
            t = t.replace("    private fun setLimit(body: String)", helpers + "    private fun setLimit(body: String)", 1)
            print("rate helpers")

    p.write_text(t)

def main() -> None:
    write_rate()
    patch_invoke()
    patch_server_rate_api()
    print("done")

if __name__ == "__main__":
    main()
