# Release — ENUM_DISPLAY_NAMES

Mode: bot
Branch: feat/enum-display-names
Image: vingame-bot:latest (built 2026-06-17 ~16:47 local / 12:47 UTC on Bot-1)
Date: 2026-06-17T16:55+04:00 (local)

## Build

- `mvn clean install`: PASS (18.693 s, 648/648 tests, BUILD SUCCESS)
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`: PASS
  (image sha256:f50ee6414e058d99b83100124af74e074db8f11421eca2aa115f5b8eeed64b23)
- `docker save -o bot.tar vingame-bot:latest`: PASS (391,387,648 bytes / 373 MB)

## Ship

- `sftp put bot.tar`: PASS (374 MB on Bot-1 at `/home/sgame/bot-java/bot.tar`)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image untagged + deleted)
- `docker load -i bot.tar`: PASS ("Loaded image: vingame-bot:latest")
- `docker compose up -d`: PASS (bot-manager, mongo, loki, promtail all up)

Note: the chained ssh block returned a non-zero exit code, but every individual
command in the chain succeeded — `docker compose ps` confirmed all four
services running, and the container went on to become `(healthy)`. Treated as
benign per the standing rule on Bot-1 shell noise.

## Smoke test

- `docker ps` shows `Up 39 seconds (healthy)`: PASS
- Spring Boot ready log: PASS
  `12:49:21.791 [main] INFO  Starter - Started Starter in 14.608 seconds (process running for 16.137)`
- Auto-start log: PASS
  Per-bot `Started bot demob0t1a*` lines from `BotGroupBehaviorService` at
  12:49:17 (immediately after Tomcat came up on port 8085).

## Plan verification

### Step 1: GET /api/v1/game/types returns array of {code, displayName}
Command: `curl -fsS http://127.0.0.1:8080/api/v1/game/types`
Expected: 200, JSON array; includes `{"code":"BETTING_MINI","displayName":"Betting mini"}`
Actual: HTTP 200, body =
```
[{"code":"BETTING_MINI","displayName":"Betting mini"},
 {"code":"SLOT","displayName":"Slot"},
 {"code":"TAI_XIU","displayName":"Tài Xỉu"},
 {"code":"CARD_GAME","displayName":"Card game"},
 {"code":"UP_DOWN","displayName":"Up / Down"}]
```
Result: PASS

### Step 2: GET /api/v1/strategy/ returns RANDOM with description
Command: `curl -fsS http://127.0.0.1:8080/api/v1/strategy/`
Expected: 200, `[{"id":"RANDOM","displayName":"Random","description":"..."}]`
Actual: HTTP 200, body =
```
[{"id":"RANDOM","displayName":"Random",
  "description":"Pure RNG decisions on every tick. Ignores affinity weights and history."}]
```
Result: PASS

### Step 3: GET /actuator/health is UP
Command: `curl -fsS http://127.0.0.1:8080/actuator/health`
Expected: 200, `{"status":"UP"}`
Actual: HTTP 200, top-level `"status":"UP"`; components diskSpace/mongo/ping/ssl all UP.
Result: PASS

### Step 4: POST /api/v1/game/filter/ — each productCode has BOTH name AND displayName
Command: `curl -fsS -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8080/api/v1/game/filter/`
Expected: 200; every game's `productCode` JSON object has both `name` and `displayName`.
Actual: HTTP 200, 7 games returned. Sample productCodes (all include both fields):
- BOM: `{"code":"097","name":"BOM","appId":"bc114097","usernameMaxLength":null,"displayName":"BOM"}`
- NOHU: `{"code":"118","name":"NOHU","appId":null,"usernameMaxLength":null,"displayName":"NOHU"}`
- B52: `{"code":"098","name":"B52","appId":"bc114098","usernameMaxLength":null,"displayName":"B52"}`
- TIP: `{"code":"116","name":"TIP","appId":"bc115116","usernameMaxLength":12,"displayName":"TIP"}`
All 7 games verified by visual inspection of raw body — every productCode block carries both `name` and `displayName`.
Result: PASS

### Step 5: Swagger UI + /v3/api-docs include GameTypeDTO + StrategyInfoDTO
Commands:
- `curl -fsS -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/swagger-ui/index.html` → 200
- `curl -fsS -o /tmp/apidocs.json http://127.0.0.1:8080/v3/api-docs` → 200
- `grep -c "GameTypeDTO" /tmp/apidocs.json` → 1
- `grep -c "StrategyInfoDTO" /tmp/apidocs.json` → 1
Expected: Swagger 200, api-docs 200, both schemas present.
Actual: All four checks match. Schemas present.
Result: PASS

### Step 6: Container healthy and zero new ERROR lines in last 2 minutes
Commands:
- `docker ps --filter name=bot-manager --format "{{.Status}}"` → `Up 2 minutes (healthy)`
- `docker logs --since 2m bot-java-bot-manager-1 2>&1 | grep -E " ERROR "` → empty (zero matches)
Expected: container healthy, zero new ERROR lines.
Actual: Container `(healthy)`. No ERROR lines emitted in the trailing 2-minute window.
Result: PASS

## Verdict

PASS

## Logs

Not applicable — no failed checks.
