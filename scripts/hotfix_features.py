#!/usr/bin/env python3
"""Web: edit domain DB; invoke|save.key.value[.domain.rbx] optional domain."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_invoke() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt"
    t = p.read_text()
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
                // save.<key>.<value>.<domain.rbx>  (domain optional; default local.rbx)
                if (data1.isEmpty()) {
                    replyErr("save", "need save.key.value[.domain.rbx]")
                    return
                }
                val (value, domain) = splitOptionalDomain(data2)
                val result = store.save(domain, data1, value)
                result.fold(
                    onSuccess = {
                        replyOk(
                            "save",
                            "$data1 → $domain (${value.length} chars)",
                        )
                    },
                    onFailure = { replyErr("save", it.message ?: "fail") },
                )
            }'''
    if old not in t:
        if "splitOptionalDomain" in t:
            print("invoke save already patched")
        else:
            raise SystemExit("save block not found")
    else:
        t = t.replace(old, new, 1)
        print("invoke save patched")

    if "fun splitOptionalDomain" not in t:
        helper = r'''
    /**
     * If [raw] ends with `.name.rbx`, peel domain off; otherwise default domain.
     * Value may contain dots. Domain is optional on save.
     */
    private fun splitOptionalDomain(raw: String): Pair<String, String> {
        val m = Regex("""^(.*)\.([a-z0-9_-]+\.rbx)$""", RegexOption.IGNORE_CASE).matchEntire(raw.trim())
        return if (m != null) {
            m.groupValues[1] to m.groupValues[2].lowercase()
        } else {
            raw to Store.DEFAULT_DOMAIN
        }
    }

'''
        # before private suspend fun dispatch or at end before last brace of class
        if "private suspend fun dispatch" in t:
            t = t.replace("    private suspend fun dispatch", helper + "    private suspend fun dispatch", 1)
        else:
            idx = t.rfind("}")
            t = t[:idx] + helper + "}\n"
        print("splitOptionalDomain added")

    # help text
    t = t.replace(
        "cmds: save load status tap tappx paste clip focus submit wait toast echo help",
        "cmds: save load status tap tappx paste clip focus submit wait toast echo help | save.key.val[.domain.rbx]",
        1,
    )
    p.write_text(t)

def patch_server() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()

    if "/api/store/keys" not in t:
        old = 'path == "/api/store" && method == "GET" -> respond(out, 200, storeJson())'
        new = (
            'path == "/api/store" && method == "GET" -> respond(out, 200, storeJson())\n'
            '            path == "/api/store/keys" && method == "GET" -> respond(out, 200, storeKeysJson(query))\n'
            '            path == "/api/store/delete" && method == "POST" -> respond(out, 200, deleteKey(body))'
        )
        if old not in t:
            raise SystemExit("store GET route missing")
        t = t.replace(old, new, 1)
        print("store routes added")

    # Need query string parsing - check if query already extracted in handle
    if "storeKeysJson" not in t:
        # Check how request path is parsed
        if "val query" not in t and "queryString" not in t:
            # find path assignment and add query split
            pass

    # helpers
    if "fun storeKeysJson" not in t:
        helpers = r'''
    private fun storeKeysJson(query: String): String {
        val domain = queryParam(query, "domain").ifBlank {
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

    private fun queryParam(query: String, name: String): String {
        if (query.isBlank()) return ""
        for (part in query.split("&")) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = URLDecoder.decode(part.substring(0, eq), "UTF-8")
            if (k == name) {
                return URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }
        }
        return ""
    }

'''
        if "private fun storeJson" in t:
            t = t.replace("    private fun storeJson", helpers + "    private fun storeJson", 1)
            print("store helpers")
        else:
            raise SystemExit("storeJson missing")

    # Wire query into request handler — find path extraction
    if "storeKeysJson(query)" in t and "val query =" not in t:
        # Typical pattern: val path = ...
        # Look for how path is set
        import re
        # common: val path = uri path only
        m = re.search(r'(val path = [^
]+)', t)
        if m:
            line = m.group(1)
            print("path line:", line[:80])
        # Try replace path+query split near request line parse
        # Search for patterns like:
        # val pathOnly = path.substringBefore('?')
        if "substringBefore(\"?\")" not in t and "indexOf('?')" not in t:
            # inject at start of handleClient after reading request line
            # Safer: change the route to parse from full path
            t = t.replace(
                'path == "/api/store/keys" && method == "GET" -> respond(out, 200, storeKeysJson(query))',
                'path.startsWith("/api/store/keys") && method == "GET" -> respond(out, 200, storeKeysJson(path.substringAfter("?", "")))',
                1,
            )
            # But path might already strip query — fix extract
            print("keys route uses path query suffix")

    # Ensure path keeps query or we parse from request target
    # Read how path is computed
    p.write_text(t)
    print("server written")

def patch_server_path_query() -> None:
    """Ensure request path parsing exposes query string for /api/store/keys."""
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    # Find request-target handling
    if "rawTarget" in t or "pathAndQuery" in t:
        print("path query already")
        return
    # Look for typical:
    # val path = parts.getOrNull(1) ...
    import re
    # Pattern used in many NanoHTTPD-like: split request line
    if 'val path =' in t and 'substringBefore' not in t:
        # replace first val path = X with split query
        def repl(m):
            expr = m.group(1).strip()
            return (
                f'val pathAndQuery = {expr}\n'
                f'        val path = pathAndQuery.substringBefore("?")\n'
                f'        val query = pathAndQuery.substringAfter("?", "")'
            )
        t2, n = re.subn(
            r'val path = ([^
]+)',
            repl,
            t,
            count=1,
        )
        if n:
            # fix keys route to use query var
            t2 = t2.replace(
                'path.startsWith("/api/store/keys") && method == "GET" -> respond(out, 200, storeKeysJson(path.substringAfter("?", "")))',
                'path == "/api/store/keys" && method == "GET" -> respond(out, 200, storeKeysJson(query))',
            )
            t2 = t2.replace(
                'path.startsWith("/api/store/keys") && method == "GET" -> respond(out, 200, storeKeysJson(path.substringAfter("?", "")))',
                'path == "/api/store/keys" && method == "GET" -> respond(out, 200, storeKeysJson(query))',
            )
            # if still path.startsWith form from earlier
            if 'storeKeysJson(path.substringAfter' in t2:
                t2 = t2.replace(
                    'path.startsWith("/api/store/keys") && method == "GET" -> respond(out, 200, storeKeysJson(path.substringAfter("?", "")))',
                    'path == "/api/store/keys" && method == "GET" -> respond(out, 200, storeKeysJson(query))',
                )
            p.write_text(t2)
            print("path/query split injected")
        else:
            print("WARN: could not inject path query")
    else:
        print("path handling ok or already split")

def patch_webui() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()

    if "id=\"editDomain\"" in t or 'id="editDomain"' in t:
        print("edit domain UI already")
    else:
        card = r'''  <div class="card">
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
        # insert before Open domains or after store card - prefer near store section
        if '<h2>Domain storage</h2>' in t or '<h2>Store</h2>' in t:
            # find that card start
            for h in ('<h2>Domain storage</h2>', '<h2>Store</h2>', '<h2>Quotas</h2>'):
                if h in t:
                    idx = t.find(h)
                    # back up to <div class="card">
                    start = t.rfind('<div class="card">', 0, idx)
                    if start >= 0:
                        t = t[:start] + card + t[start:]
                        print("card before store")
                        break
        elif '<h2>Open domains</h2>' in t:
            needle = '  <div class="card">\n    <h2>Open domains</h2>'
            if needle in t:
                t = t.replace(needle, card + needle, 1)
                print("card before open domains")
            else:
                t = t.replace('<h2>Open domains</h2>', card + '<h2>Open domains</h2>', 1)
                print("card near open domains")
        else:
            # before closing body
            t = t.replace('</body>', card + '</body>', 1)
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
      D("domainDbRows").innerHTML = keys.map(function(k){
        var key = k.key || "";
        var val = k.value || "";
        var id = "kv_" + key.replace(/[^a-zA-Z0-9_]/g, "_");
        return "<div class=\"row\" style=\"margin-bottom:6px;align-items:flex-start\">" +
          "<div class=\"field\" style=\"flex:0 0 28%\"><label>" + esc(key) + "</label></div>" +
          "<div class=\"field\" style=\"flex:1\"><textarea id=\"" + id + "\" rows=\"2\">" + esc(val) + "</textarea></div>" +
          "<button type=\"button\" onclick=\"saveDomainKey('" + esc(key) + "','" + id + "')\">Save</button>" +
          "<button type=\"button\" class=\"danger\" onclick=\"deleteDomainKey('" + esc(key) + "')\">Del</button></div>";
      }).join("");
    }
    msg("domainDbMsg", keys.length + " key(s)", true);
  } catch(e){
    msg("domainDbMsg", String(e), false);
  }
}
async function saveDomainKey(key, inputId){
  var d = (D("editDomain").value || "").trim();
  var val = D(inputId) ? D(inputId).value : "";
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
    list.innerHTML = (s.domains || []).map(function(d){
      return "<option value=\"" + esc(d.domain) + "\">";
    }).join("");
  } catch(e){}
}
'''
        if "async function loadAutoDomain" in t:
            t = t.replace("async function loadAutoDomain", js + "\nasync function loadAutoDomain", 1)
        elif "async function openDomains" in t:
            t = t.replace("async function openDomains", js + "\nasync function openDomains", 1)
        else:
            t = t.replace("</script>", js + "\n</script>", 1)
        print("domain db js")

    if "refreshDomainDatalist()" not in t:
        if "loadAutoDomain()" in t:
            t = t.replace("loadAutoDomain()", "loadAutoDomain(); refreshDomainDatalist()", 1)
        elif "refreshStore()" in t:
            t = t.replace("refreshStore()", "refreshStore(); refreshDomainDatalist()", 1)
        print("boot datalist")

    p.write_text(t)
    print("WebUi done")

def main() -> None:
    patch_invoke()
    patch_server()
    patch_server_path_query()
    patch_webui()
    print("done")

if __name__ == "__main__":
    main()
