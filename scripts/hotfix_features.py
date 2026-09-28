#!/usr/bin/env python3
"""v2.13: mobile WebUi polish, diagnose API, screenshot errors, Roblox deeplink, domain open."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(p: Path) -> str:
    return p.read_text()

def write(p: Path, t: str) -> None:
    p.write_text(t)
    print(f"wrote {p.relative_to(ROOT)}")

def patch_bridge_control() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/bridge/BridgeControl.kt"
    t = read(p)

    old = '''            context.packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                "Roblox restarting"
            } ?: "Roblox installed but no launch intent"'''

    new = '''            // Prefer launcher intent; if missing (some OEMs / sideload), use CatWeb deeplink.
            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                "Roblox restarting"
            } else {
                LogBuffer.w("Control", "no launch intent for $packageName — CatWeb deeplink")
                openCatWeb(context)
            }'''

    if old in t:
        t = t.replace(old, new, 1)
        print("restartRoblox deeplink fallback")
    elif "no launch intent for" in t:
        print("restartRoblox already patched")
    else:
        print("WARN: restartRoblox block not found")

    # Add openDomains sequence if missing
    if "fun openDomains" not in t:
        insert = '''

    /**
     * Open each domain in CatWeb via Ctrl+T → tap URL bar (coords) → type → Enter.
     * Roblox is OpenGL so the a11y tree is empty; taps are percent-based.
     * After all tabs: Ctrl+1 focuses the first.
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
            if (!ctrl) {
                results += "$d: Ctrl+T failed"
                continue
            }
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            val tapped = svc?.clickAtPercent(urlBarXPct, urlBarYPct) == true
            if (!tapped) {
                results += "$d: URL-bar tap failed"
                continue
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            val typed = if (ShizukuShell.isReady()) {
                ShizukuShell.inputText(d)
            } else {
                false
            }
            if (!typed) {
                results += "$d: type failed (need Shizuku for text)"
                continue
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            val enter = when {
                ShizukuShell.isReady() -> ShizukuShell.pressEnter()
                svc != null -> svc.pressEnter()
                else -> false
            }
            results += if (enter) "$d: ok" else "$d: Enter failed"
            try { Thread.sleep(pauseMs) } catch (_: InterruptedException) {}
        }
        // Focus first tab
        val ctrl1 = if (ShizukuShell.isReady()) ShizukuShell.pressCtrlNumber(1) else false
        results += if (ctrl1) "Ctrl+1 ok" else "Ctrl+1 failed"
        return results.joinToString("; ")
    }
'''
        # insert before closing brace of object
        t = t.rstrip() + "\n" + insert + "\n}\n"
        # remove duplicate closing if any
        while t.count("\n}\n") > 1 and t.rstrip().endswith("}'):
            pass
        # Fix: we may have doubled the final }
        # Original ended with }
        # Better approach: replace last standalone }
        if t.count("object BridgeControl") == 1:
            # strip trailing braces and re-add once
            body = t[: t.rfind("fun restartRoblox")]
            # simpler: just append method before final }
            t = read(p)
            if "fun openDomains" not in t:
                t = t.rstrip()
                if t.endswith("}"):
                    t = t[:-1] + insert + "\n}\n"
                print("added openDomains")
            else:
                print("openDomains exists")
        write(p, t)
        return

    write(p, t)

def patch_shizuku() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/ShizukuShell.kt"
    t = read(p)
    if "pressCtrlNumber" in t:
        print("ShizukuShell already has pressCtrlNumber")
        return

    # Strengthen pressCtrlT and add pressCtrlNumber
    old = '''    fun pressCtrlT(): Boolean {
'''
    # Find the existing function and replace whole thing
    m = re.search(
        r"    fun pressCtrlT\(\): Boolean \{.*?\n    \}\n",
        t,
        re.S,
    )
    if not m:
        print("WARN: pressCtrlT not found")
        return

    repl = '''    fun pressCtrlT(): Boolean {
        // Try several input forms — OEM keyboards differ.
        val cmds = listOf(
            "input keycombination 113 48",          // CTRL_LEFT + T
            "input keycombination 114 48",          // CTRL_RIGHT + T
            "input keyevent --longpress 113 48",
            "input text '' && input keycombination 113 48",
        )
        for (cmd in cmds) {
            val (code, out) = exec(cmd)
            LogBuffer.i("Shizuku", "pressCtrlT cmd=$cmd exit=$code ${out.take(80)}")
            if (code == 0) return true
        }
        return false
    }

    /** Ctrl+1..9 for tab focus (KEYCODE_1 = 8). */
    fun pressCtrlNumber(n: Int): Boolean {
        val key = 7 + n.coerceIn(1, 9) // KEYCODE_0=7, KEYCODE_1=8
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
    write(p, t)
    print("ShizukuShell Ctrl improved")

def patch_tapservice_screenshot() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/TapService.kt"
    t = read(p)
    old = '''                    override fun onFailure(errorCode: Int) {
                        exec.shutdown()
                        LogBuffer.w("A11y", "takeScreenshot failed code=$errorCode")
                        callback(null)
                    }'''
    new = '''                    override fun onFailure(errorCode: Int) {
                        exec.shutdown()
                        val why = when (errorCode) {
                            1 -> "INTERNAL_ERROR"
                            2 -> "NO_ACCESSIBILITY_ACCESS"
                            3 -> "INTERVAL_TOO_SHORT"
                            4 -> "INVALID_DISPLAY"
                            5 -> "INVALID_WINDOW"
                            else -> "code=$errorCode"
                        }
                        // Roblox (and many games) set FLAG_SECURE → system refuses capture.
                        LogBuffer.w("A11y", "takeScreenshot failed: $why (games with FLAG_SECURE cannot be captured)")
                        callback(null)
                    }'''
    if old in t:
        t = t.replace(old, new, 1)
        write(p, t)
        print("screenshot error detail")
    else:
        print("screenshot onFailure already patched or missing")

def patch_http_server() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/LocalHttpServer.kt"
    t = read(p)

    # diagnose route
    if 'path == "/api/diagnose"' not in t:
        anchor = '            path == "/api/status" -> respond(out, 200, statusJson())'
        if anchor not in t:
            print("WARN: status route not found")
        else:
            t = t.replace(
                anchor,
                anchor
                + "\n"
                + '            path == "/api/diagnose" -> respond(out, 200, diagnoseJson())',
                1,
            )
            print("added /api/diagnose route")

    if 'path == "/api/domains"' not in t:
        anchor = '            path == "/api/status" -> respond(out, 200, statusJson())'
        # insert after diagnose if present
        if 'path == "/api/diagnose"' in t:
            t = t.replace(
                'path == "/api/diagnose" -> respond(out, 200, diagnoseJson())',
                'path == "/api/diagnose" -> respond(out, 200, diagnoseJson())\n'
                '            path == "/api/domains" && method == "POST" -> respond(out, 200, openDomainsJson(body))',
                1,
            )
            print("added /api/domains route")

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
        val shizuku = ShizukuShell.statusLine()
        val shot = when {
            !BridgeControl.screenshotSupported() -> "unsupported (need Android 11+)"
            !a11yBound -> "needs CWBridge Tap connected"
            else -> "supported (games with FLAG_SECURE still fail)"
        }
        val issues = mutableListOf<String>()
        if (!a11yBound) issues += if (a11yListed) "A11y listed but not bound — toggle Tap off/on" else "Enable CWBridge Tap"
        if (!ShizukuShell.isReady()) issues += "Shizuku not ready — open Shizuku and grant CWBridge"
        if (!BridgeControl.screenshotSupported()) issues += "Screenshot needs Android 11+"
        return json(
            mapOf(
                "a11yBound" to a11yBound,
                "a11yListed" to a11yListed,
                "shizuku" to shizuku,
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
        if (domains.isEmpty()) return json(mapOf("error" to "domains required (comma or newline separated)"))
        val x = (jsonDouble(body, "urlBarX") ?: 50.0).toFloat()
        val y = (jsonDouble(body, "urlBarY") ?: 6.0).toFloat()
        val msg = BridgeControl.openDomains(domains, x, y)
        return json(mapOf("message" to msg))
    }
'''
        # insert before class end - find private fun statusJson
        if "private fun statusJson" in t:
            t = t.replace("    private fun statusJson", helper + "\n    private fun statusJson", 1)
            print("added diagnoseJson + openDomainsJson")

    # Better screenshot error message
    t2 = t.replace(
        'shot.fold(\n',
        'shot.fold(\n',  # noop keep
    )
    write(p, t)

def patch_webui() -> None:
    p = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
    t = read(p)

    # Mobile-friendlier CSS overrides injected into PART_A style
    if "/* mobile-v213 */" not in t:
        css = """
