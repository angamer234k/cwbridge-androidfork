# CWBridge Android

Native Android companion for [cwbridge](https://www.npmjs.com/package/cwbridge) — the local Roblox / CatWeb services bridge.

Windows CWBridge drives the Roblox window from the desktop. This app does the same on a phone:

- **CWBridge Tap** — `AccessibilityService` that clicks nodes by text and can dispatch gesture taps
- **Logcat** — process-local ring buffer always on; system `logcat` when `READ_LOGS` is granted via ADB
- **Bridge control** — start / stop with the same idle-while-Roblox-closed model as desktop

Version aligns with the npm package line: **2.7.1-android**.

## Build a debug APK (CI)

Every push to `main` (and manual **Run workflow**) builds a debug APK:

**.github/workflows/build-debug-apk.yml** → artifact **`cwbridge-debug-apk`**

```bash
# Download from the Actions run, or build locally:
gradle :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Install & permissions

```bash
adb install -r app-debug.apk

# Accessibility: Settings → Accessibility → CWBridge Tap → On

# Optional system logcat (privileged):
adb shell pm grant com.cwbridge.android.debug android.permission.READ_LOGS
```

Without `READ_LOGS`, the in-app log view still shows CWBridge / A11y lines from the process buffer.

## Package

| | |
|---|---|
| applicationId | `com.cwbridge.android` (debug suffix `.debug`) |
| minSdk | 26 |
| targetSdk | 34 |
| Tap service | `com.cwbridge.android.TapService` |

## Security notes

- Accessibility can observe and click other apps. Only enable builds you trust.
- `READ_LOGS` is signature/privileged; production Play Store builds will not receive it without OEM privileges. Debug sideload + ADB grant is the intended path.
- Network services (weather, fetch, etc.) can be wired in follow-up; this tree ships the Android control plane first.

## License

UNLICENSED — companion experiment around the public `cwbridge` npm CLI.
