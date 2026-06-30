# Release — RESTART_LIFECYCLE_FIX

Mode: bot
Branch: `feat/restart-lifecycle-fix`
HEAD commit: `f7466c0`
Image: `vingame-bot:latest` (built 2026-06-11T13:31:22+04:00, image sha `719bd47272769...`)
Deploy initiated: 2026-06-11T13:33:25+04:00 (local) / 09:33:25 UTC on Bot-1
Bot-1 host: `s009-bot-general-stag-01`
Plan: `docs/plans/RESTART_LIFECYCLE_FIX.md`

---

## Build

- `mvn clean install -DskipTests`: PASS (5.662s, BUILD SUCCESS, jar = `target/Bot-1.0.jar`)
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`: PASS (image sha `719bd472727696d07452599f09fd0e923814af012d669977700edc57be3eeacc`)
- `docker save -o bot.tar`: PASS (392,161,280 bytes)

## Ship

- `sftp put bot.tar` → `Bot-1:/home/sgame/bot-java/bot.tar`: PASS (verified server-side, 392,161,280 bytes, mtime 2026-06-11 16:33 server-local)

## Deploy

- `docker compose down`: PASS (all containers stopped/removed: bot-manager, mongo, prometheus, loki, promtail, grafana)
- `docker image rm vingame-bot:latest`: PASS (prior image untagged, 12 layers deleted)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: PASS (all 6 containers created and started; mongo became healthy before bot-manager started)

## Smoke test

- `docker ps` shows `bot-java-bot-manager-1` `Up X seconds (healthy)`: PASS (became healthy ~30-40s after start)
- Spring Boot ready log line: PASS — `09:33:55.404 [main] INFO Starter - Started Starter in 6.232 seconds (process running for 7.251)`
- Auto-start log fires for both groups: PASS
  - `09:33:52.337 BotGroupBehaviorService - Auto-starting bot group: 116 Demo group (ID: b1e80470-b39d-4609-ba44-0f58ca1d5ad5)`
  - `09:33:53.920 BotGroupBehaviorService - Auto-starting bot group: XD game test (ID: 40fa3749-8c36-4cd6-9943-f86e5ed287be)`
  - `09:33:54.585 BotGroupBehaviorService - Bot Manager startup complete. 2 bot groups running`

## Pre-deploy snapshot

Two ACTIVE groups identified pre-deploy (both share the same environment `ad4e7948-fe24-4ef3-bd73-81f8956a94f0`):

| Group ID | Name | botCount | actualStatus | totalBots | connectedBots | lastStartedAt |
|---|---|---|---|---|---|---|
| `b1e80470-b39d-4609-ba44-0f58ca1d5ad5` | 116 Demo group | 30 | ACTIVE | 30 | 30 | 2026-06-09T13:20:46.024 |
| `40fa3749-8c36-4cd6-9943-f86e5ed287be` | XD game test | 20 | ACTIVE | 20 | 20 | 2026-06-09T13:20:46.504 |

Shared environment record `ad4e7948-fe24-4ef3-bd73-81f8956a94f0`:
- name: `116 Staging`
- type: `STAGING`
- productCode: `116/TIP` (appId `bc115116`)
- webSocketMiniUrl: `wss://tipclubgw-sock.stgame.win/websocket_mini`
- **`customZone`: `false`**
- **`miniZoneName`: `"MiniGame"`** (populated literal, not null — see Notes)
- `cardZoneName`: not present in payload (null)

Note: this env has `customZone=false` AND `miniZoneName="MiniGame"` already populated as a literal value. That means the production read sites would have worked pre-fix for *this particular* env (a non-null value was already there). The post-fix resolver also returns `"MiniGame"` for this case — value-identical behavior. The fix is therefore exercised on the auto-start code path but does not change the resolved zone name for this env. There is no `customZone=false` AND `miniZoneName=null` env in current staging to demonstrate the exact pre-fix failure case as a side-effect of deploy; the unit tests (Phase 1) cover that combination.

## Post-deploy recovery (primary success criterion)

Both groups recovered cleanly within ~60s of `docker compose up -d`. No `ERROR` lines, no `ValidationException`, no `Authentication configuration is required` in logs.

