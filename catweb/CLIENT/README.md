# CLIENT (updated)

Import as a **whole page**.

## Smoke test
1. New blank site in CatWeb
2. Import raw [mini.json](https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/main/catweb/CLIENT/mini.json) first
3. Then import this `page.json`

## Full client
1. New blank site
2. Import this `page.json`
3. Import SiteGPT decoder (`e` / `d`) onto the same page
4. Edit variable `1` = your server page URL (`site.rbx/path`)

## API
```
Set 30 = cmd          # load | save | any invoke name
Set 31 = arg1         # optional
Set 32 = arg2         # optional
Run function (call)   # or load / save
→ {4} = 0.body | 1.err | 1.timeout
```

Result also shows live on the page under "Last result".

Wire: `C|reqId|urlEnc|cmdEnc[|arg1][|arg2]` → server → `R|reqId|bodyEnc`  
Timeout 30s.
