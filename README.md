<p align="center">
  <img src="https://www.xn--e1aleee.space/api/png/text?t=cwbridge-androidfork" alt="CWBridge Android" />
  <br />
  <img src="https://img.shields.io/github/v/release/angamer234k/cwbridge-androidfork?label=Release&style=flat-square" alt="Release" />
  <img src="https://img.shields.io/github/downloads/angamer234k/cwbridge-androidfork/total?label=Downloads&style=flat-square" alt="Downloads" />
  <img src="https://img.shields.io/github/actions/workflow/status/angamer234k/cwbridge-androidfork/build-debug-apk.yml?label=Build&style=flat-square" alt="Build" />
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
4. Optionally allow **Display over other apps** for the floating status dot (green = listening, yellow = waiting, red = error). Tap the dot to see the last console lines.
5. Open CWBridge → **Start bridge**.

Commands use the `invoke|…` format (same idea as the old MacroDroid flow), e.g. save/load keys, status, tap, paste.

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

## License

UNLICENSED experiment. The helper vendors [cgutman/AdbLib](https://github.com/cgutman/AdbLib) (BSD-3-Clause) at build time.
