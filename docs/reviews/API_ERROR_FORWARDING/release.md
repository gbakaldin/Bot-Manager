# Release — API_ERROR_FORWARDING

Mode: bot
Branch: feat/api-error-forwarding
HEAD: ef08f2547536c20c5440b82dd785d5c8b82df493
Image: vingame-bot:latest (built 2026-06-11 14:44 local)
Date: 2026-06-11T10:48Z (Bot-1 local time)

## What's shipping

4 commits, mixed scope:

- `f7508b5` feat(error-handling): add exception hierarchy + RestExceptionHandler advice
- `25560a5` refactor(controllers): delete ~36 try/catch ladders across 4 controllers, retype service throws
- `164b52b` test(api-error-forwarding): QA — advice mapping + classifier arms + per-controller body shape
- `ef08f25` fix(api-error-forwarding): reviewer findings — 500 sanitization, Spring 4xx default preservation, start() cause, cleanup-catch throwable

User-visible behavior changes:

- Errors return structured `{type, msg}` body instead of mostly-empty 4xx/5xx
- `HttpRequestMethodNotSupportedException` and `HttpMediaTypeNotSupportedException` now correctly return 405/415 instead of 500

No schema changes, no new dependencies.

## Pre-deploy state

Verified on Bot-1 before `docker compose down`:

- `b1e80470-b39d-4609-ba44-0f58ca1d5ad5` — `116 Demo group`, targetStatus=ACTIVE, botCount=30
- `40fa3749-8c36-4cd6-9943-f86e5ed287be` — `XD game test`,    targetStatus=ACTIVE, botCount=20

Matches expectation. Working tree carried the expected docs noise (`.gitignore`, `CLAUDE.md`, `docs/plans/OBSERVABILITY.md`, `docs/reviews/OBSERVABILITY/release.md`) — non-blocking, left as-is per user instruction.

## Build

- `mvn clean install`: PASS — `Tests run: 431, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`
- `docker build --no-cache --platform linux/amd64`: PASS — image sha256:4f7350e7b51c
- `docker save -o bot.tar`: PASS — 392,165,376 bytes

## Ship

- `sftp put bot.tar`: PASS — landed at `/home/sgame/bot-java/bot.tar` (392,165,376 bytes)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (prior image removed)
- `docker load -i bot.tar`: PASS — `Loaded image: vingame-bot:latest`
- `docker compose up -d`: PASS — all 6 containers (loki, mongo, promtail, bot-manager, prometheus, grafana) created and started

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 | Up About a minute (healthy)`
- Spring Boot ready log: PASS — `10:46:48.452 [main] INFO Starter - Started Starter in 30.926 seconds (process running for 31.97)`
- Auto-start log: PASS — both expected groups picked up:
  - `10:46:20.483 Auto-starting bot group: 116 Demo group (ID: b1e80470-b39d-4609-ba44-0f58ca1d5ad5)`
  - `10:46:47.158 Auto-starting bot group: XD game test (ID: 40fa3749-8c36-4cd6-9943-f86e5ed287be)`

## Post-deploy verification

### Both target groups recovered

`GET /api/v1/bot-group/`:

- `b1e80470 | 116 Demo group | targetStatus=ACTIVE | botCount=30 | lastStartedAt=2026-06-11T10:46:47.134`
- `40fa3749 | XD game test   | targetStatus=ACTIVE | botCount=20 | lastStartedAt=2026-06-11T10:46:47.572`

Live log traffic confirms both groups are operating normally — Bau Cua bots receiving StartGame frames (cmd:8005, sid:2779577), Xoc Dia bots placing bets (cmd:3002 on sid:3058578) with both SENT and RECEIVED frames alternating.

Result: PASS

### Optional spot check — Spring-defaults fix (405)

Command: `curl -X GET http://localhost:8080/api/v1/bot-group/some-id/start` (endpoint is POST-only)

Expected: HTTP 405 with body `{type:"Method not allowed", msg:...}`

Actual:
```
status=405
{"type":"Method not allowed","msg":"Request method 'GET' is not supported"}
```

Result: PASS — Spring servlet exceptions are correctly mapped through the new advice (preserves Spring's status code) and the body matches the new structured format.

## Verdict

SHIPPED

Pre-deploy state, build, ship, deploy, smoke, and both post-deploy checks (group recovery + structured-error spot check) all passed. The exception-hierarchy refactor is live on Bot-1, both running bot groups are back online and actively betting, and the user-facing error shape change is confirmed working end-to-end.
