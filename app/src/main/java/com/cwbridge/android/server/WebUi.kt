package com.cwbridge.android.server

/**
 * Built-in control panel. Split into PART_* raw strings so check-webui.js can
 * reconstruct and syntax-check the inline JS at CI time.
 */
object WebUi {

    private val PART_A: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark">
<title>CWBridge</title>
<style>
:root{
  --bg:#0c0e12; --panel:#151922; --panel2:#1b2030; --line:rgba(255,255,255,.08);
  --text:#eef1f7; --muted:#8b93a7; --accent:#6ea8fe; --accent2:#5b8def;
  --ok:#3dd68c; --warn:#f5a524; --danger:#f76c6c; --radius:14px;
}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text);
  font:15px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif;
  -webkit-text-size-adjust:100%}
a{color:var(--accent)}
.hide{display:none!important}
header{
  position:sticky;top:0;z-index:20;backdrop-filter:blur(12px);
  background:rgba(12,14,18,.88);border-bottom:1px solid var(--line);
  padding:12px 16px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;
}
.brand{font-weight:700;letter-spacing:.02em}
.chip{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;
  padding:4px 10px;border-radius:999px;background:var(--panel2);color:var(--muted)}
.chip.ACTIVE{background:rgba(61,214,140,.15);color:var(--ok)}
.chip.WAITING{background:rgba(245,165,36,.15);color:var(--warn)}
.chip.ERROR{background:rgba(247,108,108,.15);color:var(--danger)}
.chip.IDLE{background:var(--panel2);color:var(--muted)}
.detail{color:var(--muted);font-size:12px;flex:1;min-width:120px}
main{max-width:920px;margin:0 auto;padding:14px;display:grid;gap:12px}
.card{
  background:linear-gradient(180deg,var(--panel),var(--panel2));
  border:1px solid var(--line);border-radius:var(--radius);padding:14px 14px 12px;
  box-shadow:0 8px 24px rgba(0,0,0,.25);
}
.card h2{margin:0 0 10px;font-size:12px;font-weight:700;letter-spacing:.08em;
  text-transform:uppercase;color:var(--muted)}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
@media(max-width:560px){.grid2{grid-template-columns:1fr}}
button,.btn{
  appearance:none;border:0;border-radius:12px;padding:11px 14px;min-height:44px;
  font:inherit;font-weight:650;cursor:pointer;color:#0b1020;background:var(--accent);
  touch-action:manipulation;-webkit-tap-highlight-color:transparent;
}
button:active{transform:scale(.98)}
button.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}
button.soft{background:rgba(110,168,254,.14);color:var(--accent)}
button.danger{background:rgba(247,108,108,.18);color:var(--danger)}
button.block{width:100%}
input,textarea,select{
  width:100%;background:#0f131b;border:1px solid var(--line);border-radius:12px;
  color:var(--text);padding:11px 12px;font:inherit;min-height:44px;
}
textarea{min-height:96px;resize:vertical}
label{display:block;font-size:12px;color:var(--muted);margin:0 0 4px}
.field{flex:1;min-width:120px}
.msg{margin-top:8px;font-size:13px;color:var(--muted);word-break:break-word}
.msg.good{color:var(--ok)}.msg.err{color:var(--danger)}
pre{
  margin:0;background:#0b0f16;border:1px solid var(--line);border-radius:12px;
  padding:12px;max-height:280px;overflow:auto;font:12px/1.4 ui-monospace,Menlo,Consolas,monospace;
  color:#b7c7ff;white-space:pre-wrap;word-break:break-word;
}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:8px 6px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th{color:var(--muted);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.05em}
.svc{display:flex;gap:8px;flex-wrap:wrap;align-items:center;padding:10px 0;border-bottom:1px solid var(--line)}
.svc:last-child{border-bottom:0}
.badge{font-size:11px;padding:2px 8px;border-radius:999px;background:rgba(255,255,255,.06)}
.badge.on{color:var(--ok)}.badge.off{color:var(--warn)}
#shotImg{max-width:100%;border-radius:12px;border:1px solid var(--line);margin-top:8px}
.hint{font-size:12px;color:var(--muted);margin:0 0 10px;line-height:1.4}
ul.diag{margin:8px 0 0;padding-left:18px;color:var(--muted);font-size:13px}
ul.diag .ok{color:var(--ok)}ul.diag .bad{color:var(--danger)}
.footer{text-align:center;color:var(--muted);font-size:11px;padding:8px 0 24px}
</style>
</head>
<body>
""".trimIndent()

    private val PART_B: String = """
