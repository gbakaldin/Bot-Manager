# Release — GATEWAY_REQUEST_BUDGET (Phases 1-5) + RIK/114

Mode: **bot**
Target: **Bot-1 (staging)**, host `s009-bot-general-stag-01`, `/home/sgame/bot-java`
Branch: `feature/gateway-request-budget`, **commit `0c62576`** (detached worktree, clean)
Image: `vingame-bot:latest` = `sha256:b5da0f81efaaad634ca4d816a8fcea7e71598033ae47a1ccd7b3c00ef219a846` (linux/amd64, built 2026-10-01T09:51Z)
Rollback tag: **`vingame-bot:rollback-20261001` = `f284ca5390c3`** (the 2026-09-21 image that was running, up 10 days)
Date: 2026-10-01T09:45Z (build start) to 10:28Z (last check)
Budget mode on Bot-1: **`enforce`** (`GATEWAY_BUDGET_MODE=enforce` appended to the host's `secrets.env`; compose untouched)

**Verdict: PASS, with anomalies (see the Anomalies section).** The deploy is clean, the budget runs in
`enforce`, every per-env window stayed far under 900 (max 723, on 114), no edge block or circuit
opened anywhere, and V5c in-repo login passes on all four brands. **Anomaly A1 needs attention
before prod**: on each start, 4-8 bots of the two 100-bot RIK groups fail with the JDK's
`IOException: too many concurrent streams` (HTTP/2). That string appears nowhere in the 14 days
of logs from the previous image.

## Authorisation

The go-ahead came from the pipeline coordinator ("the user approved this deploy"), and the
permission system then cleared each step. Staging only. No host other than Bot-1 was touched.

## Pre-flight

- `git rev-parse HEAD` = `0c62576dfe57e418ba15eb89d424660fd68ace15`. The working tree is dirty
  (Aviator/JS/docs/`deploy.sh`), so **the build came from a clean detached worktree at `0c62576`**
  (`git worktree add --detach`). Its `git status` was empty.
- No concurrent `mvn` was running.
- Docker Desktop was down. I started it, and built with `DOCKER_CONFIG=<scratch>` holding
  `{"auths":{}}` as a precaution. No hang.

## Build

- `mvn clean install` (JDK 21.0.2, worktree): **PASS**, 1 min 59 s. Tests per module: bot-api 148,
  bot-strategies 126, bot-messages 308, bot-engine 671, bot-app 1,451, so **2,704 tests, 0 failures, 0
  errors**. `Bot-1.0.jar` is 61,880,534 B.
- **V3g** (laptop, before shipping, A24.2 command)
  `mvn -pl bot-engine -am test -Dtest=GatewayBudgetEscalationIT -Dsurefire.failIfNoSpecifiedTests=false`:
  **PASS**, 4 tests (`theCapHoldsAgainstTheReceiversOwnCount`, `theOverflowWaitsForExpiry`,
  `defaultStopsAtItsCeilingAndEssentialDoesNot`, `aLoginIsCountedAndParsed`), 0 failures, 36.2 s.
  The IT prints no per-level summary lines, so none are pasted here.
- `docker build --no-cache --platform linux/amd64`: **PASS** (`b5da0f81efaa`)
- `docker save`: **PASS**. `bot.tar` is 396,196,352 B, md5 `06650afe23026d4cd6fc05f7857295f6`.

## Ship

I took remote backups first, suffix `.bak-20261001T0952Z`: `docker-compose.yml`,
`prometheus/alerts.yml`, `alertmanager/alertmanager.yml`, `grafana/.../game-server.json`,
`grafana/.../per-environment.json`, `secrets.env`, `.env`.

| File | Source | Result |
|---|---|---|
| `bot.tar` | scratch | PASS, remote md5 = local |
| `docker-compose.yml` | worktree `0c62576` | PASS (sha256 `feb80e95…`). Additive diff only: `BOT_RECOVERY_ENABLED` and `BOT_GATEWAY_BUDGET_MODE` passthroughs |
| `prometheus/alerts.yml` | worktree | PASS (`bbc6ec11…`). Adds 10 rules incl. `GatewayEdgeBlocked/Observed/Uncleared`, `GatewayBudgetNearCap`, `Registration*` |
| `alertmanager/alertmanager.yml` | worktree | PASS (`a0ba6681…`). Comment-only diff |
| `grafana/provisioning/dashboards/game-server.json`, `per-environment.json` | worktree | PASS |
| `logging/log4j2.properties` | worktree | PASS (already byte-identical, `d2d51b36…`) |
| `deploy.sh` | **working-tree copy** `/Users/gleb/IdeaProjects/Bot/deploy.sh` | PASS (already byte-identical, 1,440 B, merge block intact) |

`secrets.env`: appended a comment line plus `GATEWAY_BUDGET_MODE=enforce`. `BOT_RECOVERY_ENABLED`
is absent from the file, as before, so recovery stays at its default `false`. Nothing else changed.

## Deploy (09:53:41Z to 09:54:04Z)

- `docker tag vingame-bot:latest vingame-bot:rollback-20261001`: PASS (`f284ca5390c3`)
- `docker compose down`: PASS (all 10 containers, no `-v`)
- `docker image rm vingame-bot:latest`: PASS (untagged only; the rollback tag keeps the image)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest` → `b5da0f81efaa`)
- `./deploy.sh`: PASS. Printed `Merging secrets.env into .env`, then `compose up -d` brought up all 10 containers.
- `docker compose restart prometheus` (A32.4, single-file bind mount): PASS. The new rules were
  loaded (see V1d/V5a).

## Smoke test

| Check | Result |
|---|---|
| bot-manager `Up … (healthy)`, `RestartCount=0`, running image `b5da0f81…` | **PASS** |
| `Started Starter in 4.726 seconds` | **PASS** |
| `Bot Manager startup: 9 bot groups queued for daisy-chained start` | **PASS** |
| (non-blocking) `startup complete. 9 bot groups running (9 started by this chain)` at 09:54:16 | present |
| `MessageTypesRegistry initialized: BETTING_MINI 6 products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119]` | **PASS** (boot line as required) |
| Prometheus `/-/healthy`, targets `bot-manager` up and `node` up | PASS |
| Grafana `/api/health` `"database":"ok"` (11.4.0), 5 dashboards provisioned | PASS |
| Loki `/ready`: 503 warm-up on the first poll, then `ready`. New-JVM lines are queryable in Loki | PASS |
| Alertmanager `/-/ready` (in-container) `OK` | PASS |
| evidence-shim, viptalk-shim `(healthy)`; mongo `(healthy)`; promtail, node-exporter up | PASS |

## Plan verification: A32.5 (authoritative), in phase order

Window figures are the app's own `gateway_budget_window_requests` and Prometheus
`max_over_time`. Prometheus was restarted at 09:56, so its history starts there. The 09:54 startup
burst is still captured because the gauge is a 5-min sliding count.

### V0a: app up. **PASS**
`until curl -sf …/actuator/health | grep -q UP` returned UP immediately after `compose up`, i.e. within 20 s.

### V0b: startup lines. **PASS**
`Started Starter` 09:54:10.467; `9 bot groups queued for daisy-chained start` 09:54:10.727;
`startup complete` 09:54:16.026.

### V0c: observability survived. **PASS**
`Prometheus Server is Healthy.` and Grafana `"database":"ok"`.

### V1a: SKIPPED (prod-only per A32.5; replaced by V3a on staging)

### V1b: every call site counts. **PASS (substituted)**
I did not start a fresh 50-bot group. The startup chain provided the same evidence on a larger
scale: env 114 had 224 bots across 6 groups, up within about 6 s.
`gateway_budget_requests_total{env=114}`: ESSENTIAL admitted **672** (= 224 × 3: login + upgrade +
first read), PRIORITIZED **51** (reconnects), DEFAULT 0, every `timeout` series 0. The window was
**723/900**. Every env with traffic has series; DEFAULT is present at 0.

### V1c: rollup carries the window. **PASS**
`env 394301f4… (114 Staging, product 114): groups=5, bots=224, connected=224, dead=0, … gateway=723/900 queued=0/0/0 circuit=closed`
(along with the matching lines for 097, 116, 119 and 119-Club).

### V1d: near-cap rule loaded, not firing. **PASS**
`"name":"GatewayBudgetNearCap"` is present. `/api/v1/alerts | grep -c GatewayBudgetNearCap` returned `0`.

### V1e: SKIPPED (prod-only, A32.5)

### V2a: async start. **PASS**
New 5-bot `existingGroup:true` group `cf541124` on 097 (accounts `bomflowtest1-5`).
`POST /start` returned **200** with `actualStatus:"STARTING", botsUp:0`. Polled
`STARTING 0/5` → `ACTIVE 5/5`.

### V2b: double start is one attempt. **PASS**
Two `/start` calls 20 ms apart returned 200 and 200. `start admitted.*cf541124` count is **1**. The second call logged
`start ignored — a start is already in flight (origin REST, phase BUILDING, …)`.

### V2c: stop during a start. **PASS (second attempt)**
New 100-bot `existingGroup:true` group `4e7ef42e` (097, `bomflowtest1-100`).
- Attempt 1 (stop after 3 s, as written) was **inconclusive**: the 100-bot build finished in 1.3 s,
  before the stop arrived. The stop returned 200 in 0.33 s and the group went STOPPED.
- Attempt 2 (stop after 0.3 s): stop returned **200 in 0.115 s**. `/status` shows `STOPPED/STOPPED`. Logs:
  `stop requested while a start was in flight (… 20 up / 0 failed …) — cancelling it`,
  `70/100 bots were not built — the start was cancelled`,
  `A stop during the start … cancelled the build at 21/100 bots — unwinding`,
  `Start of bot group … was cancelled by a stop — runtime torn down`.
  The plan's grep text (`start of group <GID> cancelled`) does not match the shipped wording,
  but the behaviour is as specified.

### V2d: restart is async. **PASS**
`/restart` on `cf541124` returned **200** with `actualStatus:"STARTING"`, and was `ACTIVE 5/5` 8 s later.

### V2e: 404/400 stay synchronous. **PASS (404) / N/A (400)**
`/start` on an unknown id returned **404**. A group without `gameId` can no longer be *created*:
`POST /` returns `400 {"type":"Bad request","msg":"gameId must not be blank"}` before anything exists.
That is the same 400 shape, now earlier, so the start-time 400 is unreachable through the API.

### V2f: the chain. **PASS**
After the V4e restart, `Auto-starting bot group` lines appear one group at a time, and each one
follows the previous group's `started successfully` line (10 groups, 10:01:06.635 to 10:01:11.324).

### V3a: mode. **PASS**
`Gateway budget registry started (mode=enforce, window=PT5M, hard-cap=900, ceilings default=500 prioritized=750 essential=900, count-ws-upgrades=true)`, one line per JVM start.

### V3b: two ~300-bot groups back to back. **NOT RUN as written (deliberate)**
Staging has no ~300-bot groups. Creating two would have meant about 600 new registrations, and the
step's assertion (ESSENTIAL queue depth > 0) only triggers by pushing one gateway toward its
ceiling. The brief forbids both. The realistic shape that did happen: 5 groups / 224 bots
back to back on 114, twice, 6 min 50 s apart. Window max **723 ≤ 900**. The queue never formed
(`max_over_time(gateway_budget_queue_depth[40m])` = 0 on every env and tier) because demand
stayed under the ceiling. The "≤ 900" assertion holds. The queueing half is unverified on staging.

### V3c: DEFAULT throttled, PRIORITIZED not fatal. **PASS (vacuous)**
`grep -cE "tier throttled|tier throttle cleared"` returned 0, and no tier reached its ceiling.
`increase(gateway_budget_requests_total{outcome=~"timeout|circuit_open"}[40m]) > 0` returned an empty result.

### V3d: registration rejection shape. **N/A**
DEFAULT was never starved, so no 429 could occur. V4a's create returned 200 and nothing returned a 502.

### V3e: stop cancels queued work. **NOT RUN**
This needs a queue, as in V3b. `outcome="cancelled"` stayed 0. Build cancellation itself is covered by V2c.

### V3f: rollup during a start. **PASS (partial)**
Every rollup line reads `queued=0/0/0 circuit=closed`. No queued state was produced (see V3b).

### V4a: create returns immediately, creates nothing upstream. **PASS**
40-bot normal create `895fa6d6` on 097 (prefix `grbrel`) returned **200 in 0.016 s** with
`targetStatus:"REGISTRATION_PENDING", registeredCount:0`.

### V4b: progress visible and monotonic. **PASS**
2-s polls: 0 → 18 → 36 → 40, then `targetStatus:null, registeredCount:40, namedCount:40`.
The serial worker finished all 40 in about 6 s, so the plan's 30-s cadence would have seen only the end state.

### V4c: the worker is paced. **PASS**
`max_over_time(gateway_budget_window_requests[15m])` on 097 was **412**. DEFAULT
`increase(…{tier="DEFAULT",outcome="admitted"}[5m])` peaked at **≈81** (≤ 500). Registration on 097
was 40 `register` + 40 `update-fullname` plus re-rolls.

### V4d: `/start` on a registering group is a clean 400. **PASS (on the 40-bot group)**
Immediately after the V4a create, `POST /start` returned **400**
`{"type":"Bad request","msg":"Bot group GRB release V4 reg 40 is still registering (0/40 accounts). …"}`.
I used the V4a group rather than creating a 200-account group, to stay within the brief.

### V4e: resume across a restart. **PASS**
I PATCHed `botCount` 40→60 (V4f), then ran `docker compose restart bot-manager` about 1 s later. The worker shut
down at 10:01:00.226 with `registeredCount` persisted at 40. After boot:
`Registration worker: 1 bot groups resuming account registration` (one line). Count went 41 → 49 → 60,
then `registration complete, 60/60 accounts (60 named)` at 10:01:17. `registration_accounts_total`
in the new JVM: success **18**, exists **1**. The one index in flight at the restart was re-registered and
answered EXISTED, which is within the 0-or-1 bound.

### V4f: extend by PATCH. **PASS** (to 60, not 45; it doubled as V4e's workload)
`PATCH {"botCount":60}` returned **200** with `targetStatus:"REGISTRATION_PENDING"`, then climbed to 60 as above.

### V4g: failure is terminal and explicit. **N/A**
No failure occurred naturally, so I did not manufacture one.

### V5a: detector present, circuit closed, rules loaded. **PASS**
`gateway_circuit_open{…} 0` for all 6 budgeted envs. `gateway_edge_blocks_total` has **7 endpoint
series per env** (`circuit-probe, deposit, login, register, update-fullname, verifytoken, ws-upgrade`), all `0`.
Rules: `GatewayEdgeBlocked`, `GatewayEdgeBlockObserved`, `GatewayEdgeBlockUncleared` (three
`GatewayEdge*` names), plus `RegistrationNotProgressing`. None of them appear in `/api/v1/alerts`.

### V5b: no synthetic traffic while closed. **PASS**
Idle env 098 (`3cda38f9-…7770`, no running group), measured over 30 min at 10:27Z:
`increase(gateway_budget_requests_total{outcome="admitted"}[30m])` = **0**.
`docker logs … | grep -c "circuit-probe"` = **0**. 098 does show `outcome="counted"` ≈ 28 over 30 min:
those are the DEAD-group recovery WS probe's upgrades (once per 60 s; the probe runs with recovery off
by design), stamped rather than admitted.

### V5c: in-repo login per brand (the regression gate). **PASS on all four brands**

| Brand | Groups | `bot_login_total{outcome="success"}` | `failure` | Connected / playing |
|---|---|---|---|---|
| **097 BOM** | new 5-bot `cf541124` (`bomflowtest1-5`, existing accounts) | **5** | 0 | 5/5 `CONNECTION_AUTHENTICATED`, bets placed, rounds settling (`staked=390000`) |
| **116 TIP** | auto-start of `2bf237bd` Slot group 120 | **20** | 0 | 20/20 connected (15 `STARTED`, 5 `AUTHENTICATING_CONNECTION`, the same shape as before; slot bets are not counted here) |
| **114 RIK** | auto-start of 6 groups | **224** (1st JVM) / **220** (2nd JVM) | 0 / **4** (A1) | 224/224 / 220/224 connected; rounds settling |
| **119 WIN79** | auto-start of `57b99074`, `c93c82a5`, and `c7d19d23` (Club env) | **30** | 0 | 30/30 connected, bets placed |

The 4 RIK "failures" on the second start are not gateway refusals. They are the local HTTP/2
`too many concurrent streams` error (A1), which `bot_login_total` counts as a failure. No brand
showed an upstream auth rejection.

### V5d: block handling, laptop + `StubGatewayMain`. **PASS**
I ran it locally: a throwaway `mongo:8.0` on `127.0.0.1:27018`, the same `Bot-1.0.jar` with
`--bot.gateway.budget.mode=enforce --bot.gateway.budget.block-probe-interval=2m`, and a log config
redirected to scratch. Env `apiGatewayUrl=http://127.0.0.1:18099`. Everything was torn down afterwards.
1. 2-bot `existingGroup` group, `/__stub/block`, `/start`. Exactly **one** ERROR:
   `Cloudflare edge block on login, cf-ray a3c7e4004acc850e-HKG — circuit open: nothing is sent to this gateway except one clearance probe every PT2M`.
   Stub `login.aspx=2` (≤ 2). One WARN `2/2 bots refused by an open gateway circuit`. **No**
   per-bot `Failed to create bot` ERROR. `/status`: `DEAD`, `botsUp:0`,
   `lastError: "Started 0/2 bots — Gateway edge block on environment … (cf-ray a3c7e4004acc850e-HKG) …"`.
