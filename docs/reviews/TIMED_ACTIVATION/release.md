# Release — TIMED_ACTIVATION

Mode: bot
Branch: feat/timed-activation
Image: vingame-bot:latest (built at 2026-07-07 ~15:30 +04)
Date: 2026-07-07T15:43:25+0400

Target: Bot-1 (`bot-java-bot-manager-1`, host port 8080 → container 8085)
Server zone: Asia/Ho_Chi_Minh (confirmed from reconciler startup log). Verification ran at ~18:35–18:43 ICT.

## Build

- `mvn clean install`: PASS (27s) — 1286 tests run, 0 failures, 0 errors, BUILD SUCCESS
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:3008f954e184…)
- `docker save`: PASS (392,932,352 bytes)

## Ship

- `sftp put bot.tar`: PASS (remote size verified: 392,932,352 bytes, exact match)

## Deploy

Whole single-Compose project bounced (bot-manager + mongo + loki + promtail + prometheus + grafana), as expected on this host.

- `docker compose down`: PASS (all 6 containers removed cleanly)
- `docker image rm vingame-bot:latest`: PASS (prior image untagged/deleted)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all containers started; mongo reached Healthy before bot-manager)

## Smoke test

- `docker ps` shows healthy: PASS — `Up About a minute (healthy)` (re-confirmed `Up 11 minutes (healthy)` at end of run)
- Spring Boot ready log: PASS — `Started Starter in 12.794 seconds (process running for 13.85)`
- Auto-start log: PASS — `Bot Manager startup complete. 8 bot groups running`

### Post-deploy sanity: live 100-bot group (ab81f9e6-764b-4785-9039-84add04e2fb0)

Legacy null-mode group with `targetStatus=ACTIVE`. Auto-started on boot as expected
(`Auto-starting bot group: BOM flow test 100 (kept running)`) and confirmed
`{"targetStatus":"ACTIVE","actualStatus":"ACTIVE"}` both immediately after smoke and
again at the end of the run. Not stopped, not deleted, not modified. PASS.

## Test parameters used

- `$H` = `http://localhost:8080` (run on-host via ssh; API/actuator not exposed externally)
- `$ENV` = `ad4e7948-fe24-4ef3-bd73-81f8956a94f0` (from the healthy, actively-betting "XD game test" group — auth/registration verified working, avoids the known TIP/P_116 WS DNS block)
- `$GAME` = `4780941c-3b30-45c5-9f79-a6b529ceb423` (BETTING_MINI in that env)

### Deviations from the plan's literal payloads (environment constraints, not feature-related)

1. `betIncrement`: plan uses `0`; this build's pre-existing config validation rejects it
   (`betIncrement (0) must be > 0`). Used `betIncrement: 1` (inconsequential since minBet==maxBet==100).
2. Name prefixes: plan uses `tact` / `tinact`; the XD product enforces a 6-char minimum
   username, so `tact1` (5 chars) is rejected by the game server at registration. Used
   `tactive` / `tinactive` instead (recognizable, cleanup is by group id so prefix value is
   irrelevant to teardown). The bad-schedule step used prefix `tbadsched`.

Neither deviation touches the activation logic under test.

## Plan verification

Steps as numbered in `docs/plans/TIMED_ACTIVATION.md` § Verification (0–7).

### Step 0: Universal smoke — actuator health
Command: `curl -s -o /dev/null -w '%{http_code}' $H/actuator/health`
Expected: `200`
Actual: `200`
Result: PASS

### Step 1: Reconciler is scheduled (log)
Command: grep container logs for `activation-reconciler` / `Activation reconcile` / `ActivationScheduler`
Expected: reconciler thread appears (≥1)
Actual: `Activation reconciler started (zone: Asia/Ho_Chi_Minh, tick: 60s, first tick in 8767 ms)` at startup, and per-tick lines on thread `[activation-reconciler]` (e.g. `11:38:00.009 [activation-reconciler] INFO ActivationScheduler … → START`). Ticks fire on minute boundaries (`:00`), confirming AD-8 minute alignment.
Result: PASS

