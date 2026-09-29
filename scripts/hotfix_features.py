#!/usr/bin/env python3
"""Add GET/POST /api/auto-domain + web card to edit CW-load domain."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_server() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    if "/api/auto-domain" in t:
        print("auto-domain route already")
    else:
        t = t.replace(
            'path == "/api/domains" && method == "POST" -> respond(out, 200, openDomainsJson(body))',
            'path == "/api/domains" && method == "POST" -> respond(out, 200, openDomainsJson(body))\n'\n            '            path == "/api/auto-domain" && method == "GET" -> respond(out, 200, getAutoDomainJson())\n'\n            '            path == "/api/auto-domain" && method == "POST" -> respond(out, 200, setAutoDomainJson(body))',
            1,
        )
        print("routes added")

    if "fun getAutoDomainJson" not in t:
        helpers = r'''
    private fun getAutoDomainJson(): String {
        val domains = BridgeControl.loadAutoOpenDomains(context)
        val one = domains.firstOrNull().orEmpty()
        return json(
            mapOf(
                "domain" to one,
                "domains" to domains,
            ),
        )
    }

    private fun setAutoDomainJson(body: String): String {
        // Single domain only (multi-tab not supported)
        val raw = jsonString(body, "domain").ifBlank {
            jsonString(body, "domains")
        }
        val one = raw.lines()
            .flatMap { it.split(",", ";") }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        BridgeControl.saveAutoOpenDomains(
            context,
            if (one.isEmpty()) emptyList() else listOf(one),
        )
        LogBuffer.i("Server", "auto-open domain set to '${one.ifEmpty { "(cleared)" }}'")
        return json(
            mapOf(
                "ok" to true,
                "domain" to one,
                "message" to if (one.isEmpty()) "auto-open cleared" else "auto-open set to $one",
            ),
        )
    }

'''
        # insert before openDomainsJson
        if "private fun openDomainsJson" in t:
            t = t.replace(
                "    private fun openDomainsJson",
                helpers + "    private fun openDomainsJson",
                1,
            )
            print("helpers added")
        else:
            print("WARN: openDomainsJson not found")
    p.write_text(t)

def patch_webui() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()
    if "id=\"autoDomain\"" in t or "id='autoDomain'" in t:
        print("auto domain card already")
    else:
        card = '''  <div class="card">
    <h2>Auto-open on CW load</h2>
    <p class="hint">Single domain only. When CatWeb logs finished, the bridge opens this in the current tab (OCR URL bar). Empty = off.</p>
    <div class="row">
      <div class="field"><input id="autoDomain" placeholder="67.rbx" autocomplete="off"></div>
      <button type="button" onclick="saveAutoDomain()">Save</button>
      <button type="button" class="ghost" onclick="clearAutoDomain()">Clear</button>
    </div>
    <div id="autoDomMsg" class="msg"></div>
  </div>

'''
        needle = '  <div class="card">\n    <h2>Open domains</h2>'
        if needle in t:
            t = t.replace(needle, card + needle, 1)
            print("card inserted")
        else:
            print("WARN: Open domains card not found")

    if "async function loadAutoDomain" not in t:
        js = r'''
async function loadAutoDomain(){
  try {
    var r = await get("/api/auto-domain");
    if (r.domain != null) D("autoDomain").value = r.domain || "";
  } catch (e) {}
}
async function saveAutoDomain(){
  var d = (D("autoDomain").value || "").trim();
  try {
    var r = await post("/api/auto-domain", {domain: d});
    msg("autoDomMsg", r.message || ("saved " + d), !r.error);
    if (r.domain != null) D("autoDomain").value = r.domain || "";
  } catch (e) {
    msg("autoDomMsg", String(e), false);
  }
}
async function clearAutoDomain(){
  D("autoDomain").value = "";
  return saveAutoDomain();
}
'''
        # after openDomains function or near other async functions
        if "async function openDomains()" in t:
            # append js after openDomains block - find a safe anchor
            if "async function sendInvoke" in t:
                t = t.replace("async function sendInvoke", js + "\nasync function sendInvoke", 1)
                print("js helpers added")
            else:
                t = t.replace("async function openDomains()", js + "\nasync function openDomains()", 1)
                print("js before openDomains")
        else:
            print("WARN: openDomains fn missing")

    # boot load on page open
    if "loadAutoDomain()" not in t:
        if "loadServices()" in t:
            t = t.replace("loadServices()", "loadServices(); loadAutoDomain()", 1)
            print("boot loadAutoDomain")
        elif "refreshStatus()" in t:
            t = t.replace("refreshStatus()", "refreshStatus(); loadAutoDomain()", 1)
            print("boot via refreshStatus")
        else:
            # try end of script boot
            if "DOMContentLoaded" in t:
                t = t.replace(
                    "DOMContentLoaded",
                    "DOMContentLoaded",  # no-op marker
                    1,
                )
            # inject before closing script
            if "</script>" in t:
                t = t.replace(
                    "</script>",
                    "try{loadAutoDomain()}catch(e){}\n</script>",
                    1,
                )
                print("boot at script end")

    # ensure get() helper exists - many WebUi have post and get
    if "function get(" not in t and "async function get(" not in t:
        # add simple get next to post
        if "function post(path, body)" in t:
            t = t.replace(
                "function post(path, body)",
                "async function get(path){\n  var r = await fetch(path, {credentials:\"same-origin\"});\n  return r.json();\n}\nfunction post(path, body)",
                1,
            )
            print("get() helper added")
        elif "async function post(" in t:
            t = t.replace(
                "async function post(",
                "async function get(path){\n  var r = await fetch(path, {credentials:\"same-origin\"});\n  return r.json();\n}\nasync function post(",
                1,
            )
            print("get() helper added (async)")

    p.write_text(t)
    print("WebUi done")

def main() -> None:
    patch_server()
    patch_webui()
    print("done")

if __name__ == "__main__":
    main()