2. 20-bot normal create returned `REGISTRATION_PENDING`. `registeredCount:0` held, **no `register.aspx`** reached the
   stub, and there was no `registrationError`.
3. Two intervals: `Cloudflare edge block still in force — clearance probe refused (HTTP 403 …)` at 14:07:58 and
   14:09:58 local. The stub shows only `verifytoken.aspx` increments.
4. `/__stub/unblock`: `Cloudflare edge block cleared — clearance probe answered HTTP 200; circuit closed after PT6M0.03S`.
   Then `registration complete, 20/20 accounts (20 named)` 25 s later.
   **Stub per-minute line: `window=43 max=43 total=46`**, paths `login=2, register=20,
   update-fullname=20, verifytoken=3, websocket_mini=1` (the last is the recovery WS probe for the DEAD 2-bot group).

### V6a / V6b: SKIPPED (Phase 6)

## RIK 114 live groups (brief item 7)

- **Zic Zac `1804a704`**: ACTIVE, 100/100 connected. **95 playing, 5 stuck in
  `AUTHENTICATING_CONNECTION`** (8 after the first start, 5 after the second): their bot thread died in
  `initial session setup failed`, from A1. Rounds settle: 52 sessions ended between 10:01 and 10:28,
  e.g. `confirmed staked: 480000, total win: 618000`. `bot_winnings_total` 1.65e8 → 1.00e9 over 10 min.
