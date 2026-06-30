# Release — OBSERVABILITY_AUTH_FIX (register-payload polish)

Mode: bot
Branch: feat/observability-metrics
Image: vingame-bot:latest (built at 2026-06-09T14:57Z local)
Date: 2026-06-09T14:59Z local

## Scope

Redeploy current head of `feat/observability-metrics` to Bot-1 staging. Two new commits since the previous deploy (`1771c70`):

- `195c215 fix(auth): derive appId from ProductCode for known products` — appId now resolved from `ProductCode.getAppId()` (bc114097 / bc114098 / bc115116 for P_097 / P_098 / P_116) with fallback to `Environment.appId`. Fixes register payloads that were missing `app_id` / `source` for Tip.
- `645f46e fix(auth): use browser=WEB on register payload` — register payload now sends `"browser":"WEB"` to match the Bot-collection.http reference; login keeps `"browser":"chrome"`.

Branch HEAD at deploy time: `645f46e`.

## Build

- `mvn clean install`: PASS (~15s)
- `docker build --no-cache --platform linux/amd64`: PASS (~13s)
- `docker save -o bot.tar`: PASS (392,158,208 bytes)

## Ship

- `sftp put bot.tar`: PASS (~38s)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image removed)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all containers created and started: mongo, loki, promtail, bot-manager, prometheus, grafana)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up 32 seconds (healthy)`
- Spring Boot ready log: PASS — `Started Starter in 4.101 seconds (process running for 5.13)`
- Auto-start log: PASS — `Bot Manager startup complete. 0 bot groups running`
- `/actuator/health` (port 8085 inside container): PASS — `{"status":"UP", components: diskSpace UP, mongo UP, ping UP, ssl UP}`

## Plan verification

Per user instructions, no plan-driven verification is required for this deploy. User will exercise the auth payload changes manually. Acceptable smoke checks both passed:

- `docker compose ps` shows bot-manager running: PASS
- `/actuator/health` returns UP: PASS

## Verdict

PASS
