# Release — BOTGROUP_GAME_MANAGEMENT

Mode: bot
Branch: feat/botgroup-game-management
Merge commit: 2d1bb9c ("Merge branch 'main' into feat/botgroup-game-management")
Image: vingame-bot:latest (built 2026-07-06T13:49 +04:00, sha256:28ab95f564d6)
Date: 2026-07-06T14:03 +04:00
Target: Bot-1 (/home/sgame/bot-java)

Note: the branch carries BOTH BOTGROUP_GAME_MANAGEMENT and the previously-released
STRATEGY_DECISION_AGGREGATION logging work (main merged in so staging does not
regress the aggregation). The pre-build scratch file
`src/main/java/com/vingame/bot/T.java` was confirmed absent (`rm -f`'d) before the
Docker build.

## Build

- `mvn clean install`: PASS (26s) — Tests run: 1236, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS; clean tree.
- `docker build --no-cache --platform linux/amd64`: PASS (13s) — the Dockerfile COPYs the pre-built `target/Bot-1.0.jar`, no in-image mvn compile. Image sha256:28ab95f564d6.
- `docker save -o bot.tar`: PASS (392,918,016 bytes).

## Ship

- `sftp put bot.tar`: PASS — remote size 392,918,016 bytes matches local exactly.
- `sftp put 001_game_env_scope.js` / `002_botgroup_timestamps.js`: PASS (migration scripts staged in /home/sgame/bot-java).

## Deploy

Single remote ssh block (exit 0):
- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image untagged + 12 layers deleted).
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest).
- `docker compose up -d`: PASS — all 6 containers of the single Compose project recreated.

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1  Up (healthy)`.
- Spring Boot ready log: PASS — `Started Starter in 9.627 seconds (process running for 10.68)`.
- Auto-start log: PASS — `Bot Manager startup complete. 7 bot groups running`.

### Observability stack re-verify (single Compose project — down/up cycles it too)

All 6 containers came back Up:

```
bot-java-grafana-1       Up (healthy target)   grafana /api/health   = 200
bot-java-prometheus-1    Up                    prometheus /-/healthy = 200
bot-java-loki-1          Up                    loki /ready = ready (200), /metrics = 200
bot-java-promtail-1      Up
bot-java-bot-manager-1   Up (healthy)
bot-java-mongo-1         Up (healthy)
```

- Grafana: PASS (200). Prometheus: PASS (200). Loki: PASS — `/ready` returned a
  single transient `503` at first query (momentary WAL catch-up), then `ready` on
  3/3 immediate retries; `/metrics` 200; actively flushing/ingesting streams.
- Operational note (NOT a regression, NOT caused by this deploy): Loki logs show
  recurring `Ingestion rate limit exceeded for user fake (limit: 4194304 bytes/sec)`
  — promtail is replaying the production DEBUG-level log backlog faster than Loki's
  4 MB/s per-tenant cap. Consistent with the known high-log-volume / Loki-retention
  ops item; Loki itself is up and ingesting.

### STRATEGY_DECISION_AGGREGATION logging non-regression (light smoke)

PASS — the 5 s `SessionAggregationService` aggregate flush still carries the
option/bet histograms at DEBUG. Live sample:

```
DEBUG SessionAggregationService [.../BETTING_MINI] - UpdateBet #2 | ... | options: [0]x33 [1]x36 [2]x25 [3]x42 [4]x37 [5]x27 | amount min/avg/max: 5000/247350/500000
DEBUG SessionAggregationService [.../TAI_XIU]      - UpdateBet #3 | ... | options: [1]x10 | amount min/avg/max: 10000/280000/500000
DEBUG SessionAggregationService [.../SLOT]         - SlotWindow ... | bets: [500]x20 | amount min/avg/max: 12500/12500/12500
```

## Data migrations (Releaser-run, DB `botmanager`, container `bot-java-mongo-1`)

### 001_game_env_scope.js
Built the (brand|product)→env map (5 entries, all unambiguous):
`G2|P_097 → ...768`, `G4|P_118 → ...769`, `G2|P_098 → ...770`, `G3|P_116 → ad4e7948…`, `G3|P_114 → 394301f4…`.

| Count | Before | After |
|---|---|---|
| games total | 11 | 11 |
| games missing environmentId | 11 | 0 |
| games missing createdAt | 11 | 0 |

Script report: `11 game(s) processed, 0 with unmapped (brand|product)`. Idempotent.

### 002_botgroup_timestamps.js

| Count | Before | After |
|---|---|---|
| botGroups total | 24 | 24 |
| botGroups missing createdAt | 17 | 0 |
| botGroups missing updatedAt | 17 | 0 |

Script report: `24 bot group(s) backfilled`. Idempotent.

### Cross-check
- group→game env MISMATCH count = 0 (every bot group's `gameId` resolves to a game
  whose `environmentId` equals the group's `environmentId`).

## Plan verification

Ran against staging via `ssh Bot-1 'curl … http://localhost:8080…'` (actuator/API is
internal-only), piping bodies to local `jq` (Bot-1 has no `jq`). Non-TIP env used for
all live checks: `394301f4-6daf-4c55-a073-502a81c00731` (114 Staging, G3/P_114) which
hosts the running group `75899bb9` (RIK114 TaiXiu Jackpot 100, 100 bots betting).
TIP/P_116 (`ad4e7948…`) deliberately avoided per the known WS DNS block.

### Step 1: App up (universal smoke)
Command: `curl -s -o /dev/null -w '%{http_code}' /actuator/health`
Expected: 200
Actual: 200 (`{"status":"UP", mongo UP, diskSpace UP, ...}`)
Result: PASS

### Step 2: Game env-scoped list
Command: `curl /api/v1/game/G3/P_114/${ENV}` + `jq -e 'all(.[]; .environmentId==null or .environmentId=="${ENV}")'`
Expected: 200, JSON array, every element env == ${ENV} (or null)
Actual: 200; `[{"name":"Tai Xiu Jackpot","environmentId":"394301f4…"}]`; jq exit 0
Result: PASS

### Step 3: Game env-scoped filter
Command: `curl -X POST /api/v1/game/G3/P_114/${ENV}/filter -d '{}'`
Expected: 200
Actual: 200
Result: PASS

### Step 4: Bot-group env-in-path filter (empty body → env's groups)
Command: `curl -X POST /api/v1/bot-group/${ENV}/filter -d '{}'` + `jq -e 'all(.[]; .environmentId=="${ENV}")'`
Expected: exit 0
Actual: 4 groups returned, all env == ${ENV}; jq exit 0
Result: PASS

### Step 5: Old unscoped list is gone (AD-6)
Command: `curl -s -o /dev/null -w '%{http_code}' /api/v1/bot-group/`
Expected: 404 or 405, not 200
Actual: 405
Result: PASS

### Step 6: Statistics populated for a running group
Command: `curl /api/v1/bot-group/${GROUP}/health | jq '.stats'` (GROUP=75899bb9 running; STOPPED=c45b3b16)
Expected: running → non-null stats, activeBots>=0, activeTimeSeconds>0; stopped → stats block present with null fields
Actual: running → `{roundsSinceRestart:3, activeTimeSeconds:227, activeBots:100, averageBalance:1460107353, averageWinning:241758}`; stopped → all-null block, `.stats.activeBots==null` jq exit 0
Result: PASS

### Step 7: Bot-group sorting
Command: `filter -d '{"sortBy":"name","sortDir":"asc"}'` + `jq -e '[.[].name]==([.[].name]|sort)'`; unknown key `{"sortBy":"nonsense"}`
Expected: names ascending (exit 0); unknown key → 400
Actual: names ascending, jq exit 0; unknown key → 400
Result: PASS

### Step 8: Game sorting
Command: `POST /api/v1/game/G2/P_097/${097ENV}/filter -d '{"sortBy":"name","sortDir":"desc"}'` + `jq -e '[.[].name]==([.[].name]|sort|reverse)'`
Note: used the 097 env (G2/P_097, 2 games) so the sort is non-trivial.
Expected: names descending (exit 0)
Actual: `["Xoc Dia mini","TaiXiu Seven"]`; jq exit 0
Result: PASS

### Step 9: Sort-key lookups
Command: `curl /api/v1/bot-group/sort-keys` and `/api/v1/game/sort-keys`
Expected: bot-group list contains BALANCE and AVG_WINNING; game list contains BOT_GROUP_COUNT
Actual: bot-group = [STATUS,BOT_COUNT,CREATED_TIME,NAME,BET_AMOUNT,BALANCE,ACTIVE_BOTS,UPDATED_TIME,GAME_TYPE,AVG_WINNING,ACTIVE_TIME,MAX_PER_ROUND] (jq exit 0); game = [CREATED_TIME,BOT_GROUP_COUNT,BOT_COUNT,GAME_TYPE,NAME,ACTIVE_GROUP_COUNT,ACTIVE_BOT_COUNT] (jq exit 0)
Result: PASS

### Step 10: Metrics unchanged (regression guard)
Command: `curl -s -o /dev/null -w '%{http_code}' /api/v1/metrics/game/${GAME}/summary` (GAME=29d419f1)
Expected: 200
Actual: 200 — `{"scope":"GAME","scopeName":"Tai Xiu Jackpot","metrics":{bot_groups:1, total_bots:100, rtp:0.28, ...}}`
Result: PASS

### Step 11: Cascade delete (throwaway env/game/group)
Created disposable resources (also exercised the AD-7 gameId∈env validation on the group create, which passed):
- T_ENV = 71ab698b-e524-4ddd-84b9-cdaa315f8307
- T_GAME = 2026a2cd-644d-479e-abd9-adc391560bbe (created with environmentId + createdAt/updatedAt stamped)
- T_GROUP = bf886004-2a99-4107-847d-b93fbe2f0a8c (existingGroup=true, never started)

Command: `DELETE /api/v1/environment/${T_ENV}`, then verify no orphans.
Expected: DELETE 200; group-filter on env → 404 or empty array; deleted game GET → 404; cascade stop/logout logged for the group.
Actual:
- DELETE → 200
- Mongo post-delete: env=0, game=0, group=0 (all cascaded)
- `POST /bot-group/${T_ENV}/filter` → 200 with `[]` (empty array — plan accepts 404 or empty)
- `GET /api/v1/game/${T_GAME}` → 404
- Log: `BotGroupBehaviorService - Bot group bf886004… is not running; nothing to stop/logout before delete` — confirms the cascade invoked `stopAndLogout` on the group and correctly tolerated the not-running case (the group was created via existingGroup and never started, so the periodic-logout-style running branch did not fire; the stop/logout code path executed as designed).
Result: PASS

### Step 12: Migration verification (post-script)
Command: `countDocuments({environmentId:{$exists:false}})`, `countDocuments({createdAt:{$exists:false}})`, group→game env mismatch scan.
Expected: games missing environmentId = 0; games missing createdAt = 0; no MISMATCH.
Actual: games missing environmentId = 0; games missing createdAt = 0; botGroups missing createdAt/updatedAt = 0; group→game env MISMATCH count = 0.
Result: PASS

## Verdict

PASS — 12 of 12 plan verification steps passed. Build, ship, deploy, both data
migrations, universal smoke, observability re-verify, and the
STRATEGY_DECISION_AGGREGATION logging non-regression all passed. No orphan data;
no regressions. One non-blocking operational note: Loki is rate-limiting the
production DEBUG log backlog (pre-existing, unrelated to this deploy).
