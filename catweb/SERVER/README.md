# SERVER pack (updated)

Import `page.json` plus the decoder (`../codec/decoder.json`).

Keep the page open with the **inbox** TextBox focused.  
Client waits 30s then returns `1.timeout`.

Queue is a simple table (reqId → clientUrl). Stale entries get cleaned when replied. Status label shows last action.

`e` / `d` come from the decoder.
