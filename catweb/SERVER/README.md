# SERVER pack

One page that stays open while CWBridge runs (example: `yoursite.rbx/bridge`).

Import Decode (`d` / `e`) on this page. `page.json` is a minimal shell with an `inbox` TextBox (alias `inbox`). The bridge must paste into that field (focus it; set CWBridge focus % to the inbox).

## Flow

1. Cross-site `C|reqId|encUrl|encCmd|...` arrives
2. Decode, queue `reqId → client URL`
3. `Log invoke|cmd.arg1.arg2||reqId`
4. Poll `inbox` for `0.body||id` or `1.body||id`
5. If that id is queued, encode the body (prefix included) and cross-site `R|id|enc` back, then delete the queue row

Client waits 30s, then returns `1.timeout` on its own.

Empty loads and bridge errors arrive already prefixed `1.` (for example `1.empty||id`).
