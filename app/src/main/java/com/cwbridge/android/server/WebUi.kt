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
  --bg:#0b0e13; --panel:#11151c; --panel2:#171c24; --line:rgba(255,255,255,.08);
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
/* Top chips were stage-2 section anchors; sidebar is the sole nav (no double-nav on phone). */
nav.sec{display:none}
nav.sec a{display:none}
main{max-width:920px;margin:0 auto;padding:14px;display:grid;gap:16px}
.card{
  background:linear-gradient(180deg,var(--panel),var(--panel2));
  border:1px solid var(--line);border-radius:var(--radius);padding:14px 14px 12px;
  box-shadow:0 8px 24px rgba(0,0,0,.25);scroll-margin-top:110px;
}
.card h2{margin:0 0 10px;font-size:12px;font-weight:700;letter-spacing:.08em;
  text-transform:uppercase;color:var(--muted);display:flex;align-items:center;gap:8px}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.grid3{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
@media(max-width:720px){.grid3{grid-template-columns:1fr 1fr}}
@media(max-width:560px){.grid2,.grid3{grid-template-columns:1fr}}
.stat-card{
  background:var(--panel2);border:1px solid var(--line);border-radius:14px;padding:14px 16px;
  display:flex;flex-direction:column;gap:6px;min-height:88px
}
.stat-card .stat-label{font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}
.stat-card .stat-value{font-size:1.55rem;font-weight:750;letter-spacing:-.02em;line-height:1.15}
.stat-card .stat-sub{font-size:12px;color:var(--muted)}
.stat-card.ok .stat-value{color:var(--ok)}
.stat-card.warn .stat-value{color:var(--warn)}
.stat-card.danger .stat-value{color:var(--danger)}
.stat-card.accent .stat-value{color:var(--accent)}
.storage-wrap{margin-top:4px}
.storage-meta{display:flex;justify-content:space-between;align-items:baseline;gap:8px;margin-bottom:8px}
.storage-meta .big{font-size:1.35rem;font-weight:750}
.storage-meta .cap{font-size:13px;color:var(--muted)}
.bar{
  height:12px;border-radius:999px;background:rgba(255,255,255,.06);overflow:hidden;
  border:1px solid var(--line)
}
.bar > i{
  display:block;height:100%;width:0%;border-radius:999px;
  background:linear-gradient(90deg,#3dd68c,#6ea8fe);
  transition:width .45s ease, background .45s ease
}
.bar.mid > i{background:linear-gradient(90deg,#f5a524,#f5a524)}
.bar.hot > i{background:linear-gradient(90deg,#f76c6c,#f5a524)}
.bar.full > i{background:linear-gradient(90deg,#f76c6c,#c0392b)}
.about-list{list-style:none;margin:0;padding:0;display:grid;gap:8px}
.about-list li{
  display:flex;justify-content:space-between;gap:12px;padding:10px 12px;
  background:var(--panel2);border:1px solid var(--line);border-radius:12px
}
.about-list .k{color:var(--muted);font-size:12px;font-weight:650}
.about-list .v{font-weight:650;text-align:right;word-break:break-all}
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
#console{
  margin:0;padding:10px;border-radius:10px;background:#0a0c10;border:1px solid var(--line);
  max-height:280px;overflow:auto;font:12px/1.45 ui-monospace,Consolas,monospace;white-space:pre-wrap;
}
#console .log-line{display:block;padding:1px 0;word-break:break-word}
#console .tag-BRIDGE{color:#ffffff;font-weight:650}
#console .tag-APP{color:#f5d76e;font-weight:650}
#console .tag-ROBOT{color:#9aa3b5;font-weight:650}
#console .tag-SHIZUKU{color:#6ea8fe;font-weight:650}
#console .tag-other{color:var(--muted);font-weight:650}
#console .log-empty{color:var(--muted)}
#shotBox img{max-width:100%;border-radius:10px;border:1px solid var(--line);margin-top:8px}
.empty{
  color:var(--muted);font-size:13px;padding:16px;text-align:center;
  border:1px dashed var(--line);border-radius:12px;display:flex;flex-direction:column;
  align-items:center;gap:8px;
}
.footer{display:none}
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

img#shotImg{max-width:100%;height:auto;border-radius:10px;border:1px solid var(--line);cursor:crosshair;touch-action:none;user-select:none}
.stream-on #btnStream{background:var(--good)}
#streamImg,#shotImg{cursor:crosshair;width:100%;max-width:100%;display:block;touch-action:none;user-select:none}
#shotBox{margin-top:10px}

/* Stage 3 shell */
.shell{display:flex;min-height:100vh}
.sidebar{width:240px;flex-shrink:0;background:var(--panel);border-right:1px solid var(--line);display:flex;flex-direction:column;padding:14px 10px;position:fixed;inset:0 auto 0 0;z-index:30}
.sidebar .brand-side{display:flex;align-items:center;gap:8px;padding:8px 10px 14px;font-weight:700}
.sidebar .nav-side{display:flex;flex-direction:column;gap:2px;flex:1}
.nav-item{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:10px;color:var(--muted);text-decoration:none;font-size:13px;font-weight:650;background:transparent;border:0;width:100%;text-align:left;cursor:pointer;min-height:40px}
.nav-item:hover{background:var(--panel2);color:var(--text)}
.nav-item.active{background:rgba(110,168,254,.12);color:var(--accent)}
.main-wrap{flex:1;margin-left:240px;min-width:0}
.page{display:none}.page.active{display:block}
.top-slim{display:flex;align-items:center;gap:10px;padding:10px 14px;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:20;background:rgba(11,14,19,.9);backdrop-filter:blur(10px)}
.top-slim .menu{display:none}
@media(max-width:819px){
  .sidebar{transform:translateX(-105%);transition:transform .2s ease}
  .sidebar.open{transform:translateX(0);box-shadow:8px 0 32px rgba(0,0,0,.4)}
  .main-wrap{margin-left:0}
  .top-slim .menu{display:inline-flex}
  main{padding:12px;gap:14px;max-width:none}
  .card{padding:12px}
  .stat-card{min-height:0;padding:12px 14px}
}


/* CWBridge visual refresh */
:root{
  --bg:#080d16;--panel:#101827;--panel2:#151f30;--line:rgba(148,163,184,.14);
  --text:#edf3ff;--muted:#91a0b8;--accent:#78aaff;--accent2:#4f8df5;
  --ok:#43d69a;--warn:#f4bd62;--danger:#fb7777;--radius:18px;
}
html,body{background:radial-gradient(ellipse at 70% -15%,rgba(49,95,166,.18),transparent 38%),var(--bg);color:var(--text)}
.sidebar{width:252px;background:rgba(12,19,31,.97);border-right:1px solid var(--line);padding:20px 12px}
.sidebar .brand-side{font-size:16px;padding:8px 12px 26px;letter-spacing:.02em}
.nav-side{gap:5px!important}
.nav-item{min-height:46px;padding:12px 14px;border-radius:12px;font-size:13px;transition:background .16s,color .16s}
.nav-item.active{background:linear-gradient(90deg,rgba(89,146,255,.19),rgba(89,146,255,.07));color:#a9caff;box-shadow:inset 2px 0 #78aaff}
.main-wrap{margin-left:252px}
.top-slim{min-height:60px;padding:10px 28px;background:rgba(8,13,22,.82);border-bottom-color:var(--line)}
.top-slim #pageTitle{font-size:13px;color:#cbd7eb}
main{max-width:none;padding:28px clamp(16px,3vw,48px) 48px;gap:18px}
.page-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;margin:6px 0 24px}
.page-hero .eyebrow{font-size:10px;letter-spacing:.14em;font-weight:750;color:#7f9bc4;text-transform:uppercase;margin-bottom:8px}
.page-hero h1{font-size:clamp(25px,3vw,34px);letter-spacing:-.045em;line-height:1.12;margin:0;font-weight:760}
.page-hero p{margin:8px 0 0;color:var(--muted);font-size:13px}
.card{background:linear-gradient(145deg,rgba(18,29,46,.96),rgba(14,22,36,.98));border:1px solid var(--line);border-radius:18px;padding:22px;box-shadow:0 12px 34px rgba(0,0,0,.16);margin-bottom:16px}
.card h2{font-size:14px;letter-spacing:-.01em;text-transform:none;color:#dce8fa;margin:0 0 16px;font-weight:700}
.hint{line-height:1.6;color:#91a0b8}
.stat-card{background:rgba(21,32,50,.82);border:1px solid var(--line);border-radius:16px;padding:18px;min-height:110px}
.stat-card .stat-label{font-size:10px;letter-spacing:.12em}
.stat-card .stat-value{font-size:1.9rem}
button,.btn{border-radius:11px;font-weight:680;box-shadow:none}
button.ghost{border-color:rgba(148,163,184,.2);background:rgba(255,255,255,.025)}
button.soft{background:#1b2a41}
input,textarea,select{background:#0b1422;border-color:rgba(148,163,184,.2);border-radius:11px}
table{border-collapse:separate;border-spacing:0}
th{font-size:10px;letter-spacing:.1em;padding:12px 10px}
td{padding:12px 10px}
.page{min-width:0}
.domain-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:12px}
.domain-card{background:rgba(8,16,29,.55);border:1px solid var(--line);border-radius:15px;padding:16px;display:grid;gap:12px}
.domain-card-top{display:flex;align-items:center;gap:10px;min-width:0}
.domain-card-name{font-weight:720;overflow-wrap:anywhere}
.domain-metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.domain-metric{background:rgba(255,255,255,.035);border-radius:10px;padding:9px}
.domain-metric b{display:block;font-size:13px}
.domain-metric span{font-size:10px;color:var(--muted)}
.service-list{display:grid;gap:10px}
.service-entry{display:flex;align-items:center;gap:12px;padding:15px;border:1px solid var(--line);background:rgba(8,16,29,.52);border-radius:15px;flex-wrap:wrap}
.service-entry .service-meta{flex:1;min-width:150px}
.service-entry .service-meta strong{display:block;font-size:14px}
.service-entry .service-meta small{display:block;color:var(--muted);font-size:11px;margin-top:4px}
#svcEditor{background:rgba(5,12,22,.58);border:1px solid rgba(120,170,255,.2);border-radius:18px;padding:20px!important}
#svcEditor h3{font-size:11px!important;letter-spacing:.12em!important;text-transform:uppercase}
.workflow-node{position:relative;background:linear-gradient(145deg,#17253a,#111c2d);border:1px solid rgba(148,163,184,.18);border-radius:15px;padding:15px;margin:10px 0}
.workflow-node:before{content:"";position:absolute;left:22px;top:-11px;height:10px;border-left:2px solid rgba(120,170,255,.35)}
.workflow-node:first-child:before{display:none}
.workflow-node .node-title{display:flex;align-items:center;gap:9px;font-weight:730;margin-bottom:12px}
.workflow-node .node-kind{font-size:9px;letter-spacing:.1em;text-transform:uppercase;color:#9fc3ff;background:rgba(120,170,255,.12);padding:4px 8px;border-radius:999px}
.workflow-node .node-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:9px}
.workflow-node .node-fields label{display:block;font-size:10px;color:var(--muted);margin-bottom:5px}
.workflow-node .node-fields input,.workflow-node .node-fields select,.workflow-node .node-fields textarea{min-width:0}
.workflow-node .node-actions{display:flex;gap:5px;margin-left:auto}
.workflow-node .node-actions button{min-height:32px;padding:5px 9px;font-size:12px}
.advanced-settings{margin-top:12px;border-top:1px solid var(--line);padding-top:10px}
.advanced-settings summary{font-size:11px;color:#9fb8da;cursor:pointer;font-weight:700}
@media(max-width:819px){
  .sidebar{width:min(290px,86vw);padding:16px 12px}
  .main-wrap{margin-left:0}
  .top-slim{padding:9px 14px}
  main{padding:18px 14px 32px;gap:12px}
  .card{padding:16px;margin-bottom:12px}
  .page-hero{margin:2px 0 18px}
  .stat-card{min-height:92px}
  .service-entry{align-items:flex-start}
  .service-entry button{flex:1}
}
@media(max-width:480px){
  .page-hero h1{font-size:27px}
  .workflow-node{padding:12px}
  .workflow-node .node-fields{grid-template-columns:1fr}
  .domain-metrics{grid-template-columns:1fr 1fr 1fr}
}

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
<div class="shell" id="app">
<aside class="sidebar" id="sidebar">
  <div class="brand-side"><div class="gooey-wrap" title="Bridge status"><div id="gooeyBlobSide" class="gooey-blob IDLE"></div></div>CWBridge</div>
  <nav class="nav-side" aria-label="Sections">
    <button type="button" class="nav-item active" data-page="overview" onclick="showPage('overview')"><span class="ms sm">monitor_heart</span> Overview</button>
    <button type="button" class="nav-item" data-page="control" onclick="showPage('control')"><span class="ms sm">tune</span> Control</button>
    <button type="button" class="nav-item" data-page="domains" onclick="showPage('domains')"><span class="ms sm">language</span> Domains</button>
    <button type="button" class="nav-item" data-page="services" onclick="showPage('services')"><span class="ms sm">extension</span> Services</button>
    <button type="button" class="nav-item" data-page="store" onclick="showPage('store')"><span class="ms sm">database</span> Store</button>
    <button type="button" class="nav-item" data-page="console" onclick="showPage('console')"><span class="ms sm">terminal</span> Console</button>
    <button type="button" class="nav-item" data-page="about" onclick="showPage('about')"><span class="ms sm">info</span> About</button>
  </nav>
  <button type="button" class="ghost" style="width:100%;margin-top:8px" onclick="logout()"><span class="ms sm">lock</span> Lock</button>
</aside>
<div class="main-wrap">
<div class="top-slim">
  <button type="button" class="ghost menu" onclick="var s=document.getElementById('sidebar');if(s)s.classList.toggle('open')"><span class="ms">menu</span></button>
  <span id="pageTitle" style="font-weight:700">Overview</span>
  <span style="flex:1"></span>
  <span id="pill" class="chip IDLE">-</span>
  <span id="detail" class="detail"></span>
  <button type="button" class="ghost" onclick="refreshAll()"><span class="ms sm">refresh</span></button>
</div>
<header style="display:none">
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
<div class="page-hero" id="pageHero">
  <div><div class="eyebrow" id="pageEyebrow">WORKSPACE / OVERVIEW</div><h1 id="pageHeading">Overview</h1><p id="pageSubheading">Your CWBridge system at a glance.</p></div>
</div>
<div class="page active" data-page="overview">
  <div class="card" id="sec-storage">
    <h2><span class="ms sm">hard_drive</span> Server storage</h2>
    <div class="storage-wrap">
      <div class="storage-meta">
        <span class="big" id="storUsed">—</span>
        <span class="cap">of <span id="storCap">—</span></span>
      </div>
      <div class="bar" id="storBar" title="Device-wide store usage"><i id="storFill"></i></div>
      <p class="hint" style="margin-top:8px">Total across every domain on this device. Raise the server limit under Domains → Limits (or set 0 for unlimited).</p>
    </div>
  </div>
  <div class="grid3" id="overviewStats" style="margin:4px 0 12px">
    <div class="stat-card accent"><div class="stat-label">Bridge</div><div class="stat-value" id="statState">—</div><div class="stat-sub" id="statDetail">—</div></div>
    <div class="stat-card"><div class="stat-label">CatWeb</div><div class="stat-value" id="statCat">—</div><div class="stat-sub">ready signal</div></div>
    <div class="stat-card"><div class="stat-label">Shizuku</div><div class="stat-value" id="statShizuku">—</div><div class="stat-sub">keys / force-stop</div></div>
  </div>
  <div class="card" id="sec-status">
    <h2><span class="ms sm">monitor_heart</span> Status</h2>
    <div class="row">
      <button type="button" class="soft" onclick="runDiagnose()"><span class="ms sm">troubleshoot</span> Diagnose</button>
      <button type="button" class="ghost" onclick="refreshAll()"><span class="ms sm">refresh</span> Refresh</button>
    </div>
    <div class="ready" id="readyList" aria-live="polite"></div>
    <ul id="diagOut" class="diag"></ul>
  </div>

  </div>
<div class="page" data-page="control">
<div class="card" id="sec-control">
    <h2><span class="ms sm">tune</span> Remote control</h2>
    <p class="hint">Screenshot prefers Shizuku screencap (sees Roblox). Stream: live view — tap image to tap device; hold ~0.5s for long-press.</p>
    <div class="row">
      <button type="button" id="btnRestartBridge" onclick="ctlBusy(this,'restart-bridge')"><span class="ms sm">restart_alt</span> Restart bridge</button>
      <button type="button" id="btnRestartRoblox" onclick="ctlBusy(this,'restart-roblox')"><span class="ms sm">sports_esports</span> Restart Roblox</button>
      <button type="button" onclick="toggleBridge()"><span class="ms sm">power_settings_new</span> Toggle bridge</button>
      <!--SCREENSHOT_BUTTON-->
      <button type="button" id="btnStream" onclick="toggleStream()"><span class="ms sm">live_tv</span> Start stream</button>
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

  </div>
<div class="page" data-page="domains">
<div class="card">
  <h2><span class="ms sm">language</span> Registered domains</h2>
  <p class="hint">Domains already registered in CWBridge, with their current storage limits and usage.</p>
  <div id="registeredDomains" class="domain-grid"><div class="empty">Loading registered domains…</div></div>
</div>
<div class="card" id="sec-domains">
    <h2><span class="ms sm">language</span> Auto-open on CW load</h2>
    <p class="hint">One optional auto-start domain. CWBridge opens this domain when CatWeb reports that it has finished loading. Leave empty to disable auto-start.</p>
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
  </div>
<div class="page" data-page="services">
<div class="card" id="sec-services">
    <h2><span class="ms sm">extension</span> Services</h2>
    <p class="hint">Macros with triggers + actions. Enabled = always listening (log/time). Edit anytime.</p>
    <div id="svcList" class="service-list"></div>
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
        <button type="button" class="ghost" onclick="addTrigger('LOG')"><span class="ms sm">article</span> Log event</button>
        <button type="button" class="ghost" onclick="addTrigger('TIME')"><span class="ms sm">schedule</span> Timer event</button>
        <button type="button" class="ghost" onclick="addTrigger('ACCESSIBILITY')"><span class="ms sm">touch_app</span> Accessibility event</button>
      </div>
      <h3 style="font-size:12px;letter-spacing:.06em;color:var(--muted);margin:14px 0 6px">ACTIONS</h3>
      <div id="edActions"></div>
      <div class="row" style="margin-top:6px">
        <select id="edAddAct" style="max-width:160px">
          <option value="DELAY">Delay</option>
          <option value="TAP">Tap</option>
          <option value="GET_INFO">Get info</option>
          <option value="HTTP_REQUEST">HTTP request</option>
          <option value="AI">AI action</option>
          <option value="RUN_SERVICE">Run another service</option>
          <option value="CTRL_T">Ctrl+T</option>
          <option value="PRESS_ENTER">Enter</option>
          <option value="SEND_TEXT">Send text</option>
          <option value="ENTER_TEXT">Paste + Enter</option>
          <option value="SET_VAR">Set variable</option>
          <option value="TEXT_MAN">TextMan</option>
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

  <div class="card" id="sec-ai">
    <h2><span class="ms sm">smart_toy</span> AI (LLM)</h2>
    <p class="hint">invoke|ai.prompt uses this. OpenAI-compatible. Token stays on device. Load models hits /v1/models. Prices are USD per 1M tokens (from the provider, or a public catalog when the provider omits them). invoke|ai.models · invoke|ai.pricing · invoke|ai.pricing.model</p>
    <div class="row">
      <div class="field" style="flex:2"><label class="hint">Base URL</label>
        <input id="aiUrl" placeholder="https://api.openai.com"></div>
      <div class="field"><label class="hint">Model</label>
        <input id="aiModel" placeholder="gpt-4o-mini"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field"><label class="hint">API style</label>
        <select id="aiStyle">
          <option value="chat">chat (v1 completions)</option>
          <option value="responses">responses (v2)</option>
        </select></div>
      <div class="field" style="flex:2"><label class="hint">API token</label>
        <input id="aiToken" type="password" placeholder="sk-… (leave blank to keep)" autocomplete="off"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field" style="flex:2"><label class="hint">System prompt</label>
        <input id="aiSystem" placeholder="optional instructions"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field"><label class="hint">Temperature</label>
        <input id="aiTemp" placeholder="0.7"></div>
      <div class="field"><label class="hint">Max tokens</label>
        <input id="aiMax" placeholder="1024"></div>
      <div class="field"><label class="hint">Timeout (sec)</label>
        <input id="aiTimeout" placeholder="90"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <button type="button" onclick="saveAiConfig()"><span class="ms sm">save</span> Save AI</button>
      <button type="button" class="ghost" onclick="loadAiConfig()"><span class="ms sm">refresh</span> Reload</button>
      <button type="button" class="ghost" onclick="loadAiModels()"><span class="ms sm">list</span> Load models</button>
      <button type="button" class="ghost" onclick="clearAiToken()">Clear token</button>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field" style="flex:2"><label class="hint">From /models</label>
        <select id="aiModelPick" onchange="applyAiModel()"><option value="">load models first</option></select></div>
    </div>
    <div id="aiMeta" class="hint" style="margin-top:8px"></div>
    <div id="aiModels" style="margin-top:8px;overflow-x:auto"></div>
    <div id="aiMsg" class="msg"></div>
  </div>

  <div class="card" id="sec-limits">
    <h2><span class="ms sm">speed</span> Limits</h2>
    <p class="hint">Server limit is device-wide (all domains). Domain default applies when a domain has no custom limit. Request budgets are separate. 0 = unlimited.</p>
    <div class="row">
      <div class="field"><label class="hint">Server storage max</label>
        <input id="limServer" placeholder="2GB"></div>
      <div class="field"><label class="hint">Domain data default</label>
        <input id="limDefault" placeholder="1GB"></div>
      <div class="field"><label class="hint">Reqs / day (global)</label>
        <input id="limGlobalReq" type="number" min="0" placeholder="500"></div>
      <div class="field"><label class="hint">Reqs / day / domain</label>
        <input id="limDomainReq" type="number" min="0" placeholder="250"></div>
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

  </div>
<div class="page" data-page="store">
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
  <div class="card" id="sec-sink">
    <h2><span class="ms sm">cloud_upload</span> Off-device save</h2>
    <p class="hint">Copies datastore saves off the phone. Webhook is any HTTPS POST (Discord webhook URLs get a short message). Google Sheet is an Apps Script web app URL ending in /exec — not the spreadsheet link. Scope “saves only” or “every invoke”. Game: invoke|sink (pastes 1 or 0) · invoke|sink.test</p>
    <div class="row">
      <label class="hint" style="display:flex;align-items:center;gap:6px;padding-top:18px">
        <input type="checkbox" id="sinkOn" style="width:auto;min-height:0"> Enabled
      </label>
      <div class="field"><label class="hint">What to send</label>
        <select id="sinkScope">
          <option value="save">Saves only</option>
          <option value="all">Every invoke</option>
        </select></div>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field" style="flex:1"><label class="hint">Webhook URL</label>
        <input id="sinkWebhook" placeholder="https://discord.com/api/webhooks/… or https://…"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field" style="flex:1"><label class="hint">Google Sheet (Apps Script /exec)</label>
        <input id="sinkSheet" placeholder="https://script.google.com/macros/s/…/exec"></div>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field"><label class="hint">Shared secret (optional header)</label>
        <input id="sinkSecret" type="password" placeholder="leave blank to keep" autocomplete="off"></div>
      <button type="button" onclick="saveSink()"><span class="ms sm">save</span> Save sink</button>
      <button type="button" class="ghost" onclick="testSink()">Send test</button>
      <button type="button" class="ghost" onclick="clearSinkSecret()">Clear secret</button>
    </div>
    <div id="sinkMeta" class="hint" style="margin-top:8px"></div>
    <div id="sinkMsg" class="msg"></div>
  </div>
  <div class="card">
    <h2><span class="ms sm">data_object</span> Variables</h2>
    <div class="row" style="margin-bottom:8px">
      <button type="button" class="ghost" onclick="copyVars()"><span class="ms sm">content_copy</span> Copy</button>
    </div>
    <div style="overflow-x:auto">
      <table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody id="varRows"></tbody></table>
    </div>
  </div>

  </div>
<div class="page" data-page="console">
<div class="card" id="sec-console">
    <h2><span class="ms sm">terminal</span> Console</h2>
    <p class="hint">Structured app logs ([APP] [BRIDGE] [SHIZUKU] [ROBOT] …). System noise is filtered.</p>
    <div id="console" class="console"><span class="log-empty">-</span></div>
    <div class="row" style="margin-top:10px">
      <button type="button" class="ghost" onclick="refreshConsole()"><span class="ms sm">refresh</span> Refresh</button>
      <button type="button" class="ghost" onclick="copyConsole()"><span class="ms sm">content_copy</span> Copy</button>
      <label style="display:flex;align-items:center;gap:6px;color:var(--muted)">
        <input type="checkbox" id="auto" checked style="width:auto;min-height:0"> auto
      </label>
    </div>
  </div>
</div>
<div class="page" data-page="about">
  <div class="card" id="sec-about">
    <h2><span class="ms sm">info</span> About</h2>
    <ul class="about-list">
      <li><span class="k">App</span><span class="v">CWBridge Android</span></li>
      <li><span class="k">Version</span><span class="v" id="aboutVersion">—</span></li>
      <li><span class="k">Package</span><span class="v" id="aboutPkg">—</span></li>
      <li><span class="k">Server storage</span><span class="v" id="aboutStorage">—</span></li>
      <li><span class="k">Credits</span><span class="v">made by lemon</span></li>
      <li><span class="k">Companion</span><span class="v">cwbridge (npm) · CatWeb</span></li>
      <li><span class="k">Source</span><span class="v">github.com/angamer234k/cwbridge-androidfork</span></li>
    </ul>
    <p class="hint" style="margin-top:12px">Native Android companion for CatWeb / cwbridge. Reads invoke| commands from Roblox logcat and runs taps, storage, services, and more on-device.</p>
  </div>
</div>
</main>
</div>
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

function showPage(name){
  document.querySelectorAll('.page').forEach(function(p){ p.classList.toggle('active', p.getAttribute('data-page')===name); });
  document.querySelectorAll('.nav-item').forEach(function(a){ a.classList.toggle('active', a.getAttribute('data-page')===name); });
  var meta={
    overview:['Overview','Your CWBridge system at a glance.'],
    control:['Control','Operate and inspect the connected Android device.'],
    domains:['Domains','Registered domains, limits, and stored data.'],
    services:['Services','Build and manage event-driven automations.'],
    store:['Store','Manage datastore entries and integrations.'],
    console:['Console','Inspect structured logs and runtime activity.'],
    about:['About','Version, device, and project information.']
  };
  var chosen=meta[name]||[name,''];
  var pt=D('pageTitle'); if(pt) pt.textContent=chosen[0];
  var ph=D('pageHeading'); if(ph) ph.textContent=chosen[0];
  var ps=D('pageSubheading'); if(ps) ps.textContent=chosen[1];
  var pe=D('pageEyebrow'); if(pe) pe.textContent='CWBRIDGE / '+String(name).toUpperCase();
  try{ localStorage.setItem('cw_page', name); }catch(e){}
  if(window.innerWidth<820){ var sb=D('sidebar'); if(sb) sb.classList.remove('open'); }
}

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
function paintStorage(s){
  var used = s.totalUsed || "—";
  var cap = s.serverUnlimited ? "unlimited" : (s.serverLimit || "—");
  var pct = typeof s.serverPct === "number" ? s.serverPct : 0;
  var elUsed = D("storUsed");
  var elCap = D("storCap");
  var bar = D("storBar");
  var fill = D("storFill");
  if(elUsed) elUsed.textContent = used;
  if(elCap) elCap.textContent = cap;
  if(fill){
    var w = s.serverUnlimited ? 0 : Math.max(0, Math.min(100, pct));
    fill.style.width = w + "%";
  }
  if(bar){
    bar.classList.remove("mid","hot","full");
    if(!s.serverUnlimited){
      if(pct >= 95) bar.classList.add("full");
      else if(pct >= 80) bar.classList.add("hot");
      else if(pct >= 55) bar.classList.add("mid");
    }
  }
  var aboutS = D("aboutStorage");
  if(aboutS) aboutS.textContent = used + " / " + cap;
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
    var gs = D("gooeyBlobSide");
    if(gs) gs.className = "gooey-blob " + st;
    D("detail").textContent = s.detail || "";
    renderReady(s);
    paintStorage(s);
    var ss = D("statState"); if(ss) ss.textContent = st;
    var sd = D("statDetail"); if(sd) sd.textContent = s.detail || "";
    var sc = D("statCat"); if(sc) sc.textContent = s.catwebReady ? "YES" : "no";
    var sh = D("statShizuku"); if(sh) sh.textContent = s.shizuku ? "OK" : "off";
    var av = D("aboutVersion"); if(av) av.textContent = (s.versionName || "?") + " (" + (s.versionCode != null ? s.versionCode : "?") + ")";
    var ap = D("aboutPkg"); if(ap) ap.textContent = s.packageName || "—";
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


var streamOn = false;
var streamTimer = null;

function toggleStream(){
  if(streamOn) stopStream();
  else startStream();
}
function startStream(){
  streamOn = true;
  try{ document.body.classList.add("stream-on"); }catch(e){}
  var btn = D("btnStream");
  if(btn) btn.innerHTML = "<span class=\"ms sm\">stop_circle</span> Stop stream";
  msg("ctlMsg", "streaming…", "good");
  pollStream();
}
function stopStream(){
  streamOn = false;
  try{ document.body.classList.remove("stream-on"); }catch(e){}
  if(streamTimer){ clearTimeout(streamTimer); streamTimer = null; }
  var btn = D("btnStream");
  if(btn) btn.innerHTML = "<span class=\"ms sm\">live_tv</span> Start stream";
  msg("ctlMsg", "stream stopped", "");
}
async function pollStream(){
  if(!streamOn) return;
  try{
    var res = await fetch("/api/screenshot?fmt=jpeg&q=50", {credentials:"same-origin", cache:"no-store"});
    if(res.status === 401){ location.reload(); return; }
    if(res.ok){
      var blob = await res.blob();
      if(blob && blob.size > 100){
        var url = URL.createObjectURL(blob);
        var box = D("shotBox");
        var img = document.getElementById("streamImg");
        if(!img && box){
          box.innerHTML = "<img id=\"streamImg\" alt=\"live\">";
          img = document.getElementById("streamImg");
          if(img) bindStreamTap(img);
        }
        if(img){
          var old = img.src;
          img.src = url;
          if(old && old.indexOf("blob:") === 0){ try{ URL.revokeObjectURL(old); }catch(e){} }
        }
      }
    }
  }catch(e){}
  if(streamOn) streamTimer = setTimeout(pollStream, 400);
}
function bindStreamTap(img){
  var downAt = 0, downX = 0, downY = 0;
  function pct(ev){
    var r = img.getBoundingClientRect();
    var src = (ev.changedTouches && ev.changedTouches[0]) || ev;
    var cx = src.clientX, cy = src.clientY;
    var x = ((cx - r.left) / Math.max(1, r.width)) * 100;
    var y = ((cy - r.top) / Math.max(1, r.height)) * 100;
    return {x: Math.max(0, Math.min(100, x)), y: Math.max(0, Math.min(100, y))};
  }
  function onDown(ev){
    try{ ev.preventDefault(); }catch(e){}
    downAt = Date.now();
    var p = pct(ev);
    downX = p.x; downY = p.y;
  }
  function onUp(ev){
    try{ ev.preventDefault(); }catch(e){}
    if(!downAt) return;
    var held = Date.now() - downAt;
    downAt = 0;
    var body = {mode:"percent", x: downX, y: downY};
    if(held >= 450) body.holdMs = Math.min(held, 2000);
    post("/api/tap", body).then(function(j){
      if(j && j.error) msg("ctlMsg", j.error, "err");
      else msg("ctlMsg", (body.holdMs ? "long-press " : "tap ") + downX.toFixed(1) + "% " + downY.toFixed(1) + "%", "good");
    }).catch(function(e){ msg("ctlMsg", e.message || String(e), "err"); });
  }
  img.addEventListener("pointerdown", onDown);
  img.addEventListener("pointerup", onUp);
  img.addEventListener("pointercancel", function(){ downAt = 0; });
}

async function loadShot(){
  msg('ctlMsg', 'capturing...', '');
  try{
    var res = await fetch('/api/screenshot', {credentials:'same-origin'});
    if(res.status === 401){ location.reload(); return; }
    var ct = res.headers.get('content-type') || '';
    if(!res.ok){
      var errMsg = 'HTTP ' + res.status;
      if(ct.indexOf('json') >= 0){
        try{ var j = await res.json(); errMsg = j.error || j.message || errMsg; }catch(e){}
      } else {
        try{ errMsg = (await res.text()).slice(0, 180) || errMsg; }catch(e){}
      }
      msg('ctlMsg', errMsg, 'err');
      try{ toast(errMsg, false); }catch(e){}
      return;
    }
    var blob = await res.blob();
    if(!blob || blob.size < 100){
      msg('ctlMsg', 'empty screenshot ('+(blob&&blob.size||0)+' bytes)', 'err');
      return;
    }
    var url = URL.createObjectURL(blob);
    var ts = new Date().toISOString().replace(/[:.]/g, '-');
    D('shotBox').innerHTML =
      '<img id="shotImg" alt="screenshot" src="' + url + '">' +
      '<div class="row" style="margin-top:8px">' +
      '<a class="btn ghost" download="cwbridge-' + ts + '.png" href="' + url + '">' +
      '<span class="ms sm">download</span> Download</a></div>';
    var si = document.getElementById('shotImg');
    if(si) bindStreamTap(si);
    msg('ctlMsg', 'screenshot ok (' + Math.round(blob.size/1024) + ' KB)', 'good');
  }catch(e){
    msg('ctlMsg', e.message||String(e), 'err');
    try{ toast(e.message||String(e), false); }catch(x){}
  }
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


async function loadAiConfig(){
  try{
    const j=await api('/api/ai-config');
    const u=document.getElementById('aiUrl');
    const m=document.getElementById('aiModel');
    const s=document.getElementById('aiStyle');
    const meta=document.getElementById('aiMeta');
    if(u) u.value=j.url||'';
    if(m) m.value=j.model||'';
    if(s) s.value=(j.style==='responses'?'responses':'chat');
    var sys=document.getElementById('aiSystem');
    var temp=document.getElementById('aiTemp');
    var mx=document.getElementById('aiMax');
    var to=document.getElementById('aiTimeout');
    if(sys) sys.value=j.system||'';
    if(temp) temp.value=(j.temperature!=null?j.temperature:'');
    if(mx) mx.value=(j.maxTokens!=null?j.maxTokens:'');
    if(to) to.value=(j.timeoutSec!=null?j.timeoutSec:'');
    if(meta) meta.textContent=j.tokenSet?('Token: '+(j.tokenMasked||'set')):'Token: not set';
  }catch(e){}
}
async function saveAiConfig(){
  const body={
    url:(document.getElementById('aiUrl')&&document.getElementById('aiUrl').value||'').trim(),
    model:(document.getElementById('aiModel')&&document.getElementById('aiModel').value||'').trim(),
    style:(document.getElementById('aiStyle')&&document.getElementById('aiStyle').value)||'chat',
    system:(document.getElementById('aiSystem')&&document.getElementById('aiSystem').value||''),
    temperature:(document.getElementById('aiTemp')&&document.getElementById('aiTemp').value||'').trim(),
    maxTokens:(document.getElementById('aiMax')&&document.getElementById('aiMax').value||'').trim(),
    timeoutSec:(document.getElementById('aiTimeout')&&document.getElementById('aiTimeout').value||'').trim()
  };
  const tok=document.getElementById('aiToken');
  if(tok&&tok.value) body.token=tok.value;
  try{
    const j=await post('/api/ai-config', body);
    if(j.error){ msg('aiMsg', j.error, 'err'); toast(j.error,false); return; }
    if(tok) tok.value='';
    msg('aiMsg', 'AI settings saved', 'good');
    toast('AI settings saved', true);
    loadAiConfig();
  }catch(e){ msg('aiMsg', e.message||String(e), 'err'); }
}
async function clearAiToken(){
  try{
    await post('/api/ai-config', {clearToken:true});
    toast('Token cleared', true);
    loadAiConfig();
  }catch(e){ toast(e.message||String(e), false); }
}
function applyAiModel(){
  var pick=document.getElementById('aiModelPick');
  var m=document.getElementById('aiModel');
  if(pick&&m&&pick.value) m.value=pick.value;
}
async function loadAiModels(){
  try{
    const j=await api('/api/ai-models');
    if(j.error){ msg('aiMsg', j.error, 'err'); return; }
    const rows=j.models||[];
    const pick=document.getElementById('aiModelPick');
    if(pick){
      var html='<option value="">pick a model</option>';
      for(var i=0;i<rows.length;i++){
        html+='<option value="'+esc(rows[i].id)+'">'+esc(rows[i].id)+'</option>';
      }
      pick.innerHTML=html;
    }
    const box=document.getElementById('aiModels');
    if(box){
      var n=Math.min(rows.length, 40);
      var t='<table><thead><tr><th>Model</th><th>In / 1M</th><th>Out / 1M</th><th></th></tr></thead><tbody>';
      for(var k=0;k<n;k++){
        var r=rows[k];
        t+='<tr><td>'+esc(r.id)+'</td><td>'+esc(r.input==null?'-':String(r.input))+'</td><td>'+esc(r.output==null?'-':String(r.output))+'</td><td>'+esc(r.priceSource||'')+'</td></tr>';
      }
      if(rows.length>n) t+='<tr><td colspan="4">+'+(rows.length-n)+' more</td></tr>';
      t+='</tbody></table>';
      box.innerHTML=t;
    }
    msg('aiMsg', (j.count||rows.length)+' models'+(j.note?(' — '+j.note):''), 'good');
  }catch(e){ msg('aiMsg', e.message||String(e), 'err'); }
}
async function loadSinkConfig(){
  try{
    const j=await api('/api/sink-config');
    const on=document.getElementById('sinkOn');
    const sc=document.getElementById('sinkScope');
    const wh=document.getElementById('sinkWebhook');
    const sh=document.getElementById('sinkSheet');
    const meta=document.getElementById('sinkMeta');
    if(on) on.checked=!!j.on;
    if(sc) sc.value=(j.scope==='all'?'all':'save');
    if(wh) wh.value=j.webhook||'';
    if(sh) sh.value=j.sheet||'';
    if(meta) meta.textContent=j.secretSet?'Secret: set':'Secret: not set';
  }catch(e){}
}
async function saveSink(){
  const body={
    on:!!(document.getElementById('sinkOn')&&document.getElementById('sinkOn').checked),
    scope:(document.getElementById('sinkScope')&&document.getElementById('sinkScope').value)||'save',
    webhook:(document.getElementById('sinkWebhook')&&document.getElementById('sinkWebhook').value||'').trim(),
    sheet:(document.getElementById('sinkSheet')&&document.getElementById('sinkSheet').value||'').trim()
  };
  const sec=document.getElementById('sinkSecret');
  if(sec&&sec.value) body.secret=sec.value;
  try{
    const j=await post('/api/sink-config', body);
    if(j.error){ msg('sinkMsg', j.error, 'err'); return; }
    if(sec) sec.value='';
    msg('sinkMsg', 'Off-device save updated', 'good');
    loadSinkConfig();
  }catch(e){ msg('sinkMsg', e.message||String(e), 'err'); }
}
async function testSink(){
  try{
    const j=await post('/api/sink-test', {});
    if(j.error){ msg('sinkMsg', j.error, 'err'); return; }
    msg('sinkMsg', j.message||'test sent', 'good');
  }catch(e){ msg('sinkMsg', e.message||String(e), 'err'); }
}
async function clearSinkSecret(){
  try{
    await post('/api/sink-config', {clearSecret:true});
    toast('Secret cleared', true);
    loadSinkConfig();
  }catch(e){ toast(e.message||String(e), false); }
}

async function loadLimits(){
  try{
    const j=await api('/api/rate-limits');
    const def=document.getElementById('limDefault');
    const srv=document.getElementById('limServer');
    const g=document.getElementById('limGlobalReq');
    const d=document.getElementById('limDomainReq');
    if(def) def.value=j.defaultLimit||'';
    if(srv) srv.value=j.serverLimit||'';
    if(g) g.value=(j.globalLimit!=null?j.globalLimit:'');
    if(d) d.value=(j.domainLimit!=null?j.domainLimit:'');
    const meta=document.getElementById('limitsMeta');
    if(meta){
      let s='Today: global '+ (j.globalUsed||0) +'/'+ (j.globalLimit||0);
      if(j.domainUsed!=null) s+=' · domain '+j.domainUsed+'/'+(j.domainLimit||0);
      s+=' · server '+ (j.totalUsed||'?') +' / '+ (j.serverLimit||'?');
      s+=' · domain default '+ (j.defaultLimit||'?');
      meta.textContent=s;
    }
    paintStorage(j);
  }catch(e){ toast(e.message||String(e),false); }
}
async function saveLimits(){
  const body={};
  const def=document.getElementById('limDefault');
  const srv=document.getElementById('limServer');
  const g=document.getElementById('limGlobalReq');
  const d=document.getElementById('limDomainReq');
  if(def && def.value.trim()) body.defaultLimit=def.value.trim();
  if(srv && srv.value.trim()) body.serverLimit=srv.value.trim();
  if(g && g.value!=='') body.globalPerDay=parseInt(g.value,10);
  if(d && d.value!=='') body.domainPerDay=parseInt(d.value,10);
  const msg=document.getElementById('limitsMsg');
  try{
    const j=await api('/api/rate-limits',{method:'POST',body:JSON.stringify(body)});
    if(j.error){ if(msg) msg.innerHTML='<span class="bad">'+(j.error||'')+'</span>'; toast(j.error,false); return; }
    if(msg) msg.innerHTML='<span class="ok">Saved</span>';
    toast('Limits saved',true);
    loadLimits();
    refreshStatus();
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
          "<button type=\"button\" onclick='saveDomainKey(" + arg(key) + "," + arg(id) + ")'>Save</button>" +
          "<button type=\"button\" class=\"danger\" onclick='deleteDomainKey(" + arg(key) + ")'>Del</button></div>";
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
    if(list) list.innerHTML = (s.domains || []).map(function(x){ return "<option value=\"" + esc(x.domain) + "\">"; }).join("");
  } catch(e){}
}
async function refreshRegisteredDomains(){
  var host=D("registeredDomains"); if(!host) return;
  try{
    var results=await Promise.all([get("/api/store"),get("/api/auto-domain")]);
    var domains=results[0].domains||[], auto=(results[1].domain||"").toLowerCase();
    if(!domains.length){host.innerHTML="<div class=\"empty\"><span class=\"ms\">language</span>No domains registered yet. A domain appears here once it has stored data or a configured limit.</div>";return;}
    host.innerHTML=domains.map(function(d){
      var isAuto=String(d.domain||"").toLowerCase()===auto;
      return "<div class=\"domain-card\"><div class=\"domain-card-top\"><span class=\"ms\" style=\"font-size:22px;color:var(--accent)\">language</span><div style=\"flex:1;min-width:0\"><div class=\"domain-card-name\">"+esc(d.domain)+"</div><div class=\"hint\">"+(isAuto?"Auto-start domain":"Registered domain")+"</div></div>"+(isAuto?"<span class=\"chip ACTIVE\">AUTO-START</span>":"")+"</div><div class=\"domain-metrics\"><div class=\"domain-metric\"><b>"+esc(d.keys)+"</b><span>Keys</span></div><div class=\"domain-metric\"><b>"+esc(d.used)+"</b><span>Used</span></div><div class=\"domain-metric\"><b>"+esc(d.limit)+"</b><span>Limit</span></div></div><button type=\"button\" class=\"ghost\" onclick=\"openRegisteredDomain("+arg(d.domain)+")\">Manage stored data <span class=\"ms sm\">arrow_forward</span></button></div>";
    }).join("");
  }catch(e){host.innerHTML="<div class=\"empty\">Could not load registered domains: "+esc(e.message)+"</div>";}
}
function openRegisteredDomain(domain){
  var input=D("editDomain"); if(input) input.value=domain;
  loadDomainDb();
  var editor=D("domainDbMeta"); if(editor) editor.scrollIntoView({behavior:"smooth",block:"center"});
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
var SERVICE_CATALOG = [];
async function refreshServices(){
  try{
    var s = await api("/api/services");
    var list = s.services || [];
    SERVICE_CATALOG = list;
    if(!list.length){
      D("svcList").innerHTML = "<div class=\"empty\"><span class=\"ms\">extension_off</span>No services yet — create your first workflow</div>";
      return;
    }
    D("svcList").innerHTML = list.map(function(sv){
      var on = sv.enabled !== false;
      return "<div class=\"service-entry\">" +
        "<span class=\"ms\" style=\"font-size:24px;color:var(--accent)\">account_tree</span>" +
        "<div class=\"service-meta\"><strong>" + esc(sv.name||sv.id) + "</strong>" +
        "<small>" + (sv.triggers||0) + " events · " + (sv.actions||0) + " blocks · " +
        (on ? "<span class=\"ok\">Enabled</span>" : "<span class=\"bad\">Disabled</span>") + "</small></div>" +
        "<button type=\"button\" class=\"ghost\" onclick=\"toggleService(" + arg(sv.id) + "," + (!on) + ")\">" + (on ? "Disable" : "Enable") + "</button>" +
        "<button type=\"button\" class=\"soft\" onclick=\"editService(" + arg(sv.id) + ")\">Edit workflow</button>" +
        "<button type=\"button\" class=\"soft\" onclick=\"runService(" + arg(sv.id) + ")\">Run test</button>" +
        "<button type=\"button\" class=\"ghost\" onclick=\"deleteService(" + arg(sv.id) + ")\">Delete</button></div>";
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
  if(!ED.triggers.length) tg.innerHTML = "<div class=\"hint\">No event triggers. This workflow can still be run manually.</div>";
  else tg.innerHTML = ED.triggers.map(function(tr,i){
    var kind = (tr._kind||tr.type||"LOG").toString().toUpperCase();
    var fields = "";
    if(kind==="TIME"){
      fields = "<div><label>Interval (ms)</label><input type=\"number\" min=\"100\" value=\"" + esc(String(tr.intervalMs||1000)) + "\" onchange=\"ED.triggers["+i+"].intervalMs=Math.max(100,parseInt(this.value,10)||1000)\"></div>" +
        "<div><label>Repeat</label><select onchange=\"ED.triggers["+i+"].repeat=this.value==='true'\"><option value=\"true\" "+(tr.repeat!==false?"selected":"")+">Repeating</option><option value=\"false\" "+(tr.repeat===false?"selected":"")+">Once</option></select></div>";
    } else if(kind==="ACCESSIBILITY"){
      fields = "<div><label>Visible text pattern</label><input value=\"" + esc(tr.textPattern||"") + "\" onchange=\"ED.triggers["+i+"].textPattern=this.value\"></div>" +
        "<div><label>Node ID (optional)</label><input value=\"" + esc(tr.nodeId||"") + "\" onchange=\"ED.triggers["+i+"].nodeId=this.value\"></div>" +
        "<div><label>Event type</label><select onchange=\"ED.triggers["+i+"].eventType=this.value\">" +
        ["click","text_changed","focused","selected"].map(function(v){return "<option value=\""+v+"\" "+((tr.eventType||"click")===v?"selected":"")+">"+v+"</option>";}).join("") + "</select></div>";
    } else {
      fields = "<div><label>Log pattern</label><input value=\"" + esc(tr.pattern||"") + "\" onchange=\"ED.triggers["+i+"].pattern=this.value\"></div>" +
        "<div><label>Match case</label><select onchange=\"ED.triggers["+i+"].matchCase=this.value==='true'\"><option value=\"false\" "+(!tr.matchCase?"selected":"")+">Ignore case</option><option value=\"true\" "+(tr.matchCase?"selected":"")+">Match case</option></select></div>" +
        "<div><label>Pattern mode</label><select onchange=\"ED.triggers["+i+"].useRegex=this.value==='true'\"><option value=\"false\" "+(!tr.useRegex?"selected":"")+">Contains text</option><option value=\"true\" "+(tr.useRegex?"selected":"")+">Regular expression</option></select></div>";
    }
    return "<div class=\"workflow-node\"><div class=\"node-title\"><span class=\"ms\">bolt</span>"+esc(tr.name||kind)+"<span class=\"node-kind\">Event · "+esc(kind)+"</span><span class=\"node-actions\"><button type=\"button\" class=\"ghost\" onclick=\"ED.triggers.splice("+i+",1);renderEditorLists()\">Remove</button></span></div><div class=\"node-fields\">"+fields+"</div></div>";
  }).join("");
  var ac = D("edActions");
  if(!ED.actions.length) ac.innerHTML = "<div class=\"hint\">No action blocks yet. Add the first step below.</div>";
  else ac.innerHTML = ED.actions.map(function(a,i){
    var kind = (a._kind||a.type||"?").toString().toUpperCase();
    var fields = "";
    if(kind==="DELAY") fields = "<div><label>Duration (ms)</label><input type=\"number\" min=\"0\" value=\""+esc(String(a.milliseconds||1000))+"\" onchange=\"ED.actions["+i+"].milliseconds=Math.max(0,parseInt(this.value,10)||0)\"></div>";
    else if(kind==="SEND_TEXT"||kind==="ENTER_TEXT") fields = "<div style=\"grid-column:1/-1\"><label>Text / variable template</label><textarea onchange=\"ED.actions["+i+"].text=this.value\">"+esc(a.text||"")+"</textarea></div>";
    else if(kind==="SET_VAR") fields = "<div><label>Variable key</label><input value=\""+esc(a.key||"")+"\" onchange=\"ED.actions["+i+"].key=this.value\"></div><div><label>Value</label><input value=\""+esc(a.value||"")+"\" onchange=\"ED.actions["+i+"].value=this.value\"></div>";
    else if(kind==="TEXT_MAN") fields = "<div><label>Source</label><input value=\""+esc(a.source||"$lastMatch")+"\" onchange=\"ED.actions["+i+"].source=this.value\"></div><div><label>Mode</label><select onchange=\"ED.actions["+i+"].mode=this.value\">"+["full","regex","replace"].map(function(v){return "<option "+((a.mode||"full")===v?"selected":"")+" value=\""+v+"\">"+v+"</option>";}).join("")+"</select></div><div><label>Pattern</label><input value=\""+esc(a.pattern||"")+"\" onchange=\"ED.actions["+i+"].pattern=this.value\"></div><div><label>Capture group</label><input type=\"number\" value=\""+esc(String(a.group||1))+"\" onchange=\"ED.actions["+i+"].group=parseInt(this.value,10)||1\"></div><div><label>Replace with</label><input value=\""+esc(a.replaceWith||"")+"\" onchange=\"ED.actions["+i+"].replaceWith=this.value\"></div><div><label>Save result to</label><input value=\""+esc(a.saveTo||"result")+"\" onchange=\"ED.actions["+i+"].saveTo=this.value\"></div>";
    else if(kind==="TAP") fields = "<div><label>X percent</label><input type=\"number\" value=\""+esc(a.xPercent==null?"":String(a.xPercent))+"\" onchange=\"ED.actions["+i+"].xPercent=this.value===''?null:parseFloat(this.value)\"></div><div><label>Y percent</label><input type=\"number\" value=\""+esc(a.yPercent==null?"":String(a.yPercent))+"\" onchange=\"ED.actions["+i+"].yPercent=this.value===''?null:parseFloat(this.value)\"></div><div><label>X pixels</label><input type=\"number\" value=\""+esc(a.xPx==null?"":String(a.xPx))+"\" onchange=\"ED.actions["+i+"].xPx=this.value===''?null:parseInt(this.value,10)\"></div><div><label>Y pixels</label><input type=\"number\" value=\""+esc(a.yPx==null?"":String(a.yPx))+"\" onchange=\"ED.actions["+i+"].yPx=this.value===''?null:parseInt(this.value,10)\"></div><div><label>Accessibility text (optional)</label><input value=\""+esc(a.text||"")+"\" onchange=\"ED.actions["+i+"].text=this.value\"></div>";
    else if(kind==="GET_INFO") fields = "<div><label>Information type</label><select onchange=\"ED.actions["+i+"].infoType=this.value\">"+["SCREEN_TEXT","NODE_TEXT","NODE_BOUNDS","CURRENT_APP","TIMESTAMP","CLIPBOARD"].map(function(v){return "<option value=\""+v+"\" "+((a.infoType||"SCREEN_TEXT")===v?"selected":"")+">"+v.replace(/_/g," ")+"</option>";}).join("")+"</select></div><div><label>Store in variable</label><input value=\""+esc(a.storeInVariable||"")+"\" onchange=\"ED.actions["+i+"].storeInVariable=this.value\"></div>";
    else if(kind==="HTTP_REQUEST") fields = "<div style=\"grid-column:1/-1\"><label>Request URL</label><input value=\""+esc(a.url||"")+"\" onchange=\"ED.actions["+i+"].url=this.value\"></div><div><label>Method</label><select onchange=\"ED.actions["+i+"].method=this.value\">"+["GET","POST","PUT","PATCH","DELETE"].map(function(v){return "<option value=\""+v+"\" "+((a.method||"GET")===v?"selected":"")+">"+v+"</option>";}).join("")+"</select></div><div><label>Store response in</label><input value=\""+esc(a.storeResponseIn||"")+"\" onchange=\"ED.actions["+i+"].storeResponseIn=this.value\"></div><div style=\"grid-column:1/-1\"><label>Request body</label><textarea onchange=\"ED.actions["+i+"].body=this.value\">"+esc(a.body||"")+"</textarea></div><details class=\"advanced-settings\" style=\"grid-column:1/-1\"><summary>Advanced · request headers (JSON)</summary><textarea onchange=\"try{ED.actions["+i+"].headers=JSON.parse(this.value||'{}')}catch(e){this.setCustomValidity('Invalid JSON');return}this.setCustomValidity('')\">"+esc(JSON.stringify(a.headers||{},null,2))+"</textarea></details>";
    else if(kind==="AI") fields = "<div style=\"grid-column:1/-1\"><label>Prompt</label><textarea onchange=\"ED.actions["+i+"].prompt=this.value\">"+esc(a.prompt||"")+"</textarea></div><div><label>Model</label><input value=\""+esc(a.model||"default")+"\" onchange=\"ED.actions["+i+"].model=this.value\"></div><div><label>Store response in</label><input value=\""+esc(a.storeResponseIn||"")+"\" onchange=\"ED.actions["+i+"].storeResponseIn=this.value\"></div>";
    else if(kind==="RUN_SERVICE") fields = "<div><label>Service ID</label><select onchange=\"ED.actions["+i+"].serviceId=this.value\"><option value=\"\">Choose a service</option>"+SERVICE_CATALOG.filter(function(sv){return sv.id!==ED.id;}).map(function(sv){return "<option value=\""+esc(sv.id)+"\" "+(a.serviceId===sv.id?"selected":"")+">"+esc(sv.name)+"</option>";}).join("")+"</select></div>";
    else fields = "<div class=\"hint\">This action has no additional settings.</div>";
    return "<div class=\"workflow-node\"><div class=\"node-title\"><span class=\"ms\">"+(kind==="DELAY"?"schedule":kind==="TAP"?"touch_app":kind==="HTTP_REQUEST"?"http":kind==="AI"?"smart_toy":"play_arrow")+"</span>"+esc(a.name||kind)+"<span class=\"node-kind\">Step "+(i+1)+" · "+esc(kind)+"</span><span class=\"node-actions\"><button type=\"button\" class=\"ghost\" onclick=\"moveAction("+i+",-1)\" "+(i===0?"disabled":"")+">↑</button><button type=\"button\" class=\"ghost\" onclick=\"moveAction("+i+",1)\" "+(i===ED.actions.length-1?"disabled":"")+">↓</button><button type=\"button\" class=\"ghost\" onclick=\"ED.actions.splice("+i+",1);renderEditorLists()\">Remove</button></span></div><div class=\"node-fields\">"+fields+"</div></div>";
  }).join("");
}
function moveAction(index,delta){
  var next=index+delta;
  if(next<0||next>=ED.actions.length) return;
  var item=ED.actions.splice(index,1)[0];
  ED.actions.splice(next,0,item);
  renderEditorLists();
}
function addTrigger(kind){
  if(kind==="TIME"){
    ED.triggers.push({_kind:"TIME",id:uid(),name:"Timer event",intervalMs:5000,repeat:true,type:"TIME"});
  } else if(kind==="ACCESSIBILITY"){
    ED.triggers.push({_kind:"ACCESSIBILITY",id:uid(),name:"Accessibility event",textPattern:"",nodeId:"",eventType:"click",type:"ACCESSIBILITY"});
  } else {
    ED.triggers.push({_kind:"LOG",id:uid(),name:"Log event",pattern:"invoke|",matchCase:false,useRegex:false,type:"LOG"});
  }
  renderEditorLists();
}
function addAction(){
  var kind = (D("edAddAct").value||"DELAY").toUpperCase();
  var a = {_kind:kind,id:uid(),name:kind,type:kind};
  if(kind==="DELAY") a.milliseconds = 500;
  if(kind==="SEND_TEXT"||kind==="ENTER_TEXT") a.text = "";
  if(kind==="SET_VAR"){ a.key=""; a.value=""; }
  if(kind==="TEXT_MAN"){ a.source="$lastMatch"; a.mode="full"; a.pattern=""; a.group=1; a.replaceWith=""; a.saveTo="result"; }
  if(kind==="TAP"){ a.text=""; a.xPercent=50; a.yPercent=50; }
  if(kind==="GET_INFO"){ a.infoType="SCREEN_TEXT"; a.storeInVariable=""; }
  if(kind==="HTTP_REQUEST"){ a.url=""; a.method="GET"; a.headers={}; a.body=""; a.storeResponseIn=""; }
  if(kind==="AI"){ a.prompt=""; a.model="default"; a.storeResponseIn=""; }
  if(kind==="RUN_SERVICE") a.serviceId="";
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
        "<button type=\"button\" class=\"danger\" onclick='clearDomain(" + arg(d.domain) + ")'>Clear</button></td></tr>";
    }).join("") || "<tr><td colspan=\"5\"><div class=\"empty\"><span class=\"ms\">database</span>empty</div></td></tr>";
    paintStorage(s);
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
function copyText(t, okMsg){
  t = t || "";
  function ok(){ toast(okMsg || "copied", "good"); }
  function fail(){ toast("copy failed — select text manually", "err"); }
  function fallback(){
    try{
      var ta = document.createElement("textarea");
      ta.value = t;
      ta.setAttribute("readonly", "");
      ta.style.cssText = "position:fixed;left:-9999px;top:0";
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      ta.setSelectionRange(0, ta.value.length);
      var done = document.execCommand("copy");
      document.body.removeChild(ta);
      if(done) ok(); else fail();
    }catch(e){ fail(); }
  }
  if(navigator.clipboard && window.isSecureContext){
    navigator.clipboard.writeText(t).then(ok).catch(function(){ fallback(); });
  } else {
    fallback();
  }
}
function copyVars(){
  var rows = (D("varRows") && D("varRows").innerText) || "";
  copyText(rows, "vars copied");
}
async function refreshConsole(){
  try{
    var s = await api("/api/logs");
    var box = D("console");
    var atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
    var lines = s.lines || [];
    if(!lines.length){
      box.innerHTML = '<span class="log-empty">(no lines)</span>';
    }else{
      box.innerHTML = lines.map(function(line){
        var raw = String(line == null ? "" : line);
        var m = raw.match(/^\[([A-Z0-9_]+)\]:\s*(.*)$/);
        if(m){
          var tag = m[1];
          var known = {BRIDGE:1, APP:1, ROBOT:1, SHIZUKU:1};
          var cls = known[tag] ? ("tag-" + tag) : "tag-other";
          return '<div class="log-line"><span class="' + cls + '">[' + esc(tag) + ']:</span> ' + esc(m[2]) + '</div>';
        }
        return '<div class="log-line">' + esc(raw) + '</div>';
      }).join("");
    }
    if(atBottom) box.scrollTop = box.scrollHeight;
  }catch(e){}
}
function copyConsole(){
  var box = D("console");
  var txt = (box && (box.innerText || box.textContent)) || "";
  copyText(txt, "console copied");
}
function refreshAll(){
  refreshStatus(); refreshServices(); refreshStore(); refreshVars(); refreshConsole();
  loadAutoDomain(); refreshDomainDatalist(); refreshRegisteredDomains(); loadLimits(); loadSinkConfig();
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
try{ showPage(localStorage.getItem('cw_page')||'overview'); }catch(e){ showPage('overview'); }
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
<title>CWBridge - Lock</title>
<style>
:root{--bg:#0b0e13;--panel:#11151c;--line:rgba(255,255,255,.08);--text:#eef1f7;--muted:#8b93a7;--accent:#6ea8fe;}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-system-ui,Segoe UI,Roboto,sans-serif;min-height:100vh;display:grid;place-items:center}
main{width:min(360px,92rwl);padding:24px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:22px;box-shadow:0 12px 32px rgba(0,0,0,.35)}
h1{margin:0 0 10px;font-size:1.25rem}
.subh{color:var(--muted);font-size:13px;margin:0 0 14px}
input{width:100%;padding:12px 14px;border-radius:10px;border:1px solid var(--line);background:#0c0e12;color:var(--text);font:inherit;min-height:44px}
button{width:100%;margin-top:10px;padding:12px 14px;border:0;border-radius:12px;background:var(--accent);color:#0b1020;font:inherit;font-weight:650;cursor:pointer;min-height:44px}
.msg{min-height:1.2em;margin-top:8px;color:#f76c6c;font-size:13px}
</style>
</head>
<body>
<main>
  <div class="card">
    <h1>CWBridge</h1>
    <div class="subh">Enter panel password to unlock.</div>
    <input id="pw" type="password" autocomplete="current-password" placeholder="Password" />
    <button type="button" id="btn">Unlock</button>
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
