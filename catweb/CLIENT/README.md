# CLIENT (from scratch)

Import as a **whole page** (same method that worked for SERVER).

## Smoke test first
1. New blank site in CatWeb
2. Import raw [mini.json](https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/main/catweb/CLIENT/mini.json)
3. If that fails, the problem is the import path — not the client logic

## Full client
1. New blank site
2. Import raw [page.json](https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/main/catweb/CLIENT/page.json)
3. Import SiteGPT decoder (`e` / `d`) onto the same page
4. Edit variable `1` = your server page URL (`site.rbx/path`)

## API
```
Set 30 = cmd          # load | save | any invoke name
Set 31 = arg1         # optional
Set 32 = arg2         # optional
Run function (load)   # or save / call
→ {4} = 0.body | 1.err | 1.timeout
```

Wire: `C|reqId|urlEnc|cmdEnc[|arg1][|arg2]` → server → `R|reqId|bodyEnc`
Timeout 30s (60 × 0.5s).

Old `api.json` / `config.json` are obsolete — use `page.json`.
