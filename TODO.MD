- MEANS NOT FINISHED
+ FINISHED
= HALF COMPLETE

## DONE (shipped)

+ FLOATING OVERLAY — status colors (red=error, yellow=waiting CatWeb, green=active)
+ TAP OVERLAY → last Roblox/CatWeb console lines
+ CHECK FOR UPDATES — works (version suffix handling fixed)
+ WEB SERVER — port 8080/80/8765 control panel
+ `paste` with no arg pastes current clipboard
+ APP DOESN'T CRASH if a tool/service fails
+ PER-DOMAIN STORAGE LIMIT (bits/bytes/KB/MB/GB) — over-cap saves rejected
+ SERVICES CREATOR — list / create / edit JSON / enable / disable / delete / run
+ REMOTE CONTROL AUTH — LAN (192.168/10/172.16-31) no password; else generated password + cookie
+ TAKE SCREENSHOT — Android 11+ only; button auto-hides on older (endpoint still reports unsupported)
+ FIRST-RUN COMPATIBILITY NOTICE
+ RESTART BRIDGE / RESTART ROBLOX (Shizuku force-stop + relaunch)
+ Ctrl+T, Enter, read clipboard, toggle bridge, tap by %/px/text, send invoke, live console, variables
+ SERVER UI WIRING — real categoryServer section, password + quota controls
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

## STILL TO DO

- PAIR WITH 6-DIGIT CODE — next month's problem; needs Vercel/Railway pairing service

### Windows helper
- REAL-DEVICE TEST — GUI not yet run by a human against a plugged-in phone (self-test only)

## Notes
- Overlay long-press → Ctrl+T in 3s (Shizuku preferred, TapService fallback)
- Debug keystore: decode keystore/cwbridge-debug.p12.b64 before local build
- Helper needs AdbLib vendored at build time (see build-debug-apk.yml)
- Same-LAN clients still skip the password by design
