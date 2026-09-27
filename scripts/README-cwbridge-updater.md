# CWBridge Updater (Windows)

A Windows port of the Android **helper** app. It installs or updates CWBridge on a
USB-connected device from a PC, using `adb`.

> The CWBridge *bridge* itself is Android-only — it needs `AccessibilityService`,
> `READ_LOGS`/`logcat`, a `WindowManager` overlay and Shizuku. None of that
> exists on Windows. This tool does what the helper does: push the APK, grant
> permissions, and report device state.

## Requirements

- Windows 10/11 with PowerShell 5.1+
- [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`)
  — auto-detected, or pass `-Adb <path>`
- Device with **USB debugging** enabled and the prompt accepted

## Usage

```powershell
# report device state without changing anything
.\scripts\cwbridge-updater.ps1 -Status

# interactive install / update from the latest GitHub release
.\scripts\cwbridge-updater.ps1

# tail logcat from the device
.\scripts\cwbridge-updater.ps1 -Logs

# pick a device, skip side effects
.\scripts\cwbridge-updater.ps1 -Serial 0123456789ABCDEF -SkipGrant -SkipLaunch
```

If PowerShell blocks the script:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\cwbridge-updater.ps1 -Status
```

## What it does

1. Finds `adb` (PATH, `ANDROID_HOME`, or common SDK paths)
2. Lists devices; prompts if more than one
3. Reports Android version, installed CWBridge version, Shizuku, and whether
   **CWBridge Tap** is enabled in Accessibility
4. Fetches the latest GitHub release and picks the **main app** APK
   (deliberately excluding `cwbridge-helper-debug.apk`, a different package)
5. Downloads with a progress bar and verifies the byte count
6. `adb install -r -g`
7. Grants `READ_LOGS` (the bridge cannot read console output without it)
8. Optionally starts Shizuku and launches CWBridge

APKs cache in `%TEMP%\cwbridge-updater`, so repeat runs skip the download.

## Notes

- `-Logs` and the Shizuku step are conveniences; neither is required.
- On **INSTALL_FAILED_UPDATE_INCOMPATIBLE** a differently-signed build is
  installed. Uninstall first: `adb -s <serial> uninstall com.cwbridge.android.debug`
  (this clears app data).
- Restarting Shizuku from a script only works while the device has not rebooted.