- **Coins `90064a8d`**: ACTIVE, **96/100**. 4 bots were never created on the second start (A1). 48
  sessions ended between 10:01 and 10:28, and winnings are rising.
- **Nameless bots**: no account was (re)registered or renamed for either group; both use the existing
  named `rikzz*` / `rikcoins*` accounts. The room-freeze symptom is absent, since rounds ended about every 30 s
  for 27 minutes. I could not check names directly: the login response line does not log `fullname`.
- Probes `1134449f`, `cd77131c`, `50dd0560`: ACTIVE, full, betting.

## 119 Bau Cua STOPPED groups (brief item 8)

`b44f8b3d` (50) and `97314241` (10) stayed **STOPPED/STOPPED** through both JVM starts. They were not
queued by the chain and not recovered (recovery is off).

## Burst safety (brief item 6)

- Max per-env window **723/900** (114, at each startup). Every other env was ≤ 412.
- `gateway_edge_blocks_total` and `gateway_circuit_opened_total` sum to **0** at the end.
- The two full-fleet starts (09:54:10 and 10:01:06) were **6 min 56 s apart**, which exceeds Cloudflare's
  5-min window, so 114 never carried two startup bursts in one window. The budget is in-memory and
  resets on restart; **a second bot-manager restart within 5 min of a startup could put ~1,450 on
  114 in one Cloudflare window**. That is worth stating in the runbook.
