package com.cwbridge.android.server

/**
 * Built-in control panel. Split into PART_* raw strings so check-webui.js can
 * reconstruct and syntax-check the inline JS at CI time.
 *
 * Stage 2: gooey status, material icons, readiness checklist, section nav,
 * busy buttons, toasts, confirm modal, screenshot download, console polish.
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
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0,0&display=swap">
<style>
:root{
  --bg:#0c0e12; --panel:#151922; --panel2:#1b2030; --line:rgba(255,255,255,.08);
  --text:#eef1f7; --muted:#8b93a7; --accent:#6ea8fe; --accent2:#5b8def;
  --ok:#3dd68c; --warn:#f5a524; --danger:#f76c6c; --radius:14px;
}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text);
  font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
  -webkit-text-size-adjust:100%}
a{color:var(--accent)}
.ms{
  font-family:"Material Symbols Rounded",sans-serif;
  font-weight:400;font-style:normal;font-size:20px;line-height:1;
  vertical-align:middle;letter-spacing:normal;text-transform:none;
  font-variation-settings:"FILL" 0,"wght" 400,"GRAD" 0,"opsz" 24;
  font-feature-settings:"liga";-webkit-font-feature-settings:"liga";
  user-select:none;display:inline-block;
}
.ms.sm{font-size:18px}
h2 .ms,button .ms,a .ms,nav .ms{text-transform:none;letter-spacing:normal}
header{
  position:sticky;top:0;z-index:20;backdrop-filter:blur(12px);
  background:rgba(12,14,18,.9);border-bottom:1px solid var(--line);
  padding:10px 14px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;
}
.brand{font-weight:700;letter-spacing:.02em;display:flex;align-items:center;gap:8px}
.gooey-wrap{width:28px;height:28px;display:grid;place-items:center;filter:url(#goo)}
.gooey-blob{width:14px;height:14px;border-radius:50%;background:var(--muted);
  transition:background .35s,transform .35s,box-shadow .35s}
.gooey-blob.ACTIVE{background:var(--ok);box-shadow:0 0 12px rgba(61,214,140,.55);transform:scale(1.15)}
.gooey-blob.WAITING{background:var(--warn);box-shadow:0 0 12px rgba(245,165,36,.5);transform:scale(1.1)}
.gooey-blob.ERROR{background:var(--danger);box-shadow:0 0 12px rgba(247,108,108,.55);transform:scale(1.1)}
.gooey-blob.IDLE{background:var(--muted)}
@media (prefers-reduced-motion:reduce){
  .gooey-blob{transition:none}
  .gooey-wrap{filter:none}
}
.chip{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;
  padding:4px 10px;border-radius:999px;background:var(--panel2);color:var(--muted)}
.chip.ACTIVE{background:rgba(61,214,140,.15);color:var(--ok)}
.chip.WAITING{background:rgba(245,165,36,.15);color:var(--warn)}
.chip.ERROR{background:rgba(247,108,108,.15);color:var(--danger)}
.chip.IDLE{background:var(--panel2);color:var(--muted)}
.detail{color:var(--muted);font-size:12px;flex:1;min-width:100px}
nav.sec{
  display:flex;gap:6px;overflow-x:auto;padding:8px 14px;border-bottom:1px solid var(--line);
  background:rgba(12,14,18,.6);position:sticky;top:52px;z-index:15;-webkit-overflow-scrolling:touch;
}
nav.sec a{
  flex:0 0 auto;text-decoration:none;color:var(--muted);font-size:12px;font-weight:650;
  padding:8px 12px;border-radius:999px;background:var(--panel2);display:flex;align-items:center;gap:6px;
}
nav.sec a:hover{color:var(--text)}
main{max-width:920px;margin:0 auto;padding:14px;display:grid;gap:12px}
.card{
  background:linear-gradient(180deg,var(--panel),var(--panel2));
  border:1px solid var(--line);border-radius:var(--radius);padding:14px 14px 12px;
  box-shadow:0 8px 24px rgba(0,0,0,.25);scroll-margin-top:110px;
}
.card h2{margin:0 0 10px;font-size:12px;font-weight:700;letter-spacing:.08em;
  text-transform:uppercase;color:var(--muted);display:flex;align-items:center;gap:8px}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
@media(max-width:560px){.grid2{grid-template-columns:1fr}}
button,.btn{
  appearance:none;border:0;border-radius:12px;padding:11px 14px;min-height:44px;
  font:inherit;font-weight:650;cursor:pointer;color:#0b1020;background:var(--accent);
  touch-action:manipulation;-webkit-tap-highlight-color:transparent;
  display:inline-flex;align-items:center;justify-content:center;gap:6px;
}
button:active{transform:translateY(1px)}
button.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}
button.soft{background:var(--panel2);color:var(--text)}
button.danger{background:rgba(247,108,108,.2);color:var(--danger)}
button:disabled{opacity:.55;cursor:not-allowed;transform:none}
button.busy{opacity:.7;pointer-events:none}
.field{flex:1;min-width:120px}
.field label{display:block;font-size:11px;color:var(--muted);margin-bottom:4px}
input,textarea,select{
  width:100%;padding:10px 12px;border-radius:10px;border:1px solid var(--line);
  background:#0c0e12;color:var(--text);font:inherit;min-height:44px;
}
textarea{min-height:72px;resize:vertical}
.hint{color:var(--muted);font-size:12px;margin:0 0 8px}
.msg{font-size:13px;min-height:1.2em;margin-top:8px}
.msg.good{color:var(--ok)}.msg.err{color:var(--danger)}
.diag{list-style:none;padding:0;margin:8px 0 0}
.diag li{padding:6px 0;border-bottom:1px solid var(--line);font-size:13px}
.diag li.ok{color:var(--ok)}.diag li.bad{color:var(--danger)}
.ready{display:grid;gap:6px;margin-top:8px}
.ready .r{
  display:flex;align-items:center;gap:10px;padding:8px 10px;border-radius:10px;
  background:rgba(0,0,0,.2);border:1px solid var(--line);font-size:13px;
}
.ready .dot{width:8px;height:8px;border-radius:50%;background:var(--muted);flex:0 0 auto}
.ready .dot.on{background:var(--ok);box-shadow:0 0 8px rgba(61,214,140,.5)}
.ready .dot.off{background:var(--danger)}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{text-align:left;padding:8px 6px;border-bottom:1px solid var(--line)}
th{color:var(--muted);font-weight:650;font-size:11px;text-transform:uppercase;letter-spacing:.06em}
pre#console{
  margin:0;padding:10px;border-radius:10px;background:#0a0c10;border:1px solid var(--line);
  max-height:280px;overflow:auto;font:12px/1.4 ui-monospace,Consolas,monospace;white-space:pre-wrap;
}
#shotBox img{max-width:100%;border-radius:10px;border:1px solid var(--line);margin-top:8px}
.empty{
  color:var(--muted);font-size:13px;padding:16px;text-align:center;
  border:1px dashed var(--line);border-radius:12px;display:flex;flex-direction:column;
  align-items:center;gap:8px;
}
.footer{text-align:center;color:var(--muted);font-size:11px;padding:8px 14px 24px}
#toastHost{
  position:fixed;bottom:16px;left:50%;transform:translateX(-50%);z-index:50;
  display:flex;flex-direction:column;gap:8px;width:min(420px,92vw);pointer-events:none;
}
.toast{
  pointer-events:auto;padding:12px 14px;border-radius:12px;background:var(--panel);
  border:1px solid var(--line);box-shadow:0 12px 32px rgba(0,0,0,.45);font-size:13px;
  animation:toastIn .2s ease;
}
.toast.good{border-color:rgba(61,214,140,.35)}.toast.err{border-color:rgba(247,108,108,.4)}
@keyframes toastIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
@media (prefers-reduced-motion:reduce){.toast{animation:none}}
#modalBack{
  position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:60;display:none;
  align-items:center;justify-content:center;padding:16px;
}
#modalBack.show{display:flex}
#modalCard{
  width:min(400px,100%);background:var(--panel);border:1px solid var(--line);
  border-radius:16px;padding:18px;box-shadow:0 20px 48px rgba(0,0,0,.5);
}
#modalCard h3{margin:0 0 8px;font-size:16px}
#modalCard p{margin:0 0 14px;color:var(--muted);font-size:14px}
#modalCard .row{justify-content:flex-end}
</style>
</head>
<body>
<svg width="0" height="0" aria-hidden="true" style="position:absolute">
  <filter id="goo">
    <feGaussianBlur in="SourceGraphic" stdDeviation="3.2" result="blur"/>
    <feColorMatrix in="blur" mode="matrix"
      values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 18 -7" result="goo"/>
    <feBlend in="SourceGraphic" in2="goo"/>
  </filter>
</svg>
""".trimIndent()

    private val PART_B: String = """
<div id="app">
<header>
  <div class="brand">
    <div class="gooey-wrap" title="Bridge status"><div id="gooeyBlob" class="gooey-blob IDLE"></div></div>
    CWBridge
  </div>
  <span id="pill" class="chip IDLE">-</span>
  <span id="detail" class="detail"></span>
  <button type="button" class="ghost" onclick="logout()"><span class="ms sm">lock</span> Lock</button>
</header>
<nav class="sec" aria-label="Sections">
  <a href="#sec-status"><span class="ms sm">monitor_heart</span> Status</a>
  <a href="#sec-control"><span class="ms sm">tune</span> Control</a>
  <a href="#sec-domains"><span class="ms sm">language</span> Domains</a>
  <a href="#sec-store"><span class="ms sm">database</span> Store</a>
  <a href="#sec-services"><span class="ms sm">extension</span> Services</a>
  <a href="#sec-console"><span class="ms sm">terminal</span> Console</a>
</nav>
<main>
  <div class="card" id="sec-status">
    <h2><span class="ms sm">monitor_heart</span> Status</h2>
    <div class="row">
      <button type="button" class="soft" onclick="runDiagnose()"><span class="ms sm">troubleshoot</span> Diagnose</button>
      <button type="button" class="ghost" onclick="refreshAll()"><span class="ms sm">refresh</span> Refresh</button>
    </div>
    <div class="ready" id="readyList" aria-live="polite"></div>
    <ul id="diagOut" class="diag"></ul>
  </div>

  <div class="card" id="sec-control">
    <h2><span class="ms sm">tune</span> Remote control</h2>
    <div class="row">
      <button type="button" id="btnRestartBridge" onclick="ctlBusy(this,'restart-bridge')"><span class="ms sm">restart_alt</span> Restart bridge</button>
      <button type="button" id="btnRestartRoblox" onclick="ctlBusy(this,'restart-roblox')"><span class="ms sm">sports_esports</span> Restart Roblox</button>
      <button type="button" onclick="toggleBridge()"><span class="ms sm">power_settings_new</span> Toggle bridge</button>
      <!--SCREENSHOT_BUTTON-->
    </div>
    <div id="ctlMsg" class="msg"></div>
    <div id="shotBox"></div>
  </div>
""".trimIndent()

    private val PART_C: String = """
  <div class="card">
    <h2><span class="ms sm">keyboard</span> Keys</h2>
    <p class="hint">Roblox must be on-screen. Ctrl+T tries inject methods until one reports success.</p>
    <div class="row">
      <button type="button" onclick="ctl('ctrl-t')"><span class="ms sm">tab</span> Ctrl+T</button>
      <button type="button" onclick="ctl('enter')"><span class="ms sm">keyboard_return</span> Enter</button>
      <button type="button" class="ghost" onclick="ctl('clipboard')"><span class="ms sm">content_paste</span> Clipboard</button>
    </div>
  </div>

  <div class="card" id="sec-domains">
    <h2><span class="ms sm">language</span> Auto-open on CW load</h2>
    <p class="hint">Single domain. When CatWeb logs finished, opens in the current tab. Empty = off.</p>
    <div class="row">
      <div class="field"><input id="autoDomain" placeholder="67.rbx" autocomplete="off"></div>
      <button type="button" onclick="saveAutoDomain()"><span class="ms sm">save</span> Save</button>
      <button type="button" class="ghost" onclick="clearAutoDomain()">Clear</button>
    </div>
    <div id="autoDomMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2><span class="ms sm">edit_note</span> Edit domain database</h2>
    <p class="hint">Load keys for a domain, edit values, save or delete. Same store as invoke|save.</p>
    <div class="row">
      <div class="field"><input id="editDomain" list="domainListDatalist" placeholder="example.rbx"></div>
      <button type="button" onclick="loadDomainDb()"><span class="ms sm">folder_open</span> Load</button>
    </div>
    <datalist id="domainListDatalist"></datalist>
    <div id="domainDbMeta" class="hint"></div>
    <div id="domainDbRows" style="margin-top:8px;overflow:auto;max-height:320px"></div>
    <div class="row" style="margin-top:8px">
      <div class="field"><input id="newKey" placeholder="new key"></div>
      <div class="field"><input id="newVal" placeholder="value"></div>
      <button type="button" onclick="addDomainKey()"><span class="ms sm">add</span> Add</button>
    </div>
    <div id="domainDbMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2><span class="ms sm">open_in_browser</span> Open domains</h2>
    <p class="hint">One domain per line. Immediate open flow (not auto-on-load).</p>
    <textarea id="domainList" placeholder="example.rbx"></textarea>
    <div class="row" style="margin-top:8px">
      <div class="field"><label>URL bar X%</label><input id="urlX" value="50" inputmode="decimal"></div>
      <div class="field"><label>URL bar Y%</label><input id="urlY" value="6" inputmode="decimal"></div>
      <button type="button" onclick="openDomains()">Open all</button>
    </div>
    <div id="domMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2><span class="ms sm">terminal</span> Send invoke</h2>
    <div class="row">
      <div class="field"><input id="inv" placeholder="tap 50 85 | paste hello | save key value"></div>
      <button type="button" onclick="sendInvoke()"><span class="ms sm">send</span> Send</button>
    </div>
    <div id="invMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2><span class="ms sm">touch_app</span> Tap</h2>
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
  <div class="card" id="sec-services">
    <h2><span class="ms sm">extension</span> Services</h2>
    <p class="hint">Macros with triggers + actions. Enabled = always listening (log/time). Edit anytime.</p>
    <div id="svcList"></div>
    <div class="row" style="margin-top:10px">
      <div class="field"><input id="svcName" placeholder="New macro name"></div>
      <button type="button" onclick="createService()"><span class="ms sm">add</span> Create</button>
    </div>
    <div id="svcEditor" style="display:none;margin-top:12px;padding-top:12px;border-top:1px solid var(--line)">
      <div class="row">
        <div class="field"><label class="hint">Name</label><input id="edName"></div>
        <div class="field"><label class="hint">Description</label><input id="edDesc" placeholder="optional"></div>
        <label class="hint" style="display:flex;align-items:center;gap:6px;padding-top:18px">
          <input type="checkbox" id="edEnabled" checked> Enabled (always on)
        </label>
      </div>
      <h3 style="font-size:12px;letter-spacing:.06em;color:var(--muted);margin:14px 0 6px">TRIGGERS</h3>
      <div id="edTriggers"></div>
      <div class="row" style="margin-top:6px">
        <button type="button" class="ghost" onclick="addTrigger('LOG')"><span class="ms sm">article</span> Log match</button>
        <button type="button" class="ghost" onclick="addTrigger('TIME')"><span class="ms sm">schedule</span> Interval</button>
      </div>
      <h3 style="font-size:12px;letter-spacing:.06em;color:var(--muted);margin:14px 0 6px">ACTIONS</h3>
      <div id="edActions"></div>
      <div class="row" style="margin-top:6px">
        <select id="edAddAct" style="max-width:160px">
          <option value="DELAY">Delay</option>
          <option value="CTRL_T">Ctrl+T</option>
          <option value="PRESS_ENTER">Enter</option>
          <option value="SEND_TEXT">Send text</option>
          <option value="ENTER_TEXT">Paste + Enter</option>
          <option value="SET_VAR">Set variable</option>
          <option value="TEXT_MAN">TextMan</option>
          <option value="TAP">Tap</option>
        </select>
        <button type="button" class="ghost" onclick="addAction()"><span class="ms sm">add</span> Add action</button>
      </div>
      <div class="row" style="margin-top:12px">
        <button type="button" onclick="saveEditor()"><span class="ms sm">save</span> Save macro</button>
        <button type="button" class="ghost" onclick="closeEditor()">Close</button>
        <button type="button" class="soft" onclick="runEditor()"><span class="ms sm">play_arrow</span> Run now</button>
      </div>
      <input type="hidden" id="edId">
    </div>
    <div id="svcMsg" class="msg"></div>
  </div>

  <div class="card" id="sec-limits">
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
    <div class="row" style="margin-top:10px">
      <div class="field"><label class="hint">Admin domain (can setlimit)</label>
        <input id="limAdminDomain" placeholder="admin.rbx"></div>
      <button type="button" class="ghost" onclick="saveAdminDomain()"><span class="ms sm">shield</span> Save admin</button>
    </div>
    <p class="hint">Game: invoke|storeinfo.domain.rbx → pastes 5.bits.limitBits.keys.used.max.left · invoke|setlimit.domain.rbx.type.value (0=reqs/day 1=data bits)</p>
    <div id="limitsMeta" class="hint" style="margin-top:8px"></div>
    <div id="limitsMsg" class="msg"></div>
  </div>

  <div class="card" id="sec-store">
    <h2><span class="ms sm">database</span> Storage</h2>
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
      <button type="button" onclick="saveKey()"><span class="ms sm">save</span> Save</button>
    </div>
    <div id="storeMsg" class="msg"></div>
  </div>
""".trimIndent()

    private val PART_E: String = """
  <div class="card">
    <h2><span class="ms sm">data_object</span> Variables</h2>
    <div class="row" style="margin-bottom:8px">
      <button type="button" class="ghost" onclick="copyVars()"><span class="ms sm">content_copy</span> Copy</button>
    </div>
    <div style="overflow-x:auto">
      <table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody id="varRows"></tbody></table>
    </div>
  </div>

  <div class="card" id="sec-console">
    <h2><span class="ms sm">terminal</span> Console</h2>
    <pre id="console">-</pre>
    <div class="row" style="margin-top:10px">
      <button type="button" class="ghost" onclick="refreshConsole()"><span class="ms sm">refresh</span> Refresh</button>
      <button type="button" class="ghost" onclick="copyConsole()"><span class="ms sm">content_copy</span> Copy</button>
      <label style="display:flex;align-items:center;gap:6px;color:var(--muted)">
        <input type="checkbox" id="auto" checked style="width:auto;min-height:0"> auto
      </label>
    </div>
  </div>
</main>
<div class="footer">CWBridge web panel</div>
</div>
<div id="toastHost" aria-live="polite" aria-relevant="additions"></div>
<div id="modalBack" role="dialog" aria-modal="true" aria-labelledby="modalTitle">
  <div id="modalCard">
    <h3 id="modalTitle">Confirm</h3>
    <p id="modalBody">Are you sure?</p>
    <div class="row">
      <button type="button" class="ghost" id="modalCancel">Cancel</button>
      <button type="button" class="danger" id="modalOk">Confirm</button>
    </div>
  </div>
</div>
""".trimIndent()

    private val PART_F: String = """
<script>
var RELOADING = false;
function D(id){ return document.getElementById(id); }
function esc(s){
  return String(s == null ? "" : s)
    .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
    .replace(/"/g,"&quot;").replace(/'/g,"&#39;");
}
function arg(s){ return JSON.stringify(String(s == null ? "" : s)); }
function toast(text, kind){
  var host = D("toastHost");
  if(!host) return;
  var el = document.createElement("div");
  el.className = "toast" + (kind === "good" || kind === true ? " good" : (kind === "err" || kind === false ? " err" : ""));
  el.textContent = text;
  host.appendChild(el);
  setTimeout(function(){ try{ el.remove(); }catch(e){} }, 3200);
}
function msg(id, text, kind){
  var el = D(id);
  if(!el) return;
  el.textContent = text || "";
  el.className = "msg" + (kind === "good" || kind === true ? " good" : (kind === "err" || kind === false ? " err" : ""));
  if(text) toast(text, kind);
}
function setBusy(btn, on){
  if(!btn) return;
  if(on){ btn.classList.add("busy"); btn.disabled = true; }
  else { btn.classList.remove("busy"); btn.disabled = false; }
}
function confirmModal(title, body){
  return new Promise(function(resolve){
    var back = D("modalBack");
    D("modalTitle").textContent = title || "Confirm";
    D("modalBody").textContent = body || "Are you sure?";
    back.classList.add("show");
    function done(v){
      back.classList.remove("show");
      D("modalOk").onclick = null;
      D("modalCancel").onclick = null;
      resolve(v);
    }
    D("modalOk").onclick = function(){ done(true); };
    D("modalCancel").onclick = function(){ done(false); };
  });
}
async function api(path, opt){
  var res = await fetch(path, Object.assign({credentials:"same-origin"}, opt || {}));
  if(res.status === 401){
    RELOADING = true;
    location.reload();
    throw new Error("locked");
  }
  var ct = res.headers.get("content-type") || "";
  if(ct.indexOf("application/json") >= 0){
    var data = await res.json();
    if(!res.ok) throw new Error(data.error || data.message || ("HTTP " + res.status));
    return data;
  }
  if(!res.ok) throw new Error("HTTP " + res.status);
  return res;
}
async function get(path){
  var r = await fetch(path, {credentials:"same-origin"});
  if(r.status === 401){ RELOADING = true; location.reload(); throw new Error("locked"); }
  return r.json();
}
function post(path, body){
  return api(path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(body || {})
  });
}
async function logout(){
  try{ await post("/api/logout"); }catch(e){}
  RELOADING = true;
  location.reload();
}
function renderReady(s){
  var host = D("readyList");
  if(!host) return;
  function row(on, label, detail){
    return "<div class=\"r\"><span class=\"dot " + (on ? "on" : "off") + "\"></span>" +
      "<span><strong>" + esc(label) + "</strong>" +
      (detail ? " <span style=\"color:var(--muted)\">" + esc(detail) + "</span>" : "") +
      "</span></div>";
  }
  host.innerHTML =
    row(!!s.accessibility, "Accessibility", s.accessibility ? "bound" : "off") +
    row(!!s.shizuku, "Shizuku", s.shizuku ? "ready" : "not ready") +
    row(!!s.catwebReady, "CatWeb ready", s.catwebReady ? "finished signal" : "waiting");
}
async function refreshStatus(){
  try{
    var s = await api("/api/status");
    var st = (s.state || "IDLE").toUpperCase();
    var p = D("pill");
    p.textContent = st;
    p.className = "chip " + st;
    var g = D("gooeyBlob");
    if(g) g.className = "gooey-blob " + st;
    D("detail").textContent = s.detail || "";
    renderReady(s);
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
async function ctlBusy(btn, action){
  setBusy(btn, true);
  try{ await ctl(action); }
  finally{ setBusy(btn, false); }
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
    var ts = new Date().toISOString().replace(/[:.]/g, "-");
    D("shotBox").innerHTML =
      "<img id=\"shotImg\" alt=\"screenshot\" src=\"" + url + "\">" +
      "<div class=\"row\" style=\"margin-top:8px\">" +
      "<a class=\"btn ghost\" id=\"shotDl\" download=\"cwbridge-" + ts + ".png\" href=\"" + url + "\">" +
      "<span class=\"ms sm\">download</span> Download</a></div>";
    msg("ctlMsg", "screenshot ok", "good");
  }catch(e){ msg("ctlMsg", e.message, "err"); }
}


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

async function loadDomainDb(){
  var d = (D("editDomain").value || "").trim();
  if(!d){ msg("domainDbMsg", "enter a domain", false); return; }
  try {
    var r = await get("/api/store/keys?domain=" + encodeURIComponent(d));
    if(r.error){ msg("domainDbMsg", r.error, false); return; }
    D("domainDbMeta").textContent = (r.domain || d) + " — " + (r.used || "?") + " / " + (r.limit || "?");
    var keys = r.keys || [];
    if(!keys.length){
      D("domainDbRows").innerHTML = "<div class=\"empty\"><span class=\"ms\">inbox</span>No keys yet</div>";
    } else {
      D("domainDbRows").innerHTML = keys.map(function(k, i){
        var key = k.key || "";
        var val = k.value || "";
        var id = "kv_" + i;
        return "<div class=\"row\" style=\"margin-bottom:6px;align-items:flex-start\">" +
          "<div class=\"field\" style=\"flex:0 0 28%\"><label>" + esc(key) + "</label></div>" +
          "<div class=\"field\" style=\"flex:1\"><textarea id=\"" + id + "\" rows=\"2\">" + esc(val) + "</textarea></div>" +
          "<button type=\"button\" onclick=\"saveDomainKey(" + arg(key) + ",'" + id + "')\">Save</button>" +
          "<button type=\"button\" class=\"danger\" onclick=\"deleteDomainKey(" + arg(key) + ")\">Del</button></div>";
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
  var ok = await confirmModal("Delete key", "Delete " + key + " from " + d + "?");
  if(!ok) return;
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
async function sendInvoke(){
  var cmd = (D("inv").value || "").trim();
  if(!cmd){ msg("invMsg", "empty", "err"); return; }
  try{
    var r = await post("/api/invoke", {command: cmd});
    msg("invMsg", r.message || "sent", "good");
    refreshConsole();
  }catch(e){ msg("invMsg", e.message, "err"); }
}
async function tapPercent(){
  try{
    var r = await post("/api/tap", {mode:"percent", x: parseFloat(D("tx").value), y: parseFloat(D("ty").value)});
    msg("tapMsg", r.message || "ok", "good");
  }catch(e){ msg("tapMsg", e.message, "err"); }
}
async function tapPx(){
  try{
    var r = await post("/api/tap", {mode:"px", x: parseFloat(D("tx").value), y: parseFloat(D("ty").value)});
    msg("tapMsg", r.message || "ok", "good");
  }catch(e){ msg("tapMsg", e.message, "err"); }
}
async function tapText(){
  try{
    var r = await post("/api/tap", {mode:"text", text: D("ttext").value});
    msg("tapMsg", r.message || "ok", "good");
  }catch(e){ msg("tapMsg", e.message, "err"); }
}

var ED = {id:'',name:'',description:'',isEnabled:true,triggers:[],actions:[]};
function uid(){ return String(Date.now()) + Math.floor(Math.random()*1000); }
async function refreshServices(){
  try{
    var s = await api("/api/services");
    var list = s.services || [];
    if(!list.length){
      D("svcList").innerHTML = "<div class=\"empty\"><span class=\"ms\">extension_off</span>No macros yet — create one</div>";
      return;
    }
    D("svcList").innerHTML = list.map(function(sv){
      var on = sv.enabled !== false;
      return "<div class=\"row\" style=\"margin-bottom:8px;align-items:center\">" +
        "<span style=\"flex:1;min-width:0\"><b>" + esc(sv.name||sv.id) + "</b>" +
        " <span class=\"hint\">" + (sv.triggers||0) + " trig · " + (sv.actions||0) + " act" +
        (on ? " · <span class=\"ok\">ON</span>" : " · <span class=\"bad\">OFF</span>") + "</span></span>" +
        "<button type=\"button\" class=\"ghost\" onclick=\"toggleService(" + arg(sv.id) + "," + (!on) + ")\">" +
        (on ? "Disable" : "Enable") + "</button>" +
        "<button type=\"button\" class=\"soft\" onclick=\"editService(" + arg(sv.id) + ")\">Edit</button>" +
        "<button type=\"button\" class=\"soft\" onclick=\"runService(" + arg(sv.id) + ")\">Run</button>" +
        "<button type=\"button\" class=\"ghost\" onclick=\"deleteService(" + arg(sv.id) + ")\">Del</button></div>";
    }).join("");
  }catch(e){}
}
async function createService(){
  try{
    var name = (D("svcName").value||"").trim();
    if(!name){ msg("svcMsg","name required","err"); return; }
    var r = await post("/api/services", {name: name});
    msg("svcMsg", r.message || "created", "good");
    D("svcName").value = "";
    await refreshServices();
    if(r.id) editService(r.id);
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function runService(id){
  try{
    var r = await post("/api/services/" + encodeURIComponent(id) + "/run");
    msg("svcMsg", r.message || "ran", "good"); toast(r.message||"ran", true);
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function toggleService(id, enabled){
  try{
    var r = await post("/api/services/" + encodeURIComponent(id), {enabled: !!enabled});
    msg("svcMsg", r.message || "ok", "good");
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function deleteService(id){
  if(!confirm("Delete this macro?")) return;
  try{
    var r = await api("/api/services/" + encodeURIComponent(id), {method:"DELETE"});
    msg("svcMsg", (r && r.message) || "deleted", "good");
    if(ED.id===id) closeEditor();
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function editService(id){
  try{
    var r = await api("/api/services/" + encodeURIComponent(id));
    var s = r.service;
    if(!s){ msg("svcMsg","load failed","err"); return; }
    ED = {
      id: s.id,
      name: s.name||"",
      description: s.description||"",
      isEnabled: s.isEnabled !== false && s.enabled !== false,
      triggers: (s.triggers||[]).slice(),
      actions: (s.actions||[]).slice()
    };
    D("edId").value = ED.id;
    D("edName").value = ED.name;
    D("edDesc").value = ED.description;
    D("edEnabled").checked = !!ED.isEnabled;
    renderEditorLists();
    D("svcEditor").style.display = "block";
    D("svcEditor").scrollIntoView({behavior:"smooth",block:"nearest"});
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
function closeEditor(){
  D("svcEditor").style.display = "none";
  ED = {id:'',name:'',description:'',isEnabled:true,triggers:[],actions:[]};
}
function renderEditorLists(){
  var tg = D("edTriggers");
  if(!ED.triggers.length) tg.innerHTML = "<div class=\"hint\">No triggers — macro only runs via Run button</div>";
  else tg.innerHTML = ED.triggers.map(function(tr,i){
    var kind = (tr._kind||tr.type||"LOG").toString().toUpperCase();
    var body = "";
    if(kind==="TIME"){
      body = "every <input type=\"number\" style=\"width:90px\" value=\"" + esc(String(tr.intervalMs||1000)) +
        "\" onchange=\"ED.triggers["+i+"].intervalMs=parseInt(this.value,10)||1000\"> ms";
    } else {
      body = "pattern <input style=\"min-width:140px\" value=\"" + esc(tr.pattern||"") +
        "\" onchange=\"ED.triggers["+i+"].pattern=this.value\">";
    }
    return "<div class=\"row\" style=\"margin-bottom:4px\"><span class=\"chip\">" + esc(kind) +
      "</span> " + body +
      " <button type=\"button\" class=\"ghost\" onclick=\"ED.triggers.splice("+i+",1);renderEditorLists()\">×</button></div>";
  }).join("");
  var ac = D("edActions");
  if(!ED.actions.length) ac.innerHTML = "<div class=\"hint\">No actions yet</div>";
  else ac.innerHTML = ED.actions.map(function(a,i){
    var kind = (a._kind||a.type||"?").toString().toUpperCase();
    var body = "";
    if(kind==="DELAY") body = "<input type=\"number\" style=\"width:90px\" value=\""+esc(String(a.milliseconds||1000))+"\" onchange=\"ED.actions["+i+"].milliseconds=parseInt(this.value,10)||1000\"> ms";
    else if(kind==="SEND_TEXT"||kind==="ENTER_TEXT") body = "<input style=\"min-width:160px\" value=\""+esc(a.text||"")+"\" onchange=\"ED.actions["+i+"].text=this.value\" placeholder=\"text / ${'$'}var\">";
    else if(kind==="SET_VAR") body = "key <input style=\"width:90px\" value=\""+esc(a.key||"")+"\" onchange=\"ED.actions["+i+"].key=this.value\"> = <input style=\"width:120px\" value=\""+esc(a.value||"")+"\" onchange=\"ED.actions["+i+"].value=this.value\">";
    else if(kind==="TEXT_MAN") body = "src <input style=\"width:90px\" value=\""+esc(a.source||"${'$'}lastMatch")+"\" onchange=\"ED.actions["+i+"].source=this.value\"> → <input style=\"width:90px\" value=\""+esc(a.saveTo||"result")+"\" onchange=\"ED.actions["+i+"].saveTo=this.value\">";
    else if(kind==="TAP") body = "text <input style=\"width:120px\" value=\""+esc(a.text||"")+"\" onchange=\"ED.actions["+i+"].text=this.value\" placeholder=\"or use %\">";
    else body = "<span class=\"hint\">(no params)</span>";
    return "<div class=\"row\" style=\"margin-bottom:4px\"><span class=\"chip\">" + (i+1) + ". " + esc(kind) +
      "</span> " + body +
      " <button type=\"button\" class=\"ghost\" onclick=\"ED.actions.splice("+i+",1);renderEditorLists()\">×</button></div>";
  }).join("");
}
function addTrigger(kind){
  if(kind==="TIME"){
    ED.triggers.push({_kind:"TIME",id:uid(),name:"Interval",intervalMs:5000,repeat:true,type:"TIME"});
  } else {
    ED.triggers.push({_kind:"LOG",id:uid(),name:"Log",pattern:"invoke|",matchCase:false,useRegex:false,type:"LOG"});
  }
  renderEditorLists();
}
function addAction(){
  var kind = (D("edAddAct").value||"DELAY").toUpperCase();
  var a = {_kind:kind,id:uid(),name:kind,type:kind};
  if(kind==="DELAY") a.milliseconds = 500;
  if(kind==="SEND_TEXT"||kind==="ENTER_TEXT") a.text = "";
  if(kind==="SET_VAR"){ a.key=""; a.value=""; }
  if(kind==="TEXT_MAN"){ a.source="${'$'}lastMatch"; a.mode="full"; a.pattern=""; a.group=1; a.replaceWith=""; a.saveTo="result"; }
  if(kind==="TAP"){ a.text=""; }
  ED.actions.push(a);
  renderEditorLists();
}
async function saveEditor(){
  if(!ED.id){ msg("svcMsg","nothing open","err"); return; }
  ED.name = (D("edName").value||"").trim() || ED.name;
  ED.description = D("edDesc").value||"";
  ED.isEnabled = !!D("edEnabled").checked;
  ED.triggers.forEach(function(tr){ if(!tr._kind) tr._kind = (tr.type||"LOG"); });
  ED.actions.forEach(function(a){ if(!a._kind) a._kind = (a.type||"DELAY"); });
  try{
    var r = await post("/api/services/" + encodeURIComponent(ED.id), {json: JSON.stringify(ED)});
    if(r.error){ msg("svcMsg", r.error, "err"); toast(r.error,false); return; }
    msg("svcMsg", r.message || "saved", "good"); toast("Macro saved", true);
    refreshServices();
  }catch(e){ msg("svcMsg", e.message, "err"); }
}
async function runEditor(){
  if(!ED.id) return;
  await saveEditor();
  await runService(ED.id);
}

async function refreshStore(){
  try{
    var s = await api("/api/store");
    var rows = s.domains || [];
    D("storeRows").innerHTML = rows.map(function(d){
      return "<tr><td>" + esc(d.domain) + "</td><td>" + esc(d.keys) + "</td><td>" +
        esc(d.used) + "</td><td>" + esc(d.limit) + "</td><td>" +
        "<button type=\"button\" class=\"danger\" onclick=\"clearDomain(" + arg(d.domain) + ")\">Clear</button></td></tr>";
    }).join("") || "<tr><td colspan=\"5\"><div class=\"empty\"><span class=\"ms\">database</span>empty</div></td></tr>";
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
  var ok = await confirmModal("Clear domain", "Clear all keys in " + d + "?");
  if(!ok) return;
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
    }).join("") || "<tr><td colspan=\"2\"><div class=\"empty\"><span class=\"ms\">data_object</span>none</div></td></tr>";
  }catch(e){}
}
function copyVars(){
  var rows = D("varRows").innerText || "";
  if(navigator.clipboard) navigator.clipboard.writeText(rows).then(function(){ toast("vars copied", "good"); });
}
async function refreshConsole(){
  try{
    var s = await api("/api/logs");
    var pre = D("console");
    var atBottom = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 40;
    pre.textContent = (s.lines || []).join("\\n") || "(no lines)";
    if(atBottom) pre.scrollTop = pre.scrollHeight;
  }catch(e){}
}
function copyConsole(){
  var t = D("console").textContent || "";
  if(navigator.clipboard) navigator.clipboard.writeText(t).then(function(){ toast("console copied", "good"); });
}
function refreshAll(){
  refreshStatus(); refreshServices(); refreshStore(); refreshVars(); refreshConsole();
  loadAutoDomain(); refreshDomainDatalist(); loadLimits();
}
var inv = D("inv");
if(inv) inv.addEventListener("keydown", function(e){
  if(e.key === "Enter"){ e.preventDefault(); sendInvoke(); }
});
document.fonts && document.fonts.load && document.fonts.load('20px "Material Symbols Rounded"').catch(function(){});
setInterval(function(){
  if(RELOADING) return;
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
        """<button type="button" class="ghost" onclick="loadShot()"><span class="ms sm">photo_camera</span> Screenshot</button>"""
}
