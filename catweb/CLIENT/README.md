# CLIENT pack

Drop these scripts on any CatWeb page. Also import the official Decode script (SiteGPT §9.2) so functions `d` and `e` exist.

## Config (`config.json`)

| global | default | meaning |
|--------|---------|---------|
| `{1}` | `yoursite.rbx/bridge` | SERVER page URL — change this |
| `{2}` | `default.rbx` | optional default domain |
| `{30}` | | command (`load`, `save`, `qr`, `totp`, `status`, …) |
| `{31}` | | arg1 |
| `{32}` | | arg2 |

## Call (30s timeout)

```
Set (30) to (load)
Set (31) to (mykey)
Set (32) to (app.rbx)
Run function (call) () → (result)
```

`{result}` starts with `0.` or `1.` (`1.timeout` if the SERVER does not answer within 30 seconds).

`load` / `save` wrappers set `{30}` then run `call`. Still set `{31}` / `{32}` first.

The client sends its full page URL (`Get URL`) encoded so the SERVER can reply to `test.example.rbx/page`, not just `example.rbx`.
