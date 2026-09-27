package com.cwbridge.android

/**
 * Single-page control panel for the built-in web server, served straight from
 * the APK as a string so there is no asset-loading plumbing. The dark palette
 * matches the native app.
 *
 * Built in parts so no single edit is huge; [PAGE] stitches them together.
 */
object WebUi {

    private val PART_A: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CWBridge</title>
<style>
:root{
  --bg:#0A0B0D; --surface:#14161B; --elevated:#1C1F26; --line:#26FFFFFF;
  --primary:#9BB8E8; --muted:#8B909A; --ok:#8FAD86; --warn:#C4A574; --danger:#C47A7A;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:#fff;font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{position:sticky;top:0;background:var(--surface);border-bottom:1px solid var(--line);
  padding:14px 18px;display:flex;align-items:center;gap:12px;flex-wrap:wrap;z-index:5}
h1{font-size:16px;margin:0;letter-spacing:.04em}
.pill{padding:3px 10px;border-radius:999px;font-size:11px;font-weight:600;text-transform:uppercase}
.pill.ACTIVE{background:#26331f;color:var(--ok)}
.pill.WAITING{background:#33291a;color:var(--warn)}
.pill.ERROR{background:#331f1f;color:var(--danger)}
.pill.IDLE{background:#24262b;color:var(--muted)}
main{padding:18px;max-width:1000px;margin:0 auto;display:grid;gap:18px}
.card{background:var(--elevated);border:1px solid var(--line);border-radius:16px;padding:16px}
h2{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);margin:0 0 12px}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
button{background:var(--primary);color:#0A0B0D;border:0;border-radius:10px;padding:9px 14px;
  font-size:13px;font-weight:600;cursor:pointer}
button.ghost{background:transparent;color:var(--primary);border:1px solid var(--line)}
button.danger{background:var(--danger);color:#fff}
input{background:var(--surface);border:1px solid var(--line);border-radius:10px;
  padding:9px 11px;color:#fff;font:inherit;font-size:13px;min-width:0}
input:focus{outline:1px solid var(--primary)}
label{color:var(--muted);font-size:12px}
pre{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:12px;
  overflow:auto;max-height:340px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11px;
  color:var(--primary);white-space:pre-wrap;word-break:break-word;margin:0}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{text-align:left;padding:8px 6px;border-bottom:1px solid var(--line)}
th{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.06em}
.bar{height:6px;background:var(--surface);border-radius:999px;overflow:hidden;min-width:90px}
.bar>span{display:block;height:100%;background:var(--primary)}
.msg{margin-top:10px;font-size:12px;color:var(--muted);min-height:16px}
.msg.err{color:var(--danger)}
.msg.good{color:var(--ok)}
.grid2{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px}
.shot{width:100%;border-radius:10px;border:1px solid var(--line);background:#000}
#login{max-width:360px;margin:14vh auto}
.hide{display:none!important}
</style>
</head>
<body>
""".trimIndent()

    private val PART_B: String = """

<div id="login" class="card hide">
  <h2>Remote access</h2>
  <p style="color:var(--muted);font-size:13px;margin:0 0 12px">
    This device is not on your local network, so the generated password is required.
    You can find it in the app under <strong>Server</strong>.
  </p>
  <div class="row">
    <input id="pw" type="password" placeholder="Server password" style="flex:1">
    <button onclick="doLogin()">Unlock</button>
  </div>
  <div id="loginMsg" class="msg"></div>
</div>

<div id="app" class="hide">
<header>
  <h1>CWBridge</h1>
  <span id="pill" class="pill IDLE">-</span>
  <span id="detail" style="color:var(--muted);font-size:12px"></span>
  <span style="flex:1"></span>
  <button class="ghost" onclick="logout()">Lock</button>
</header>

<main>
  <div class="card">
    <h2>Remote control</h2>
    <div class="row">
      <button onclick="ctl('restart-bridge')">Restart bridge</button>
      <button onclick="ctl('restart-roblox')">Restart Roblox</button>
      <!--SCREENSHOT_BUTTON-->
      <button class="ghost" onclick="toggleBridge()">Toggle bridge</button>
    </div>
    <img id="shot" class="shot hide" alt="screenshot">
    <div id="ctlMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Send invoke command</h2>
    <div class="row">
      <input id="inv" placeholder="tap.50.85  |  paste.hello  |  save.key.value" style="flex:1">
      <button onclick="sendInvoke()">Send</button>
    </div>
    <div id="invMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Tap</h2>
    <div class="grid2">
      <div class="row"><input id="tx" placeholder="X %" style="width:80px">
        <input id="ty" placeholder="Y %" style="width:80px">
        <button onclick="tapPercent()">Tap %</button></div>
      <div class="row"><input id="pxx" placeholder="X px" style="width:80px">
        <input id="pxy" placeholder="Y px" style="width:80px">
        <button onclick="tapPx()">Tap px</button></div>
      <div class="row"><input id="txt" placeholder="Button text" style="flex:1">
        <button onclick="tapText()">Tap text</button></div>
      <div class="row"><button class="ghost" onclick="ctl('ctrl-t')">Ctrl+T</button>
        <button class="ghost" onclick="ctl('enter')">Enter</button>
        <button class="ghost" onclick="ctl('clipboard')">Read clipboard</button></div>
    </div>
    <div id="clipOut" class="msg"></div>
  </div>
""".trimIndent()

    private val PART_C: String = """
  <div class="card">
    <h2>Services</h2>
    <div id="svcList"></div>
    <div class="row" style="margin-top:12px">
      <input id="svcName" placeholder="New service name" style="flex:1">
      <button onclick="createService()">Create</button>
    </div>
    <div id="svcMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Storage and limits</h2>
    <table><thead><tr><th>Domain</th><th>Keys</th><th>Used</th><th>Limit</th><th>Usage</th><th></th></tr></thead>
      <tbody id="storeRows"></tbody></table>
    <div class="row" style="margin-top:12px">
      <input id="qDomain" placeholder="name.rbx" style="width:150px">
      <input id="qLimit" placeholder="limit e.g. 2MB, 512KB, 1GB" style="width:220px">
      <button onclick="setLimit()">Set limit</button>
    </div>
    <div class="row" style="margin-top:8px">
      <input id="sDomain" placeholder="domain" style="width:150px">
      <input id="sKey" placeholder="key" style="width:150px">
      <input id="sVal" placeholder="value" style="flex:1">
      <button onclick="saveKey()">Save key</button>
    </div>
    <div id="storeMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Variables</h2>
    <table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody id="varRows"></tbody></table>
  </div>

  <div class="card">
    <h2>Console</h2>
    <pre id="console">-</pre>
    <div class="row" style="margin-top:10px">
      <button class="ghost" onclick="refreshConsole()">Refresh</button>
      <label><input type="checkbox" id="auto" checked style="vertical-align:-1px"> auto</label>
    </div>
  </div>
</main>
</div>
""".trimIndent()

    private val PART_D: String = """
<script>
const D = id => document.getElementById(id);
let NEEDS_LOGIN = false;

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function(c){
    var m = {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'};
    return m[c];
  });
}

async function api(path, opts) {
  const res = await fetch(path, Object.assign({credentials:'same-origin'}, opts || {}));
  if (res.status === 401) { NEEDS_LOGIN = true; showLogin(); throw new Error('locked'); }
  const ct = res.headers.get('content-type') || '';
  if (ct.indexOf('application/json') < 0) {
    if (!res.ok) throw new Error(await res.text());
    return res;
  }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || ('error ' + res.status));
  return data;
}

const post = (p, body) => api(p, {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify(body || {})
});

function msg(id, text, kind) {
  const el = D(id);
  el.textContent = text || '';
  el.className = 'msg' + (kind ? ' ' + kind : '');
}

function showLogin() {
  D('login').classList.remove('hide');
  D('app').classList.add('hide');
  setTimeout(function(){ D('pw').focus(); }, 30);
}

async function doLogin() {
  try {
    await post('/api/login', {password: D('pw').value});
    NEEDS_LOGIN = false;
    D('login').classList.add('hide');
    D('app').classList.remove('hide');
    msg('loginMsg', '');
    refreshAll();
  } catch (e) { msg('loginMsg', e.message, 'err'); }
}

async function logout() {
  try { await post('/api/logout'); } catch (e) {}
  NEEDS_LOGIN = true; showLogin();
}

function refreshAll() {
  refreshStatus(); refreshServices(); refreshStore(); refreshVars(); refreshConsole();
}

async function refreshStatus() {
  try {
    const s = await api('/api/status');
    const p = D('pill');
    p.className = 'pill ' + (s.state || 'IDLE');
    p.textContent = s.state || 'IDLE';
    D('detail').textContent = s.detail || '';
  } catch (e) {}
}

async function toggleBridge() {
  try {
    const r = await post('/api/bridge/toggle');
    msg('ctlMsg', r.message, 'good');
    setTimeout(refreshStatus, 300);
  } catch (e) { msg('ctlMsg', e.message, 'err'); }
}

async function ctl(action) {
  msg('ctlMsg', 'working...');
  try {
    const r = await post('/api/control/' + action);
    msg('ctlMsg', r.message, 'good');
    if (action === 'clipboard') D('clipOut').textContent = r.message;
    setTimeout(refreshStatus, 400);
  } catch (e) { msg('ctlMsg', e.message, 'err'); }
}

async function loadShot() {
  msg('ctlMsg', 'capturing...');
  try {
    const res = await api('/api/screenshot');
    const url = URL.createObjectURL(await res.blob());
    const el = D('shot');
    el.src = url; el.classList.remove('hide');
    msg('ctlMsg', 'screenshot captured', 'good');
  } catch (e) { msg('ctlMsg', e.message, 'err'); }
}

async function sendInvoke() {
  const cmd = D('inv').value.trim();
  if (!cmd) return;
  try {
    await post('/api/invoke', {command: cmd});
    msg('invMsg', 'sent: ' + cmd, 'good');
    setTimeout(refreshConsole, 700);
  } catch (e) { msg('invMsg', e.message, 'err'); }
}
""".trimIndent()

    private val PART_E: String = """
async function tapPercent() {
  const x = D('tx').value, y = D('ty').value;
  if (x === '' || y === '') return msg('ctlMsg', 'enter X and Y', 'err');
  try { const r = await post('/api/tap', {mode:'percent', x:+x, y:+y});
    msg('ctlMsg', r.message, 'good'); } catch (e) { msg('ctlMsg', e.message, 'err'); }
}
async function tapPx() {
  const x = D('pxx').value, y = D('pxy').value;
  if (x === '' || y === '') return msg('ctlMsg', 'enter X and Y', 'err');
  try { const r = await post('/api/tap', {mode:'px', x:+x, y:+y});
    msg('ctlMsg', r.message, 'good'); } catch (e) { msg('ctlMsg', e.message, 'err'); }
}
async function tapText() {
  const t = D('txt').value;
  if (!t) return msg('ctlMsg', 'enter text', 'err');
  try { const r = await post('/api/tap', {mode:'text', text:t});
    msg('ctlMsg', r.message, 'good'); } catch (e) { msg('ctlMsg', e.message, 'err'); }
}

async function refreshServices() {
  try {
    const s = await api('/api/services');
    if (!s.services.length) {
      D('svcList').innerHTML = '<div class="msg">no services yet</div>';
      return;
    }
    D('svcList').innerHTML = s.services.map(function(v){
      const id = esc(v.id);
      return '<div class="row" style="border-bottom:1px solid var(--line);padding:8px 0">' +
        '<strong style="flex:1">' + esc(v.name) + '</strong>' +
        '<span class="pill ' + (v.enabled ? 'ACTIVE' : 'IDLE') + '">' +
          (v.enabled ? 'on' : 'off') + '</span>' +
        '<span style="color:var(--muted);font-size:11px">' + v.triggers + 'T/' + v.actions + 'A</span>' +
        '<button onclick="runService(\'' + id + '\')">Run</button>' +
        '<button class="ghost" onclick="toggleService(\'' + id + '\',' + (!v.enabled) + ')">' +
          (v.enabled ? 'Disable' : 'Enable') + '</button>' +
        '<button class="ghost" onclick="editService(\'' + id + '\')">Edit</button>' +
        '<button class="danger" onclick="deleteService(\'' + id + '\')">Delete</button>' +
        '</div>';
    }).join('');
  } catch (e) { msg('svcMsg', e.message, 'err'); }
}

async function createService() {
  const name = D('svcName').value.trim();
  if (!name) return msg('svcMsg', 'enter a name', 'err');
  try {
    await post('/api/services', {name: name});
    D('svcName').value = '';
    msg('svcMsg', 'created - use Edit to add triggers and actions', 'good');
    refreshServices();
  } catch (e) { msg('svcMsg', e.message, 'err'); }
}

async function editService(id) {
  let s;
  try { s = await api('/api/services/' + encodeURIComponent(id)); }
  catch (e) { return msg('svcMsg', e.message, 'err'); }
  const next = prompt('Service JSON:', JSON.stringify(s.service, null, 2));
  if (next === null) return;
  try {
    await post('/api/services/' + encodeURIComponent(id), {json: next});
    msg('svcMsg', 'saved', 'good');
    refreshServices();
  } catch (e) { msg('svcMsg', e.message, 'err'); }
}

async function runService(id) {
  try { const r = await post('/api/services/' + encodeURIComponent(id) + '/run');
    msg('svcMsg', r.message, 'good'); } catch (e) { msg('svcMsg', e.message, 'err'); }
}
async function toggleService(id, enable) {
  try {
    await post('/api/services/' + encodeURIComponent(id), {enabled: enable});
    msg('svcMsg', enable ? 'enabled' : 'disabled', 'good');
    refreshServices();
  } catch (e) { msg('svcMsg', e.message, 'err'); }
}
async function deleteService(id) {
  if (!confirm('Delete this service?')) return;
  try {
    await api('/api/services/' + encodeURIComponent(id), {method:'DELETE'});
    msg('svcMsg', 'deleted', 'good');
    refreshServices();
  } catch (e) { msg('svcMsg', e.message, 'err'); }
}
""".trimIndent()

    private val PART_F: String = """
async function refreshStore() {
  try {
    const s = await api('/api/store');
    D('storeRows').innerHTML = s.domains.map(function(d){
      const pct = d.unlimited ? 0 : Math.min(100, Math.round(d.used / d.limit * 100));
      return '<tr><td>' + esc(d.domain) + '</td><td>' + d.keys + '</td>' +
        '<td>' + esc(d.used) + '</td><td>' + (d.unlimited ? 'unlimited' : esc(d.limit)) + '</td>' +
        '<td><div class="bar"><span style="width:' + pct + '%"></span></div></td>' +
        '<td><button class="danger" onclick="clearDomain(\'' + esc(d.domain) + '\')">Clear</button></td></tr>';
    }).join('') || '<tr><td colspan="6" style="color:var(--muted)">no domains yet</td></tr>';
  } catch (e) { msg('storeMsg', e.message, 'err'); }
}

async function setLimit() {
  const d = D('qDomain').value.trim(), l = D('qLimit').value.trim();
  if (!d) return msg('storeMsg', 'enter a domain like mygame.rbx', 'err');
  try { const r = await post('/api/limits', {domain: d, limit: l});
    msg('storeMsg', r.message, 'good'); refreshStore();
  } catch (e) { msg('storeMsg', e.message, 'err'); }
}

async function saveKey() {
  const d = D('sDomain').value.trim(), k = D('sKey').value.trim();
  if (!d || !k) return msg('storeMsg', 'domain and key are required', 'err');
  try { const r = await post('/api/store', {domain: d, key: k, value: D('sVal').value});
    msg('storeMsg', r.message, 'good'); refreshStore();
  } catch (e) { msg('storeMsg', e.message, 'err'); }
}

async function clearDomain(d) {
  if (!confirm('Clear every key in ' + d + '?')) return;
  try { const r = await post('/api/store/clear', {domain: d});
    msg('storeMsg', r.message, 'good'); refreshStore();
  } catch (e) { msg('storeMsg', e.message, 'err'); }
}

async function refreshVars() {
  try {
    const s = await api('/api/vars');
    D('varRows').innerHTML = Object.keys(s.vars).map(function(k){
      return '<tr><td>' + esc(k) + '</td><td style="word-break:break-all">' +
        esc(s.vars[k]) + '</td></tr>';
    }).join('') || '<tr><td colspan="2" style="color:var(--muted)">no variables</td></tr>';
  } catch (e) {}
}

async function refreshConsole() {
  try {
    const s = await api('/api/logs');
    D('console').textContent = s.lines.join('\n') || '(no console lines yet)';
  } catch (e) {}
}

setInterval(function(){
  if (NEEDS_LOGIN) return;
  if (D('auto').checked) { refreshConsole(); refreshStatus(); }
}, 3000);

refreshAll();
</script>
</body>
</html>
""".trimIndent()

    /** The page with every feature button present. */
    private val FULL: String by lazy {
        PART_A + PART_B + PART_C + PART_D + PART_E + PART_F
    }

    /**
     * The control panel, with features this device cannot support silently
     * removed. Screenshot needs Android 11+, so on older devices the button is
     * not rendered at all rather than failing when clicked. The placeholder
     * marker in the markup is swapped for the button, or dropped.
     */
    fun page(showScreenshot: Boolean): String = FULL.replace(
        SCREENSHOT_MARKER,
        if (showScreenshot) SCREENSHOT_BUTTON else "",
    )

    private const val SCREENSHOT_MARKER = "<!--SCREENSHOT_BUTTON-->"
    private const val SCREENSHOT_BUTTON =
        """<button onclick="loadShot()">Take screenshot</button>"""
}
