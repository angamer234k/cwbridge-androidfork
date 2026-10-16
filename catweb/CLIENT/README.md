# CLIENT pack

Drop `config.json`, `api.json`, and `../codec/decoder.json` on the page.

## Config

| global | default | meaning |
|--------|---------|---------|
| `{1}` | `yoursite.rbx/bridge` | SERVER page URL |
| `{30}` | | command |
| `{31}` | | arg1 |
| `{32}` | | arg2 |

## Call (30s)

```
Set (30) to (load)
Set (31) to (mykey)
Set (32) to (app.rbx)
Run function (call) () → (result)
```

`result` starts with `0.` or `1.` (`1.timeout` after 30s).

`e` / `d` come from `catweb/codec/decoder.json`.
