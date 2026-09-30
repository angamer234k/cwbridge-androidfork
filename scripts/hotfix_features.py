#!/usr/bin/env python3
"""Add Limits panel + 1GB default storage + rate-limit UI."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_store():
    p = ROOT / "app/src/main/java/com/cwbridge/android/data/Store.kt"
    t = p.read_text()
    old = "const val DEFAULT_LIMIT_BYTES = 1L * 1024 * 1024"
    new = "const val DEFAULT_LIMIT_BYTES = 1L * 1024 * 1024 * 1024 // 1 GiB"
    if old in t:
        t = t.replace(old, new, 1)
        print("Store: default limit → 1GB")
    else:
        print("Store: default already patched or missing")

    old_lim = """    fun limitOf(domain: String): Long {
        val d = normalizeDomain(domain) ?: return DEFAULT_LIMIT_BYTES
        return UserFileStore.limitGet(app, d, DEFAULT_LIMIT_BYTES)
    }"""
    new_lim = """    fun limitOf(domain: String): Long {
        val d = normalizeDomain(domain) ?: return defaultLimitBytes()
        return UserFileStore.limitGet(app, d, defaultLimitBytes())
    }

    fun defaultLimitBytes(): Long {
        val raw = UserFileStore.getSetting(app, "store_default_limit_bytes", null)
        val parsed = raw?.let { parseSize(it) ?: it.toLongOrNull() }
        return parsed?.coerceAtLeast(0L) ?: DEFAULT_LIMIT_BYTES
    }

    fun setDefaultLimitBytes(bytes: Long): Result<Unit> {
        if (bytes < 0) return Result.failure(IllegalArgumentException("limit cannot be negative"))
        val v = if (bytes == 0L) "0" else formatBytes(bytes).replace(" ", "")
        UserFileStore.putSetting(app, "store_default_limit_bytes", if (bytes == 0L) "0" else bytes.toString())
        LogBuffer.i("Store", "default limit = ${formatBytes(bytes)}")
        return Result.success(Unit)
    }"""
    if old_lim in t:
        t = t.replace(old_lim, new_lim, 1)
        print("Store: defaultLimitBytes helpers")
    elif "fun defaultLimitBytes" in t:
        print("Store: helpers already present")
    else:
        raise SystemExit("limitOf block not found")

    p.write_text(t)
    print("Store.kt", p.stat().st_size)


def patch_server():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()

    old_snap = """    private fun rateLimitsJson(query: Map<String, String>): String {
        val domain = query["domain"]
        return json(DatastoreRateLimit.snapshot(context, domain))
    }"""
    new_snap = """    private fun rateLimitsJson(query: Map<String, String>): String {
        val domain = query["domain"]
        val snap = DatastoreRateLimit.snapshot(context, domain).toMutableMap()
        snap["defaultLimitBytes"] = store.defaultLimitBytes()
        snap["defaultLimit"] = Store.formatBytes(store.defaultLimitBytes())
        return json(snap)
    }"""
    if old_snap in t:
        t = t.replace(old_snap, new_snap, 1)
        print("Server: rateLimitsJson enriched")
    else:
        print("Server: rateLimitsJson skip")

    old_set = """    private fun setRateLimitsJson(body: String): String {
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
    }"""
    new_set = """    private fun setRateLimitsJson(body: String): String {
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
    }"""
    if old_set in t:
        t = t.replace(old_set, new_set, 1)
        print("Server: setRateLimitsJson extended")
    else:
        print("Server: setRateLimitsJson skip")

    p.write_text(t)
    print("LocalHttpServer.kt", p.stat().st_size)


def patch_webui():
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()

    old_store = """  <div class="card" id="sec-store">
    <h2><span class="ms sm">database</span> Storage</h2>"""
    limits_card = """  <div class="card" id="sec-limits">
    <h2><span class="ms sm">speed</span> Limits</h2>
    <p class="hint">Storage default applies to domains without a custom limit. Request caps count save/load actions (0 = unlimited).</p>
    <div class="row">
      <div class="field"><label class="hint">Global data default</label>
        <input id="limDefault" placeholder="1GB"></div>
      <div class="field"><label class="hint">Reqs / day (global)</label>
        <input id="limGlobalReq" type="number" min="0" placeholder="500"></div>
      <div class="field"><label class="hint">Reqs / day / domain</label>
        <input id="limDomainReq" type="number" min="0" placeholder="200"></div>
      <button type="button" onclick="saveLimits()"><span class="ms sm">save</span> Save limits</button>
      <button type="button" class="ghost" onclick="loadLimits()"><span class="ms sm">refresh</span> Refresh</button>
    </div>
    <div id="limitsMeta" class="hint" style="margin-top:8px"></div>
    <div id="limitsMsg" class="msg"></div>
  </div>

  <div class="card" id="sec-store">
    <h2><span class="ms sm">database</span> Storage</h2>"""
    if old_store in t and 'id="sec-limits"' not in t:
        t = t.replace(old_store, limits_card, 1)
        print("WebUi: Limits card added")
    elif 'id="sec-limits"' in t:
        print("WebUi: Limits card already present")
    else:
        raise SystemExit("store card not found")

    if "async function loadLimits" not in t:
        js = r"""
