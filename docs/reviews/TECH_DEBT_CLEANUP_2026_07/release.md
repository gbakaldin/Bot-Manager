# Release — TECH_DEBT_CLEANUP_2026_07

Mode: bot
Branch: chore/tech-debt-cleanup-2026-07
Image: vingame-bot:latest (built at 2026-07-10T18:00 +04:00)
Date: 2026-07-10T18:10:00+04:00

Staging note: Bot-1 runs bot-manager AND the observability stack (Grafana /
Prometheus / Loki / Promtail / Mongo) in ONE Compose project. `docker compose
down` bounced the whole project; observability was re-verified post-deploy (see
Smoke test).

## Build

- `mvn clean install` (JAVA_HOME=openjdk-21.0.2): PASS (~27s; 1478 tests, 0 failures/errors, BUILD SUCCESS)
- `docker build --no-cache --platform linux/amd64`: PASS
- `docker save -o bot.tar`: PASS (395,840,000 bytes)

Note: Docker Desktop was not running at start of pipeline (stale socket). Started
via `open -a Docker`, waited for daemon readiness, then proceeded. No impact on
artifact.

## Ship

- `sftp put bot.tar`: PASS (remote size 395,840,000 bytes — exact match to local)

## Deploy

- `docker compose down`: PASS (all 6 containers stopped + removed, network removed)
- `docker image rm vingame-bot:latest`: PASS (prior 2-day-old image untagged + deleted)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers created + started; mongo waited-healthy before bot-manager)

## Smoke test

- `docker ps` shows bot-manager healthy: PASS ("Up About a minute (healthy)")
- Spring Boot ready log: PASS ("Started Starter in 15.182 seconds")
- Auto-start log: PASS ("Bot Manager startup complete. 13 bot groups running")

### Observability stack re-verified post-deploy (single-Compose caveat)

- bot-java-grafana-1:    Up — `GET /api/health` HTTP 200 — PASS
- bot-java-prometheus-1: Up — `GET /-/healthy` HTTP 200 — PASS
- bot-java-loki-1:       Up — `GET /ready` HTTP 200 (503 on first probe during warm-up, 200 on retry) — PASS
- bot-java-promtail-1:   Up — PASS
- bot-java-mongo-1:      Up (healthy) — PASS

All 6 containers of the shared Compose project came back healthy.

## Plan verification

Section: `docs/plans/TECH_DEBT_CLEANUP_2026_07.md` § On-server verification.
Items 2/3/4/6 have no on-server check beyond the universal smoke test (per plan).

Note: bot-manager listens internally on 8085, published on host port 8080
(`0.0.0.0:8080->8085/tcp`); all curls issued to `localhost:8080`.

### Step 1 (Item 1): round-trip the three previously-dropped fields
Command:
- POST `/api/v1/environment/` with `{name:"td-cleanup-verify", type:STAGING, brandCode:G2, productCode:{code:097,...}, headers:{Host,Origin,...}, useJwtAuth:true, periodicLogoutEnabled:true, periodicLogoutIntervalMinutes:45}`
- GET `/api/v1/environment/<id>`
- DELETE `/api/v1/environment/<id>`
Expected: POST 200; GET 200 with `{useJwtAuth:true, periodicLogoutEnabled:true, periodicLogoutIntervalMinutes:45}` (pre-fix false/null/null); DELETE 200; GET-after-delete 404.
Actual: POST 200 (id 12fa6e4a-c515-405e-89c0-7c87d2e9f1ed); GET 200 with `useJwtAuth=true, periodicLogoutEnabled=true, periodicLogoutIntervalMinutes=45`; DELETE 200; GET-after-delete 404.
Result: PASS

(Incidental: the pre-req 400 `{"type":"Bad request","msg":"WebSocket headers must include both 'Host' and 'Origin'..."}` and the post-delete 404 both confirm item 4's `RestExceptionHandler` `{type,msg}` envelope + ResourceNotFound→404 mapping are intact.)

### Step 2 (Item 5): partial-name (substring) filter
Command (env):
- POST `/api/v1/environment/filter/` with `{"name":"Staging"}`
Expected: HTTP 200, length >= 1 (pre-fix anchored/exact returned 0).
Actual: HTTP 200, length 5 — [097 Staging, 118 Staging, 098 Staging, 116 Staging, 114 Staging].
Result: PASS

Command (bot-group):
- POST `/api/v1/bot-group/<envId>/filter` (env 116 Staging, ad4e7948-...) with `{"name":"Slot group"}`
Expected: HTTP 200, length >= 1 for a proper substring (pre-fix 0).
Actual: HTTP 200, length 5 — [Slot group 117, 120, 119, 118, 224]. "Slot group" is a proper substring of each; exact match would return 0.
Result: PASS

## Verdict

PASS

## Logs (only if FAIL)

N/A — no failures.
