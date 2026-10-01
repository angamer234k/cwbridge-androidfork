#!/usr/bin/env python3
"""Restore WebUi.kt then apply Stage 3 sidebar layout (CSS + nav pages)."""
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEBUI = ROOT / "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
GOOD = (
    "https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/"
    "a3713c1e015b52c63265a8449d4d009324cf4e7a/"
    "app/src/main/java/com/cwbridge/android/server/WebUi.kt"
)


def restore():
    data = urllib.request.urlopen(GOOD, timeout=90).read()
    WEBUI.write_bytes(data)
    print("restored WebUi", len(data))


def apply_sidebar():
    t = WEBUI.read_text()
    if 'class="sidebar"' in t or "data-page=\"overview\"" in t:
        print("sidebar already present")
        return

    # Palette tweak toward Linear-style dark
    t = t.replace(
        "--bg:#0c0e12; --panel:#151922; --panel2:#1b2030;",
        "--bg:#0b0e13; --panel:#11151c; --panel2:#171c24;",
        1,
    )

    # Replace sticky top section-nav with note that sidebar handles nav (keep anchors working)
    # Inject CSS for shell/sidebar at end of style block before </style>
    extra_css = r'''
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
  nav.sec{display:none}
}
'''
    if "/* Stage 3 shell */" not in t:
        t = t.replace("</style>", extra_css + "\n</style>", 1)

    # After <body> svg, wrap app into shell — replace opening of #app
    old_app = '<div id="app">\n<header>'
    new_app = '''<div class="shell" id="app">
<aside class="sidebar" id="sidebar">
  <div class="brand-side"><div class="gooey-wrap" title="Bridge status"><div id="gooeyBlobSide" class="gooey-blob IDLE"></div></div>CWBridge</div>
  <nav class="nav-side" aria-label="Sections">
    <button type="button" class="nav-item active" data-page="overview" onclick="showPage('overview')"><span class="ms sm">monitor_heart</span> Overview</button>
    <button type="button" class="nav-item" data-page="control" onclick="showPage('control')"><span class="ms sm">tune</span> Control</button>
    <button type="button" class="nav-item" data-page="domains" onclick="showPage('domains')"><span class="ms sm">language</span> Domains</button>
    <button type="button" class="nav-item" data-page="services" onclick="showPage('services')"><span class="ms sm">extension</span> Services</button>
    <button type="button" class="nav-item" data-page="store" onclick="showPage('store')"><span class="ms sm">database</span> Store</button>
    <button type="button" class="nav-item" data-page="console" onclick="showPage('console')"><span class="ms sm">terminal</span> Console</button>
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
<header style="display:none">'''
    if old_app in t:
        t = t.replace(old_app, new_app, 1)

    # Wrap main sections into pages - after <main>
    if '<main>' in t and 'data-page="overview"' not in t.split('<main>',1)[1][:200]:
        t = t.replace(
            '<main>',
            '<main>\n<div class="page active" data-page="overview">',
            1,
        )
        # close overview before sec-control, open control
        t = t.replace(
            '<div class="card" id="sec-control">',
            '</div>\n<div class="page" data-page="control">\n<div class="card" id="sec-control">',
            1,
        )
        # domains page before sec-domains
        t = t.replace(
            '<div class="card" id="sec-domains">',
            '</div>\n<div class="page" data-page="domains">\n<div class="card" id="sec-domains">',
            1,
        )
        # services
        t = t.replace(
            '<div class="card" id="sec-services">',
            '</div>\n<div class="page" data-page="services">\n<div class="card" id="sec-services">',
            1,
        )
        # store
        t = t.replace(
            '<div class="card" id="sec-store">',
            '</div>\n<div class="page" data-page="store">\n<div class="card" id="sec-store">',
            1,
        )
        # console
        t = t.replace(
            '<div class="card" id="sec-console">',
            '</div>\n<div class="page" data-page="console">\n<div class="card" id="sec-console">',
            1,
        )
        # close last page before </main>
        t = t.replace('</main>', '</div>\n</main>\n</div>', 1)

    # Inject showPage JS after function D
    nav_js = r'''
function showPage(name){
  document.querySelectorAll('.page').forEach(function(p){ p.classList.toggle('active', p.getAttribute('data-page')===name); });
  document.querySelectorAll('.nav-item').forEach(function(a){ a.classList.toggle('active', a.getAttribute('data-page')===name); });
  var pt=D('pageTitle'); if(pt){ var m={overview:'Overview',control:'Control',domains:'Domains',services:'Services',store:'Store',console:'Console'}; pt.textContent=m[name]||name; }
  try{ localStorage.setItem('cw_page', name); }catch(e){}
  if(window.innerWidth<820){ var sb=D('sidebar'); if(sb) sb.classList.remove('open'); }
}
'''
    if 'function showPage' not in t:
        t = t.replace(
            'function D(id){ return document.getElementById(id); }',
            'function D(id){ return document.getElementById(id); }\n' + nav_js,
            1,
        )
    if "showPage(localStorage.getItem('cw_page')" not in t:
        t = t.replace(
            'refreshAll();',
            "try{ showPage(localStorage.getItem('cw_page')||'overview'); }catch(e){ showPage('overview'); }\nrefreshAll();",
            1,
        )

    # Sync gooey blob side if status updates gooeyBlob
    if "gooeyBlobSide" in t and "gooeyBlob" in t:
        # mirror class updates — soft: when setting gooeyBlob class, copy
        pass

    WEBUI.write_text(t)
    print("sidebar applied", WEBUI.stat().st_size)


def main():
    restore()
    apply_sidebar()
    print("hotfix stage3 OK")


if __name__ == "__main__":
    main()
