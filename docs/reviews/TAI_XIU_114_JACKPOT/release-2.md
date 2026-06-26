# Release-2 — TAI_XIU_114_JACKPOT (P_114 / RIK Tai Xiu Jackpot variant)

Mode: bot
Branch: main
Commit: 687759f979f1a308bb9401dbe58da2cf408b66d0
Image: vingame-bot:latest (built 2026-06-26T12:50:07Z, sha256:d1ff2b949aea)
Date: 2026-06-26T13:01:06Z
Target: Bot-1 (host port 8080 -> container 8085)

> Note: a prior release-2 attempt (against older commit e50553f) failed at the
> build stage on a P_114 late-bet-cutoff test. This run is against the fixed
> main @ 687759f, where the full suite is green (942/942). This file documents
> the 687759f run and supersedes the earlier content.

## Verdict

**FAIL — gate not passed.** Redeploy succeeded and is healthy, but the 2-bot
P_114 smoke group could not be created: the RIK upstream rejects Bot-1's egress
IP on the user-registration endpoint (`IP denied, you cant used this function`,
401). Per the gate, the 100-bot group was NOT created. No bots ever reached the
login/auth step, so the new `RikLoginRequest` profile could not be exercised
end-to-end. This is an external IP-allowlist blocker on RIK's side, not a
bot-code defect.

---

## Build

- `mvn clean install`: PASS (~22s, 942 tests, 0 failures / 0 errors / 0 skipped) — Java 21.0.2
- `docker build --no-cache --platform linux/amd64`: PASS
- `docker save -o bot.tar`: PASS (392,817,152 bytes)

## Ship

- `sftp put bot.tar` -> Bot-1:/home/sgame/bot-java: PASS (remote size 392,817,152 bytes, exact match)

## Guards

- DNS override present in compose: PASS — `docker-compose.yml` line 26 `dns: ["1.1.1.1","8.8.8.8"]`
- DNS intact on running container: PASS — `HostConfig.Dns = ["1.1.1.1","8.8.8.8"]`
- Compose untouched (no overwrite): PASS
- Port mapping confirmed: `8080:8085`

## Deploy (targeted, NOT full down)

- `docker load -i bot.tar`: PASS (old image renamed away)
- `HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d bot-manager`: PASS
  (only bot-manager recreated; mongo stayed running; grafana/prometheus/promtail/loki untouched)

## Smoke test

- `docker ps` bot-manager healthy: PASS — `Up About a minute (healthy)` after start_period
- Spring Boot ready log: PASS — `Started Starter in 7.833 seconds`
- Auto-start ran: PASS — all pre-existing groups auto-started (116 Demo group,
  XD game test, Fruit shop Bots, Slot test 204, TaiXiuStagingVerifyGroup2, Tai Xiu test)
- Actuator health: PASS — HTTP 200 on `/actuator/health`
- Existing groups unaffected: PASS — 20 groups present, including 116 Tai Xiu
  groups; observed live VerifyToken 200s and active betting from existing groups
- Observability stack still up: PASS — grafana / prometheus / promtail / loki / mongo all Up

## Discovery (env + game resolution)

- BotGroup list endpoint: `GET /api/v1/bot-group/` (trailing slash required; no-slash 404s)
- "114 Staging" env: `394301f4-6daf-4c55-a073-502a81c00731` (product RIK, code 114, appId `rik.vip`)
- "Tai Xiu Jackpot" game: `29d419f1-9c96-4e74-aec1-41c7fe5849c3` (plugin `taixiuJackpotPlugin`, type TAI_XIU)
- Config source (healthy 116 TAI_XIU group "Tai Xiu test", `66cfc12c-...`):
  minBet 5000, maxBet 10000000, betIncrement 5000, maxTotalBetPerRound 50000000,
  minBetsPerRound 1, maxBetsPerRound 22, strategyMix MARTINGALE_CLASSIC_CAUTIOUS w=1.0,
  password 123123Aa, chatEnabled true, autoDepositEnabled false

## Step 2 — 2-bot 114 smoke group: FAIL