async function loadLimits(){
  try{
    const j=await api('/api/rate-limits');
    const def=document.getElementById('limDefault');
    const g=document.getElementById('limGlobalReq');
    const d=document.getElementById('limDomainReq');
    if(def) def.value=j.defaultLimit||'';
    if(g) g.value=(j.globalLimit!=null?j.globalLimit:'');
    if(d) d.value=(j.domainLimit!=null?j.domainLimit:'');
    const meta=document.getElementById('limitsMeta');
    if(meta){
      let s='Today: global '+ (j.globalUsed||0) +'/'+ (j.globalLimit||0);
      if(j.domainUsed!=null) s+=' · domain '+j.domainUsed+'/'+(j.domainLimit||0);
      s+=' · default storage '+ (j.defaultLimit||'?');
      meta.textContent=s;
    }
  }catch(e){ toast(e.message||String(e),false); }
}
async function saveLimits(){
  const body={};
  const def=document.getElementById('limDefault');
  const g=document.getElementById('limGlobalReq');
  const d=document.getElementById('limDomainReq');
  if(def && def.value.trim()) body.defaultLimit=def.value.trim();
  if(g && g.value!=='') body.globalPerDay=parseInt(g.value,10);
  if(d && d.value!=='') body.domainPerDay=parseInt(d.value,10);
  const msg=document.getElementById('limitsMsg');
  try{
    const j=await api('/api/rate-limits',{method:'POST',body:JSON.stringify(body)});
    if(j.error){ if(msg) msg.innerHTML='<span class="bad">'+(j.error||'')+'</span>'; toast(j.error,false); return; }
    if(msg) msg.innerHTML='<span class="ok">Saved</span>';
    toast('Limits saved',true);
    loadLimits();
  }catch(e){ if(msg) msg.innerHTML='<span class="bad">'+(e.message||e)+'</span>'; toast(e.message||String(e),false); }
}
"""
        anchor = "async function loadDomainDb()"
        if anchor in t:
            t = t.replace(anchor, js + "\n" + anchor, 1)
            print("WebUi: loadLimits/saveLimits JS")
        else:
            idx = t.rfind("</script>")
            if idx < 0:
                raise SystemExit("no script end")
            t = t[:idx] + js + "\n" + t[idx:]
            print("WebUi: JS appended before </script>")

    old_ra = "function refreshAll(){\n  refreshStatus(); refreshServices(); refreshStore(); refreshVars(); refreshConsole();\n  loadAutoDomain(); refreshDomainDatalist();\n}"
    new_ra = "function refreshAll(){\n  refreshStatus(); refreshServices(); refreshStore(); refreshVars(); refreshConsole();\n  loadAutoDomain(); refreshDomainDatalist(); loadLimits();\n}"
    if old_ra in t:
        t = t.replace(old_ra, new_ra, 1)
        print("WebUi: refreshAll → loadLimits")
    elif "loadLimits();" in t[t.find("function refreshAll"): t.find("function refreshAll") + 350]:
        print("WebUi: refreshAll already loads limits")
    else:
        print("WebUi: WARN refreshAll pattern miss")

    p.write_text(t)
    print("WebUi.kt", p.stat().st_size)


def main():
    patch_store()
    patch_server()
    patch_webui()
    print("hotfix limits OK")


if __name__ == "__main__":
    main()
