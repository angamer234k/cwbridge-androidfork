# CLIENT

Import **into an existing page** (not as a whole-site JSON).

1. Copy raw [config.json](https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/main/catweb/CLIENT/config.json) — paste in editor (array of one script).
2. Edit var `1` → your server page URL (`site.rbx/path`).
3. Copy raw [api.json](https://raw.githubusercontent.com/angamer234k/cwbridge-androidfork/main/catweb/CLIENT/api.json) — paste.
4. Keep encoder `e`/`d` on the same page.

Files are **clipboard arrays** `[ { "class":"script", ... } ]` (same shape as SiteGPT decoder).

Call:
```
Set 30 → cmd   (load / save / …)
Set 31 → arg1  (optional)
Set 32 → arg2  (optional)
Run function (load) or (save) or (call)
→ {4} is 0.body or 1.err ; timeout → 1.timeout
```
