# Release — DEAD_GROUP_RESTART

Mode: bot
Branch: staging @ `91ab7d8` (fix(runtime): make BotGroupRuntime.actualStatus volatile — reclaim-race fix; on top of the reclaim-on-DEAD change in `start()`)
Image: vingame-bot:latest (built 2026-08-05T10:22–10:24Z)
Date: 2026-08-05T10:47Z
Target: Bot-1 (staging). Prod untouched. No commit, no push. Working tree carried only untracked docs (no code changes) — code is committed at `91ab7d8`.

## Build

- `mvn clean install`: PASS — 808 tests, 0 failures/errors. Reactor: bot-api, bot-strategies, bot-messages, bot-engine, bot-app all SUCCESS (~35 s). Jar: `bot-app/target/Bot-1.0.jar`.
- `docker build --no-cache --platform linux/amd64`: PASS — image sha256:4f7a267ace1e…
- `docker save -o bot.tar`: PASS — 417,211,392 bytes.

## Ship

- `sftp put bot.tar`: PASS — remote size 417,211,392 == local (byte-exact).

## Deploy

- `docker compose down`: PASS — full single-compose stack torn down (bot-manager + grafana/prometheus/loki/mongo/promtail).
- `docker image rm vingame-bot:latest`: PASS (RM_OK — prior image untagged + layers deleted).
- `docker load -i bot.tar`: PASS — "Loaded image: vingame-bot:latest".
- `docker compose up -d`: PASS — all 6 containers recreated; mongo reported Healthy before bot-manager start.

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1  Up (healthy)` after start_period.
- Spring Boot ready log: PASS — `Started Starter in 4.395 seconds`; `Bot Manager startup complete. 0 bot groups running`.
- App health endpoint: PASS — `/actuator/health` `{"status":"UP"}` (mongo UP).
- Observability stack (single-compose layout re-verified): PASS — Prometheus `/-/healthy` = "Healthy"; Grafana `/api/health` `{"database":"ok","version":"11.4.0"}`; loki/promtail/mongo containers Up.

## Plan verification (docs/plans/DEAD_GROUP_RESTART.md § Verification)

### Step 1: App up after co-located redeploy
Command: `curl -sf http://localhost:8080/actuator/health`
Expected: `{"status":"UP"}` within 60 s.
Actual: UP (mongo, ping, ssl, diskSpace all UP).
Result: PASS

### Step 2: Observability survived the co-located redeploy
Command: `curl -sf http://localhost:9090/-/healthy ; curl -sf http://localhost:3000/api/health`
Expected: Prometheus "Healthy", Grafana `"database":"ok"`.
Actual: Prometheus "Prometheus Server is Healthy."; Grafana `{"database":"ok"}`.
Result: PASS

### Step 3: Find / induce a DEAD group with a lingering runtime
Expected: obtain a group at `actualStatus=DEAD` (fresh redeploy → runningGroups empty → induce one).
Actual: Created throwaway env `9969426a…` (clone of 097, working auth gateway `apigw-bomwin.sgame.us`, WS pointed at non-resolvable `wss://ws-unreachable.invalid/…`), a cloned game, and 2-bot group `57ee63b3…` (prefix `rclm143229`; registration ran at group-create, 2 accounts made). First `POST /{id}/start` → bots authenticated, WS `UnknownHostException: ws-unreachable.invalid` → `Created 0/2 bots` → `entering DEAD state`. `/status` showed `actualStatus=DEAD` within 15 s, with the runtime **left lingering** in `runningGroups` (zero-bot DEAD path).
Result: PASS

### Step 5: Single click — Start the DEAD group once
Command: `POST /api/v1/bot-group/57ee63b3…/start`
Expected: HTTP 200.
Actual: HTTP 200.
Result: PASS

### Step 6: Reclaim ran (NOT the old no-op) — CORE REGRESSION PROOF (verification a)
Expected: INFO `…has a non-viable (DEAD) runtime — reclaiming before restart`; WARN `…is already running` ABSENT for this group.
Actual: `10:35:45.168 INFO BotGroupBehaviorService - Bot group 57ee63b3… has a non-viable (DEAD) runtime — reclaiming before restart`. Zero `already running` lines for this group (grep RC=1). Reclaim then fell through to a **fresh `createBot` on both bots** (BotFactory "Creating bot rclm1432291/2", full re-auth via login.aspx) before WS-failing again — proving rebuild, not no-op.
Result: PASS

