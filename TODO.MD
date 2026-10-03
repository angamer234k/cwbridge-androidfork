- MEANS NOT FINISHED
+ FINISHED
= HALF COMPLETE

## DONE (shipped)

+ FLOATING OVERLAY — status colors (red=error, yellow=waiting CatWeb, green=active)
+ TAP OVERLAY → last Roblox/CatWeb console lines
+ CHECK FOR UPDATES — works (version suffix handling fixed)
+ WEB SERVER — port 8080 / 8765 / 80 control panel
+ `paste` with no arg pastes current clipboard
+ APP DOESN'T CRASH if a tool/service fails
+ PER-DOMAIN STORAGE LIMIT (bits/bytes/KB/MB/GB) — over-cap saves rejected
+ SERVICES CREATOR — list / create / edit JSON / enable / disable / delete / run
+ REMOTE CONTROL AUTH — LAN (192.168/10/172.16-31) no password; else generated password + cookie
+ TAKE SCREENSHOT — Android 11+ only; button auto-hides on older (endpoint still reports unsupported)
+ FIRST-RUN COMPATIBILITY NOTICE
+ RESTART BRIDGE / RESTART ROBLOX (Shizuku force-stop + relaunch)
+ Ctrl+T, Enter, read clipboard, toggle bridge, tap by %/px/text, send invoke, live console, variables
+ SERVER UI WIRING — real categoryServer section, password + quota controls + remote pair (code/status)
+ README — web server, LAN-vs-remote auth, known limits
+ PACKAGE FOLDERS — bridge/ data/ engine/ server/ under com.cwbridge.android
+ CI GUARDS — scripts/check-webui.js (gates every build); LOGIN_SHELL verified
+ WEB PANEL ESCAPING — esc() + arg() for inline onclick
+ ONE UNLOCK UI — server-side lock page only
+ WINDOWS HELPER — quiet logs on success; detail dump on failure
+ STAGE 2 WEB PANEL — gooey status blob (SVG goo + reduced-motion), Material Symbols Rounded icons,
  readiness checklist (a11y / Shizuku / CatWeb) from /api/status, section nav, busy buttons,
  aria-live toasts, confirm modal, screenshot download+timestamp, console/vars copy + autoscroll,
  Enter-to-send invoke, icon empty states; check-webui D(id)/onclick/no external script extras
+ STAGE 3 WEB PANEL — left sidebar pages (overview / control / domains / services / store / console)
  instead of one long scroll; showPage() + localStorage page memory, off-canvas list on small screens
+ PAIR WITH 6-DIGIT CODE — phone creates a code, browser claims it at cw-control.vercel.app
  (RemoteRelay, Vercel + Upstash); Server section shows the code and live pair status
+ AI (LLM) — invoke|ai.prompt via any OpenAI-compatible endpoint; /api/ai-config (chat vs responses
  style, masked token, clear) — the token stays on device
+ CATWEB AUTO-OPEN — /api/auto-domain; when CatWeb logs "finished", the saved domain opens in-tab
+ SHIZUKU KEY SHORTCUTS — Ctrl+T / Enter via Shizuku; server port fallback 8080-8765-80
+ EXTRAS — public CatWeb client for s.eof.rbx (chunk-aware REQ|/RES| protocol) in extras/

## STILL TO DO

### Web panel
- LIVE STREAM / LIVE VIEW — "Start stream" is still a placeholder ("stream coming next build");
  tap-image-to-tap and ~0.5s long-press are not wired yet

### Remote relay
= REMOTE CLIPBOARD — relay "clipboard" command returns a stub ("use local web clipboard"), not a real read

### Windows helper
- REAL-DEVICE TEST — GUI not yet run by a human against a plugged-in phone (self-test only)

## Notes
- Overlay long-press → Ctrl+T in 3s (Shizuku preferred, TapService fallback)
- Debug keystore: decode keystore/cwbridge-debug.p12.b64 before local build
- Helper needs AdbLib vendored at build time (see build-debug-apk.yml)
- Same-LAN clients still skip the password by design
