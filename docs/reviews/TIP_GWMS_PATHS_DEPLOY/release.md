# Release — TIP_GWMS_PATHS_DEPLOY

Mode: bot
Branch: feat/observability-metrics
Image: vingame-bot:latest (built at 2026-06-09T14:37:07+04:00)
Date: 2026-06-09T14:38:43+04:00 (container start)

HEAD commit: `1771c70 fix(auth): switch P_116 and catch-all branch to /gwms/v1/bot/* paths`

Previously deployed commit: `93dc78b`

Change: Tip product (P_116) and the 7-product catch-all auth branch now hit
`/gwms/v1/bot/login.aspx`, `/gwms/v1/bot/register.aspx`,
`/gwms/v1/bot/update-fullname.aspx` (matching P_097/P_098). Expected to bypass
the per-IP throttle on the `/user/*` endpoints.

## Build

- `mvn clean install`: PASS (13.661s; 375 tests, 0 failures)
- `docker build --no-cache --platform linux/amd64`: PASS (sha256:6972b80ad9be)
- `docker save -o bot.tar`: PASS (392,158,208 bytes)

## Ship

- `sftp put bot.tar`: PASS

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (old image deleted: 0c3db79307ab)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers started: loki, mongo, promtail, bot-manager, prometheus, grafana)

## Smoke test

- `docker ps` shows healthy: PASS (`bot-java-bot-manager-1   Up 33 seconds (healthy)`)
- Spring Boot ready log: PASS (`Started Starter in 3.655 seconds (process running for 4.708)`)
- Auto-start log: PASS (`Bot Manager startup complete. 0 bot groups running`)

## Plan verification

User indicated manual testing; no plan-driven verification commands to execute.
Per-task acceptable smoke checks:

### Step 1: `docker compose ps` shows bot-manager running
Command: `ssh Bot-1 'docker ps --filter name=bot-manager --format "table {{.Names}}\t{{.Status}}"'`
Expected: bot-manager container Up and healthy
Actual: `bot-java-bot-manager-1   Up 33 seconds (healthy)`
Result: PASS

### Step 2: `/actuator/health` returns UP
Command: `ssh Bot-1 'curl -s http://localhost:8080/actuator/health'`
Expected: JSON with `"status":"UP"`
Actual: `{"status":"UP","components":{"diskSpace":{"status":"UP",...},"mongo":{"status":"UP","details":{"maxWireVersion":25}},"ping":{"status":"UP"},"ssl":{"status":"UP",...}}}`
Result: PASS

## Verdict

PASS
