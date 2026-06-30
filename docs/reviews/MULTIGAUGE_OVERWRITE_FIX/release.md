# Release — MULTIGAUGE_OVERWRITE_FIX

Mode: bot (targeted single-service redeploy)
Branch: fix/multigauge-overwrite
Commit: 47cee5d fix(observability): pass overwrite=true when re-registering status MultiGauges
Base: fix/rik-login-type-bot (RIK type=BOT register/login fixes — b4bdab6, 26281cd — preserved)
Image: vingame-bot:latest (built 2026-06-29T13:09Z)
Date: 2026-06-29T13:17Z

## Context

`bots_by_game_status` / `bots_by_env_status` froze at the count present when each
(gameId,status) / (environmentId,status) row first appeared, because
`MultiGauge.register(single-arg)` defaults to `overwrite=false`. The two RIK Tai
Xiu Jackpot groups (7-bot `10056b54` + 100-bot `75899bb9`, same game
`29d419f1`, same env `394301f4`) summed to only 7 in Prometheus, so the analytics
UI and Grafana showed "7 active bots". The fix passes `overwrite=true` so the
gauge tracks live counts.

## Pre-flight

- Branch HEAD verified at 47cee5d; based on RIK fixes (b4bdab6, 26281cd): PASS
- Working tree: only untracked docs/scratch files, no modified tracked files: PASS
- Bot-1 guards confirmed before deploy: `dns: ["1.1.1.1","8.8.8.8"]` present in
  compose (lines 26–28), compose NOT modified, NO full `down`, targeted
  `up -d bot-manager` only.

## Build

- `mvn clean install`: PASS (~24s, 1079 tests run, 0 failures, 0 errors, 0 skipped)
  - Includes InfoGaugeRefresherTest with the 2 new overwrite regression tests.
- `docker build --no-cache --platform linux/amd64`: PASS
  (image sha256:9a090de88ebb…)
- `docker save -o bot.tar`: PASS (392872960 bytes)

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS
  (remote size 392872960 == local 392872960, byte-exact)

## Deploy (targeted, bot-manager only)

- `docker compose stop bot-manager`: PASS
- `docker image rm vingame-bot:latest`: container still referenced it → reported
  "no prior image / skipped"; `docker load` then renamed the old image and loaded
  the new one (effectively replaced). PASS
- `docker load -i bot.tar`: PASS ("Loaded image: vingame-bot:latest")
- `docker compose up -d bot-manager`: PASS (other 5 services untouched)
- Deploy completed 2026-06-29T13:11:38Z

## Smoke test

- `docker ps` shows healthy: PASS ("Up About a minute (healthy)", later "Up 6 minutes (healthy)")
- Spring Boot ready log: PASS ("Started Starter in 10.864 seconds")
- Auto-start log: PASS ("Bot Manager startup complete. 8 bot groups running";
  both RIK groups auto-started: 10056b54 and 75899bb9)

## Fix verification — gauge now tracks live count, not the frozen 7

Captured on the running (buggy) instance immediately BEFORE redeploy, and AFTER
the redeploy + ~3 min reconnect window.

| Query | Before (frozen) | After (live) |
|---|---|---|
| `sum(bots_by_game_status{gameId="29d419f1-9c96-4e74-aec1-41c7fe5849c3"})` | **7** | **107** |
| `sum(bots_by_env_status{environmentId="394301f4-6daf-4c55-a073-502a81c00731"})` | **7** | **107** |
| `sum(bots_by_game_status{gameId="45d42867-b3ff-4dd6-9a0c-1b371a394774"})` (Fruit Shop regression) | 40 | **40** |

Exact queries run on Bot-1 (host → Prometheus on :9090):
```
curl -s "http://localhost:9090/api/v1/query?query=sum(bots_by_game_status{gameId=\"29d419f1-9c96-4e74-aec1-41c7fe5849c3\"})"
curl -s "http://localhost:9090/api/v1/query?query=sum(bots_by_env_status{environmentId=\"394301f4-6daf-4c55-a073-502a81c00731\"})"
curl -s "http://localhost:9090/api/v1/query?query=sum(bots_by_game_status{gameId=\"45d42867-b3ff-4dd6-9a0c-1b371a394774\"})"
```
- RIK game gauge: 7 → 107 — no longer frozen: PASS
- RIK env gauge: 7 → 107 — no longer frozen: PASS
- Fruit Shop single-group regression guard: 40 → 40 (unchanged, still correct): PASS

## Cross-check against ground truth (health endpoints)

The bot-manager app port (8085) is on the docker network only, so health was read
from inside the container:
```
docker exec bot-java-bot-manager-1 curl -s "http://localhost:8085/api/v1/bot-group/75899bb9-5507-415a-8e83-2d9250ac46c5/health"
docker exec bot-java-bot-manager-1 curl -s "http://localhost:8085/api/v1/bot-group/10056b54-dec9-4a35-afe8-0b89d4883a0d/health"
```

| Group | connectedBots | totalBots |
|---|---|---|
| 75899bb9 (RIK TaiXiu Jackpot 100) | 100 | 100 |
| 10056b54 (RIK TaiXiu RegBot Smoke copy) | 7 | 7 |
| **Sum** | **107** | — |

Health connected total = 100 + 7 = **107**. Prometheus gauge sum = **107**.
**gauge == reality: PASS** (not the stale 7).

## Stack & errors

- `docker compose ps`: all 6 services up — bot-manager (healthy), grafana, loki,
  mongo (healthy), prometheus, promtail: PASS
- Metrics-path ERRORs (metric/gauge/prometheus/multigauge/InfoGauge): NONE: PASS
- Total ERROR lines: 66, all `BotGroupBehaviorService - Failed to create bot X/40
  for group 10056b54-…` — known RIK staging bot-creation/auth failures
  (pre-existing partial-registration behavior on the RIK environment), unrelated
  to the metrics fix.

## Verdict

PASS

The MultiGauge overwrite fix is live. `bots_by_game_status` and
`bots_by_env_status` now track live bot counts (7 → 107) and match the
health-endpoint ground truth exactly (107 == 107). Single-group games remain
correct (Fruit Shop 40). The RIK type=BOT fixes from the base branch are
preserved. Full observability stack healthy; no metrics-path errors.
