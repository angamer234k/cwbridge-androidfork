
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

IN PROGRESS — PACKAGE FOLDERS (branch `refactor/package-folders`, committed, NOT pushed)
= 22 of 29 files moved out of the flat package (was 29 .kt files, 0 subdirs):
    bridge/  AntiDisconnect BridgeControl BridgeNotifier BridgeStatus CatWebTracker
             LogBuffer LogcatReader RecentLogLines RobloxLogBuffer
    engine/  ExecutionEngine InvokeEngine ServiceAdapter
    data/    ServiceConverters ServiceDao ServiceDatabase ServiceEntity ServiceModels
             Store VarStore
    server/  LocalHttpServer ServerAuth WebUi
  KEPT AT ROOT ON PURPOSE: MainActivity OverlayService TapService ShizukuShell UpdateChecker
  DeviceStatus FirstRun. AndroidManifest uses dot-relative names (.MainActivity .TapService
  .OverlayService) against namespace com.cwbridge.android, and accessibility_service_config.xml
  hardcodes "com.cwbridge.android.MainActivity" as a STRING. Moving any of those breaks the
  launcher, the a11y service or the overlay with NO compile error.
= imports verified: `python scripts/_reorg.py --check` -> 41 declarations, 0 missing, 0 unused
= line endings normalised back to CRLF after the scripted rewrite
= COMMITTED as 8a0dab9 "do stuff" on branch refactor/package-folders (forked from 5c2bc9a).
  Working tree is CLEAN. Verified by `git ls-tree`: 29 .kt in HEAD, 29 in the tree (7 root + 22 moved).
- BRANCH IS BEHIND MAIN: main moved on to e99d0bc (+ d47cb75), both README.md only, and local main
  == origin/main. The branch forked at 5c2bc9a, so main is 2 ahead and the branch is 2 ahead.
  `git merge-tree` dry-run reports NO CONFLICTS. Nothing is pushed anywhere.
- scripts/check-webui.js line 20 still hardcodes `.../android/WebUi.kt` -> now server/WebUi.kt.
  THIS GATES EVERY BUILD (build-debug-apk.yml + release.yml). Must be fixed, ideally by making
  it auto-discover WebUi.kt anywhere under the java tree so the next move can't break CI again.
- 5 workflows still hardcode moved paths (earlier I said 2 — wrong):
    fix-logcat-lifecycle.yml:75          LogcatReader.kt
    patch-textman-ui.yml:195             ServiceModels.kt
    restore-good-sources.yml:18,19,25    ServiceModels.kt
    restore-textman-ui.yml:19,20,25,246  ServiceModels.kt
    ship-textman-ui-real.yml:19,20,23,28 ServiceModels.kt
  The last THREE are the dangerous ones: they do
  `git show GOOD:.../ServiceModels.kt > .../ServiceModels.kt`, which would RE-CREATE a stray
  root ServiceModels.kt = duplicate class in the root package.
- CANNOT COMPILE LOCALLY: no gradlew, no gradle-wrapper.jar, no Android SDK on this machine.
  Stage 1 is UNVERIFIED until CI runs. Land it alone and wait for green before touching the
  web panel, so a red build can only mean the move, not a CSS filter.
+ CLEANUP DONE (both were committed by mistake in 8a0dab9): scripts/_reorg.py deleted and
  scripts/__pycache__/_reorg.cpython-313.pyc untracked from git. `__pycache__/` is now in
  .gitignore. _reorg.py stays recoverable via `git show 8a0dab9:scripts/_reorg.py` — its
  `--check` mode was the only local import verifier we have, so keep that command handy.

STILL TO DO — WEB PANEL (Stage 2, not started; was blocked on Stage 1 going green)
- GOOEY STATUS INDICATOR — native SVG feGaussianBlur + feColorMatrix (alpha row 0 0 0 24 -11),
  blobs in their own filtered layer so the pill text stays crisp; maps onto the existing
  green/yellow/red overlay-dot semantics; honours prefers-reduced-motion.
- MATERIAL SYMBOLS ROUNDED ICONS (~30, e.g. restart_alt sports_esports photo_camera
  power_settings_new send touch_app keyboard_return content_paste extension database
  data_object terminal lock delete_sweep tune), gated on document.fonts.load so a failed
  font can't leak the raw ligature words (visibility:hidden until loaded). Each icon is
  paired with a text label, so a missing font costs nothing.
- READINESS CHECKLIST — /api/status already returns accessibility / shizuku / catwebReady and
  the panel throws ALL THREE away. Highest-value change, needs no backend work; this is what
  answers "why isn't my tap working?".
- section nav, in-flight button state (kills the double-fire on "Restart Roblox"), aria-live
  toasts, styled confirm modal replacing confirm(), screenshot download + timestamp,
  console/vars copy buttons + autoscroll, Enter-to-send, icon-based empty states.
- guard additions to check-webui.js: D('id') <-> id="..." cross-reference, onclick target
  exists, no external <script src>, and NO bare `$` in the page. Kotlin raw strings still
  interpolate, so a stray $ is a Kotlin COMPILE error, not a web bug — the page is
  deliberately $-free today and the inline JS uses + concatenation for exactly this reason.

WINDOWS HELPER — committed in d838f4f, but HALF COMPLETE
+ scripts/cwbridge-updater.ps1 — update check + APK download
+ scripts/cwbridge-helper-gui.ps1 — headless core + self-test: 22 passed, 0 failed
- THE WINDOW IS NOT BUILT. The file ends at Write-Log (line 864); no Form is ever constructed,
  nothing calls ShowDialog / Application.Run, and Write-Log writes to $script:Rtb which is never
  created -> calling it throws. Never launched by a human, only run with -SelfTest.
- so: still needs the actual form, then a real local run against a device.
