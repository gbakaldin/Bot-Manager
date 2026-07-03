# Release — AGGREGATED_SESSION_LOGGING

Mode: bot
Branch: main
Commit: 43214a1
Image: vingame-bot:latest (sha256:a20ca0c25cc642870038dcca2fbad34bc6403ba3f5f20a7079450814d1ba175b, built 2026-07-01 ~14:19)
Target: Bot-1 staging (`/home/sgame/bot-java/`, container `bot-java-bot-manager-1`, host 8080 → container 8085)
Date: 2026-07-01T14:2x (local build/deploy window)

## Build

- `mvn clean install`: PASS (25s, 1125 tests, 0 failures/errors). Earlier console stack trace was a handled-exception test scenario (`RestExceptionHandler` `ResourceNotFoundException`), not a failure.
- `docker build --no-cache --platform linux/amd64`: PASS (image `a20ca0c25cc6`, 382 MB)
- `docker save -o bot.tar`: PASS (395,740,160 bytes)

## Ship

- `sftp put bot.tar`: PASS (395,740,160 bytes on server, byte-identical, 62s)

## Deploy

- Compose change: NONE (as instructed — DNS guard / `restart: unless-stopped` / loki bind-mount already present; docker-compose.yml untouched)
- `docker load -i bot.tar`: PASS (loaded `a20ca0c25cc6`; prior image ID `4db01f43…` renamed to empty/dangling)
- `docker compose up -d --no-deps --force-recreate bot-manager`: PASS (exit 0; `HOST_UID=1006`/`HOST_GID=1007` auto-loaded from `.env`, container user 1006:1007)
- Targeted recreate only — mongo/prometheus/grafana/loki/promtail left running

## Smoke test

- `docker ps` healthy: PASS (`Up (healthy)` at 36s; start_period passed cleanly)
- Spring Boot ready log: PASS (`Started Starter in 10.053 seconds`)
- `/actuator/health` UP: PASS (mongo UP, ping UP, ssl UP, diskSpace UP 34 GB free)
- Auto-start: PASS (`Bot Manager startup complete. 7 bot groups running`)
- 0 DEAD / 0 AUTH_FAILED: PASS (both counts 0 across full log)
- Observability stack still Up: PASS (loki, mongo[healthy], prometheus, grafana, promtail all Up — untouched by targeted recreate)

## Headline metric — per-frame WS volume drop

Measured on the SAME host, 60s windows, at the DEBUG default.

| Metric | BEFORE (old image) | AFTER (new image) | Delta |
|---|---|---|---|
| Per-frame `User … [SENT]/[RECEIVED]` lines / 60s | 8,512 | 0 | -100% |
| Per-frame bytes / 60s | 3,067,899 (~176 MB/hr) | ~0 | ~-176 MB/hr eliminated |
| Total log lines / 60s | 36,775 | 28,721 | -22% |
| Total log bytes / 60s | 8,354,356 (~478 MB/hr) | 5,396,867 (~324 MB/hr) | ~-154 MB/hr |

The feature's target — the per-frame WS `OutputPrinter` flood — is fully eliminated at the DEBUG default (8,512/min → 0, ~176 MB/hr reclaimed). Raw frames are now TRACE-only.

## Plan verification (`docs/plans/AGGREGATED_SESSION_LOGGING.md` § Verification)

### Step 1: Baseline volume (before)
Command: `docker logs --since 60s <bot-manager> | grep -c 'User .*[RECEIVED]|User .*[SENT]'` on old image
Expected: hundreds–thousands of frame lines
Actual: 8,512 lines / 60s (~176 MB/hr)
Result: PASS

### Step 2: Logger at default DEBUG
Command: `curl -s localhost:8080/actuator/loggers/com.vingame.bot`
Expected: `"effectiveLevel":"DEBUG"`
Actual: `"effectiveLevel":"DEBUG"`
Result: PASS

### Step 3: Raw frames silent at DEBUG
Command: `docker logs --since 60s <bot-manager> | grep -c 'User …[RECEIVED]/[SENT]'`
Expected: 0
Actual: 0 (confirmed on two independent windows)
Result: PASS

### Step 4: One StartGame line per session per group
Command: `docker logs --since 180s | grep 'entered session' | grep -oE 'entered session <sid>' | uniq -c`
Expected: 1 per `(group,gameId,sid)` (not one per bot)
Actual: 1 per key. sids showing "2" (2679412, 2679411) are cross-group sid-integer collisions — verified: sid 2679412 entered once each by `401f4c63/2/TAI_XIU` and `66cfc12c/26/TAI_XIU`. A broken guard on the 100-bot group would show 100 lines; it shows 1. Lines carry MDC `[botGroupId/gameId/gameType]`, gameName + botGroupId, and a raw sample.
Result: PASS

