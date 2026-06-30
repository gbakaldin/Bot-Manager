# Release — PERIODIC_LOGOUT_DEFAULTS_DEPLOY

Mode: bot
Branch: feat/observability-metrics
Image: vingame-bot:latest (built at 2026-06-09 17:19 local)
Date: 2026-06-09T17:25:00+03:00 (local)

HEAD: `321fc68 feat(env): populate periodic-logout defaults from app config on create`
Previous deploy: `b2e71e3`

## Changes since last deploy

- `321fc68` — EnvironmentService.save reads `bot.periodic-logout.enabled` (default true) and `bot.periodic-logout.interval-minutes` (default 60) from app config and persists them when the frontend doesn't supply values. Runtime fallback in BotGroupBehaviorService remains.

## Build

- `mvn clean install`: PASS (~14s real, tests included via Surefire)
- `docker build --no-cache --platform linux/amd64`: PASS (~11s, image sha256:4806fb9ad30d…)
- `docker save -o bot.tar`: PASS (392,159,744 bytes)

## Ship

- `sftp put bot.tar` to `Bot-1:/home/sgame/bot-java`: PASS (~38s)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image removed)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: PASS (mongo healthy → bot-manager started → prometheus/grafana up)

## Smoke test

- `docker ps` shows bot-manager healthy: PASS — `bot-java-bot-manager-1   Up 33 seconds (healthy)`
- `docker compose ps` shows full stack: PASS — bot-manager, grafana, loki, mongo (healthy), prometheus, promtail all Up
- Spring Boot ready log: PASS — `Started Starter in 8.054 seconds (process running for 9.081)`
- Tomcat startup log: PASS — `Tomcat started on port 8085 (http) with context path '/'`
- Auto-start log: PASS — `Bot Manager startup complete. 2 bot groups running` (116 Demo group, XD game test)
- `/actuator/health` returns UP: PASS

```json
{
  "status": "UP",
  "components": {
    "diskSpace": {"status":"UP","details":{"total":107362627584,"free":82455064576,"threshold":10485760,"path":"/app/.","exists":true}},
    "mongo": {"status":"UP","details":{"maxWireVersion":25}},
    "ping": {"status":"UP"},
    "ssl": {"status":"UP","details":{"validChains":[],"invalidChains":[]}}
  }
}
```

## Plan verification

User testing manually — endpoints NOT exercised by Releaser per deploy request.

## Verdict

PASS
