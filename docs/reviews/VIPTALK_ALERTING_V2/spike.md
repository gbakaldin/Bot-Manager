# VIPTALK_ALERTING_V2 — Phase 0 spike results

Run 2026-08-17 against the live VipTalk API with the real bot token and the
P_116 room (`!mPUwMJaqokWLVMsCMw:matrix-uat.viptalk.org`).

## Q1 — Does VipTalk read query parameters on `sendMessage`?

**No.** Query params are ignored entirely.

```
POST /v1/bot/{token}/sendMessage?text=...&roomIds=...
Content-Type: application/json
{"ignored":true}

→ HTTP 400
{"statusCode":400,"error":"Cannot send empty message",
 "errcode":"M_CANNOT_SEND_EMPTY_MESSAGE"}
```

The server echoed the full query string back in the `path` field of the error
and still reported the message as empty — so it parsed the URL but sourced
`text` only from the body.

**Consequence: AD-V8 (static Alertmanager receiver carrying the message in the
query string) is not viable.** Phase 6 falls back to **AD-V8b**, the separate
shim container.

## Q1b — Unplanned follow-up: does VipTalk accept a JSON body?

**Yes**, and this was not known when the plan was written.

```
POST /v1/bot/{token}/sendMessage
Content-Type: application/json
{"text":"...","roomIds":["!room:matrix-uat.viptalk.org"]}

→ HTTP 200
{"requestId":"5b6a5ab7-...","message":"Request send message successful"}
```

So the API accepts **both** `application/x-www-form-urlencoded` (what
`VipTalkClient` sends today, verified 200 earlier the same day) and
`application/json` with `roomIds` as an array.

This does **not** rescue AD-V8: Alertmanager's `webhook_configs` sends its own
fixed payload schema and cannot template a body, so the bytes it emits are still
not the `{text, roomIds}` shape VipTalk wants. A transform is required either
way.

It does make **AD-V8b cheaper than costed**: the shim is a JSON→JSON field
remap, not a JSON→form-urlencoded re-encode, and it needs no form-encoding
library.

**No change to `VipTalkClient`.** The form-urlencoded path is verified working;
switching it to JSON would be churn for no gain.

## Q2 — Does node-exporter see the filesystem that filled on 2026-06-30?

**Not yet run** — blocked on Phase 5 bringing node-exporter up. Re-run the
`node_filesystem_avail_bytes` / `df -h /` comparison from the plan once it is
deployed to Bot-1.
