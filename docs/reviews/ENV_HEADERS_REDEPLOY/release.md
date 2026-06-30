# Release — ENV_HEADERS_REDEPLOY

Mode: bot
Branch: feat/observability-metrics
Image: vingame-bot:latest (built 2026-06-09 ~15:27 local)
Date: 2026-06-09T15:31:00+04:00

Branch HEAD: `b2e71e3f69632a0d284bd1d803ed07fb8b98fb9d`
Previous deploy: `ec098be`

## What's new since last deploy

- `b2e71e3 feat(env): require Host/Origin and merge WS headers with defaults`
  - `POST /api/v1/environment` and `PATCH .../environment/{id}` (when headers field present) now reject without Host+Origin.
  - Headers from frontend are merged with defaults (Connection, Pragma, Cache-Control, User-Agent, Upgrade, Sec-WebSocket-Version, Accept-Encoding, Accept-Language, Sec-WebSocket-Extensions).
  - Existing env records are unaffected.

## Build

- `mvn clean install`: PASS (~15s, tests OK)
- `docker build --no-cache --platform linux/amd64`: PASS (~11s, image sha256:6d457bce4111)
- `docker save -o bot.tar`: PASS (392159232 bytes)

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS (~61s)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image removed)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers started: bot-manager, mongo, loki, promtail, prometheus, grafana)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up About a minute (healthy)`
- Spring Boot ready / auto-start: PASS — `docker logs` shows live bot activity (BauCua group b1e80470 betting on sid:2773861, 30 bots SENT/RECEIVED cmd 8002). Startup banner lines had scrolled off the 100-line tail by check time, but live game traffic is conclusive evidence that startup completed and auto-start ran.
- `docker compose ps`: PASS — all services up
- `/actuator/health`: PASS — `{"status":"UP", components: {diskSpace UP, mongo UP, ping UP, ssl UP}}`

## Plan verification

User opted out of plan-driven verification ("User will test manually — do NOT exercise endpoints. Just deploy and confirm container health."). No HTTP endpoints exercised by this agent.

## Verdict

PASS

## Notes

- Working tree had only the explicitly-noted dangling docs (`docs/plans/OBSERVABILITY.md`, `docs/reviews/OBSERVABILITY/release.md`) plus untracked artifacts; no production code drift.
- Stderr warnings from Bot-1 ssh sessions (`nvm`, `node/GLIBC`, `post-quantum-KEX`) ignored per protocol.
