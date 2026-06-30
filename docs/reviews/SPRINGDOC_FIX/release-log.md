# Release — SPRINGDOC_FIX

Mode: bot
Branch: fix/springdoc-upgrade
Commit: b195dde fix(swagger): bump springdoc-openapi 2.3.0 -> 2.7.0
Image: vingame-bot:latest (built 2026-06-17 14:42 local)
Date: 2026-06-17T10:46:00Z (Bot-1) / 14:46 local

## Build

- `mvn clean install`: PASS (20.6s wall, 647/647 tests pass — verified via `target/surefire-reports/*.txt` aggregation: `Total: 647, Failed: 0, Errors: 0, Skipped: 0`)
- `docker build --no-cache --platform linux/amd64`: PASS (12.5s, image sha256:8f07843e5797)
- `docker save -o bot.tar`: PASS (3.1s, 391,342,080 bytes)

## Ship

- `sftp Bot-1:/home/sgame/bot-java put bot.tar`: PASS (71s, 391 MB)

## Deploy

- `docker compose down`: PASS (all containers stopped)
- `docker image rm vingame-bot:latest`: PASS (prior image layers deleted)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: PASS for bot-manager. NOTE: `bot-java-prometheus-1` failed to start because port 9090 is already in use on Bot-1. This is a pre-existing infra issue unrelated to this fix — bot-manager itself started cleanly. mongo, loki, promtail, bot-manager, grafana all created.

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up About a minute (healthy)` ~75s after `docker compose up`.
- Spring Boot ready log: PASS — `10:44:24.027 [main] INFO Starter - Started Starter in 12.866 seconds (process running for 14.282)` and `10:44:23.975 [main] INFO TomcatWebServer - Tomcat started on port 8085 (http) with context path '/'`.
- Auto-start log: PASS — `10:44:22.133 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 3 bot groups running`. Bots actively betting (observed `[SENT]`/`[RECEIVED]` pairs for sid 2796977 on Bau Cua group `b1e80470-...`).

## Plan verification

### Step 1: `/v3/api-docs` returns HTTP 200 (was 500 pre-fix)
Command: `curl -fsS -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/v3/api-docs`
Expected: `200`
Actual: `HTTP 200`
Result: PASS

### Step 2: Response body is valid OpenAPI JSON with `openapi` field and `paths` object
Command: `curl http://127.0.0.1:8080/v3/api-docs` then parsed with `python3 -c "import json; ..."`
Expected: `"openapi":"3..."`, populated `paths`, populated `components.schemas`.
Actual:
- `openapi: 3.0.1`
- `paths count: 29`
- `components.schemas count: 13`
- sample paths: `/api/v1/game/{brandCode}/{productCode}`, `/api/v1/game/filter/`, `/api/v1/environment/filter/`, `/api/v1/environment/`, `/api/v1/bot-group/{id}/stop`
- raw head: `{"openapi":"3.0.1","info":{"title":"Bot API","description":"API documentation for Bot service","version":"1.0"},"servers":[{"url":"http://127.0.0.1:8080","description":"Generated server url"}],"tags":[{"name":"Actuator","description":"Monitor and interact",...`
Result: PASS

### Step 3: `/swagger-ui/index.html` still returns HTTP 200
Command: `curl -fsS -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/swagger-ui/index.html`
Expected: `200`
Actual: `HTTP 200`
Result: PASS

### Step 4: `/actuator/health` still `{"status":"UP"}` and container healthy
Command: `curl -fsS http://127.0.0.1:8080/actuator/health` plus `docker ps --filter name=bot-manager`
Expected: `{"status":"UP"}` and container `(healthy)`.
Actual:
- `{"status":"UP","components":{"diskSpace":{"status":"UP",...},"mongo":{"status":"UP","details":{"maxWireVersion":25}},"ping":{"status":"UP"},"ssl":{"status":"UP",...}}}`
- `bot-java-bot-manager-1   Up About a minute (healthy)`
Result: PASS

### Step 5: Zero new `NoSuchMethodError` lines in container logs over the last 2 minutes
Command: `docker logs --since 2m bot-java-bot-manager-1 2>&1 | grep -c "NoSuchMethodError"` and the same grep without `-c` for visibility.
Expected: `0`
Actual: `0` (and the un-counted grep produced no output)
Result: PASS

### Step 6: Business endpoints — `POST /api/v1/game/filter/ {}` and `GET /api/v1/bot-group/` both 200
Commands:
- `curl -fsS -o /tmp/gf.json -w "%{http_code}\n" -X POST -H "Content-Type: application/json" -d "{}" http://127.0.0.1:8080/api/v1/game/filter/`
- `curl -fsS -o /tmp/bg.json -w "%{http_code}\n" http://127.0.0.1:8080/api/v1/bot-group/`

Expected: both `200`, both return real data.
Actual:
- `POST /api/v1/game/filter/`: `HTTP 200`, body begins `[{"id":"3cda38f9-2c3d-465f-a52a-18ce83207761","brandCode":"G2","productCode":{"code":"097","name":"BOM","appId":"bc114097",...`
- `GET /api/v1/bot-group/`: `HTTP 200`, body begins `[{"id":"111","name":"Test group 097","environmentId":"3cda38f9-2c3d-465f-a52a-18ce83207768","namePrefix":"bcB0Ttest",...`
Result: PASS

## Verdict

PASS — SpringDoc 2.7.0 is live on Bot-1. `/v3/api-docs` now returns HTTP 200 with a valid OpenAPI 3.0.1 document (29 paths, 13 component schemas), confirming the `ControllerAdviceBean(Object)` `NoSuchMethodError` regression against Spring 6.2 is resolved. Container healthy, Spring Boot started in ~13s, all 3 bot groups auto-resumed, business endpoints unaffected, zero `NoSuchMethodError` in logs.

## Operational notes (not blockers)

- `bot-java-prometheus-1` failed to bind 9090 during `docker compose up -d` (`bind: address already in use`). This is unrelated to the SpringDoc fix — likely another process / leftover container on Bot-1 is squatting 9090. Worth a follow-up but does not affect the bot-manager release.