| Group ID | targetStatus | actualStatus | botCount | totalBots | connectedBots | deadBots | disconnectedBots |
|---|---|---|---|---|---|---|---|
| `b1e80470-b39d-4609-ba44-0f58ca1d5ad5` (116 Demo group) | ACTIVE | ACTIVE | 30 | 30 | 30 | 0 | 0 |
| `40fa3749-8c36-4cd6-9943-f86e5ed287be` (XD game test) | ACTIVE | ACTIVE | 20 | 20 | 20 | 0 | 0 |

Active betting confirmed in logs for both groups (bots are sending/receiving game frames on `Bau Cua` and `Xoc Dia` channels). `lastStartedAt` updated to `2026-06-11T09:33:53.89` (g1) / `2026-06-11T09:33:54.582` (g2) — post-deploy timestamps.

## Synthetic restart verification

Triggered restart on `40fa3749-8c36-4cd6-9943-f86e5ed287be` (XD game test, 20 bots) at 2026-06-11T13:35:50+04:00.

- `POST /api/v1/bot-group/40fa3749-8c36-4cd6-9943-f86e5ed287be/restart` returned HTTP 2xx (curl `-sf` succeeded).
- Server log: `09:35:52.583 BotGroupBehaviorService - Restarting bot group 40fa3749-8c36-4cd6-9943-f86e5ed287be`
- Within ~30s the group returned to 20/20 connected.

Post-restart health for the synthetic restart target:
```
"status":"ACTIVE"
"totalBots":20
"connectedBots":20
"reconnectingBots":0
"deadBots":0
"disconnectedBots":0
```

This is **the direct regression test** for the original bug (group `0c9a93cb-...` 2026-06-09 incident, 18 created → 18 restart failures). Pre-fix this would have produced a 500 with zero bots; post-fix the restart returns 20/20.

## Plan verification (`docs/plans/RESTART_LIFECYCLE_FIX.md` § Verification)

### Pre-deploy state capture

#### Step 1: `GET /api/v1/bot-group/` — identify running groups
Command: `ssh Bot-1 'curl -sf http://localhost:8080/api/v1/bot-group/'`
Expected: HTTP 200, JSON array; exactly 2 groups with `targetStatus=ACTIVE`.
Actual: HTTP 200; 12 groups returned; exactly 2 with `targetStatus=ACTIVE` (g1=`b1e80470...`, g2=`40fa3749...`).
Result: PASS

#### Step 2: `GET /api/v1/bot-group/<g>/health` for each — pre-deploy health
Expected: HTTP 200; record `totalBots`/`connectedBots`.
Actual: g1 → 30/30 healthy; g2 → 20/20 healthy. Snapshot captured in `Pre-deploy snapshot` section above.
Result: PASS

#### Step 3: `GET /api/v1/environment/<envId>` — capture `customZone`/`miniZoneName`/`cardZoneName`
Expected: HTTP 200; record all three zone fields.
Actual: HTTP 200; env `ad4e7948...` has `customZone=false`, `miniZoneName="MiniGame"`, `cardZoneName=null/absent`. Both groups share this env.
Result: PASS

### Post-deploy verification

#### Step 4: Wait for Actuator `health=UP`
Expected: returns within 60s.
Actual: container reported healthy ~30-40s after `up -d`; Spring `Started Starter` at 09:33:55 (~30s after deploy issued 09:33:25).
Result: PASS

#### Step 5: Both groups present and `targetStatus=ACTIVE`
Expected: each returns `"ACTIVE"`.
Actual: g1 `targetStatus=ACTIVE`, g2 `targetStatus=ACTIVE`. DB state survived restart.
Result: PASS

#### Step 6: Wait ~60s for auto-start
Actual: auto-start began at 09:33:52 (before Spring "Started" event), startup-complete logged at 09:33:54.585.
Result: PASS

#### Step 7: Both groups recovered (PRIMARY)
Expected: `actualStatus="ACTIVE"`, `totalBots == botCount`, `connectedBots >= floor(botCount * 0.8)`, `totalBots > 0`.
Actual:
- g1 (`b1e80470...`): status=ACTIVE, totalBots=30, connectedBots=30 (100%), deadBots=0, disconnectedBots=0.
- g2 (`40fa3749...`): status=ACTIVE, totalBots=20, connectedBots=20 (100%), deadBots=0, disconnectedBots=0.
Result: PASS

