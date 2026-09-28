#!/usr/bin/env python3
"""v2.13 features: mobile UI, diagnose, screenshot detail, roblox deeplink, domains."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(rel: str) -> str:
    return (ROOT / rel).read_text()

def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text)
    print("wrote", rel)

def patch_bridge_control() -> None:
    rel = "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = read(rel)

    old = (
        "            context.packageManager.getLaunchIntentForPackage(packageName)?.let {\n"
        "                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
        "                context.startActivity(it)\n"
        "                \"Roblox restarting\"\n"
        "            } ?: \"Roblox installed but no launch intent\""
    )
    new = (
        "            val launch = context.packageManager.getLaunchIntentForPackage(packageName)\n"
        "            if (launch != null) {\n"
        "                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
        "                context.startActivity(launch)\n"
        "                \"Roblox restarting\"\n"
        "            } else {\n"
        "                LogBuffer.w(\"Control\", \"no launch intent for $packageName — CatWeb deeplink\")\n"
        "                openCatWeb(context)\n"
        "            }"
    )
    if old in t:
        t = t.replace(old, new, 1)
        print("restartRoblox deeplink")
    elif "CatWeb deeplink" in t:
        print("restartRoblox already patched")
    else:
        print("WARN: restartRoblox pattern missing")

    if "fun openDomains" not in t:
        method = '''

    /**
     * Open domains in CatWeb: Ctrl+T, tap URL bar by percent coords, type, Enter.
     * Roblox is OpenGL so the a11y tree is empty. Ends with Ctrl+1 on first tab.
     */
    fun openDomains(
        domains: List<String>,
        urlBarXPct: Float = 50f,
        urlBarYPct: Float = 6f,
        pauseMs: Long = 1000L,
    ): String {
        if (domains.isEmpty()) return "no domains"
        val svc = TapService.instance
        val results = mutableListOf<String>()
        for ((i, raw) in domains.withIndex()) {
            val d = raw.trim()
            if (d.isEmpty()) continue
            LogBuffer.i("Control", "openDomains [${i + 1}/${domains.size}] $d")
            val ctrl = when {
                ShizukuShell.isReady() -> ShizukuShell.pressCtrlT()
                svc != null -> svc.pressCtrlT()
                else -> false
            }
            if (!ctrl) { results += "$d: Ctrl+T failed"; continue }
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            val tapped = svc?.clickAtPercent(urlBarXPct, urlBarYPct) == true
            if (!tapped) { results += "$d: URL-bar tap failed"; continue }
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            val typed = ShizukuShell.isReady() && ShizukuShell.inputText(d)
            if (!typed) { results += "$d: type failed (need Shizuku)"; continue }
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            val enter = when {
                ShizukuShell.isReady() -> ShizukuShell.pressEnter()
                svc != null -> svc.pressEnter()
                else -> false
            }
            results += if (enter) "$d: ok" else "$d: Enter failed"
            try { Thread.sleep(pauseMs) } catch (_: InterruptedException) {}
        }
        val ctrl1 = ShizukuShell.isReady() && ShizukuShell.pressCtrlNumber(1)
        results += if (ctrl1) "Ctrl+1 ok" else "Ctrl+1 failed"
        return results.joinToString("; ")
    }
'''
        t = t.rstrip()
        if t.endswith("}"):
            t = t[:-1] + method + "}\n"
            print("added openDomains")
    write(rel, t)

def patch_shizuku() -> None:
    rel = "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = read(rel)
    if "pressCtrlNumber" in t:
        print("ShizukuShell already patched")
        return
    m = re.search(r"    fun pressCtrlT\(\): Boolean \{.*?\n    \}\n", t, re.S)
    if not m:
        print("WARN: pressCtrlT missing")
        return
    repl = '''    fun pressCtrlT(): Boolean {
        val cmds = listOf(
            "input keycombination 113 48",
            "input keycombination 114 48",
            "input keyevent --longpress 113 48",
        )
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "pressCtrlT cmd=$cmd exit=$code ${out.take(80)}")
            if (code == 0) return true
        }
        return false
    }

    fun pressCtrlNumber(n: Int): Boolean {
        val key = 7 + n.coerceIn(1, 9)
        val cmds = listOf(
            "input keycombination 113 $key",
            "input keycombination 114 $key",
        )
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "pressCtrl+$n cmd=$cmd exit=$code ${out.take(60)}")
            if (code == 0) return true
        }
        return false
    }

'''
    t = t[: m.start()] + repl + t[m.end() :]
    write(rel, t)
    print("ShizukuShell Ctrl improved")

def patch_screenshot() -> None:
    rel = "app/src/main/java/com/cwbridge/android/TapService.kt"
    t = read(rel)
    old = (
        "                    override fun onFailure(errorCode: Int) {\n"
        "                        exec.shutdown()\n"
        "                        LogBuffer.w(\"A11y\", \"takeScreenshot failed code=$errorCode\")\n"
        "                        callback(null)\n"
        "                    }"
    )
    new = (
        "                    override fun onFailure(errorCode: Int) {\n"
        "                        exec.shutdown()\n"
        "                        val why = when (errorCode) {\n"
        "                            1 -> \"INTERNAL_ERROR\"\n"
        "                            2 -> \"NO_ACCESSIBILITY_ACCESS\"\n"
        "                            3 -> \"INTERVAL_TOO_SHORT\"\n"
        "                            4 -> \"INVALID_DISPLAY\"\n"
        "                            5 -> \"INVALID_WINDOW\"\n"
        "                            else -> \"code=$errorCode\"\n"
        "                        }\n"
        "                        LogBuffer.w(\"A11y\", \"takeScreenshot failed: $why (FLAG_SECURE apps like Roblox cannot be captured)\")\n"
        "                        callback(null)\n"
        "                    }"
    )
    if old in t:
        write(rel, t.replace(old, new, 1))
        print("screenshot errors")
    else:
        print("screenshot already patched or pattern miss")

def patch_http() -> None:
    rel = "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = read(rel)

    if 'path == "/api/diagnose"' not in t:
        t = t.replace(
            'path == "/api/status" -> respond(out, 200, statusJson())',
            'path == "/api/status" -> respond(out, 200, statusJson())\n'
            '            path == "/api/diagnose" -> respond(out, 200, diagnoseJson())\n'
            '            path == "/api/domains" && method == "POST" -> respond(out, 200, openDomainsJson(body))',
            1,
        )
        print("api routes")

    if "fun diagnoseJson" not in t:
        helper = '''
    private fun diagnoseJson(): String {
        val a11yBound = TapService.isConnected()
        val a11yListed = try {
            val cn = android.content.ComponentName(context, TapService::class.java)
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it.trim()) == cn
            }
        } catch (_: Throwable) { false }
        val shot = when {
            !BridgeControl.screenshotSupported() -> "unsupported (need Android 11+)"
            !a11yBound -> "needs CWBridge Tap connected"
            else -> "supported (FLAG_SECURE games still fail)"
        }
        val issues = mutableListOf<String>()
        if (!a11yBound) {
            issues += if (a11yListed) "A11y listed but not bound — toggle Tap off/on"
            else "Enable CWBridge Tap"
        }
        if (!ShizukuShell.isReady()) issues += "Shizuku not ready — open Shizuku and grant CWBridge"
        if (!BridgeControl.screenshotSupported()) issues += "Screenshot needs Android 11+"
        return json(
            mapOf(
                "a11yBound" to a11yBound,
                "a11yListed" to a11yListed,
                "shizuku" to ShizukuShell.statusLine(),
                "shizukuReady" to ShizukuShell.isReady(),
                "screenshot" to shot,
                "androidSdk" to android.os.Build.VERSION.SDK_INT,
                "issues" to issues,
                "ok" to issues.isEmpty(),
            ),
        )
    }

    private fun openDomainsJson(body: String): String {
        val raw = jsonString(body, "domains")
        val domains = raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (domains.isEmpty()) return json(mapOf("error" to "domains required"))
        val x = (jsonDouble(body, "urlBarX") ?: 50.0).toFloat()
        val y = (jsonDouble(body, "urlBarY") ?: 6.0).toFloat()
        return json(mapOf("message" to BridgeControl.openDomains(domains, x, y)))
    }

'''
        t = t.replace("    private fun statusJson", helper + "    private fun statusJson", 1)
        print("diagnose helpers")

    write(rel, t)

def patch_webui() -> None:
    rel = "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = read(rel)

    if "/* mobile-v213 */" not in t:
        css = """