- `Gateway host apigw-w79.sgame.us is shared by 2 environments (8ca14218…, d005157f…)` WARN at boot.
  The two 119 envs have separate budgets but one Cloudflare count. That is fine today (≤ 80 + 64), and
  the app flags it itself.

## Anomalies

**A1 — HTTP/2 `too many concurrent streams` at group start (new in this build). Fix before prod.**
On both starts, 100-bot RIK groups lost bots to `java.io.IOException: too many concurrent streams`
from `jdk.internal.net.http.Http2Connection.reserveStream`, thrown in the JDK client:
- 09:54: 8 Zic Zac bots logged in and connected, but the first `verifytoken` balance read failed, giving
  `initial session setup failed` and leaving the bot thread dead (the bots remain "connected" in `AUTHENTICATING_CONNECTION`).
- 10:01: 5 Zic Zac bots (same failure) and 4 Coins bots (`UpstreamLoginException: Login failed for user 'rikcoinsNN': too many concurrent streams`, so `bot_login_total{outcome="failure"}` = 4).

None of these reach the gateway, so the budget is unaffected. Health reports them as connected and not
dead, so nothing recovers them. `grep "too many concurrent streams"` across 14 days of `console-*.log`
from the previous image (`f284ca53`): **0 hits**. Hits appear only from 09:54 today, 72 lines in total.