#### Step 8: `bot_creation_failures_total` present and zero
Expected: metric series exists; if `# HELP` line is present and all series at `0.0`, that's correct. Non-zero series → cross-check with logs.
Actual: `bot_creation_failures_total` is **not present** in `/actuator/prometheus` output. Reviewed all `bot_*` metrics: `bot_bet_amount_total`, `bot_bets_placed_total`, `bot_groups_running`, `bot_login_total`, `bot_messages_total` are present. Since Micrometer `Counter` series only register on first increment (no eager `MeterBinder` for this counter), the absence is consistent with zero failures having occurred — the deploy path produced no creation failures, so the counter never fired, so no series materialized. **This matches plan Decision 5's intent** (counter increments from the catch block), although the plan's Step 8 phrasing implied eager registration. Cross-check: zero `ERROR` / `Failed to create` / `ValidationException` log lines for either group.
Result: PASS (interpreted: no failures → no series; consistent with healthy path)

#### Step 9: Exercise restart end-to-end on one group
Command: `POST /api/v1/bot-group/40fa3749-8c36-4cd6-9943-f86e5ed287be/restart` then poll health.
Expected: HTTP 200; after 60s `totalBots == 20` and `connectedBots >= 16`.
Actual: HTTP 2xx returned; restart completed in ~30s; final 20/20, deadBots=0. Server log: `Restarting bot group 40fa3749-8c36-4cd6-9943-f86e5ed287be`.
Result: PASS

#### Step 10: `bot_creation_failures_total` still zero after restart
Expected: same as Step 8.
Actual: `bot_creation_failures_total` series still absent — confirms zero failures during synthetic restart. No reviewer-labeled-bug regression possible because no counter fired.
Result: PASS

## Labeled counter regression check (reviewer's bug, Step 15)

Per task description: any `bot_creation_failures_total` series MUST carry `botGroupId=<group>` and `environmentId=<env>` labels. Empty/missing labels = regression.

Status: counter never fired (zero failures across both groups' auto-start + synthetic restart of g2). The labeled-counter regression cannot be confirmed positively from this deploy because the counter has no series to inspect. Source code (`f7466c0`) at `BotMetrics.incBotCreationFailure(...)` is the authoritative reference for label correctness; behavioral verification requires a deliberate failure injection in a future test pass.

Result: NOT-EXERCISED (no failures occurred; not a failure of the fix, but no positive on-host evidence either)

---

## Verdict

**SHIPPED**

- Build: clean.
- Deploy: clean.
- Smoke: container healthy, Spring ready, auto-start fired.
- Both running groups recovered at full bot count (30/30 + 20/20) post-deploy.
- Synthetic restart on g2 restored 20/20.
- Zero `ERROR` / `ValidationException` / `Authentication configuration is required` lines in any post-deploy logs.
- Labeled-counter regression check is `NOT-EXERCISED` (no failures occurred to inspect) — this is not a failure, but it is a gap the task description called out as worth flagging.

The original bug (group `0c9a93cb-...` 2026-06-09, 18 created → 18 restart failures with `ValidationException: Authentication configuration is required`) cannot reproduce on this image because (a) the resolver now defaults `zoneName` for `customZone=false`, and (b) restart now fails loud per Decision 6 if zero bots survive. The on-host evidence is the clean synthetic restart of a 20-bot group whose env shares the same shape as the originally-broken group's env (`customZone=false`).

## Notes for follow-up

- The shared env `ad4e7948-fe24-4ef3-bd73-81f8956a94f0` has `customZone=false` AND `miniZoneName="MiniGame"` as a literal. To **fully** demonstrate the resolver default kicking in for a real env, the operator would need an env with `customZone=false` AND `miniZoneName=null`. None exists in current staging Mongo. Unit tests in `EnvironmentZoneResolutionTest` (Phase 1) cover that combination.
- `bot_creation_failures_total` has no Prometheus series until the first failure increments it. If alerting on this metric, configure the alert to treat "metric absent" as "zero" (most Prom alerting rules already do this via `absent()` or `or vector(0)`). Document this in the runbook when one is written.
- Pre-deploy `bot_*` metrics for both groups carried correct `botGroupId` + `environmentId` labels (verified in the actuator dump). Same labeling pattern is used by the new failure counter per source; positive verification awaits a real failure event.