<div id="login" class="hide">
  <main>
    <div class="card">
      <h2>Unlock remote access</h2>
      <p class="hint">Not on the same LAN as the phone — enter the password shown in the app under Server.</p>
      <div class="row">
        <div class="field"><input id="pw" type="password" placeholder="Server password" autocomplete="current-password"></div>
        <button type="button" onclick="doLogin()">Unlock</button>
      </div>
      <div id="loginMsg" class="msg"></div>
    </div>
  </main>
</div>

<div id="app">
<header>
  <div class="brand">CWBridge</div>
  <span id="pill" class="chip IDLE">-</span>
  <span id="detail" class="detail"></span>
  <button type="button" class="ghost" onclick="logout()">Lock</button>
</header>
<main>
  <div class="card">
    <h2>Status</h2>
    <div class="row">
      <button type="button" class="soft" onclick="runDiagnose()">Diagnose</button>
      <button type="button" class="ghost" onclick="refreshAll()">Refresh</button>
    </div>
    <ul id="diagOut" class="diag"></ul>
  </div>

  <div class="card">
    <h2>Remote control</h2>
    <div class="row">
      <button type="button" onclick="ctl(\'restart-bridge\')">Restart bridge</button>
      <button type="button" onclick="ctl(\'restart-roblox\')">Restart Roblox</button>
      <button type="button" onclick="toggleBridge()">Toggle bridge</button>
      <!--SCREENSHOT_BUTTON-->
    </div>
    <div id="ctlMsg" class="msg"></div>
    <div id="shotBox"></div>
  </div>
""".trimIndent()

    private val PART_C: String = """
  <div class="card">
    <h2>Keys</h2>
    <p class="hint">Roblox must be on-screen. Ctrl+T tries many inject methods until one reports success.</p>
    <div class="row">
      <button type="button" onclick="ctl(\'ctrl-t\')">Ctrl+T</button>
      <button type="button" onclick="ctl(\'enter\')">Enter</button>
      <button type="button" class="ghost" onclick="ctl(\'clipboard\')">Read clipboard</button>
    </div>
  </div>

  <div class="card">
    <h2>Open domains</h2>
    <p class="hint">One domain per line. Flow: Ctrl+T, tap URL bar (X/Y %), type domain, Enter, wait, then Ctrl+1. Needs Shizuku.</p>
    <textarea id="domainList" placeholder="example.rbx"></textarea>
    <div class="row" style="margin-top:8px">
      <div class="field"><label>URL bar X%</label><input id="urlX" value="50" inputmode="decimal"></div>
      <div class="field"><label>URL bar Y%</label><input id="urlY" value="6" inputmode="decimal"></div>
      <button type="button" onclick="openDomains()">Open all</button>
    </div>
    <div id="domMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Send invoke</h2>
    <div class="row">
      <div class="field"><input id="inv" placeholder="tap 50 85 | paste hello | save key value"></div>
      <button type="button" onclick="sendInvoke()">Send</button>
    </div>
    <div id="invMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Tap</h2>
    <div class="grid2">
      <div class="field"><label>X%</label><input id="tx" value="50" inputmode="decimal"></div>
      <div class="field"><label>Y%</label><input id="ty" value="50" inputmode="decimal"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <button type="button" onclick="tapPercent()">Tap %</button>
      <button type="button" class="ghost" onclick="tapPx()">Tap px</button>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field"><input id="ttext" placeholder="Button text (a11y tree)"></div>
      <button type="button" class="ghost" onclick="tapText()">Tap text</button>
    </div>
    <div id="tapMsg" class="msg"></div>
  </div>
