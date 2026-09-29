#!/usr/bin/env python3
"""Serve login-only HTML until session valid; full dash only after auth."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch_webui() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = p.read_text()
    if "fun loginPage" in t:
        print("loginPage already")
        return

    # Append loginPage before closing of object - replace page() block
    old = '''    private val FULL: String by lazy { PART_A + PART_B + PART_C + PART_D + PART_E + PART_F }

    fun page(showScreenshot: Boolean): String = FULL.replace(
        SCREENSHOT_MARKER,
        if (showScreenshot) SCREENSHOT_BUTTON else "",
    )

    private const val SCREENSHOT_MARKER = "<!--SCREENSHOT_BUTTON-->"
    private const val SCREENSHOT_BUTTON =
        """<button type="button" class="ghost" onclick="loadShot()">Screenshot</button>"""
}'''

    new = '''    private val FULL: String by lazy { PART_A + PART_B + PART_C + PART_D + PART_E + PART_F }

    fun page(showScreenshot: Boolean): String = FULL.replace(
        SCREENSHOT_MARKER,
        if (showScreenshot) SCREENSHOT_BUTTON else "",
    )

    /**
     * Login shell only — no dashboard markup, no control JS.
     * Served for unauthenticated clients so inspect-element cannot "unlock" the panel.
     * After /api/login sets the session cookie, client reloads `/` and gets [page].
     */
    fun loginPage(): String = LOGIN_SHELL

    private val LOGIN_SHELL: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark">
<title>CWBridge — Unlock</title>
<style>
:root{--bg:#0c0e12;--panel:#151922;--line:rgba(255,255,255,.08);--text:#eef1f7;--muted:#8b93a7;--accent:#6ea8fe;--danger:#f76c6c;--radius:14px}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,sans-serif;min-height:100%}
main{max-width:420px;margin:12vh auto;padding:16px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:20px}
h2{margin:0 0 8px;font-size:1.15rem}
.hint{color:var(--muted);font-size:13px;margin:0 0 14px}
.row{display:flex;gap:8px;flex-wrap:wrap}
.field{flex:1;min-width:140px}
input{width:100%;padding:10px 12px;border-radius:10px;border:1px solid var(--line);background:#0c0e12;color:var(--text)}
button{padding:10px 16px;border-radius:10px;border:0;background:var(--accent);color:#061018;font-weight:600;cursor:pointer}
.msg{margin-top:10px;font-size:13px;color:var(--danger);min-height:1.2em}
</style>
</head>
<body>
<main>
  <div class="card">
    <h2>Unlock remote access</h2>
    <p class="hint">Enter the password shown in the CWBridge app (Server section). The control panel is not loaded until this succeeds.</p>
    <div class="row">
      <div class="field"><input id="pw" type="password" placeholder="Server password" autocomplete="current-password" autofocus></div>
      <button type="button" id="btn">Unlock</button>
    </div>
    <div id="msg" class="msg"></div>
  </div>
</main>
<script>
(function(){
  var pw = document.getElementById("pw");
  var msg = document.getElementById("msg");
  function unlock(){
    msg.textContent = "";
    fetch("/api/login", {
      method: "POST",
      credentials: "same-origin",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({password: pw.value || ""})
    }).then(function(r){
      return r.json().then(function(j){ return {ok: r.ok, j: j}; });
    }).then(function(x){
      if(!x.ok){
        msg.textContent = (x.j && x.j.error) || "wrong password";
        return;
      }
      // Session cookie set by server — reload SAME path to receive dashboard HTML
      location.reload();
    }).catch(function(e){
      msg.textContent = String(e.message || e);
    });
  }
  document.getElementById("btn").onclick = unlock;
  pw.addEventListener("keydown", function(e){ if(e.key === "Enter") unlock(); });
})();
</script>
</body>
</html>
""".trimIndent()

    private const val SCREENSHOT_MARKER = "<!--SCREENSHOT_BUTTON-->"
    private const val SCREENSHOT_BUTTON =
        """<button type="button" class="ghost" onclick="loadShot()">Screenshot</button>"""
}'''

    if old not in t:
        raise SystemExit("WebUi page() block not found")
    t = t.replace(old, new, 1)
    p.write_text(t)
    print("WebUi.loginPage added")

def patch_server() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = p.read_text()

    # Rewrite auth gate: / gets login shell or dash; APIs still 401
    old = '''        val session = cookie(headers, "CWBridge-Session")
        val presented = query["password"] ?: headers["x-cwbridge-password"]
            ?: jsonString(body, "password").ifBlank { null }
        val denial = ServerAuth.check(context, remote, session, presented)
        if (denial != null) {
            return respond(out, 401, json(mapOf("error" to denial, "needsPassword" to true)))
        }

        if (path == "/api/logout" && method == "POST") {
            ServerAuth.logout(session)
            return respond(
                out, 200, json(mapOf("ok" to true)),
                cookie = "CWBridge-Session=; Path=/; Max-Age=0",
            )
        }

        try {
            dispatch(out, method, path, query, body)
        } catch (t: Throwable) {
            // Never let one bad request kill anything.
            LogBuffer.e("Server", "$method $path -> ${t.message}")
            respond(out, 500, json(mapOf("error" to (t.message ?: t::class.java.simpleName))))
        }'''

    new = '''        val session = cookie(headers, "CWBridge-Session")
        val presented = query["password"] ?: headers["x-cwbridge-password"]
            ?: jsonString(body, "password").ifBlank { null }
        val denial = ServerAuth.check(context, remote, session, presented)
        val authed = denial == null

        // Same path `/`: login shell OR full dashboard — never both in one response.
        // Unauthenticated clients only ever receive the lock page (no dash markup).
        if (path == "/" || path == "/index.html") {
            if (authed) {
                return respond(
                    out, 200,
                    WebUi.page(BridgeControl.screenshotSupported()),
                    "text/html; charset=utf-8",
                )
            }
            return respond(out, 200, WebUi.loginPage(), "text/html; charset=utf-8")
        }

        if (!authed) {
            return respond(out, 401, json(mapOf("error" to denial, "needsPassword" to true)))
        }

        if (path == "/api/logout" && method == "POST") {
            ServerAuth.logout(session)
            return respond(
                out, 200, json(mapOf("ok" to true)),
                cookie = "CWBridge-Session=; Path=/; Max-Age=0",
            )
        }

        try {
            dispatch(out, method, path, query, body)
        } catch (t: Throwable) {
            // Never let one bad request kill anything.
            LogBuffer.e("Server", "$method $path -> ${t.message}")
            respond(out, 500, json(mapOf("error" to (t.message ?: t::class.java.simpleName))))
        }'''

    if old not in t:
        if "WebUi.loginPage()" in t:
            print("server auth gate already")
        else:
            raise SystemExit("route auth block not found")
    else:
        t = t.replace(old, new, 1)
        print("server auth gate rewritten")

    # Remove duplicate / handler from dispatch (now handled in route)
    old_disp = '''            path == "/" || path == "/index.html" ->
                respond(out, 200, WebUi.page(BridgeControl.screenshotSupported()), "text/html; charset=utf-8")

            path == "/favicon.ico"'''
    new_disp = '''            path == "/favicon.ico"'''
    if old_disp in t:
        t = t.replace(old_disp, new_disp, 1)
        print("dispatch / removed")
    else:
        print("WARN: dispatch / not found (maybe already gone)")

    p.write_text(t)

def main() -> None:
    patch_webui()
    patch_server()
    print("done")

if __name__ == "__main__":
    main()
