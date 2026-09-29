# The gwms register envelope for an existing username — captured

Answers **Open Item 13**: async registration (Phase 4) is resumable only if re-registering
index `k` can be told apart from a real failure, and the envelope was undocumented.

Captured live against **P_097 / BOM staging** (`apigw-bomwin.sgame.us`,
`/gwms/v1/bot/register.aspx`, `app_id=bc114097`) on **2026-09-29**, four requests total.
Other brands may vary in wording; the shape is expected to hold.

## Fresh username — success

```json
{"status":"OK","code":200,
 "data":[{"main_balance":0,"uid":"8101409700000081425.0","type_id":3,
          "session_id":"<32 hex>","p":false,
          "token":"29-<32 hex>","token2":"<JWT>",
          "username":"envprobe0929a","level":"LEVEL0","is_club":false,
          "force_up":false,"last_change_password":"2026-09-29T07:57:35.464Z"}],
 "message":"Register successful"}
```
HTTP **200**.

## Same username again — the resumability case

```json
{"status":"EXISTED","code":409,"message":"Tài khoản đã tồn tại"}
```
HTTP **200**.

## What Phase 4 must take from this

1. **The discriminator is the body, never the HTTP status.** Both answers are HTTP 200, as
   the plan warned. Classify on `status == "EXISTED"` (`code` 409 agrees, but `status` is
   what every other gwms path in this codebase already switches on).
2. **`EXISTED` is not unique to registration.** `/gwms/v1/bot/update-fullname.aspx` returns
   the same `status` for a taken *display name* — that is what
   `ApiGatewayClient.isDisplayNameTaken` accepts (alongside `INVALID`). The worker must scope
   its interpretation to the endpoint it called: `EXISTED` from register means "this index is
   already done", `EXISTED` from update-fullname means "re-roll the display name".
3. **A re-register returns no tokens** — no `session_id`, no `token`, no `token2`. This is the
   part the plan did not anticipate. `setDisplayName` needs a session token, and on a fresh
   registration it comes from the register response (`registerSingleUserWithDisplayName`
   passes `result.authToken()` straight through). So for an index that was registered but
   whose display name never landed, the worker **cannot** recover a token from the re-register
   and has to **log in** first: that resumed index costs `register + login + update-fullname`
   = 3 DEFAULT requests, not 2. Budget the worker's resume path accordingly, and prefer
   persisting "registered" and "named" as distinct progress so the common case does not pay
   for it.
4. **Fail closed on anything else.** Any `status` other than `OK` or `EXISTED` is a failure
   that costs an attempt and surfaces to the operator — do not infer success from a 200.

A real account, `envprobe0929a`, now exists on 097 staging as a side effect. Harmless; reuse
it as the fixture for this check rather than creating another.
