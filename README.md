<p align="center">
  <img src="https://www.xn--e1aleee.space/api/png/text?t=cwbridge-androidfork" alt="CWBridge Android" />
  <br />
  <img src="https://img.shields.io/github/v/release/angamer234k/cwbridge-androidfork?label=Release&style=flat-square" alt="Release" />
  <img src="https://img.shields.io/github/downloads/angamer234k/cwbridge-androidfork/total?label=Downloads&style=flat-square" alt="Downloads" />
  <img src="https://img.shields.io/github/actions/workflow/status/angamer234k/cwbridge-androidfork/build-debug-apk.yml?label=Build&style=flat-square" alt="Build" />
<br />
made by lemon
</p>

## What is this?

**CWBridge Android** is a small helper app that sits on your phone (or any Android device) and talks to a game or app running on the **same device**.

In plain terms:

1. Another program (for example a Roblox experience that uses [CatWeb](https://github.com/) / [cwbridge](https://www.npmjs.com/package/cwbridge)) prints short commands into the system log, like “tap here” or “save this value”.
2. CWBridge reads those log lines, then performs the action on screen — taps, pastes text, stores data, and so on.
3. You can also build simple **services** (trigger → actions) in the app so automation runs without opening a PC.

You do **not** need to know Roblox internals to use it. Think of it as: *“read commands from the game’s console log → do taps and other actions on the device.”*

This is the native Android companion for the [cwbridge](https://www.npmjs.com/package/cwbridge) npm package.

## Downloads

Published builds: **[Releases](https://github.com/angamer234k/cwbridge-androidfork/releases)**

| APK | Purpose |
|-----|---------|
| `cwbridge-android-debug.apk` | **Main app** — listens for commands, taps the screen, runs services |
| `cwbridge-helper-debug.apk` | **Installer helper** — pushes the main app to a second device over USB (OTG) when wireless debugging isn’t available |

To publish a new build: **Actions → Release APKs → Run workflow** (tag e.g. `v2.9.2`).

## Main app (quick start)

1. Install `cwbridge-android-debug.apk` on the device where the game/app runs.
2. Enable **CWBridge Tap** under Android **Accessibility** settings.
3. Grant **READ_LOGS** once (via ADB or the helper app) so CWBridge can see console output:
   ```bash
   adb shell pm grant com.cwbridge.android.debug android.permission.READ_LOGS
   ```
4. Optionally allow **Display over other apps** for the floating status dot (green = listening, yellow = waiting, red = error).
5. Open the app → **Start bridge**. Keep Roblox (or your target app) in the foreground so logcat advances and taps land correctly.

## Built-in `invoke|` commands

The bridge watches **Roblox logcat** for lines that contain `invoke|…`. Format:

```text
invoke|<command>[.<arg1>[.<arg2>…]]
```

Dots separate parts. Example from a CatWeb / game print:

```text
invoke|load.mykey.weather.rbx
invoke|tap.50.85
invoke|enter
```

Replies (when the engine answers) show up as log-style `cwbridge|…` lines the game can read back if it watches output.

Source of truth: [`InvokeEngine.kt`](app/src/main/java/com/cwbridge/android/engine/InvokeEngine.kt).

### Datastore

| Command | Form | Notes |
|---------|------|--------|
| **save** | `save.<key>.<value>[.<domain.rbx>]` | Stores text. Optional domain; default domain if omitted. Counts toward rate limit. |
| **load** | `load.<key>.<domain.rbx>` | Loads value and **pastes** it into the focused field (~1–2s). |
| **savedomain** | `savedomain.<domain>.…` | Domain-oriented save helper (see engine). |
| **storeinfo** | `storeinfo.<domain.rbx>` | Pastes numbers: `5.STORED_BITS.LIMIT_BITS.KEYS.REQ_USED.REQ_MAX.REQ_LEFT` |
| **setlimit** | `setlimit.<domain.rbx>.<type>.<value>` | **Admin domain only.** type `0` = requests/day, `1` = data limit (bits). |
| **exists** | `exists.<key>.<domain.rbx>` | Free. Pastes `1` or `0`. |
| **keys** | `keys.<domain.rbx>` | Cost 1. Pastes key list / count. |

### Services (built-in handlers)

| Command | Form | Notes |
|---------|------|--------|
| **weather** | `weather.<lat>.<lon>` or `weather.<City>` | Cost **2**. Fetches weather and pastes a short result. |
| **alive** | `alive` | Free. Pastes `1` if accessibility is up, else `0`. |
| **ai** | `ai.<prompt>` | Cost **2**. Uses AI settings from the web panel; pastes model text (may auto-Enter depending on build). |

### Input / UI

| Command | Form | Notes |
|---------|------|--------|
| **tap** | `tap.<xPct>.<yPct>` | Percent of screen (0–100). |
| **tappx** | `tappx.<xPx>.<yPx>` | Absolute pixels. |
| **focus** | `focus.<xPct>.<yPct>` | Sets where **load/paste** tap before pasting (default 50,50). |
| **submit** | `submit.<xPx>.<yPx>` | Sets optional post-paste tap coords (`0` = skip). |
| **paste** | `paste.<text>` or `paste` | Pastes text, or clipboard if no arg. |
| **enter** / **return** | `enter` | Presses Enter (accessibility or Shizuku). |
| **clip** | `clip.set.<text>` / `clip.get` | Clipboard set / get. |

### Utility

| Command | Form | Notes |
|---------|------|--------|
| **status** | `status` | Device / bridge snapshot. |
| **wait** | `wait.<ms>` | Delay 0–30000 ms. |
| **toast** | `toast.<message>` | Android toast. |
| **echo** | `echo.<message>` | Echoes in reply only. |
| **help** | `help` | Short command list in the reply. |

### Timing note (load / paste)

After `load` or `paste`, expect roughly **1.5–2.5s** before text appears (focus tap → **1s** wait → paste). Identical `invoke|` lines within **3s** are **deduped** so Roblox does not triple-fire the same command.

## Web server (control panel)

When the local server is on, open `http://<device-ip>:<port>/` (port tries **8080**, then **8765**, then **80**).

### Access rules

- Password lock is served first; the dashboard is only after login (session cookie).
- Prefer the same Wi‑Fi network as the phone.

### Known limits

- **Screenshot** requires **Android 11 (API 30)+**. On older versions the button is not shown in the web panel at all.
- **Ctrl+T / Enter / Restart Roblox** need **Shizuku** running with CWBridge allowed. Without it, Restart Roblox cannot force-stop Roblox and says so.
- The first time you open the app after installing, CWBridge shows a one-time notice listing which of these may not work on your device.
- The 6-digit **pair code** remote control lives at the separate web control service when enabled in-app.

## Updates

**Check for updates** at the bottom of the main screen queries the GitHub releases API and offers the matching APK (debug builds get the debug asset).

## Helper app (install onto another device over USB)

Use this when the **target device** can’t use wireless debugging and you want to install or update the main APK from a second Android device (phone, etc.) over a USB/OTG cable.

1. Install the **helper** on the device that has the OTG cable plugged in (USB host).
2. On the **target device**: turn **USB debugging** on.
3. Connect host ↔ OTG ↔ target with a data cable.
4. Accept the USB debugging prompt on the target (first time only).
5. Open the helper → **Push update**.

The helper will:

1. Download the latest main APK from GitHub Releases  
2. Check whether the main app is already installed  
3. Install or update it over USB ADB  
4. Grant `READ_LOGS`  
5. Show success or errors in its log pane  

## Windows updater (same job, from a PC)

A PC is a better USB host than a phone, so the helper's job is also available as a
PowerShell script. It finds `adb`, reports the device and installed version, pulls
the latest release APK, installs it, grants `READ_LOGS`, and can start Shizuku or
tail logcat.

```powershell
# read-only device report
.\scripts\cwbridge-updater.ps1 -Status

# install / update from the latest release
.\scripts\cwbridge-updater.ps1

# tail logcat
.\scripts\cwbridge-updater.ps1 -Logs
```

Needs [Android platform-tools](https://developer.android.com/tools/releases/platform-tools); `adb` is
auto-detected. Full details and flags: [`scripts/README-cwbridge-updater.md`](scripts/README-cwbridge-updater.md).

The CWBridge **bridge** itself is Android-only (AccessibilityService, `READ_LOGS`, overlay, Shizuku) —
this tool updates it, it does not run it.

## License

UNLICENSED experiment. The helper vendors [cgutman/AdbLib](https://github.com/cgutman/AdbLib) (BSD-3-Clause) at build time.