""".trimIndent()

    private val PART_D: String = """
  <div class="card">
    <h2>Services</h2>
    <div id="svcList"></div>
    <div class="row" style="margin-top:10px">
      <div class="field"><input id="svcName" placeholder="New service name"></div>
      <button type="button" onclick="createService()">Create</button>
    </div>
    <div id="svcMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Storage</h2>
    <div style="overflow-x:auto">
      <table><thead><tr><th>Domain</th><th>Keys</th><th>Used</th><th>Limit</th><th></th></tr></thead>
      <tbody id="storeRows"></tbody></table>
    </div>
    <div class="row" style="margin-top:10px">
      <div class="field"><input id="qDomain" placeholder="name.rbx"></div>
      <div class="field"><input id="qLimit" placeholder="2MB"></div>
      <button type="button" class="ghost" onclick="setLimit()">Set limit</button>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field"><input id="sDomain" placeholder="domain"></div>
      <div class="field"><input id="sKey" placeholder="key"></div>
      <div class="field"><input id="sVal" placeholder="value"></div>
      <button type="button" onclick="saveKey()">Save</button>
    </div>
    <div id="storeMsg" class="msg"></div>
  </div>
""".trimIndent()

    private val PART_E: String = """
  <div class="card">
    <h2>Variables</h2>
    <div style="overflow-x:auto">
      <table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody id="varRows"></tbody></table>
    </div>
  </div>

  <div class="card">
    <h2>Console</h2>
    <pre id="console">-</pre>
    <div class="row" style="margin-top:10px">
      <button type="button" class="ghost" onclick="refreshConsole()">Refresh</button>
      <label style="display:flex;align-items:center;gap:6px;color:var(--muted)">
        <input type="checkbox" id="auto" checked style="width:auto;min-height:0"> auto
      </label>
    </div>
  </div>
</main>
<div class="footer">CWBridge web panel</div>
</div>
""".trimIndent()

    private val PART_F: String = """
<script>
const D = function(id){ return document.getElementById(id); };
let NEEDS_LOGIN = false;