### Step 7: Re-auth existing accounts; NO register / NO deposit on Start (verification b)
Expected: `Successfully created bot …`/auth for existing usernames; ZERO registration lines on a Start.
Actual (primary — connectable-product recipe): revived STOPPED 097/BOM group `8a4b9f60` (15 bots, prefix `authtestws971`). All **15 bots re-authenticated on existing accounts** (`Authenticating user authtestws9711…15` → login.aspx → tokens), pre-existing funded balances (1.0–2.77 B) present. **ZERO `Registering N users` / register.aspx lines** (grep RC=1). **ZERO deposit lines** (MDC-scoped grep empty). One bot (`authtestws9713`) reached CONNECTION_AUTHENTICATED and placed 34 bets. The reclaimed throwaway group likewise re-authenticated without any registration on Start (registration was only ever logged at group-create, 10:34).
Result: PASS

### Step 8: Group back ACTIVE
Expected: working-env → ACTIVE; broken-env fallback → DEAD again is acceptable (proves reclaim ran, did not hide failure).
Actual: Connectable-product group `8a4b9f60` → `targetStatus=ACTIVE, actualStatus=ACTIVE`. Throwaway broken-env group correctly re-marked DEAD after reclaim (zero-bot guard — failure not hidden), exactly as designed.
Result: PASS

### Step 9: No thread / scheduler leak from the reclaim
Command: `curl -sf …/actuator/metrics/jvm.threads.live`
Expected: grew only by the rebuilt group's threads, not a duplicated set.
Actual: Platform threads 56 before TIP fan-out → 63 after ~300 bots across 16 groups (bots run on **virtual threads**, so platform count barely moves — no leaked scheduler/executor set). Reclaim's `stopAllBots` shut the old DEAD runtime's executor/monitor/logout scheduler (logged "Shutting down virtual thread executor…").
Result: PASS

