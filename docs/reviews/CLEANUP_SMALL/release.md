# Release — CLEANUP_SMALL

Mode: bot
Branch: feat/cleanup-small
HEAD: 954f079d6871893682ebdfacfa547f213374fbed
Image: vingame-bot:latest (built 2026-06-11 14:04 local)
Date: 2026-06-11T14:08+04:00

## Commits shipped

- `2d71941` refactor(api-gateway): drop unreachable non-xToken registration branch
- `3482874` refactor(api-gateway): finish xToken cleanup across 3 more methods
- `954f079` feat(bot-group): pre-flight username length validation (Tip / P_116 = 12 chars cap)

Working tree: docs-only noise (`.gitignore`, `CLAUDE.md`, `docs/plans/OBSERVABILITY.md`, `docs/reviews/OBSERVABILITY/release.md`, untracked `Stub.java`) — non-blocking, left as-is per user instruction.

## Pre-deploy state

`docker ps` on Bot-1 prior to deploy: `bot-java-bot-manager-1   Up 29 minutes (healthy)`. Two groups expected to recover: `116 Demo group` (b1e80470) + `XD game test` (40fa3749).

## Build

- `mvn clean install`: PASS — BUILD SUCCESS, 405/405 tests, 17.062s
- `docker build --no-cache --platform linux/amd64`: PASS — image sha256:b9dc25fe796f7fdb1b923e026f727921c3e326392b57f56895ad57d15b6bc90b
- `docker save -o bot.tar`: PASS — 392160768 bytes

## Ship

- `sftp put bot.tar` → `/home/sgame/bot-java/bot.tar`: PASS — 392160768 bytes confirmed on Bot-1 at 17:05 server time

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image deleted, sha 0c3db79307ab)
- `docker load -i bot.tar`: PASS — `Loaded image: vingame-bot:latest`
- `docker compose up -d`: PASS — all 6 containers started (mongo, loki, promtail, bot-manager, prometheus, grafana)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up 33 seconds (healthy)` after start_period
- Spring Boot ready log: PASS — `10:05:53.632 Started Starter in 14.416 seconds (process running for 15.446)`
- Auto-start log: PASS — `10:05:52.855 Bot Manager startup complete. 2 bot groups running`
  - `10:05:42.174 Auto-starting bot group: 116 Demo group (ID: b1e80470-b39d-4609-ba44-0f58ca1d5ad5)`
  - `10:05:47.596 Auto-starting bot group: XD game test (ID: 40fa3749-8c36-4cd6-9943-f86e5ed287be)`

## Plan verification (light — per user instruction)

### Step 1: Both groups recover to ACTIVE
Command: `curl -s http://localhost:8080/api/v1/bot-group/`
Expected: both `b1e80470...` and `40fa3749...` show `targetStatus=ACTIVE` with fresh `lastStartedAt`.
Actual:
- `116 Demo group` (b1e80470) — `targetStatus=ACTIVE`, `lastStartedAt=2026-06-11T10:05:47.572`
- `XD game test` (40fa3749) — `targetStatus=ACTIVE`, `lastStartedAt=2026-06-11T10:05:52.852`
Result: PASS

### Step 2: All bots connected via /health
Command: `curl -s http://localhost:8080/api/v1/bot-group/{id}/health`
Expected: `connectedBots == totalBots`, `deadBots=0`, `disconnectedBots=0`.
Actual:
- 116 Demo group: `totalBots=30, connectedBots=30, reconnectingBots=0, deadBots=0, disconnectedBots=0`, status ACTIVE/IDLE
- XD game test: `totalBots=20, connectedBots=20, reconnectingBots=0, deadBots=0, disconnectedBots=0`, status ACTIVE/IDLE
Result: PASS

### Step 3: Bots actively betting (sanity)
Expected: bot betting counters > 0 shortly after start.
Actual: 116 Demo bots show `totalBetsPlaced=35-36, totalBetAmount=7.5M-11M`; XD bots show `totalBetsPlaced=27-28, totalBetAmount=4.8M-8.5M`. Active rounds visible in EndGame frames in logs.
Result: PASS

## Verdict

SHIPPED

Both target bot groups recovered cleanly. Smoke checks all green. No `ws-client.*Cannot send message` warnings observed in startup logs. 3 commits delivered: 2x api-gateway xToken cleanup refactors and 1x username length pre-flight validation. No new behavior visible at runtime (refactors are no-op by design; pre-flight is a guard exercised only on POST `/api/v1/bot-group` create — not triggered during this deploy).