function esc(s){
  return String(s == null ? "" : s).replace(/[&<>"\']/g, function(c){
    return ({"&":"&","<":"<",">":">","\"":""","\'":"&#39;"})[c];
  });
}
function msg(id, text, cls){
  var el = D(id); if(!el) return;
  el.className = "msg" + (cls ? (" " + cls) : "");
  el.textContent = text || "";
}
function showLogin(){
  NEEDS_LOGIN = true;
  D("login").classList.remove("hide");
  D("app").classList.add("hide");
}
function revealApp(){
  NEEDS_LOGIN = false;
  D("login").classList.add("hide");
  D("app").classList.remove("hide");
}

async function api(path, opts){
  var res = await fetch(path, Object.assign({credentials:"same-origin"}, opts || {}));
  if(res.status === 401){ showLogin(); throw new Error("locked"); }
  var ct = res.headers.get("content-type") || "";
  if(ct.indexOf("application/json") < 0){
    if(!res.ok) throw new Error(await res.text());
    return res;
  }
  var data = await res.json();
  if(!res.ok) throw new Error(data.error || ("error " + res.status));
  return data;
}
function post(path, body){
  return api(path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(body || {})
  });
}

async function doLogin(){
  try{
    await post("/api/login", {password: D("pw").value});
    msg("loginMsg", "");
    revealApp();
    refreshAll();
  }catch(e){ msg("loginMsg", e.message, "err"); }
}
async function logout(){
  try{ await post("/api/logout"); }catch(e){}
  showLogin();
}

async function refreshStatus(){
  try{
    var s = await api("/api/status");
    revealApp();
    var p = D("pill");
    p.textContent = s.state || "-";
    p.className = "chip " + (s.state || "IDLE");
    D("detail").textContent = s.detail || "";
  }catch(e){
    if(String(e.message) === "locked") return;
  }
}

async function runDiagnose(){
  var ul = D("diagOut");
  ul.innerHTML = "<li>checking...</li>";
  try{
    var s = await api("/api/diagnose");
    var items = [];
    function row(ok, label, extra){
      return "<li class=\"" + (ok ? "ok" : "bad") + "\">" +
        (ok ? "[OK] " : "[FAIL] ") + esc(label) +
        (extra ? " (" + esc(extra) + ")" : "") + "</li>";
    }
    items.push(row(!!s.a11yBound, "Accessibility bound",
      s.a11yListed && !s.a11yBound ? "listed but not bound" : ""));
    items.push(row(!!s.shizukuReady, "Shizuku ready", s.shizuku || ""));
    items.push(row(true, "Screenshot: " + (s.screenshot || ""), ""));
    items.push(row(true, "Android SDK " + s.androidSdk, ""));
    (s.issues || []).forEach(function(i){
      items.push("<li class=\"bad\">- " + esc(i) + "</li>");
    });
    if(!s.issues || !s.issues.length){
      items.push("<li class=\"ok\">- no blocking issues</li>");
    }
    ul.innerHTML = items.join("");
  }catch(e){
    ul.innerHTML = "<li class=\"bad\">" + esc(e.message) + "</li>";
  }
}

async function openDomains(){
  var raw = D("domainList").value || "";
  var x = parseFloat(D("urlX").value); if(isNaN(x)) x = 50;
  var y = parseFloat(D("urlY").value); if(isNaN(y)) y = 6;
  msg("domMsg", "opening...", "");
  try{
    var r = await post("/api/domains", {domains: raw, urlBarX: x, urlBarY: y});
    msg("domMsg", r.message || "done", "good");
  }catch(e){ msg("domMsg", e.message, "err"); }
}

async function ctl(action){
  msg("ctlMsg", "working...", "");
  try{
    var r = await api("/api/control/" + action);
    msg("ctlMsg", r.message || "ok", "good");
    refreshConsole();
  }catch(e){ msg("ctlMsg", e.message, "err"); }
}
async function toggleBridge(){
  try{
    var r = await post("/api/bridge/toggle");
    msg("ctlMsg", r.message, "good");
    refreshStatus();
  }catch(e){ msg("ctlMsg", e.message, "err"); }
}
async function loadShot(){
  msg("ctlMsg", "capturing...", "");
  try{
    var res = await api("/api/screenshot");
    var blob = await res.blob();
    var url = URL.createObjectURL(blob);
    D("shotBox").innerHTML = "<img id=\"shotImg\" alt=\"screenshot\" src=\"" + url + "\">";
    msg("ctlMsg", "screenshot ok", "good");
  }catch(e){ msg("ctlMsg", e.message, "err"); }
}
async function sendInvoke(){
  try{
    var r = await post("/api/invoke", {line: D("inv").value});
    msg("invMsg", r.message || "ok", "good");
  }catch(e){ msg("invMsg", e.message, "err"); }
}
async function tapPercent(){
  try{
    var r = await post("/api/tap", {mode:"percent", x: parseFloat(D("tx").value), y: parseFloat(D("ty").value)});
    msg("tapMsg", r.message, "good");
  }catch(e){ msg("tapMsg", e.message, "err"); }
}
async function tapPx(){
  try{
    var r = await post("/api/tap", {mode:"px", x: parseFloat(D("tx").value), y: parseFloat(D("ty").value)});
    msg("tapMsg", r.message, "good");
  }catch(e){ msg("tapMsg", e.message, "err"); }
}
async function tapText(){
  try{
    var r = await post("/api/tap", {mode:"text", text: D("ttext").value});
    msg("tapMsg", r.message, "good");
  }catch(e){ msg("tapMsg", e.message, "err"); }
}

async function refreshServices(){
  try{
    var s = await api("/api/services");
    var list = s.services || [];
    D("svcList").innerHTML = list.map(function(v){
      var id = esc(v.id || v.name);
      return "<div class=\"svc\"><strong>" + esc(v.name) + "</strong>" +
        " <span class=\"badge " + (v.enabled ? "on" : "off") + "\">" + (v.enabled ? "ON" : "OFF") + "</span>" +
        " <button type=\"button\" class=\"soft\" onclick=\"runService(\'" + id + "\')\">Run</button>" +
        " <button type=\"button\" class=\"ghost\" onclick=\"toggleService(\'" + id + "\'," + (!v.enabled) + ")\">" +
        (v.enabled ? "Disable" : "Enable") + "</button>" +
        " <button type=\"button\" class=\"danger\" onclick=\"deleteService(\'" + id + "\')\">Delete</button></div>";
    }).join("") || "<div class=\"hint\">No services yet</div>";
  }catch(e){}
}
async function createService(){
  try{
    var r = await post("/api/services", {name: D("svcName").value});
    msg("svcMsg", r.message || "created", "good");
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function runService(id){
  try{
    var r = await post("/api/services/" + encodeURIComponent(id) + "/run");
    msg("svcMsg", r.message || "running", "good");
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function toggleService(id, enabled){
  try{
    var r = await post("/api/services/" + encodeURIComponent(id) + "/toggle", {enabled: enabled});
    msg("svcMsg", r.message || "ok", "good");
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function deleteService(id){
  if(!confirm("Delete service?")) return;
  try{
    var r = await post("/api/services/" + encodeURIComponent(id) + "/delete");
    msg("svcMsg", r.message || "deleted", "good");
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}

async function refreshStore(){
  try{
    var s = await api("/api/store");
    var rows = s.domains || [];
    D("storeRows").innerHTML = rows.map(function(d){
      return "<tr><td>" + esc(d.domain) + "</td><td>" + esc(d.keys) + "</td><td>" +
        esc(d.used) + "</td><td>" + esc(d.limit) + "</td><td>" +
        "<button type=\"button\" class=\"danger\" onclick=\"clearDomain(\'" + esc(d.domain) + "\')\">Clear</button></td></tr>";
    }).join("") || "<tr><td colspan=\"5\" style=\"color:var(--muted)\">empty</td></tr>";
  }catch(e){}
}
async function setLimit(){
  try{
    var r = await post("/api/limits", {domain: D("qDomain").value, limit: D("qLimit").value});
    msg("storeMsg", r.message, "good"); refreshStore();
  }catch(e){ msg("storeMsg", e.message, "err"); }
}
async function saveKey(){
  try{
    var r = await post("/api/store", {domain: D("sDomain").value, key: D("sKey").value, value: D("sVal").value});
    msg("storeMsg", r.message, "good"); refreshStore();
  }catch(e){ msg("storeMsg", e.message, "err"); }
}
async function clearDomain(d){
  if(!confirm("Clear " + d + "?")) return;
  try{
    var r = await post("/api/store/clear", {domain: d});
    msg("storeMsg", r.message, "good"); refreshStore();
  }catch(e){ msg("storeMsg", e.message, "err"); }
}
async function refreshVars(){
  try{
    var s = await api("/api/vars");
    var keys = Object.keys(s.vars || {});
    D("varRows").innerHTML = keys.map(function(k){
      return "<tr><td>" + esc(k) + "</td><td style=\"word-break:break-all\">" + esc(s.vars[k]) + "</td></tr>";
    }).join("") || "<tr><td colspan=\"2\" style=\"color:var(--muted)\">none</td></tr>";
  }catch(e){}
}
async function refreshConsole(){
  try{
    var s = await api("/api/logs");
    D("console").textContent = (s.lines || []).join("\n") || "(no lines)";
  }catch(e){}
}
function refreshAll(){
  refreshStatus(); refreshServices(); refreshStore(); refreshVars(); refreshConsole();
}
setInterval(function(){
  if(NEEDS_LOGIN) return;
  if(D("auto") && D("auto").checked){ refreshConsole(); refreshStatus(); }
}, 3000);
refreshAll();
</script>
</body>
</html>
""".trimIndent()

    private val FULL: String by lazy { PART_A + PART_B + PART_C + PART_D + PART_E + PART_F }

    fun page(showScreenshot: Boolean): String = FULL.replace(
        SCREENSHOT_MARKER,
        if (showScreenshot) SCREENSHOT_BUTTON else "",
    )

    private const val SCREENSHOT_MARKER = "<!--SCREENSHOT_BUTTON-->"
    private const val SCREENSHOT_BUTTON =
        """<button type="button" class="ghost" onclick="loadShot()">Screenshot</button>"""
}