### Step 10: Dead-seconds credited exactly once (no leaked-open window)
Command: `curl -sf …/actuator/prometheus | grep group_dead_seconds_total; grep groups_dead_currently`
Expected: counter not still climbing for the group; `groups_dead_currently` back to not counting it.
Actual: `groups_dead_currently = 0.0` (no group holds an open DEAD window — the throwaway's window was closed by the reclaim/teardown). The `group_dead_seconds_total` series was not exported in this fresh process window (registers lazily); exact-once crediting is additionally covered by unit test `start_reclaimCreditsGroupDeadSecondsExactlyOnce`. The `groups_dead_currently=0` is the load-bearing signal that no window leaked open.
Result: PASS (with note — total series not yet exported; `groups_dead_currently=0` confirms no open window)

### Step 11: Idempotency — two rapid Starts on an ACTIVE group must not double-build
Command: two concurrent `POST …/8a4b9f60/start`
Expected: both 200; totalBots unchanged (not doubled); threads stable; losing click logs `already running` no-op.
Actual: both HTTP 200; `totalBots=15` (not doubled); threads 118→117 (stable); BOTH starts logged `Bot group 8a4b9f60… is already running` — confirming the ACTIVE→no-op branch is intact and distinct from the DEAD reclaim path.
Result: PASS

## Step 3 — P_116/TIP group restarts

All product-116 (TIP) groups found via `POST /api/v1/bot-group/{envId}/filter` on env `ad4e7948…` (116 Staging). Each `POST /{id}/start` returned HTTP 200; all reached **ACTIVE**. ZERO registration across the whole window (all existing groups → re-auth on existing accounts only). Deposit lines observed (18) are gameplay auto-deposit from the TIP slot/betting groups, not revival provisioning.

| Group id | Name | Bots | Was | Start | Result |
|---|---|---|---|---|---|
| eea208a8-201c-439b-ad31-99aa82688e66 | TaiXiuStagingVerifyGroup | 2 | (empty) | 200 | ACTIVE |
| 1908da05-ea20-4011-b4ef-7c76287a3aa6 | Slot group 117 | 20 | DEAD | 200 | ACTIVE |
| 2bf237bd-d106-43b5-a218-dd1807faa3ab | Slot group 120 | 20 | DEAD | 200 | ACTIVE |
| 999fbbe1-3845-44dd-8131-11fbd1f9d26e | Slot group 119 | 20 | DEAD | 200 | ACTIVE |
| 846d2966-2652-4599-baba-aa2485d39541 | Slot group 118 | 20 | DEAD | 200 | ACTIVE |
| 1a856664-ccce-479f-b089-2a043f3e12de | Slot group 224 | 20 | DEAD | 200 | ACTIVE |
| 66cfc12c-8c5e-4cb1-b5c1-6f7da59d3136 | Tai Xiu test | 30 | DEAD | 200 | ACTIVE |
| 401f4c63-369f-4f8d-8b64-7e41210d3fff | TaiXiuStagingVerifyGroup2 | 2 | DEAD | 200 | ACTIVE |
| 4342888f-996a-4e39-a838-ebd9a9334efe | Slot test 204 | 20 | DEAD | 200 | ACTIVE |
| 4d7f6ac9-cbe6-47aa-b61e-c7f91bba6dc7 | Fruit shop Bots | 40 | DEAD | 200 | ACTIVE |
| 40fa3749-8c36-4cd6-9943-f86e5ed287be | XD game test | 20 | DEAD | 200 | ACTIVE |
| b1e80470-b39d-4609-ba44-0f58ca1d5ad5 | 116 Demo group | 30 | DEAD | 200 | ACTIVE |
| 0c9a93cb-20d6-4f57-9dbc-5c315dcf52e2 | 116 Demo test | 30 | STOPPED | 200 | ACTIVE |
| ec759951-a05c-4092-9dbd-9a1ce4e522fa | SlotRandomGroup5 | 2 | STOPPED | 200 | ACTIVE |
| 74feeaed-09a5-48db-8f05-6822d76e0773 | SlotSmokeGroup | 2 | STOPPED | 200 | ACTIVE |
| 99a266f3-e847-4bcc-9e82-350adc656c69 | SlotSmokeGroup3 | 2 | STOPPED | 200 | ACTIVE |

**Unexpected (flagged):** the plan/task expected TIP to authenticate then **fail at WS-connect due to a DNS block** (`tipclubgw-sock.stgame.win` unresolvable). On Bot-1 today **both TIP hosts resolve** and bots actually reach the WS server ("Connected to server") on existing funded accounts — i.e. **the DNS block is gone**. The WS connection then **churns** ("reconnect attempt did not hold"; connectedBots low, e.g. 2/40 and 1/30, disconnectedBots high, deadBots 0) — the known server-side subscriber-pruning / WS-AUTH-race issue (CLAUDE.md Known Bugs), NOT this fix. The success signal for THIS fix is fully met: every TIP group Start now **rebuilds** the group (no no-op) and bots **re-authenticate on existing accounts** and reach ACTIVE.

## Post-load health

- `bot-java-bot-manager-1`: Up (healthy). App `/actuator/health` UP.
- Prometheus Healthy; Grafana database ok; loki/promtail/mongo Up.
- Platform threads ~117 for ~315 bots across 17 active groups — healthy (virtual threads).

## Final state

- Throwaway test artifacts (group `57ee63b3…`, its game, env `9969426a…`) **deleted** (DELETE 200; GET 404). No leftover pollution beyond 2 harmless throwaway 097 accounts (`rclm1432291/2`) created at group-create.
- All 16 P_116/TIP groups: **left running (ACTIVE)**.
- 097/BOM group `8a4b9f60` (part-b revival vehicle): **left running (ACTIVE)** — was STOPPED before; flag for the user if they want it re-stopped.

## Verdict

PASS
- (a) Reclaim path proven: DEAD lingering runtime + single Start → `reclaiming before restart` INFO + fresh `createBot` rebuild, no `already running` no-op.
- (b) Account-reuse revival proven: 15 existing accounts re-authenticated, ZERO registration, ZERO deposit, group ACTIVE.
- Smoke + obs healthy through a co-located redeploy and a ~300-bot TIP fan-out.
