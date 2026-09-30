#!/usr/bin/env python3
"""storeinfo paste + admin setlimit + per-domain request limits."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_rate_limit():
    p = ROOT / "app/src/main/java/com/cwbridge/android/data/DatastoreRateLimit.kt"
    t = p.read_text()

    old = """    fun domainLimit(ctx: Context): Int =
        UserFileStore.getSetting(ctx, "rate_limit_domain_day", DEFAULT_DOMAIN_PER_DAY.toString())
            ?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_DOMAIN_PER_DAY

    fun setLimits(ctx: Context, globalPerDay: Int?, domainPerDay: Int?) {
        globalPerDay?.let {
            UserFileStore.putSetting(ctx, "rate_limit_global_day", it.coerceAtLeast(0).toString())
        }
        domainPerDay?.let {
            UserFileStore.putSetting(ctx, "rate_limit_domain_day", it.coerceAtLeast(0).toString())
        }
    }"""

    new = """    fun domainLimit(ctx: Context): Int =
        UserFileStore.getSetting(ctx, "rate_limit_domain_day", DEFAULT_DOMAIN_PER_DAY.toString())
            ?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_DOMAIN_PER_DAY

    /** Per-domain override; falls back to global domain default. 0 = unlimited. */
    fun domainLimit(ctx: Context, domain: String): Int {
        val d = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
        val override = UserFileStore.getSetting(ctx, "rate_limit_for_$d", null)
            ?.toIntOrNull()
        if (override != null) return override.coerceAtLeast(0)
        return domainLimit(ctx)
    }

    fun setDomainRequestLimit(ctx: Context, domain: String, perDay: Int) {
        val d = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
        UserFileStore.putSetting(ctx, "rate_limit_for_$d", perDay.coerceAtLeast(0).toString())
    }

    fun setLimits(ctx: Context, globalPerDay: Int?, domainPerDay: Int?) {
        globalPerDay?.let {
            UserFileStore.putSetting(ctx, "rate_limit_global_day", it.coerceAtLeast(0).toString())
        }
        domainPerDay?.let {
            UserFileStore.putSetting(ctx, "rate_limit_domain_day", it.coerceAtLeast(0).toString())
        }
    }"""

    if "fun domainLimit(ctx: Context, domain: String)" in t:
        print("RateLimit: per-domain already present")
    elif old in t:
        t = t.replace(old, new, 1)
        print("RateLimit: per-domain limit helpers")
    else:
        raise SystemExit("RateLimit domainLimit block not found")

    old_c = """        val gLim = globalLimit(ctx)
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
        }"""
    new_c = """        val gLim = globalLimit(ctx)
        val dNorm = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
        val dLim = domainLimit(ctx, dNorm)

        val gKey = "rate_count_global_$day"
        val dKey = "rate_count_domain_${dNorm}_$day"

        val gUsed = UserFileStore.getSetting(ctx, gKey, "0")?.toIntOrNull() ?: 0
        val dUsed = UserFileStore.getSetting(ctx, dKey, "0")?.toIntOrNull() ?: 0

        if (gLim > 0 && gUsed >= gLim) {
            return "RATELIMIT global $gUsed/$gLim per day"
        }
        if (dLim > 0 && dUsed >= dLim) {
            return "RATELIMIT domain $dNorm $dUsed/$dLim per day"
        }"""
    if old_c in t:
        t = t.replace(old_c, new_c, 1)
        print("RateLimit: checkAndConsume uses per-domain")
    else:
        print("RateLimit: checkAndConsume skip")

    old_s = """        if (domain != null) {
            val d = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
            val dUsed = UserFileStore.getSetting(ctx, "rate_count_domain_${d}_$day", "0")?.toIntOrNull() ?: 0
            out["domain"] = d
            out["domainUsed"] = dUsed
        }"""
    new_s = """        if (domain != null) {
            val d = Store.normalizeDomain(domain) ?: domain.lowercase().trim()
            val dUsed = UserFileStore.getSetting(ctx, "rate_count_domain_${d}_$day", "0")?.toIntOrNull() ?: 0
            val dLimEff = domainLimit(ctx, d)
            out["domain"] = d
            out["domainUsed"] = dUsed
            out["domainLimit"] = dLimEff
            out["domainLeft"] = if (dLimEff <= 0) -1 else (dLimEff - dUsed).coerceAtLeast(0)
        }"""
    if old_s in t:
        t = t.replace(old_s, new_s, 1)
        print("RateLimit: snapshot per-domain")
    else:
        print("RateLimit: snapshot skip")

    p.write_text(t)
    print("DatastoreRateLimit", p.stat().st_size)


def patch_invoke():
    p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
    t = p.read_text()

    if "import com.cwbridge.android.data.UserFileStore" not in t:
        t = t.replace(
            "import com.cwbridge.android.data.Store",
            "import com.cwbridge.android.data.Store\nimport com.cwbridge.android.data.UserFileStore",
            1,
        )
        print("Invoke: import UserFileStore")

    old_cmds = '"cmds: save load status tap tappx paste clip focus submit wait toast echo help"'
    new_cmds = '"cmds: save load storeinfo setlimit status tap tappx paste clip focus submit wait toast echo help"'
    if old_cmds in t:
        t = t.replace(old_cmds, new_cmds, 1)

    if '"storeinfo"' in t:
        print("Invoke: storeinfo already present")
    else:
        marker = '            "status" -> {'
        if marker not in t:
            marker = '            "savedomain" -> {'
        if marker not in t:
            raise SystemExit("no status/savedomain marker")

        handlers = r'''
            "storeinfo" -> {
                // storeinfo.<domain.rbx> → paste: 5.STORED_BITS.LIMIT_BITS.KEYS.REQ_USED.REQ_MAX.REQ_LEFT
                val domainRaw = listOf(data1, data2).filter { it.isNotEmpty() }.joinToString(".")
                val domain = Store.normalizeDomain(domainRaw)
                    ?: run {
                        replyErr("storeinfo", "need storeinfo.domain.rbx")
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

'''
        t = t.replace(marker, handlers + marker, 1)
        print("Invoke: storeinfo + setlimit handlers")

    old_help = (
        '"save.key.data | load.key.domain | status | tap.x.y | tappx.x.y | " +\n'
        '                        "paste.text | clip.set.text | clip.get | focus.x.y | submit.x.y | " +\n'
        '                        "wait.ms | toast.msg | echo.msg | help",'
    )
    new_help = (
        '"save.key.data | load.key.domain | storeinfo.domain | setlimit.domain.type.val | " +\n'
        '                        "status | tap.x.y | paste.text | clip | focus | submit | wait | toast | echo | help",'
    )
    if old_help in t:
        t = t.replace(old_help, new_help, 1)
        print("Invoke: help updated")

    p.write_text(t)
    print("InvokeEngine", p.stat().st_size)


def patch_server_and_web():
    sp = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    st = sp.read_text()

    if "/api/admin-domain" not in st:
        route = '''
            path == "/api/admin-domain" && method == "GET" -> respond(out, 200, adminDomainJson())
            path == "/api/admin-domain" && method == "POST" -> respond(out, 200, setAdminDomainJson(body))
'''
        anchor = 'path == "/api/rate-limits" && method == "POST" -> respond(out, 200, setRateLimitsJson(body))'
        if anchor in st:
            st = st.replace(anchor, anchor + "\n" + route, 1)
            print("Server: admin-domain routes")
        else:
            print("Server: rate-limits anchor miss")

        handlers = '''
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
'''
        if "fun adminDomainJson" not in st:
            mark = "    private fun rateLimitsJson"
            if mark in st:
                st = st.replace(mark, handlers + "\n" + mark, 1)
                print("Server: admin handlers")
            else:
                print("Server: rateLimitsJson mark miss")
    else:
        print("Server: admin-domain already present")

    sp.write_text(st)
    print("LocalHttpServer", sp.stat().st_size)

    wp = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    wt = wp.read_text()
    if "limAdminDomain" in wt:
        print("WebUi: admin field already present")
    else:
        needle = '<div id="limitsMeta" class="hint" style="margin-top:8px"></div>'
        extra = '''<div class="row" style="margin-top:10px">
      <div class="field"><label class="hint">Admin domain (can setlimit)</label>
        <input id="limAdminDomain" placeholder="admin.rbx"></div>
      <button type="button" class="ghost" onclick="saveAdminDomain()"><span class="ms sm">shield</span> Save admin</button>
    </div>
    <p class="hint">Game: invoke|storeinfo.domain.rbx → pastes 5.bits.limitBits.keys.used.max.left · invoke|setlimit.domain.rbx.type.value (0=reqs/day 1=data bits)</p>
    ''' + needle
        if needle in wt:
            wt = wt.replace(needle, extra, 1)
            print("WebUi: admin domain field")
        else:
            print("WebUi: limitsMeta miss")

    if "async function loadAdminDomain" not in wt and "function loadAdminDomain" not in wt:
        js = r'''
async function loadAdminDomain(){
  try{
    const j=await api('/api/admin-domain');
    const el=document.getElementById('limAdminDomain');
    if(el) el.value=j.adminDomain||'';
  }catch(e){}
}
async function saveAdminDomain(){
  const el=document.getElementById('limAdminDomain');
  const v=(el&&el.value||'').trim();
  try{
    const j=await post('/api/admin-domain',{adminDomain:v});
    if(j.error){ toast(j.error,false); return; }
    toast(j.message||('Admin: '+(j.adminDomain||'off')),true);
    loadAdminDomain();
  }catch(e){ toast(e.message||String(e),false); }
}
'''
        if "async function loadLimits()" in wt:
            wt = wt.replace("async function loadLimits()", js + "\nasync function loadLimits()", 1)
            print("WebUi: admin JS")
        else:
            print("WebUi: loadLimits miss")

    if "loadAdminDomain();" not in wt and "loadLimits();" in wt:
        wt = wt.replace("loadLimits();", "loadLimits(); loadAdminDomain();", 1)
        print("WebUi: boot loadAdminDomain")

    wp.write_text(wt)
    print("WebUi", wp.stat().st_size)


def main():
    patch_rate_limit()
    patch_invoke()
    patch_server_and_web()
    print("hotfix storeinfo OK")


if __name__ == "__main__":
    main()