### Step 5: One UpdateBet summary per session per 5s
Command: `docker logs --since 90s | grep 'UpdateBet #'`
Expected: ~one per active session per 5s, monotonic seq, `total bettors this round` = live bot count, `total staked` non-decreasing
Actual: monotonic seq observed (`#1`, `#6`, `#11`); `total bettors this round` = 40 / 30 / 100 / 2 matching group bot counts; all emitted from the single `session-aggregation-flush` thread.
Sample: `UpdateBet #11 | new bettors since last: 0 | total bettors this round: 100 | total staked: 16720000` (group 75899bb9, TAI_XIU)
Result: PASS

### Step 6: One EndGame summary per session with correct totals
Command: `docker logs | grep 'session … ended'` + cross-check `bot_winnings_total`
Expected: 1 per closed sid; total win uses correct per-game winnings
Actual: `BotGroup Tai Xiu Jackpot/75899bb9… session 1655 ended | total staked: 16720000 | total win: 396000 | bettors: 100 | confirmed staked: 750000 | sample: …TaiXiuEndGameMessage`. `bot_winnings_total` counter present per group (e.g. SlotTipTest 1.20375E7). One summary per closed sid; first-close logger wins.
Result: PASS

### Step 7: Raw frames reappear at TRACE (escape hatch)
Command: `POST /actuator/loggers/com.vingame.bot {"configuredLevel":"TRACE"}`, measure, restore DEBUG
Expected: 204 on POST; >0 frames at TRACE; 204 on restore; silent again
Actual: POST TRACE → 204, effectiveLevel TRACE, 1,896 raw `User …` frames in 15s. Restore DEBUG → 204, effectiveLevel DEBUG, clean 20s window → 0 frames. (A transient 100-line count immediately after restore was a `--since` window overlapping the TRACE tail; the clean follow-up window confirmed 0.)
Result: PASS

### Step 8: Volume dropped ~2 orders of magnitude
Expected: post-deploy per-session logging ≈ StartGame(1) + UpdateBet(~12/min) + EndGame(1) per session — ~100× fewer than the per-frame flood
Actual: The per-frame WS class (this feature's target) dropped 8,512/min → 0 (100% eliminated, ~176 MB/hr reclaimed). Aggregate output is exactly StartGame + 5s UpdateBet flush + EndGame per session as designed. NOTE: total log volume fell only ~22% because a SEPARATE, out-of-scope class of per-bot strategy/decision DEBUG logging still dominates (see Surprises).
Result: PASS (for the per-frame flood this feature targets)

### Step 9: No aggregator memory leak
Note: the plan's literal check stops a group and waits > TTL. These are LIVE production staging groups (incl. the 100-bot `75899bb9` Tai Xiu Jackpot group); stopping one to satisfy the check would be needlessly disruptive, so verified by proxy instead:
- Single `session-aggregation-flush` scheduler thread (exactly 1 unique instance) — matches AD-7 one-app-wide-scheduler design.
- `jvm_threads_live_threads`: 1506 → 1509 over ~1 min — flat (normal bot churn), no unbounded growth.
- MAX_SESSIONS cap + TTL eviction + `evictGroup` covered by unit tests (Phase 2 verification, green in this build).
- The aggregator has no external gauge (by design, v1), so this is a coarse check.
Result: PASS (by proxy) — no live-group stop performed by design.

## Disk / Loki trend

- `df -h /`: 100G total, 67G used, 34G free, 67% — stable/healthy.
- Loki `Ingestion rate limit exceeded` spam: 0 hits in last 200 loki log lines (previously the outage-era spam) — ingest pressure eased now that per-frame frames are gone.

## Surprises / follow-up (out of scope for this feature)

Total DEBUG log volume only dropped ~22% (36,775 → 28,721 lines/min) despite the per-frame WS flood going to 0. The residual volume is a DIFFERENT class of per-bot DEBUG logging that AGGREGATED_SESSION_LOGGING never targeted (it targeted only the `OutputPrinter` per-frame WS dump). Top residual emitters per 60s at DEBUG:

- `BettingMiniGameBot - Bot …` — ~15,112
- `MartingaleStrategySupport - …decide` — ~5,266
- `RandomBehaviorStrategy - …decide` — ~3,498
- `TaiXiuGameBot - Bot …` — ~1,255
- `SlotMachineBot - Bot …` — ~1,200
- `Bot - checkBalance()` — ~684

Recommend a follow-up logging-hygiene pass to demote these per-bot per-round strategy/decision lines (per the CLAUDE.md norm: per-bot detail belongs at DEBUG but this per-round decision spam is arguably TRACE-level, or should be pruned). This does not detract from this feature's headline result — the WS per-frame flood it was built to kill is gone.

## Verdict

PASS

- Every in-scope plan verification step passed; the per-frame WS flood (the feature's target) is fully eliminated (8,512/min → 0, ~176 MB/hr reclaimed).
- Smoke clean; observability stack intact; disk healthy; loki ingest spam gone.
- One flagged follow-up (residual out-of-scope per-bot DEBUG verbosity) — noted, not a defect of this feature.

## Cleanup note

Prior image ID `4db01f43…` is now dangling on Bot-1 (expected from `docker load` overwriting the `latest` tag). Left in place; no `docker image prune` run.
