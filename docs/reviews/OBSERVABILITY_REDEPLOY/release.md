# Release — OBSERVABILITY_REDEPLOY

Mode: bot
Branch: feat/observability-metrics
Image: vingame-bot:latest (built at 2026-06-09 13:59 local)
Date: 2026-06-09T14:01:10+01:00

## Context

Redeploy of `feat/observability-metrics` HEAD to Bot-1 staging.

Branch HEAD: `93dc78b` — `fix(tip): supply x-token on P_116 auth profile`.

New since previous deploy (`1af2c47`):
- `93dc78b fix(tip): supply x-token on P_116 auth profile` — Tip AuthProfile now passes the shared gateway secret `58bc2820612d23c34fe43d0b2c6f7223` as x-token, matching Bom/B52. Fixes Tip auth gateway rate-limiting that caused partial registration cascades.
- `fc5dc15 feat(bot-group): forward upstream registration errors to client` — `POST /api/v1/bot-group/` now returns HTTP 502 with `{type, msg}` JSON body on `IllegalStateException` (registration hard-failure) instead of an empty 500.

Working tree had dangling uncommitted docs (`docs/plans/OBSERVABILITY.md`, `docs/reviews/OBSERVABILITY/release.md`) — explicitly ignored per user instruction.

## Build

- `mvn clean install`: PASS (~5 min, full test suite executed; `target/Bot-1.0.jar` produced, 60,661,909 bytes)
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`: PASS (sha256:5b820ac342ec…)
- `docker save -o bot.tar vingame-bot:latest`: PASS (392,158,208 bytes)

## Ship

- `sftp put bot.tar` → `/home/sgame/bot-java/bot.tar`: PASS (392,158,208 bytes verified on remote)
- (mode=infra) `sftp put infra-images.tar.gz`: N/A — mode=bot

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image `0c3db79307ab…` removed)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: PASS (all services Created → Started, mongo Healthy gate satisfied, bot-manager Started)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 | Up 38 seconds (healthy)`
- Spring Boot ready log: PASS — `Started Starter in 4.395 seconds (process running for 5.413)`
- Auto-start log: PASS — `Bot Manager startup complete. 1 bot groups running`
- `/actuator/health` (port 8085, from inside container): PASS — `{"status":"UP", components: diskSpace=UP, mongo=UP, ping=UP, ssl=UP}`

## Plan verification

N/A — user requested manual verification only. No endpoints exercised, no test bot groups created.

## Verdict

PASS

## Notes

- Same procedure as `docs/reviews/TIP_DEPLOY/release.md` (third deploy this session).
- Docker healthcheck and actuator are on port `8085` (not 8080) per `Dockerfile` HEALTHCHECK and bot-manager management port config.
- All sidecar services (mongo, loki, promtail, prometheus, grafana) brought up cleanly alongside bot-manager.
