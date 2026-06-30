# Release — TAIXIU_WINNINGS_FIX

Mode: bot
Branch: fix/taixiu-114-winnings
Image: vingame-bot:latest (built 2026-06-30 ~13:15 +04:00; image id sha256:888e80e4f917…)
Date: 2026-06-30T13:25+04:00 (09:25 UTC)
HEAD: 7ff3e16603f8661deb3a87b642106e773da79ece (amended — test fix applied)

## Summary

**PASS.** This is the re-trigger of the previously-aborted release. The prior
attempt stopped at the build gate because `TaiXiuMessageDeserializationTest`
still encoded the old `winnings = G` contract. The developer updated that test
(and siblings) to the revised `winnings = max(0, GX - gR)` contract and added a
P_114 case. The full suite is now green (1084 tests, 0 failures), the image was
built, shipped, and the `bot-manager` service was recreated in place on Bot-1
(no full `down`, no compose overwrite, DNS block preserved). After a few minutes
of live RIK rounds, winnings and RTP now populate for RIK Tai Xiu where they
were previously 0/absent.

## Build

- `mvn clean install`: **PASS** (25s) — `Tests run: 1084, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS
- `docker build --no-cache --platform linux/amd64`: **PASS** — image id sha256:888e80e4f917…
- `docker save bot.tar`: **PASS** — 392,872,960 bytes

## Ship

- `sftp put bot.tar`: **PASS** — remote size 392,872,960 bytes (byte-exact match with local)

## Deploy (targeted, Bot-1 guards honored)

- Full `docker compose down`: **NOT RUN** (intentional — bounce only bot-manager)
- `docker compose config` service check: `bot-manager` (single bot service)
- DNS guard: compose still has `dns: ["1.1.1.1","8.8.8.8"]` — **unchanged**, not modified
- `docker load -i bot.tar`: **PASS** — old image 9a090de8… → new image 888e80e4… (matches local build)
- `docker compose up -d bot-manager`: **PASS** — `bot-java-bot-manager-1 Recreated → Started`; mongo left Running/Healthy; observability stack untouched

## Smoke test

- `docker ps` shows healthy: **PASS** — `bot-java-bot-manager-1  Up (healthy)` after ~35s
- Spring Boot ready log: **PASS** — `Started Starter in 10.303 seconds`
- Auto-start log: **PASS** — `Bot Manager startup complete. 8 bot groups running`; target group `RIK114 TaiXiu Jackpot 100 (75899bb9…) started successfully with 100 bots`
- Observability stack up: **PASS** — `prometheus Up 24h`, `grafana Up 3d`, `loki Up 5d`

## Plan verification (winnings/RTP for RIK Tai Xiu gameId=29d419f1-9c96-4e74-aec1-41c7fe5849c3)

### Baseline (before accrual, 09:18:49 UTC, immediately post-restart)
Metrics API summary → `winnings_rate_5m: null`, `rtp_5m: 0.0`, `total_bots: 107` (all CONNECTION_AUTHENTICATED).
Matches the pre-fix condition: winnings 0/absent.

### Step 1: winnings rate 5m
Command: `sum(rate(bot_winnings_total{gameId="29d419f1-9c96-4e74-aec1-41c7fe5849c3"}[5m]))`
Expected: > 0 (was 0/absent)
Actual: **322193.79**
Result: **PASS**

### Step 2: RTP 5m
Command: `(sum(rate(bot_winnings_total{gameId="29d419f1…"}[5m])) / sum(rate(bot_bet_amount_total{gameId="29d419f1…"}[5m]))) or vector(0)`
Expected: plausible non-zero (~0.8–1.7), not 0
Actual: **0.99** (numerator 322193.79 / denominator 325448.28)
Result: **PASS**

### Step 3: Metrics API summary
Command: `curl http://localhost:8080/api/v1/metrics/game/29d419f1-9c96-4e74-aec1-41c7fe5849c3/summary`
Expected: `winnings_rate_5m` and `rtp_5m` non-zero
Actual: `winnings_rate_5m: 322193.79`, `rtp_5m: 0.99`
Result: **PASS**

### Step 4: Ground truth — RIK group health (75899bb9-5507-415a-8e83-2d9250ac46c5)
Command: `curl http://localhost:8080/api/v1/bot-group/75899bb9…/health`
Expected: some RIK bots show `lastRoundWinnings > 0` for recent winners
Actual: **53 of 100 bots** have `lastRoundWinnings > 0`; e.g. riktxjp15=3,168,000, riktxjp21=871,200, riktxjp4=1,584,000; max=3,484,800. `lastRoundWinnings` non-null for all 100.
Result: **PASS**

## Regression

- Per-game RTP (5m) across all active games — all sane, none broken by the change:
  - `29d419f1` RIK Tai Xiu Jackpot (target): 0.99
  - `c5a22c44` **SlotTipTest** (non-TaiXiu): 0.79
  - `9d0f4b43` **Bau Cua** (non-TaiXiu): 0.89
  - `45d42867`: 1.00
  - `9d05c039`: 0.99
- Full build suite green: 1084 tests, 0 failures.
Result: **PASS** — non-Tai-Xiu winnings/RTP unchanged/sane.

## Health / errors

- bot-manager healthy; observability stack (Prometheus/Grafana/Loki) up.
- No metrics-path ERRORs. Total ERROR lines (66) are all auth failures on a
  **different** group `10056b54` ("RIK114 TaiXiu RegBot Smoke copy") whose
  accounts do not exist upstream (`"status":"NOT_FOUND","code":504,"message":"Tài
  khoản không tồn tại"`). Pre-existing environmental noise, unrelated to this fix
  and not on the metrics path. Target group `75899bb9` has all 100 bots
  authenticated and accruing winnings.

## Verdict

**PASS** — build green, deployed in place (bot-manager only), smoke passed,
winnings/RTP now populate for RIK Tai Xiu (322193.79 / RTP 0.99 vs prior 0/absent),
ground-truth health confirms 53/100 winners, regression clean.
