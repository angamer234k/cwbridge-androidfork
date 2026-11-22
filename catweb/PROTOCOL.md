# CWBridge public CatWeb protocol (v1)

Wire format for **CLIENT** (any CatWeb page) ↔ **SERVER** (page that talks to CWBridge) ↔ **CWBridge**.

## Goals

- CLIENT never talks to CWBridge directly (only SERVER does).
- Payloads that can hit the Roblox filter are encoded with the CatWeb decoder table (SiteGPT §9).
- Every request has a short **reqId**; the bridge requires it and echoes it so SERVER can match responses. Missing, malformed, empty, or reserved `0` IDs are silently ignored.
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
- `reqId` = unique ID (1–64 ASCII letters, digits, `_` or `-`) — **not encoded**. The bundled client combines a page-load tick with a per-page counter so a reload does not immediately reuse IDs.
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

## SERVER → CWBridge (ACK log)

After the response has been parsed, the client reply has been broadcast (when a matching queue row exists), and the inbox has been cleared, SERVER logs:

```
invoke||ACK||<reqId>||<first 10 characters of the pasted 0./1. response>
```

The ten-character prefix includes the leading `0.` or `1.`. CWBridge releases its single active request only when both the REQID and prefix match.

If CWBridge resends the same response because the ACK was lost, SERVER clears the inbox and logs the same ACK again. It must not broadcast the client reply a second time for an already-consumed REQID.

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

On matching paste `||reqId`: delete row, encode body (keep `0.`/`1.` prefix), cross-site broadcast `R|...` to `clientUrl`, clear the inbox, then log the ACK above.

CWBridge processes only one request at a time. Queue order is:
1. P0 datastore: `save`, `load`, `storeinfo`, `exists`, `keys`
2. P1 administration/control: `setlimit` and other control commands
3. P2 weather
4. P3 AI: `ai`, `ai.models`, `ai.pricing`

FIFO is preserved within each priority. A running request is never preempted. CWBridge waits 5 seconds for an ACK and makes up to three delivery attempts; after that it marks the response unconfirmed and pauses the queue until a matching late ACK arrives or the bridge is stopped.

## Requirements

| Piece | Need |
|-------|------|
| CWBridge | build with `\|\|reqId` paste protocol (`0.`/`1.` prefixes) |
| CatWeb | v2.19+ (cross-site broadcast + Get URL) |
| Decode script | SiteGPT §9.2 on both CLIENT and SERVER pages (`d` + `e`) |
| Focus | SERVER page open; inbox TextBox focused for pastes |
