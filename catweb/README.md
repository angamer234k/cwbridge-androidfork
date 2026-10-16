# CatWeb ↔ CWBridge public scripts

Reusable **CLIENT** + **SERVER** CatWeb scripts so any site can talk to CWBridge without reinventing encode / queue / reply logic.

Full wire format: **[PROTOCOL.md](./PROTOCOL.md)**

## Layout

```
catweb/
  PROTOCOL.md          wire format (source of truth)
  CLIENT/              drop on any page that needs bridge features
  SERVER/              one dedicated bridge page (must stay open)
  tools/encode.py      encode/decode helper for config strings
```

## Quick start

1. **Phone:** CWBridge + Tap accessibility + Shizuku, bridge running
2. **SERVER** page (e.g. `yoursite.rbx/bridge`):
   - Import SERVER scripts
   - Import official CatWeb **Decode** (SiteGPT §9.2) — provides `d` / `e`
   - Keep tab focused; bridge pastes into the `inbox` TextBox
3. **CLIENT** page:
   - Import CLIENT scripts + Decode
   - Set `{1}` = SERVER URL
4. Call from any CLIENT-side script:

```
Set (30) to (load)
Set (31) to (mykey)
Set (32) to (app.rbx)
Run function (call) () → (result)
```

`result` always starts with `0.` (ok) or `1.` (error).

## Status prefix

| prefix | meaning |
|--------|---------|
| `0.` | success body |
| `1.` | error body |

## Improving this

PRs welcome. Keep PROTOCOL.md as the wire-format source of truth.