Hypothesis (unverified): the async/daisy-chained start now fires all 100 bots' first balance reads
at once on the shared per-environment `HttpClient`'s single HTTP/2 connection, while the next group's
logins are already going out on the same host. That exceeds the origin's `SETTINGS_MAX_CONCURRENT_STREAMS`.
Candidate fixes: bound in-flight requests per client (a semaphore around `httpClient.send`), retry
on that specific IOException, or `HttpClient.Version.HTTP_1_1`. This is not mass auth failure (4-8 %
of two groups), so it did not meet the rollback criterion.

**A2 — staging V3b/V3e unverifiable without approaching the cap.** The queueing paths are exercised only
by `GatewayBudgetEscalationIT` (V3g, passed) and unit tests. Staging never formed a queue.

**A3 — plan text drift (cosmetic):** V2c's log text; V4b's 30-s cadence versus a 6-s registration;
V2e's start-time 400 is now a create-time 400.

**A4 — staging counters:** `bot_bets_placed_total{Coins}` = 2,112 against `/health` `totalBetsPlaced`
11,040. This is the known `project_staging_reconnect_hotloop` counter gap and is not caused by this release.

## Test artefacts left on staging (097 env; all STOPPED or idle; delete at will)

- `cf541124-ab1a-4926-a190-1ae43ad6510a` "GRB release V5c 097": 5 bots, existing `bomflowtest1-5`, **STOPPED** at the end.
- `4e7ef42e-21b7-4f48-8ed7-6c61a04ae7fd` "GRB release V2c stop-during-start": 100 bots, existing `bomflowtest1-100`, STOPPED.
- `895fa6d6-a8f3-4061-b93f-b047b25885c3` "GRB release V4 reg 40": **60 new accounts `grbrel1-60`** registered and named on 097, never started.

## Rollback

Not needed. If required:
```bash
ssh Bot-1 'cd /home/sgame/bot-java && docker compose down && docker tag vingame-bot:rollback-20261001 vingame-bot:latest && ./deploy.sh'
```
Optionally restore the `.bak-20261001T0952Z` configs and drop `GATEWAY_BUDGET_MODE` from
`secrets.env`. The old jar ignores the new compose env vars, and nothing new is persisted that an older
jar cannot read (A1: `STARTING` is never persisted).

## Verdict

**PASS**: deploy, smoke and V5c gate all pass. Plan verification: of 33 V-steps, **24 PASS**
(V2c on its second attempt, V2e on its 404 half, V1b substituted, V3f partial), **3 N/A or vacuous**
(V3c, V3d, V4g), **2 not run by design** (V3b, V3e) and **4 skipped per A32.5** (V1a, V1e, V6a, V6b).
**0 FAIL.** Anomaly A1 is the open item to fix before prod.
