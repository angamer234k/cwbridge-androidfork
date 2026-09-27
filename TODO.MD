- MEANS NOT FINISHED
+ FINISHED
= HALF COMPLETE

## DONE (shipped)

+ FLOATING OVERLAY — status colors (red=error, yellow=waiting CatWeb, green=active)
+ TAP OVERLAY → last Roblox/CatWeb console lines
+ CHECK FOR UPDATES — works (version suffix handling fixed)
+ WEB SERVER — port 8765 control panel
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
+ PACKAGE FOLDERS — merged + pushed (1c43a72+). bridge/ data/ engine/ server/ under com.cwbridge.android;
  root kept on purpose: MainActivity OverlayService TapService ShizukuShell UpdateChecker DeviceStatus FirstRun
+ CI GUARDS — scripts/check-webui.js (gates every build); path search for WebUi.kt after package move
+ WINDOWS HELPER — cwbridge-updater.ps1 (CLI) + cwbridge-helper-gui.ps1 (window + self-test 22/22)
  latent [switch] positional bug fixed (New-Lbl)

## STILL TO DO

- PAIR WITH 6-DIGIT CODE — deferred on purpose; needs separate Vercel/Firestore pairing service

### Web panel polish (Stage 2)
- GOOEY STATUS INDICATOR — SVG feGaussianBlur + feColorMatrix; maps to green/yellow/red; prefers-reduced-motion
- MATERIAL SYMBOLS ROUNDED ICONS (~30) — gated on document.fonts.load; each icon + text label
- READINESS CHECKLIST — surface accessibility / shizuku / catwebReady from /api/status (panel currently ignores them)
- UX polish — section nav, in-flight button state (no double-fire Restart Roblox), aria-live toasts,
  styled confirm modal, screenshot download+timestamp, console/vars copy + autoscroll, Enter-to-send,
  icon empty states
- check-webui.js extras — D('id') ↔ id="..." cross-ref, onclick targets exist, no external script src, no bare `$`

### Windows helper
- REAL-DEVICE TEST — GUI not yet run by a human against a plugged-in phone (self-test only)

## Notes
- Overlay long-press → Ctrl+T in 3s (Shizuku preferred, TapService fallback)
- Debug keystore: decode keystore/cwbridge-debug.p12.b64 before local build
- Helper needs AdbLib vendored at build time (see build-debug-apk.yml)
