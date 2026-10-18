# v2 (simple)

Wire:
- request: `1|<id>|<yourDomain>|<payload>`
- reply:   `2|<id>|<result>`

## Server
Import `cwbridge-server-v2.json` on the host (e.g. s.eof.rbx).
Tap the box once. Bridge writes result, press Enter. Reply goes to domain from the request.

## Client
Import `cwbridge-client-v2.json`.
1. Top box = your domain (the page that should get the reply)
2. Second box = payload (e.g. alive)
3. Enter

No queue, no PART chunks, no sourceDomain guessing.
