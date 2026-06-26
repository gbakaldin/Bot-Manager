# Release — TAI_XIU_BOT (AD-13: single-entry-lock)

Mode: bot
Branch: main @ `be961a2`
Image: vingame-bot:latest (built 2026-06-26T08:49:42Z–08:50Z UTC; image id `fa22f9e406fd`)
Date: 2026-06-26T08:54:49Z
Target: Bot-1 only

## Change summary

AD-13 — Tai Xiu bots bet only ONE entry per round. The first bet of a round locks
the side; later bets that round increase the same side; the lock resets per round.
Entry ids are 1-based (Tài=1, Xỉu=2), never 0. Plus prior Tai Xiu work already on
staging.

## Deploy approach

**Targeted recreate** (NOT a full `docker compose down`). The DNS override on the
`bot-manager` service and the co-located observability stack (Grafana / Prometheus /
Promtail / Loki / mongo) had to be preserved, so only `bot-manager` was recreated via
`docker compose up -d bot-manager`. The targeted recreate picked up the new image on
the first try — no fallback to a full down/up was needed.

## Pre-flight

- `git status` working tree: only untracked files (docs, build artifacts); no modified
  tracked files. HEAD = `be961a2` on `main`. — OK to deploy.
- DNS override present before deploy (`grep -n dns docker-compose.yml`):
  line 26 `dns:` → `- "1.1.1.1"` / `- "8.8.8.8"`. — PRESENT, not touched.
- Pre-deploy stack: bot-manager + grafana + prometheus + promtail + mongo + loki all Up.

## Build

- `mvn clean install`: PASS (~5 min) — `target/Bot-1.0.jar` (61,389,868 bytes)
- Tests: 894 run, 0 failures, 0 errors, 0 skipped (aggregated from surefire reports)
- `docker build --no-cache --platform linux/amd64`: PASS (image `fa22f9e406fd`)
- `docker save -o bot.tar`: PASS (392,801,280 bytes)

## Ship

- `sftp put bot.tar`: PASS — remote size 392,801,280 bytes (matches local exactly)

## Deploy

- `docker compose down`: NOT RUN (intentional — targeted recreate to protect
  observability stack + DNS override)
- `docker image rm`: NOT RUN (load auto-renamed the old image to empty tag)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest, old image renamed)
- `HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d bot-manager`: PASS
  - bot-java-bot-manager-1 recreated (CreatedAt 2026-06-26 15:52:48 +07), running new
    image `fa22f9e406fd` (matches `vingame-bot:latest`).
  - grafana / prometheus / promtail / mongo / loki: all still **Up 46 hours** — never
    went down.

## DNS override

- Present before deploy (line 26). Compose file never regenerated (mode=bot).
- Post-deploy resolution inside container:
  `docker exec bot-java-bot-manager-1 getent hosts tipclubgw-sock.stgame.win`
  → resolves (`2a06:98c1:3105::ac40:974a`, `2606:4700:440c::6812:24b6`).
- **PRESERVED and working.**

## Smoke test

- `docker ps` bot-manager healthy: PASS — `Up (healthy)` after start_period
- Spring Boot ready log: PASS — `Started Starter in 7.81 seconds (process running for 9.024)`
- Observability stack still UP: PASS — grafana/prometheus/promtail/mongo/loki all Up 46h
- DNS override resolves: PASS (see above)

## Bot connection state

- `CONNECTION_AUTHENTICATED` events (full log): 142, including the Tai Xiu group
  (e.g. `txb0tt3st19/11/4/9/23: STARTED → CONNECTION_AUTHENTICATED`).
- `UnknownHostException` count (full log): **0**.
- Bots are mid-session: actively sending bets and receiving game messages.
- **All bots reached CONNECTION_AUTHENTICATED; 0 DNS failures.**

## Plan verification — AD-13 live check

A Tai Xiu group `TaiXiuStagingVerify` (`66cfc12c-8c5e-4cb1-b5c1-6f7da59d3136`, 30 bots)
was actively betting live rounds during the deploy, so AD-13 was verified live (not just
by the 894 unit tests).

### Step 1: eids are 1-based (never 0)
Command: tally `option=` across all captured Tai Xiu `sending bet` lines.
Expected: only option=1 and option=2; never option=0.
Actual: 28× `option=1`, 20× `option=2`, 0× `option=0`.
Result: PASS

### Step 2: each bot bets only ONE side within a single round
Command: for round sid=2673071, list distinct `option` per bot.
Expected: every bot has exactly one distinct option for the round.
Actual: all 30 bots present (txb0tt3st1..30), each with exactly one side
(mix of 1 and 2). Violation check: NONE — every bot bet exactly one side.
Result: PASS

### Step 3: no cross-round violations
Command: scan every captured (sid, bot) pair for >1 distinct side.
Expected: no bot bets both sides in any single sid.
Actual: NONE across all captured rounds — single-entry lock holds.
Result: PASS

### Step 4: lock actively coerces conflicting bets
Command: observe `single-entry lock — remapping bet entry X -> Y` logs.
Expected: when the strategy picks the non-locked side, the bot remaps to the locked side.
Actual: observed e.g. `txb0tt3st19: remapping bet entry 2 -> 1`,
`txb0tt3st23: remapping bet entry 1 -> 2`, `txb0tt3st11: remapping bet entry 2 -> 1`.
Result: PASS

## Verdict

**PASS**

- Deploy approach: targeted recreate (bot-manager only); observability stack untouched.
- DNS override: preserved and resolving.
- Smoke: PASS. Observability stack: all UP throughout.
- Bots: all CONNECTION_AUTHENTICATED, 0 UnknownHostException.
- AD-13: unit-verified (894 tests) AND live-confirmed on running group
  `TaiXiuStagingVerify` — 1-based eids, single side per bot per round, lock remapping
  observed.