### Step 2: Group whose window is OPEN now → starts within one tick
Command: create SCHEDULED group, window `00:00:00–23:59:00`, empty days; wait one tick; `GET /{id}/status`
Expected: `actualStatus=ACTIVE`, `targetStatus=ACTIVE`; log `Activation reconcile: group … → START`
Actual: group `94f66643-9dc4-4b6f-9426-648c6c6a7634` → `{"targetStatus":"ACTIVE","actualStatus":"ACTIVE","playingStatus":"IDLE"}`; log `11:38:00.009 [activation-reconciler] … group 94f66643… window open → START` followed by 2 bots created & started. Group was NOT started at create time (AD-10) — the reconciler owned the start.
Result: PASS

### Step 3: Group whose window is CLOSED now → stays stopped
Command: create SCHEDULED group, window `03:00:00–03:01:00` (excludes current ~18:38 ICT); wait one tick; `GET /{id}/status`
Expected: `actualStatus=STOPPED`; no `→ START` for this group
Actual: group `6d23881c-837d-4471-964d-a635dca61f25` → `{"targetStatus":null,"actualStatus":"STOPPED"}`; only `Activation reconcile: group 6d23881c… → NONE (running=false, dead=false)` logged, never START.
Result: PASS

(No-flapping check, per plan Implementation Notes: the tick after the OPEN group started logged `group 94f66643… → NONE (running=true, dead=false)` — a single transition, no oscillation.)

### Step 4: Manual override parks a scheduled group (AD-4)
Command: `POST /{id}/stop` on the open group; read `activationMode`; wait a full tick; `GET /{id}/status`
Expected: `/stop` → 200; `activationMode == MANUAL_OFF`; after a tick still `actualStatus=STOPPED` (reconciler does not restart); no `→ START`
Actual: `/stop` → `200`; `activationMode = MANUAL_OFF`; after 75s → `{"targetStatus":"STOPPED","actualStatus":"STOPPED"}`. No START line emitted while parked (next START only appeared after the step-5 PATCH).
Result: PASS

### Step 5: Un-park returns it to the schedule
Command: `PATCH /{id}` `{"activationMode":"SCHEDULED"}`; wait a full tick; `GET /{id}/status`
Expected: `PATCH` → 200; `actualStatus=ACTIVE` again (window still open) with a fresh `→ START`
Actual: `PATCH` → `200`; after 75s → `{"targetStatus":"ACTIVE","actualStatus":"ACTIVE","playingStatus":"IDLE"}`; fresh log `11:42:00.004 [activation-reconciler] INFO ActivationScheduler … group 94f66643… window open → START`.
Result: PASS

### Step 6: Validation rejects a bad schedule (AD-7)
Command: `POST /` with SCHEDULED + no window; and SCHEDULED + `from==to` (`10:00:00`==`10:00:00`)
Expected: `400` for both
Actual:
- no window → `400` `Invalid bot-group activation config: activationWindow is required when activationMode is SCHEDULED`
- `from==to` → `400` `activationWindow.from (10:00) must differ from activationWindow.to (10:00); a zero-length window is ambiguous — use MANUAL_ON for always-on`
Result: PASS

### Step 7: Cleanup
Command: `DELETE` both throwaway groups
Expected: `200` each
Actual: open → `200`, closed → `200`; subsequent `GET` → `404` for both (confirmed removed).
Result: PASS

Additional confirmation: the new DTO fields `activationMode` / `activationWindow` round-trip correctly (present and null on legacy groups, populated on the SCHEDULED groups), and legacy null-mode groups (the 100-bot group and the other 7 auto-started groups) are unaffected.

## Verdict

PASS — 8 of 8 verification steps passed; smoke passed; live 100-bot group restored and untouched.
