#!/usr/bin/env python3
"""Add GET/POST /api/auto-domain + web card to edit CW-load domain."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_server() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()
    if "/api/auto-domain" not in t:
        old = 'path == "/api/domains" && method == "POST" -> respond(out, 200, openDomainsJson(body))'
        new = (
            'path == "/api/domains" && method == "POST" -> respond(out, 200, openDomainsJson(body))\n'
            '            path == "/api/auto-domain" && method == "GET" -> respond(out, 200, getAutoDomainJson())\n'
            '            path == "/api/auto-domain" && method == "POST" -> respond(out, 200, setAutoDomainJson(body))'
        )
        if old not in t:
            raise SystemExit("domains route not found")
        t = t.replace(old, new, 1)
        print("routes added")
    else:
        print("routes ok")

    if "fun getAutoDomainJson" not in t:
        helpers = (
            "\n"
            "    private fun getAutoDomainJson(): String {\n"
            "        val domains = BridgeControl.loadAutoOpenDomains(context)\n"
            "        val one = domains.firstOrNull().orEmpty()\n"
            "        return json(\n"
            "            mapOf(\n"
            "                \"domain\" to one,\n"
            "                \"domains\" to domains,\n"
            "            ),\n"
            "        )\n"
            "    }\n"
            "\n"
            "    private fun setAutoDomainJson(body: String): String {\n"
            "        val raw = jsonString(body, \"domain\").ifBlank {\n"
            "            jsonString(body, \"domains\")\n"
            "        }\n"
            "        val one = raw.lines()\n"
            "            .flatMap { it.split(\",\", \";\") }\n"
            "            .map { it.trim() }\n"
            "            .firstOrNull { it.isNotEmpty() }\n"
            "            .orEmpty()\n"
            "        BridgeControl.saveAutoOpenDomains(\n"
            "            context,\n"
            "            if (one.isEmpty()) emptyList() else listOf(one),\n"
            "        )\n"
            "        LogBuffer.i(\"Server\", \"auto-open domain set to '${one.ifEmpty { \"(cleared)\" }}'\")\n"
            "        return json(\n"
            "            mapOf(\n"
            "                \"ok\" to true,\n"
            "                \"domain\" to one,\n"
            "                \"message\" to if (one.isEmpty()) \"auto-open cleared\" else \"auto-open set to $one\",\n"
            "            ),\n"
            "        )\n"
            "    }\n"
            "\n"
        )
        if "private fun openDomainsJson" not in t:
            raise SystemExit("openDomainsJson missing")
        t = t.replace("    private fun openDomainsJson", helpers + "    private fun openDomainsJson", 1)
        print("helpers added")
    p.write_text(t)

def patch_webui() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()

    if 'id="autoDomain"' not in t:
        card = (
            "  <div class=\"card\">\n"
            "    <h2>Auto-open on CW load</h2>\n"
            "    <p class=\"hint\">Single domain only. When CatWeb logs finished, opens this in the current tab. Empty = off.</p>\n"
            "    <div class=\"row\">\n"
            "      <div class=\"field\"><input id=\"autoDomain\" placeholder=\"67.rbx\" autocomplete=\"off\"></div>\n"
            "      <button type=\"button\" onclick=\"saveAutoDomain()\">Save</button>\n"
            "      <button type=\"button\" class=\"ghost\" onclick=\"clearAutoDomain()\">Clear</button>\n"
            "    </div>\n"
            "    <div id=\"autoDomMsg\" class=\"msg\"></div>\n"
            "  </div>\n"
            "\n"
        )
        needle = "  <div class=\"card\">\n    <h2>Open domains</h2>"
        if needle not in t:
            raise SystemExit("Open domains card not found")
        t = t.replace(needle, card + needle, 1)
        print("card inserted")

    if "async function loadAutoDomain" not in t:
        js = (
            "\n"
            "async function loadAutoDomain(){\n"
            "  try {\n"
            "    var r = await get(\"/api/auto-domain\");\n"
            "    if (r.domain != null) D(\"autoDomain\").value = r.domain || \"\";\n"
            "  } catch (e) {}\n"
            "}\n"
            "async function saveAutoDomain(){\n"
            "  var d = (D(\"autoDomain\").value || \"\").trim();\n"
            "  try {\n"
            "    var r = await post(\"/api/auto-domain\", {domain: d});\n"
            "    msg(\"autoDomMsg\", r.message || (\"saved \" + d), !r.error);\n"
            "    if (r.domain != null) D(\"autoDomain\").value = r.domain || \"\";\n"
            "  } catch (e) {\n"
            "    msg(\"autoDomMsg\", String(e), false);\n"
            "  }\n"
            "}\n"
            "async function clearAutoDomain(){\n"
            "  D(\"autoDomain\").value = \"\";\n"
            "  return saveAutoDomain();\n"
            "}\n"
            "\n"
        )
        if "async function sendInvoke" in t:
            t = t.replace("async function sendInvoke", js + "async function sendInvoke", 1)
        elif "async function openDomains()" in t:
            t = t.replace("async function openDomains()", js + "async function openDomains()", 1)
        else:
            raise SystemExit("no JS anchor")
        print("js added")

    if "loadAutoDomain()" not in t:
        if "loadServices()" in t:
            t = t.replace("loadServices()", "loadServices(); loadAutoDomain()", 1)
        elif "</script>" in t:
            t = t.replace("</script>", "try{loadAutoDomain()}catch(e){}\n</script>", 1)
        print("boot load")

    if "function get(" not in t and "async function get(" not in t:
        get_fn = (
            "async function get(path){\n"
            "  var r = await fetch(path, {credentials:\"same-origin\"});\n"
            "  return r.json();\n"
            "}\n"
        )
        if "function post(path, body)" in t:
            t = t.replace("function post(path, body)", get_fn + "function post(path, body)", 1)
        elif "async function post(" in t:
            t = t.replace("async function post(", get_fn + "async function post(", 1)
        print("get helper")

    p.write_text(t)
    print("WebUi done")

def main() -> None:
    patch_server()
    patch_webui()
    print("done")

if __name__ == "__main__":
    main()
