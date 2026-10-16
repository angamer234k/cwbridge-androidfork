# CWBridge public CatWeb protocol (v1)

Wire format for **CLIENT** (any CatWeb page) ↔ **SERVER** (page that talks to CWBridge) ↔ **CWBridge**.

## Goals

- CLIENT never talks to CWBridge directly (only SERVER does).
- Payloads that can hit the Roblox filter are encoded with the CatWeb decoder table (SiteGPT §9).
- Every request has a short **reqId**; the bridge echoes it so SERVER can match responses.
- Status is always a single leading digit on the decoded body:
  - `0.` = ok
  - `1.` = error (empty load, missing key, bridge down, timeout, etc.)

## Config (CLIENT)

| global | meaning | example |
|--------|---------|---------|
| `{1}` | SERVER endpoint page URL | `yoursite.rbx/bridge` |
| `{2}` | default domain for save/load shortcuts | `app.rbx` |
| `{30}` | command for next `call` | `load` |
| `{31}` | arg1 | key / text |
| `{32}` | arg2 | domain / value |

CLIENT also sends its **full page URL** (`Get URL` id 117), encoded — CatWeb only exposes the bare domain otherwise.

## CLIENT → SERVER (cross-site broadcast)

```
C|<reqId>|<encFullUrl>|<encCmd>[|<encArg1>[|<encArg2>]]
```

- `C` = call
- `reqId` = short id (digits preferred) — **not encoded**
- other fields = CatWeb-encoded (`e` / §9 table)

SERVER handles this on **When cross-site message received** (event 14).

## SERVER → CWBridge (Log)

```
invoke|<cmd>.<arg1>.<arg2>||<reqId>
```

Examples:

```
invoke|load.mykey.weather.rbx||48291
invoke|save.mykey.hello.weather.rbx||48292
invoke|qr.otpauth://totp/...||48293
invoke|totp.BASE32SECRET||48294
```

## CWBridge → SERVER (paste into focused inbox)

```
0.<body>||<reqId>     # ok
1.<body>||<reqId>     # error
```

Examples:

```
0.secretvalue||48291
1.empty||48291
1.not found||48291
```

SERVER keeps focus on a reply inbox TextBox, reads the paste, checks the queue for `reqId`, then replies to the client.

## SERVER → CLIENT (cross-site broadcast)

```
R|<reqId>|<encPayload>
```

Decoded payload **must** start with `0.` or `1.`.

## Public CLIENT API

From any script on the same page:

```
Set (30) to (load)
Set (31) to (session)
Set (32) to (app.rbx)
Run function (call) () → (resultVar)
```

Or convenience wrappers `load` / `save` once those scripts are imported.

`Run function (DATA) (ARG1) (ARG2)` maps to CatWeb id 87 — DATA is the function name.

## Error handling

1. Empty / failed LOAD → SERVER sends `1.empty` or `1.not found` (encoded) to the client.
2. Unknown / expired reqId → SERVER drops the paste (no client reply).
3. Queue wait on the client is 30s (then `1.timeout`); delete after match or timeout.
4. Never put raw user text in broadcasts without encoding.

## Queue (SERVER)

| reqId | clientUrl | createdTick |

On matching paste `||reqId`: delete row, encode body (keep `0.`/`1.` prefix), cross-site broadcast `R|...` to `clientUrl`.

## Requirements

| Piece | Need |
|-------|------|
| CWBridge | build with `\|\|reqId` paste protocol (`0.`/`1.` prefixes) |
| CatWeb | v2.19+ (cross-site broadcast + Get URL) |
| Decode script | SiteGPT §9.2 on both CLIENT and SERVER pages (`d` + `e`) |
| Focus | SERVER page open; inbox TextBox focused for pastes |
