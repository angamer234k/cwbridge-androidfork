package com.cwbridge.android.data

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
