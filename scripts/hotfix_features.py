#!/usr/bin/env python3
"""Domain DB editor API/UI + invoke|save.key.value[.domain.rbx]."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_invoke() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
    t = p.read_text()
    if "splitOptionalDomain" in t:
        print("invoke already")
        return

    old = '''            "save" -> {
                // save.key.data  OR  save.key.data with domain in key as domain/key
                // Wire: save.<key>.<data>  → default domain local.rbx
                //       save.<domain>.<key> not used — user asked save.key.data
                if (data1.isEmpty()) {
                    replyErr("save", "need save.key.data")
                    return
                }
                val result = store.saveDefault(data1, data2)
                result.fold(
                    onSuccess = { replyOk("save", "$data1 stored (${data2.length} chars)") },
                    onFailure = { replyErr("save", it.message ?: "fail") },
                )
            }'''

    new = '''            "save" -> {
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

    if old not in t:
        raise SystemExit("save block not found in InvokeEngine")
    t = t.replace(old, new, 1)

    helper = '''
    /** If raw ends with .name.rbx, peel domain; else default local.rbx. */
    private fun splitOptionalDomain(raw: String): Pair<String, String> {
        val m = Regex("""^(.*)\\.([a-z0-9_-]+\\.rbx)$""", RegexOption.IGNORE_CASE).matchEntire(raw.trim())
        return if (m != null) {
            m.groupValues[1] to m.groupValues[2].lowercase()
        } else {
            raw to Store.DEFAULT_DOMAIN
        }
    }

'''
    t = t.replace("    private suspend fun dispatch", helper + "    private suspend fun dispatch", 1)
    p.write_text(t)
    print("InvokeEngine OK")

def patch_server() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()

    if "/api/store/keys" not in t:
        old = '''            path == "/api/store" && method == "GET" -> respond(out, 200, storeJson())

            path == "/api/store" && method == "POST" -> respond(out, 200, saveKey(body))

            path == "/api/store/clear" && method == "POST" -> respond(out, 200, clearDomain(body))'''
        new = '''            path == "/api/store" && method == "GET" -> respond(out, 200, storeJson())

            path == "/api/store/keys" && method == "GET" -> respond(out, 200, storeKeysJson(query))

            path == "/api/store" && method == "POST" -> respond(out, 200, saveKey(body))

            path == "/api/store/delete" && method == "POST" -> respond(out, 200, deleteKey(body))

            path == "/api/store/clear" && method == "POST" -> respond(out, 200, clearDomain(body))'''
        if old not in t:
            raise SystemExit("store routes block not found")
        t = t.replace(old, new, 1)
        print("routes OK")

    if "fun storeKeysJson" not in t:
        # query is Map<String, String>
        helpers = '''
    private fun storeKeysJson(query: Map<String, String>): String {
        val domain = query["domain"].orEmpty()
        if (domain.isBlank()) {
            return json(mapOf("error" to "domain required (?domain=name.rbx)"))
        }
        val d = Store.normalizeDomain(domain)
            ?: return json(mapOf("error" to "bad domain '$domain'"))
        val prefix = "$d::"
        val all = com.cwbridge.android.data.UserFileStore.storeAll(context)
        val keys = all.entries
            .filter { it.key.startsWith(prefix) }
            .map { e ->
                val key = e.key.removePrefix(prefix)
                mapOf(
                    "key" to key,
                    "value" to e.value,
                    "bytes" to e.value.toByteArray(Charsets.UTF_8).size,
                )
            }
            .sortedBy { it["key"] as String }
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

'''
        if "private fun storeJson" not in t:
            raise SystemExit("storeJson missing")
        t = t.replace("    private fun storeJson", helpers + "    private fun storeJson", 1)
        print("helpers OK")

    p.write_text(t)

def patch_webui() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()

    if 'id="editDomain"' not in t:
        card = '''  <div class="card">
    <h2>Edit domain database</h2>
    <p class="hint">Pick a domain, load keys, edit values, save or delete. Same store as invoke|save.</p>
    <div class="row">
      <div class="field"><input id="editDomain" list="domainListDatalist" placeholder="example.rbx"></div>
      <button type="button" onclick="loadDomainDb()">Load</button>
    </div>
    <datalist id="domainListDatalist"></datalist>
    <div id="domainDbMeta" class="hint"></div>
    <div id="domainDbRows" style="margin-top:8px;overflow:auto;max-height:320px"></div>
    <div class="row" style="margin-top:8px">
      <div class="field"><input id="newKey" placeholder="new key"></div>
      <div class="field"><input id="newVal" placeholder="value"></div>
      <button type="button" onclick="addDomainKey()">Add</button>
    </div>
    <div id="domainDbMsg" class="msg"></div>
  </div>

'''
        # Prefer before store / quotas section
        inserted = False
        for marker in (
            '  <div class="card">\n    <h2>Store</h2>',
            '  <div class="card">\n    <h2>Domain storage</h2>',
            '  <div class="card">\n    <h2>Quotas</h2>',
            '  <div class="card">\n    <h2>Open domains</h2>',
        ):
            if marker in t:
                t = t.replace(marker, card + marker, 1)
                inserted = True
                print("card at", marker.split("<h2>")[1][:20])
                break
        if not inserted:
            # fuzzy: any h2 Store
            if "<h2>Store</h2>" in t:
                t = t.replace("<h2>Store</h2>", card + "<h2>Store</h2>", 1)
                inserted = True
            else:
                t = t.replace("</body>", card + "</body>", 1)
                print("card at body end")

    if "async function loadDomainDb" not in t:
        js = r'''
async function loadDomainDb(){
  var d = (D("editDomain").value || "").trim();
  if(!d){ msg("domainDbMsg", "enter a domain", false); return; }
  try {
    var r = await get("/api/store/keys?domain=" + encodeURIComponent(d));
    if(r.error){ msg("domainDbMsg", r.error, false); return; }
    D("domainDbMeta").textContent = (r.domain || d) + " — " + (r.used || "?") + " / " + (r.limit || "?");
    var keys = r.keys || [];
    if(!keys.length){
      D("domainDbRows").innerHTML = "<p class=\"hint\">No keys yet.</p>";
    } else {
      D("domainDbRows").innerHTML = keys.map(function(k, i){
        var key = k.key || "";
        var val = k.value || "";
        var id = "kv_" + i;
        return "<div class=\"row\" style=\"margin-bottom:6px;align-items:flex-start\">" +
          "<div class=\"field\" style=\"flex:0 0 28%\"><label>" + esc(key) + "</label></div>" +
          "<div class=\"field\" style=\"flex:1\"><textarea id=\"" + id + "\" rows=\"2\">" + esc(val) + "</textarea></div>" +
          "<button type=\"button\" onclick=\"saveDomainKey(" + JSON.stringify(key) + ",'" + id + "')\">Save</button>" +
          "<button type=\"button\" class=\"danger\" onclick=\"deleteDomainKey(" + JSON.stringify(key) + ")\">Del</button></div>";
      }).join("");
    }
    msg("domainDbMsg", keys.length + " key(s)", true);
  } catch(e){
    msg("domainDbMsg", String(e), false);
  }
}
async function saveDomainKey(key, inputId){
  var d = (D("editDomain").value || "").trim();
  var el = D(inputId);
  var val = el ? el.value : "";
  try {
    var r = await post("/api/store", {domain: d, key: key, value: val});
    msg("domainDbMsg", r.message || r.error || "saved", !r.error);
    if(!r.error) loadDomainDb();
  } catch(e){ msg("domainDbMsg", String(e), false); }
}
async function deleteDomainKey(key){
  var d = (D("editDomain").value || "").trim();
  if(!confirm("Delete " + key + " from " + d + "?")) return;
  try {
    var r = await post("/api/store/delete", {domain: d, key: key});
    msg("domainDbMsg", r.message || r.error || "deleted", !r.error);
    if(!r.error) loadDomainDb();
  } catch(e){ msg("domainDbMsg", String(e), false); }
}
async function addDomainKey(){
  var d = (D("editDomain").value || "").trim();
  var key = (D("newKey").value || "").trim();
  var val = D("newVal").value || "";
  if(!d || !key){ msg("domainDbMsg", "domain and key required", false); return; }
  try {
    var r = await post("/api/store", {domain: d, key: key, value: val});
    msg("domainDbMsg", r.message || r.error || "added", !r.error);
    if(!r.error){ D("newKey").value = ""; D("newVal").value = ""; loadDomainDb(); }
  } catch(e){ msg("domainDbMsg", String(e), false); }
}
async function refreshDomainDatalist(){
  try {
    var s = await get("/api/store");
    var list = D("domainListDatalist");
    if(!list) return;
    list.innerHTML = (s.domains || []).map(function(x){
      return "<option value=\"" + esc(x.domain) + "\">";
    }).join("");
  } catch(e){}
}
'''
        if "async function loadAutoDomain" in t:
            t = t.replace("async function loadAutoDomain", js + "\nasync function loadAutoDomain", 1)
        elif "</script>" in t:
            t = t.replace("</script>", js + "\n</script>", 1)
        print("js OK")

    if "refreshDomainDatalist()" not in t:
        if "loadAutoDomain()" in t:
            t = t.replace("loadAutoDomain()", "loadAutoDomain(); refreshDomainDatalist()", 1)
        elif "try{loadAutoDomain()}" in t:
            t = t.replace("try{loadAutoDomain()}catch(e){}", "try{loadAutoDomain();refreshDomainDatalist()}catch(e){}", 1)
        print("boot datalist")

    p.write_text(t)
    print("WebUi OK")

def main() -> None:
    patch_invoke()
    patch_server()
    patch_webui()
    print("done")

if __name__ == "__main__":
    main()
