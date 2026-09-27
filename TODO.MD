
- MEANS NOT FINISHED
+ FINISHED
= HALF COMPLETE

TODO:

+ FLOATING OVERLAY WITH STATUS COLORS, RED = ERROR IN APP/CUSTOM SERVICE, YELLOW = WAITING FOR CATWEB INIT, GREEN = ACTIVE, LISTENING
+ TAP OVERLAY → LAST 10 ROBLOX CONSOLE LINES ([FLog::CreatorOutput])
+  MAKE "CHECK FOR UPDATES" ACTUALLY WORK
+ MAKE WEB SERVER
+ MAKE `paste` INTERNAL SERVICE PASTE CLIPBOARD TEXT IF NO TEXT ARG. IS PROVIDED
+ DELETE FINISHED TODO'S
+ MAKE APP DONT CRASH IF A TOOL/SERVICE FAILS (MAIN APP)
+ FOR WEB SERVER: MAKE A LIMIT ON HOW MUCH DATA CAN ONE DOMAIN STORE (BITS, BYTES, KB, MB, GB); MAKE SERVICES CREATOR; MAKE REMOTE CONTROL (IF USER ACCESSES THROUGH LOCAL NETWORK IP (192.XXX.XXX.XXX) DONT ASK FOR IN APP PASSWORD, IF PROXIED OR ANYTHING, GENERATE A PASSWORD OR TO SKIP, PAIR WITH 6 DIGIT CODE IN APP (PAIR CODE WOULD BE ON A DIFF. REPO WITH ITS OWN VERCEL.APP SUBDOMAIN FINISHED WITH FIRESTORE AND OTHER)
= FOR WEB SERVER: FOR REMOTE CONTROL: ADD "TAKE SCREENSHOT" BUTTON, "RESTART BRIDGE", "RESTART ROBLOX" AND MORE.

SUB ITEMS:
+ PER-DOMAIN STORAGE LIMIT (BITS / BYTES / KB / MB / GB) — Store.parseSize + formatBytes, over-cap saves rejected
+ SERVICES CREATOR — list / create / edit JSON / enable / disable / delete / run from the web UI
+ REMOTE CONTROL — LAN IP (192.168.x, 10.x, 172.16-31.x) needs NO password; anything else needs the generated password (ServerAuth + session cookie)
= "TAKE SCREENSHOT" BUTTON — auto-hides on Android <11 (WebUi.page); endpoint reports unsupported if called anyway
+ FIRST-RUN COMPATIBILITY NOTICE — one-time dialog after install listing what may not work (screenshot, Shizuku, permissions)
+ "RESTART BRIDGE" BUTTON
+ "RESTART ROBLOX" BUTTON (force-stop via Shizuku, then relaunch)
+ "AND MORE" — Ctrl+T, Enter, read clipboard, toggle bridge, tap by %/px/text, send invoke, live console, variables
+ SERVER UI WIRING — real categoryServer section (was dead getIdentifier code), password + quota controls
+ README.MD — web server, LAN-vs-remote auth rules, known limits

STILL TO DO:
- PAIR WITH 6 DIGIT CODE IN APP — deliberately deferred, needs the separate Vercel/Firestore pairing repo

CI GUARDS ADDED:
+ scripts/check-webui.js — reconstructs the page from WebUi.kt and compiles the inline JS, because the
  Kotlin compiler never sees it. A \\' inside a Kotlin raw string ships a page whose <script> block
  fails to parse at runtime, so the panel renders blank while the build stays green.
  Runs in both build-debug-apk.yml (every push) and release.yml, before the Gradle build.
+ ALSO CHECKS: SCREENSHOT_MARKER matches the placeholder comment in the markup (silent button-drop otherwise)
