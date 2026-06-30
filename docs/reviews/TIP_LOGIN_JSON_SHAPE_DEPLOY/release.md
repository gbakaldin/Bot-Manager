# Release — TIP_LOGIN_JSON_SHAPE_DEPLOY

Mode: bot
Branch: feat/observability-metrics
Image: vingame-bot:latest (built at 2026-06-09 15:05 local)
Date: 2026-06-09T15:08:00+03:00

Deployed commit: `ec098be fix(tip): align TipLoginRequest JSON shape with reference`
Previous deploy: `645f46e`

## Build

- `mvn clean install`: PASS (~15s; tests + package succeeded)
- `docker build --no-cache --platform linux/amd64`: PASS (sha256:295c06e9fd71…)
- `docker save -o bot.tar`: PASS (392,158,208 bytes)

## Ship

- `sftp put bot.tar`: PASS (~50s end-to-end including connection)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image removed)
- `docker load -i bot.tar`: PASS
- `docker compose up -d`: PASS (all 6 containers started: bot-manager, mongo, loki, promtail, prometheus, grafana)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up 41 seconds (healthy)`
- Spring Boot ready log: PASS — `Started Starter in 7.727 seconds (process running for 8.71)`
- Auto-start log: PASS — `Bot Manager startup complete. 1 bot groups running`

## Plan verification

User-specified acceptable checks (no plan/Verification section to execute; manual testing follows):

### Step 1: docker compose ps shows bot-manager running
Command: `ssh Bot-1 'docker compose -f /home/sgame/bot-java/docker-compose.yml ps'`
Expected: bot-manager container Up
Actual: `bot-java-bot-manager-1 ... Up 41 seconds (healthy)`
Result: PASS

### Step 2: /actuator/health returns UP
Command: `ssh Bot-1 'curl -s http://localhost:8080/actuator/health'`
Expected: `{"status":"UP",...}`
Actual: `{"status":"UP","components":{"diskSpace":{"status":"UP",...},"mongo":{"status":"UP","details":{"maxWireVersion":25}},"ping":{"status":"UP"},"ssl":{"status":"UP",...}}}`
Result: PASS

## Verdict

PASS
