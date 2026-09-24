# CWBridge Android

Native Android companion for [cwbridge](https://www.npmjs.com/package/cwbridge) — the local Roblox / CatWeb services bridge.

Windows CWBridge drives the Roblox window from the desktop. This app does the same on a phone:

- **CWBridge Tap** — `AccessibilityService` that:
  - clicks nodes by text (normal Android UI)
  - injects **gesture taps by % of screen or pixels** (Roblox / game surfaces with an empty a11y tree)
- **Logcat** — process-local ring buffer always on; system `logcat` when `READ_LOGS` is granted via ADB
- **Bridge control** — start / stop with the same idle-while-Roblox-closed model as desktop

Version: **2.7.2-android**.

## Why % / px for Roblox

Roblox draws with its own renderer. Android only sees one opaque surface — no buttons or labels in the accessibility tree. `clickByText` therefore finds nothing inside the game. `dispatchGesture` still works at screen coordinates, so use **Tap %** (preferred) or **Tap px**.

Example defaults: `50%` / `85%` ≈ center-bottom (often a primary action).

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
# Android 13+: App info → ⋮ → Allow restricted settings (once)

# Optional system logcat (privileged):
adb shell pm grant com.cwbridge.android.debug android.permission.READ_LOGS
```

Without `READ_LOGS`, the in-app log view still shows CWBridge / A11y lines from the process buffer.

## Usage

1. Enable **CWBridge Tap** in Accessibility (unlock restricted settings if needed).
2. Open the app → **Start**.
3. Open Roblox in the foreground.
4. In CWBridge, set **X % / Y %** (or pixels) → **Tap %** / **Tap px**.
5. Logs show each gesture and resolved pixel position.

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
