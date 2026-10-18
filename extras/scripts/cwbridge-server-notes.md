# CWBridge server

Import `cwbridge-server.json` as one site. Do not split the script out. The public client calls `s.eof.rbx`.

The page is one multiline box. Tap it once so it stays focused. The placeholder is the status: `ready`, `wait <id>`, `queued`, `idle`.

1. Cross-site `REQ` lines are queued FIFO. One job at a time.
2. `alive` answers immediately: `RES|<id>|OK|1`.
3. Anything else is logged as `invoke|<payload>` for the Android bridge, and the box is cleared.
4. The bridge writes the result into that empty box, then Enter.
5. Under 800 bytes the reply is `RES|<id>|OK|<text>`. Longer replies are `RES|<id>|PART|<index>|<total>|<chunk>` (index starts at 0, 800-byte chunks, 0.5s apart, 8 max).
6. No result in 15s → `RES|<id>|TIMEOUT`, then the next job starts.
7. Inbound parts use `REQ|<id>|PART|<index>|<total>|<chunk>` in order. A hole gets `RES|<id>|ERR|gap`. A second id mid-assembly gets `RES|<id>|ERR|busy`. No new part for 15s → `RES|<id>|ERR|drop`.

A line you type that starts with `REQ|` is treated as a local request and the reply is written into the box. Any other line while a job is waiting is the bridge result.

Do not put `|` inside a single-shot payload or a PART chunk, `~` or a newline inside a queued payload, or a bridge result that starts with `REQ|`.

Letters in a broadcast can still get tagged. Digits and spaces survive. `REQ` / `RES` / `PART` stay because that is the wire format already published in `extras/scripts/client.json`.

Cross-site is capped by the game (about 4 uncached messages per 20s, or 5 per 2s if cached). A long PART reply can lose the tail on the slow cap.

# CWBridge client

Import `cwbridge-client.json` on the other site. Top box: payload, then Enter. Reply shows in the big box.

From your own buttons, do not set globals first:

`Run function (ask)` with one tuple argument (the payload) → your result variable.

`ask` sends `REQ|<id>|<payload>` to `s.eof.rbx`. Over 700 bytes it sends `REQ|<id>|PART|...` (max 6). It waits about 20s. `ERR|gap` or a skipped part does one full resend. The return is the joined reply, or `TIMEOUT`.