/* mobile-v213 */
@media (max-width:700px){
  main{padding:10px;gap:12px}
  .card{padding:12px;border-radius:14px}
  header{padding:10px 12px;gap:8px}
  button{padding:12px 14px;font-size:14px;min-height:44px;flex:1 1 auto}
  input{padding:12px 12px;font-size:16px;width:100%}
  .row{gap:8px}
  .row > *{flex:1 1 120px}
  table{display:block;overflow-x:auto;-webkit-overflow-scrolling:touch}
  pre{max-height:240px;font-size:12px}
  h1{font-size:15px}
}
button{touch-action:manipulation;-webkit-tap-highlight-color:transparent}
#shotImg{max-width:100%;height:auto;border-radius:12px;border:1px solid var(--line)}
.diag{font-size:13px;line-height:1.45}
.diag li{margin:4px 0}
.diag .ok{color:var(--ok)}.diag .bad{color:var(--danger)}
"""
        t = t.replace("</style>", css + "</style>", 1)
        print("mobile CSS")

    # Add diagnose + domains cards before Services if not present
    if 'id="diagnoseCard"' not in t:
        block = '''
  <div class="card" id="diagnoseCard">
    <h2>Diagnose</h2>
    <div class="row">
      <button onclick="runDiagnose()">Diagnose issues</button>
    </div>
    <ul id="diagOut" class="diag" style="margin:10px 0 0;padding-left:18px;color:var(--muted)"></ul>
  </div>

  <div class="card" id="domainsCard">
    <h2>Open domains (Ctrl+T)</h2>
    <p style="color:var(--muted);font-size:12px;margin:0 0 8px">
      One domain per line. Uses Ctrl+T, taps the URL bar by coordinates
      (Roblox has no a11y tree), types the domain, presses Enter, then Ctrl+1.
      Needs Shizuku for keys/text.
    </p>
    <textarea id="domainList" rows="4" placeholder="catweb.rbx&#10;mysite.rbx"
      style="width:100%;background:var(--surface);border:1px solid var(--line);border-radius:10px;color:#fff;padding:10px;font:inherit"></textarea>
    <div class="row" style="margin-top:8px">
      <label>URL bar X% <input id="urlX" value="50" style="width:70px"></label>
      <label>Y% <input id="urlY" value="6" style="width:70px"></label>
      <button onclick="openDomains()">Open all</button>
    </div>
    <div id="domMsg" class="msg"></div>
  </div>
'''
        # Insert before Services card
        marker = '  <div class="card">\n    <h2>Services</h2>'
        if marker in t:
            t = t.replace(marker, block + marker, 1)
            print("diagnose + domains cards")
        else:
            print("WARN: Services card not found")

    if "async function runDiagnose" not in t:
        js = '''
async function runDiagnose() {
  const ul = D('diagOut'); ul.innerHTML = '<li>checking…</li>';
  try {
    const s = await api('/api/diagnose');
    const items = [];
    items.push(li(s.a11yBound, 'Accessibility bound', s.a11yListed && !s.a11yBound ? 'listed but not bound — toggle Tap off/on' : ''));
    items.push(li(s.shizukuReady, 'Shizuku ready', s.shizuku));
    items.push(li(true, 'Screenshot: ' + s.screenshot, ''));
    items.push(li(true, 'Android SDK ' + s.androidSdk, ''));
    (s.issues || []).forEach(function(i){ items.push('<li class="bad">• ' + esc(i) + '</li>'); });
    if (!s.issues || !s.issues.length) items.push('<li class="ok">• no blocking issues detected</li>');
    ul.innerHTML = items.join('');
  } catch (e) { ul.innerHTML = '<li class="bad">' + esc(e.message) + '</li>'; }
}
function li(ok, label, extra) {
  return '<li class="' + (ok ? 'ok' : 'bad') + '">' + (ok ? '✓ ' : '✗ ') + esc(label)
    + (extra ? ' <span style="color:var(--muted)">(' + esc(extra) + ')</span>' : '') + '</li>';
}
async function openDomains() {
  const raw = D('domainList').value;
  const x = parseFloat(D('urlX').value) || 50;
  const y = parseFloat(D('urlY').value) || 6;
  msg('domMsg', 'opening…', '');
  try {
    const r = await post('/api/domains', { domains: raw, urlBarX: x, urlBarY: y });
    msg('domMsg', r.message, 'good');
  } catch (e) { msg('domMsg', e.message, 'err'); }
}
'''
        # inject before refreshAll();
        if "refreshAll();" in t:
            t = t.replace("refreshAll();", js + "\nrefreshAll();", 1)
            print("diagnose/domains JS")
        else:
            print("WARN: refreshAll not found")

    write(p, t)

def main() -> None:
    patch_bridge_control()
    patch_shizuku()
    patch_tapservice_screenshot()
    patch_http_server()
    patch_webui()
    print("hotfix done")

if __name__ == "__main__":
    main()
