# CWBridge Android

Native Android companion for [cwbridge](https://www.npmjs.com/package/cwbridge).

Version **2.8.0-android** — Accessibility taps + **`invoke|` log engine** (MacroDroid-compatible).

## invoke| protocol

Roblox / Creator logs a line containing:

```text
invoke|request.data1.data2
```

CWBridge watches system logcat (needs `READ_LOGS`) and dispatches:

| Command | Meaning |
|---------|---------|
| `save.key.data` | Store value under `local.rbx` / key |
| `load.key.domain` | Load key for domain (`weather.rbx` only — no extra subdomains/paths); value → clipboard + `cwbridge\|ok\|load\|…` |
| `status` | Battery %, charging, wifi RSSI + level (**no SSID**), uptime |
| `tap.x.y` | Gesture tap at % of screen |
| `tappx.x.y` | Gesture tap at pixels |
| `paste.text` | Focus % → wait 1s → paste clipboard → wait 1s → submit px |
| `clip.set.text` / `clip.get` | Clipboard |
| `focus.x.y` | Set paste focus % (default 50 50) |
| `submit.x.y` | Set paste submit pixels (default 730 1028) |
| `wait.ms` | Delay up to 30s |
| `toast.msg` | Toast |
| `echo.msg` | Reply with payload |
| `help` | List commands |
| `ai.prompt` | Passthrough paste for now (no in-app LLM key yet) |

Replies are logged as:

```text
cwbridge|ok|<request>|<payload>
cwbridge|err|<request>|<reason>
```

### Domain rule (`load`)

Domain must match `^[a-z0-9_-]+\.rbx$` (e.g. `weather.rbx`, `notes.rbx`).  
Rejected: `a.b.rbx`, `weather.rbx/page`, `https://…`.

## Permissions

```bash
adb install -r app-debug.apk
# Accessibility → CWBridge Tap → On  (allow restricted settings once)
adb shell pm grant com.cwbridge.android.debug android.permission.READ_LOGS
```

`READ_LOGS` is required to see Roblox `FLog::CreatorOutput` lines with `invoke|`.

## Build

CI on push to `main` → artifact **cwbridge-debug-apk** (fixed debug keystore — updates without uninstall after 2.7.3+).

```bash
base64 -d keystore/cwbridge-debug.p12.b64 > keystore/cwbridge-debug.p12
gradle :app:assembleDebug
```

## License

UNLICENSED — companion experiment around the public `cwbridge` npm CLI.
