# Release — OBSERVABILITY

Mode: bot
Branch: main
HEAD commit: de833c627f4dba47a31a1c849170f0efe1c40a7f
Working tree: dirty at deploy time (pre-approved by user; ~20 uncommitted modified files plus untracked deploy artefacts)
Image: vingame-bot:latest (built 2026-06-03 ~13:33 +04, sha256:ab44f1fa31a1392d793bdef4f6d552d7ad40d91e3cf280d659cc54bce82c9924)
Date: 2026-06-03T13:38+04:00

## Build

- `mvn clean install`: PASS (Tests + repackage, BUILD SUCCESS)
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`: PASS (Docker Desktop daemon had to be started manually before build)
- `docker save -o bot.tar vingame-bot:latest`: PASS (389,757,440 bytes)

Jar produced: `/Users/gleb/IdeaProjects/Bot/target/Bot-1.0.jar` (58 MB, repackaged Spring Boot)

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS (~54 s)
- mode=infra steps: skipped (not requested)

## Deploy

- `docker compose down`: PASS (network and all containers removed)
- `docker image rm vingame-bot:latest`: PASS (previous image and all dangling layers deleted)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: PASS (mongo healthy → bot-manager started)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up 33 seconds (healthy)`
- Spring Boot ready log: PASS — `09:35:08.780 ... Started Starter in 3.128 seconds (process running for 3.942)`
- Auto-start log: PASS — `09:35:08.104 ... Bot Manager starting up - checking for bot groups to auto-start` followed by `09:35:08.194 ... Bot Manager startup complete. 0 bot groups running`

## Loki verification (user-requested)

LogQL query (labels API):
```
GET http://localhost:3100/loki/api/v1/labels
```
Result: `["botGroupId","environmentId","filename","gameType","job","level","service_name"]` — pipeline labels match plan expectations.

LogQL query (range query for fresh logs):
```
{job="bot-manager"} |= "Started Starter"   start=now-10m
```
Result: 1 hit, timestamp `2026-06-03T09:35:08.780+0000`, message `Started Starter in 3.128 seconds (process running for 3.942)` — confirms the freshly-deployed container's startup banner reached Loki.

LogQL query (general activity in last 5 min):
```
{job="bot-manager"}   start=now-5m
```
Sample lines (3 representative entries):
- `09:38:33.250 health-monitor INFO  BotGroupBehaviorService — Group 9b54e101-2640-40db-a367-36e088d23cd8 health — playing: 10, reconnecting: 0, dead: 0/10`
- `09:38:32.869 netty-ws-message-processor-ws-test_bcmini_007 INFO  OutputPrinter — User test_bcmini_007: [RECEIVED] [5,{"bs":[…],"cmd":9002}]`
- `09:38:32.864 netty-ws-message-processor-ws-test_bcmini_005 INFO  OutputPrinter — User test_bcmini_005: [RECEIVED] [5,{"bs":[…],"cmd":9002}]`

MDC labels confirmed populated: `botGroupId=9b54e101-2640-40db-a367-36e088d23cd8`, `environmentId=3cda38f9-2c3d-465f-a52a-18ce83207770`.

Loki verification: PASS — logs from the freshly-deployed container are being ingested with correct labels.

## Plan verification

For each step in `docs/plans/OBSERVABILITY.md § Verification`:

### Step 1: Prometheus endpoint exists
Command: `curl -fsS http://Bot-1:8080/actuator/prometheus | head -5`
Expected: Prometheus text-format output (lines beginning `# HELP` / `# TYPE`).
Actual: `curl: (22) The requested URL returned error: 404`. Available actuator endpoints (from `GET /actuator`): `health, info, loggers, metrics` — **no `prometheus` endpoint**.
Result: **FAIL** — Phase 1 of the plan (Micrometer Prometheus registry dependency, `management.endpoints.web.exposure.include=…,prometheus`) is not in this build.

### Step 2: Key bot-side metrics registered
Command: `curl -fsS http://Bot-1:8080/actuator/prometheus | grep -E '^(bot_groups_running|bots_managed|bot_messages_total|bot_failures_total|bot_reconnects_total|bot_bets_placed_total|bot_winnings_total|bot_auto_deposits_total|bot_watchdog_expired_total) '`
Expected: each named metric appears at least once.
Actual: empty (endpoint 404; nothing to grep).
Result: **FAIL** — Phase 2 instrumentation has not been deployed.

### Step 3: Start a bot group and watch counters move
Sub-step 3a — `curl -X POST .../9b54e101-2640-40db-a367-36e088d23cd8/start`: HTTP 200. PASS.
Sub-step 3b — wait 60 s: done.
Sub-step 3c — `curl … | grep …` for `bot_groups_running >=1`, `bots_managed > 0`, `bot_messages_total > 0`: endpoint still 404; no counter output. FAIL.
Indirect confirmation that the group did start successfully (out of band, via existing health DTO): `GET /api/v1/bot-group/9b54e101-…/health` returns `status: ACTIVE`, `playingStatus: IDLE`, `totalBots: 10`, `connectedBots: 10`, `deadBots: 0`, and every bot shows `totalBetsPlaced=39, totalBetAmount` in the 5–7M range — runtime is healthy, only the metrics export path is missing.
Result: **FAIL** (sub-step 3c) — the underlying group runs but is not observable through Prometheus.

