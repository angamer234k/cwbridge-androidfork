# SERVER pack (updated)

Import `page.json` plus the decoder (`../codec/decoder.json`).

Keep the page open with the **inbox** TextBox focused.  
Client waits 30s then returns `1.timeout`.

Queue is a simple table (reqId → clientUrl). Stale entries get cleaned when replied. Status label shows last action. After a response is broadcast and the inbox is cleared, the page logs `invoke||ACK||reqId||<first 10 chars of 0./1. response>`. A duplicate delivery is ACKed again without broadcasting twice.

`e` / `d` come from the decoder.