Payload posted to `POST /api/v1/bot-group/`: env 394301f4 (114), game 29d419f1
(Tai Xiu Jackpot), botCount 2, namePrefix `riksmk`, config copied from the
healthy 116 Tai Xiu group.

Result: **HTTP 502 "Game server error"** — group NOT created. Upstream rejected
user registration for both bots before any login / WebSocket auth.

Exact upstream register call (from logs):
```
[Register] POST https://api-gwrik.sgame.us/gwms/v1/bot/register.aspx
  | X-TOKEN: 58bc2820612d23c34fe43d0b2c6f7223
  | body: {"register_ip":"16.162.36.69","app_id":"rik.vip","username":"riksmk1",
           "password":"123123Aa","ip":"16.162.36.69","os":"OS X","device":"Computer",
           "browser":"WEB","source":"rik.vip"}
```
Exact upstream response body (HTTP 200 envelope, 401 in body):
```
{"status":"UNAUTHORIZED","code":401,"message":"IP denied, you cant used this function"}
```
Both `riksmk1` and `riksmk2` returned identical errors ->
`UpstreamRegistrationException` -> 502 to caller. Group creation rolled back
atomically (group list remained at 20; 0 RIK/114 groups persisted).

### Verification checklist (Step 2)

| Check | Result |
|---|---|
| Both bots AUTHENTICATE (RikLoginRequest / rik.vip) | NOT REACHED — blocked at registration |
| 0 login/auth non-200s | N/A — never got to login |
| 0 register non-200s | FAIL — both register calls returned 401 "IP denied" |
| Reach CONNECTION_AUTHENTICATED | NOT REACHED |
| 0 UnknownHostException | PASS — DNS resolved api-gwrik.sgame.us; no UnknownHost (HTTP round-trip completed) |
| taixiuJackpotPlugin subscribe 1105 / bet 1100 | NOT REACHED |
| start 1102 / end 1104 received | NOT REACHED |
| bets carry a:false | NOT REACHED |
| refund accounting | NOT REACHED |

Group id: none (creation failed).

## Step 3 — GATE: FAIL -> STOP

2-bot smoke did not authenticate and did not bet (blocked upstream at
registration). Per the brief, the 100-bot group was NOT created.

## Step 4 — 100-bot 114 group: NOT ATTEMPTED (gate failed)

## Analysis

- The shipped `RikLoginRequest` / `rik.vip` profile is correctly applied: the
  registration request used `app_id: rik.vip` and `source: rik.vip` (RIK
  brand knowledge), not the generic Default profile. DNS, X-TOKEN, and request
  shape all look correct; the request reached RIK and got a clean 200-envelope
  response. So the redeploy itself delivered the RIK profile as intended.
- The blocker is a **server-side IP allowlist** on RIK's `bot/register.aspx`:
  Bot-1's egress IP `16.162.36.69` is not authorized for the registration
  function. This is upstream/infra, outside this image's control.
- The brief's noted possible culprit (RIK recaptcha `r_token`) is NOT the cause
  here — the failure is on the **register** call, before login, and the message
  is explicitly an IP-denial, not a captcha/token error. `r_token` would only
  become relevant on the login call, which we never reached.

## Recommended next steps (for the user / infra owner)

1. Get Bot-1's egress IP `16.162.36.69` allowlisted on RIK's
   `api-gwrik.sgame.us` `bot/register.aspx` endpoint, OR
2. Pre-create the RIK users out-of-band and create the bot group with
   `existingGroup=true` to skip registration and go straight to login (this
   would then exercise `RikLoginRequest`). Note: login may subsequently surface
   the `r_token`/recaptcha issue flagged in the brief — to be verified once
   registration is unblocked.

## Final state

- bot-manager: Up (healthy), running commit 687759f with the RIK login profile
- Pre-existing groups: 20, all intact (incl. 116 Tai Xiu), actively betting
- Observability: grafana / prometheus / promtail / loki / mongo all up
- New 114 groups created: 0 (smoke failed at gate; 100-bot not attempted)
- Nothing deleted; compose not modified; no git push/amend