### Step 4: Server-side health log shows the group active
Command: `ssh Bot-1 'docker logs --tail 500 bot-manager 2>&1 | grep -E "Group .* health"' | tail -3`
Expected: at least one line, `dead: 0/<N>`.
Actual: `09:37:33.250 [health-monitor-9b54e101-…] INFO  BotGroupBehaviorService — Group 9b54e101-…-36e088d23cd8 health — playing: 10, reconnecting: 0, dead: 0/10`.
Result: **PASS**.

### Step 5: Grafana dashboards render
Commands:
- `curl http://Bot-1:3000/d/bots` → HTTP 302 (redirect to login, dashboard slug not actually resolved to a real dashboard).
- `curl http://Bot-1:3000/d/game-server` → HTTP 302 (same).
- `docker exec bot-java-grafana-1 ls /etc/grafana/provisioning/datasources/` → "No such file or directory" (the mount path inside the container is `/etc/grafana/provisioning` but datasources/dashboards subdirs are empty).
- `ls /home/sgame/bot-java/grafana/provisioning` (host side) → empty.
Expected: every panel populates within 30 s.
Actual: no datasource provisioning, no dashboard provisioning, no Prometheus datasource exists (Phase 1 not in place). Grafana is up but configured with only the default Loki datasource (from prior work) — no `bots` or `game-server` dashboards are provisioned.
Result: **FAIL** — Phase 5 dashboard provisioning has not been deployed.

### Step 6: Winnings path actually fires
Command: `curl … | grep '^bot_winnings_total '` (and fallback inspection of `lastRoundWinnings` in the health DTO).
Expected: `bot_winnings_total > 0` or `lastRoundWinnings` populated in the health DTO after a full round.
Actual: Prometheus endpoint absent (FAIL). Fallback check on `GET /api/v1/bot-group/9b54e101-…/health`: every bot shows `lastRoundWinnings: 0` despite 39 bets placed each over ~1 minute, with `totalBetAmount` between 5,185,000 and 7,235,000 per bot. The field is still declared but never written.
Result: **FAIL** — Phase 3 winnings refetch path has not been deployed.

## Cleanup

- `curl -X POST .../9b54e101-…/stop` after verification: HTTP 200, group stopped.

## Verdict

**FAIL** (deploy succeeded, plan verification did not).

Smoke test: PASS
Plan verification: **1 of 6 steps passed** (only Step 4 — server-side health log).

Plan steps 1, 2, 3 (sub-step c), 5, 6 all fail because Phases 1, 2, 3, and 5 of the OBSERVABILITY plan have not been implemented in the deployed build. The image deployed cleanly, container is healthy, runtime is functional, bots can authenticate and place bets, the existing Loki pipeline still works, but the Prometheus / Micrometer / winnings refetch / Grafana provisioning work from the plan is not present in the source tree at HEAD `de833c6`.

This is a release-blocker only for the OBSERVABILITY deliverable — the underlying bot manager is healthy and could continue serving its existing purpose.

## Logs

### `GET /actuator` (top-level)
```
{"_links":{"self":{"href":"http://localhost:8080/actuator","templated":false},
 "health":{"href":"http://localhost:8080/actuator/health","templated":false},
 "health-path":{"href":"http://localhost:8080/actuator/health/{*path}","templated":true},
 "info":{"href":"http://localhost:8080/actuator/info","templated":false},
 "loggers":{"href":"http://localhost:8080/actuator/loggers","templated":false},
 "loggers-name":{"href":"http://localhost:8080/actuator/loggers/{name}","templated":true},
 "metrics-requiredMetricName":{"href":"http://localhost:8080/actuator/metrics/{requiredMetricName}","templated":true},
 "metrics":{"href":"http://localhost:8080/actuator/metrics","templated":false}}}
```
No `prometheus` link.

### Bot group health excerpt (proves group ran but no winnings captured)
```
{"groupId":"9b54e101-...","status":"ACTIVE","playingStatus":"IDLE","totalBots":10,
 "connectedBots":10,"reconnectingBots":0,"deadBots":0,"disconnectedBots":0,
 "bots":[
   {"username":"test_bcmini_001","totalBetsPlaced":39,"totalBetAmount":6645000,"lastRoundWinnings":0},
   {"username":"test_bcmini_002","totalBetsPlaced":39,"totalBetAmount":5550000,"lastRoundWinnings":0},
   ...
   {"username":"test_bcmini_0010","totalBetsPlaced":39,"totalBetAmount":5200000,"lastRoundWinnings":0}
 ]}
```

### Grafana provisioning directories
- `/home/sgame/bot-java/grafana/provisioning` on host: empty (no datasources/, no dashboards/).
- Inside container the provisioning mount also resolves to empty subdirectories.