/* mobile-v213 */
@media (max-width:700px){
  main{padding:10px;gap:12px}
  .card{padding:12px;border-radius:14px}
  header{padding:10px 12px;gap:8px}
  button{padding:12px 14px;font-size:14px;min-height:44px;flex:1 1 auto}
  input{padding:12px;font-size:16px;width:100%}
  .row{gap:8px}
  .row > *{flex:1 1 120px}
  table{display:block;overflow-x:auto;-webkit-overflow-scrolling:touch}
  pre{max-height:240px;font-size:12px}
}
button{touch-action:manipulation;-webkit-tap-highlight-color:transparent}
#shotImg{max-width:100%;height:auto;border-radius:12px;border:1px solid var(--line)}
.diag{font-size:13px;line-height:1.45;margin:10px 0 0;padding-left:18px;color:var(--muted)}
.diag .ok{color:var(--ok)}.diag .bad{color:var(--danger)}
"""
        t = t.replace("</style>", css + "</style>", 1)
        print("mobile css")

    if 'id="diagnoseCard"' not in t:
        block = '''
  <div class="card" id="diagnoseCard">
    <h2>Diagnose</h2>
    <div class="row">
      <button onclick="runDiagnose()">Diagnose issues</button>
    </div>
    <ul id="diagOut" class="diag"></ul>
  </div>

  <div class="card" id="domainsCard">
    <h2>Open domains (Ctrl+T)</h2>
    <p style="color:var(--muted);font-size:12px;margin:0 0 8px">
      One domain per line. Ctrl+T, tap URL bar by coordinates (Roblox has no a11y tree),
      type domain, Enter, then Ctrl+1. Needs Shizuku.
    </p>
    <textarea id="domainList" rows="4" placeholder="catweb.rbx"
      style="width:100%;background:var(--surface);border:1px solid var(--line);border-radius:10px;color:#fff;padding:10px;font:inherit"></textarea>
    <div class="row" style="margin-top:8px">
      <label>URL X% <input id="urlX" value="50" style="width:70px"></label>
      <label>Y% <input id="urlY" value="6" style="width:70px"></label>
      <button onclick="openDomains()">Open all</button>
    </div>
    <div id="domMsg" class="msg"></div>
  </div>
'''
        marker = "  <div class=\"card\">\n    <h2>Services</h2>"
        if marker in t:
            t = t.replace(marker, block + marker, 1)
            print("cards")
        else:
            print("WARN: Services marker missing")

    if "async function runDiagnose" not in t:
        js = '''
async function runDiagnose() {
  var ul = D('diagOut'); ul.innerHTML = '<li>checking…</li>';
  try {
    var s = await api('/api/diagnose');
    var items = [];
    items.push(li(s.a11yBound, 'Accessibility bound', s.a11yListed && !s.a11yBound ? 'listed but not bound' : ''));
    items.push(li(s.shizukuReady, 'Shizuku ready', s.shizuku || ''));
    items.push(li(true, 'Screenshot: ' + s.screenshot, ''));
    items.push(li(true, 'Android SDK ' + s.androidSdk, ''));
    (s.issues || []).forEach(function(i){ items.push('<li class="bad">• ' + esc(i) + '</li>'); });
    if (!s.issues || !s.issues.length) items.push('<li class="ok">• no blocking issues</li>');
    ul.innerHTML = items.join('');
  } catch (e) { ul.innerHTML = '<li class="bad">' + esc(e.message) + '</li>'; }
}
function li(ok, label, extra) {
  return '<li class="' + (ok ? 'ok' : 'bad') + '">' + (ok ? '✓ ' : '✗ ') + esc(label)
    + (extra ? ' <span style="color:var(--muted)">(' + esc(extra) + ')</span>' : '') + '</li>';
}
async function openDomains() {
  var raw = D('domainList').value;
  var x = parseFloat(D('urlX').value) || 50;
  var y = parseFloat(D('urlY').value) || 6;
  msg('domMsg', 'opening…', '');
  try {
    var r = await post('/api/domains', { domains: raw, urlBarX: x, urlBarY: y });
    msg('domMsg', r.message, 'good');
  } catch (e) { msg('domMsg', e.message, 'err'); }
}
'''
        if "refreshAll();" in t:
            t = t.replace("refreshAll();", js + "\nrefreshAll();", 1)
            print("js")
        else:
            print("WARN: refreshAll missing")

    write(rel, t)

def main() -> None:
    patch_bridge_control()
    patch_shizuku()
    patch_screenshot()
    patch_http()
    patch_webui()
    print("done")

if __name__ == "__main__":
    main()
