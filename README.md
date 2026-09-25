<p align="center">
  <img src="https://www.xn--e1aleee.space/api/png/text?t=cwbridge-androidfork" alt="CWBridge Android" />
  <br />
  <img src="https://img.shields.io/github/v/release/angamer234k/cwbridge-androidfork?label=Release" alt="Release" />
  <img src="https://img.shields.io/github/downloads/angamer234k/cwbridge-androidfork/total?label=Downloads" alt="Downloads" />
  <img src="https://img.shields.io/github/actions/workflow/status/angamer234k/cwbridge-androidfork/build-debug-apk.yml?label=Build" alt="Build" />
</p>

Native Android companion for [cwbridge](https://www.npmjs.com/package/cwbridge).

## Downloads (Releases)

Published builds live under **[Releases](https://github.com/angamer234k/cwbridge-androidfork/releases)**:

| APK | What |
|-----|------|
| `cwbridge-android-debug.apk` | Main app — taps, `invoke|` engine, logcat |
| `cwbridge-helper-debug.apk` | Phone helper — OTG ADB push to tablet |

Trigger a new release: **Actions ↦ Release APKs ↦ Run workflow** (enter tag e.g. `v2.8.1`).

## Helper (phone → tablet)

1. Install **helper** on the phone (OTG host).
2. Tablet: **USB debugging** ON.
3. Connect phone ↔ OTG ↔ tablet (data cable).
4. Allow USB debugging on the tablet (once).
5. Open helper ↦ **Push update**.

The helper:

1. Downloads latest `cwbridge-android-debug.apk` from GitHub Releases  
2. Checks if `com.cwbridge.android.debug` is installed  
3. Installs or updates via OTG ADB  
4. Runs `pm grant … READ_LOGS`  
5. Shows success / error in the log pane  

## Main app — `invoke|` (see earlier docs)

`save` / `load.key.domain` / `status` / `tap` / `paste` / … need **READ_LOGS** on the tablet to see Roblox FLog lines.

## License

UNLICENSED experiment. Helper vendors [cgutman/AdbLib](https://github.com/cgutman/AdbLib) (BSD-3-Clause) at build time.
