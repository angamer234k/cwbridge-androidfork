
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
+ scripts/test-helper-gui.ps1 - smoke test for the Windows helper window, 37 checks. It strips ONLY
  the ShowDialog message-loop line, then builds the REAL window and asserts the layout at both the
  default and the minimum size, the device auto-pick, the status colours and the in-flight button
  guard. It also pins the [switch]-position bug that silently ate the label ink.
  Proven by MUTATION: four deliberate breaks (layout overflow, wrong status colour, swallowed label
  ink, renamed message loop) each produced a FAIL and exit 1 - the checks are not vacuous.
  Windows-only (WinForms), so it is deliberately NOT wired into CI: the build runners are ubuntu.
+ ALSO CHECKS: SCREENSHOT_MARKER matches the placeholder comment in the markup (silent button-drop otherwise)

DONE — PACKAGE FOLDERS (merged to main as 1c43a72, branch deleted, still UNPUSHED)
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
+ MERGED INTO MAIN LOCALLY: merge commit 1c43a72, branch refactor/package-folders deleted.
  README.md auto-merged cleanly (main's 2 README commits vs the branch side) — no conflicts.
  main is now AHEAD OF origin/main BY 4 (d838f4f, 8a0dab9, 1ddc2ae, 1c43a72) and NOT pushed.
  `git ls-tree`: 29 .kt in main — 7 at root + 22 under bridge/ data/ engine/ server/.
+ FIXED — scripts/check-webui.js line 20 hardcoded `.../android/WebUi.kt` (now server/WebUi.kt) and
  THIS GATES EVERY BUILD (build-debug-apk.yml + release.yml). It now SEARCHES app/src/main/java for
  WebUi.kt instead of hardcoding one path, refuses to guess when it finds more than one, still takes
  an explicit path argument, and prints WHICH file it checked. Verified: `node scripts/check-webui.js`
  -> OK for app/src/main/java/com/cwbridge/android/server/WebUi.kt (6 parts, 1 script block,
  16510 bytes). The old hardcoded path now exits 1 instead of silently passing on nothing.
+ FIXED — 5 workflows hardcoded moved paths (earlier I said 2 — wrong). CRUCIAL DISTINCTION: the path
  after `GOOD:` is HISTORICAL and MUST stay at the root (that is where the file sat in that commit);
  only the destination after `>` moves. Rewriting the source path too would make `git show` fail.
    fix-logcat-lifecycle.yml:75          -> bridge/LogcatReader.kt
    patch-textman-ui.yml:195             -> data/ServiceModels.kt
    restore-good-sources.yml:22,23,29    -> data/ServiceModels.kt
    restore-textman-ui.yml:21,22,27,248  -> data/ServiceModels.kt
    ship-textman-ui-real.yml:21,22,25,30 -> data/ServiceModels.kt
  The last THREE were the dangerous ones: they do
  `git show GOOD:.../ServiceModels.kt > .../ServiceModels.kt`, which would have RE-CREATED a stray
  root ServiceModels.kt = duplicate class in the root package. All five now carry a comment spelling
  out historical-source vs current-destination, so nobody "fixes" them back the wrong way.
+ MainActivity paths in those workflows are CORRECT and were left alone: MainActivity is one of the
  7 files that had to stay at the root. build-debug-apk.yml and release.yml were not edited at all —
  only the check-webui.js they call — so the push-critical path carries no YAML risk.
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

WINDOWS HELPER — core committed in d838f4f; THE WINDOW IS NOW BUILT TOO
+ scripts/cwbridge-updater.ps1 — update check + APK download (CLI)
+ scripts/cwbridge-helper-gui.ps1 — headless core + self-test: 22 passed, 0 failed, 0 skipped
+ THE WINDOW IS BUILT (it was missing: the file used to end at Write-Log, no Form was ever
  constructed, and Write-Log wrote to a $script:Rtb that did not exist). Added ~375 lines:
    Start-Core runs the SAME $script:CoreText in a background runspace, so adb cannot freeze
    the message loop; Pump-Core drains the log queue from a 120 ms timer; Set-Working
    disables every action button in flight, so nothing can double-fire.
    Form: adb path + Browse, device combo + Refresh (auto-picks the first usable device and
    keeps your pick across refreshes), 10 action buttons (Check device, Install/update,
    Install local APK, Grant READ_LOGS, Launch CWBridge, Start Shizuku, Web panel,
    Diagnostics, Clear log, Save log), a 6-row status grid coloured green/amber/red, a log
    pane and a progress bar. Resize re-runs one Set-Layout (no docking, no dock-order traps).
+ VERIFIED HERE (headless box: no adb, no device, no interactive desktop):
    - -SelfTest 22 passed, 0 failed, 0 skipped
    - a throwaway smoke harness stripped the two message-loop lines, built the real window and
      ran the real layout + fill functions -> 13/13 (controls exist, nothing lays out
      off-window, device auto-pick, status colouring, log text reaches the pane).
      Harness lived in %TEMP% and was deleted afterwards; recreate it if you want it in CI.
    - launching the real script: STA, no exception on stdout/stderr, still alive after 8 s
      inside ShowDialog. That is what a healthy modal window looks like from the outside.
- STILL NOT RUN BY A HUMAN AGAINST A REAL PHONE. Also: this box cannot display windows at
  all - even a 3-line probe Form gives FindWindow hwnd=0 - so "window not found" here proves
  nothing. The real test is running it on the desktop with a device plugged in.
+ LATENT BUG FOUND AND FIXED while building this (remember it):
    New-Lbl was `param([string]$Text, [double]$Size = 9, [switch]$Bold, $Ink)`.
    PowerShell EXCLUDES [switch] parameters from positional binding, so a switch sitting
    between two positionals shifts every later argument by one and SILENTLY DROPS the last:
    `New-Lbl 'x' 17 $true $col` bound $Ink = $true and threw "Cannot convert value "True" to
    type System.Drawing.Color" on the very first label. Invisible until now because nothing
    ever called New-Lbl (no window existed). FIX: switch moved last ->
    param(..., $Ink, [switch]$Bold), and all 6 call sites pass -Bold by name.
    RULE: never put a [switch] between positional parameters in this file.
