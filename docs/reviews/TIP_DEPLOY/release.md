# Release — TIP_DEPLOY

Mode: bot
Branch: feat/observability-metrics
Commit: 1af2c47 (`fix(tip): add ip + apVer to TipLoginRequest`)
Image: vingame-bot:latest — sha256:059ce7edd75bbd700d9d3191b2aa278657faae42790c7eecd7d30a5232903031
Built: 2026-06-09T13:29Z (local)
Date: 2026-06-09T13:31Z (Bot-1 host clock — Spring Boot wallclock 09:30:40 in container UTC offset)

## Context

Redeploy of `feat/observability-metrics` HEAD (`1af2c47`) to Bot-1 staging, requested by user via "redeploy via releaser". This release ships the TIP login hotfix (`fix(tip): add ip + apVer to TipLoginRequest`) on top of the previously-deployed `9fd623a` TIP wiring. Prior commits on this branch are also shipped: jackpot wiring on Bom/B52/Nohu (`85f1744`), `/api/v1/game/types` endpoint (`c88fefe`), OBSERVABILITY Phase A.5 cleanup (`0a4f681`).

Working tree had two dangling, doc-only changes (`docs/plans/OBSERVABILITY.md`, `docs/reviews/OBSERVABILITY/release.md`) — verified non-source via `git diff --stat`; not part of the bot image. Deploy proceeded.

Per release task scope: basic liveness smoke only. User will drive Tip-specific verification themselves through staging UI/API. No `/api/v1/game/types` exercise, no Tip bot start, no resolver validation.

## Build

- `mvn clean install`: PASS (~16s wall; JAR `target/Bot-1.0.jar` = 60,660,464 bytes)
- `docker build --no-cache --platform linux/amd64`: PASS (sha256:059ce7edd75bbd70…, ~11s)
- `docker save -o bot.tar`: PASS (392,156,672 bytes)

## Ship

- `sftp Bot-1:/home/sgame/bot-java <<< "put bot.tar"`: PASS (~58s)

(mode=bot — `infra-images.tar.gz` not shipped per task instructions.)

## Deploy

- `docker compose down`: PASS (loki, mongo, promtail, bot-manager, prometheus, grafana, network all removed)
- `docker image rm vingame-bot:latest`: PASS (prior image sha256:15f3ae37b836… untagged + 11 layers deleted)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: PASS — loki, mongo, promtail, bot-manager, prometheus, grafana all created and started; mongo reported healthy before bot-manager start

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up 32 seconds (healthy)`
- Spring Boot ready log: PASS — `09:30:40.548 [main] INFO Starter - Started Starter in 3.881 seconds (process running for 4.889)`
- Auto-start log: PASS — `09:30:39.836 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 0 bot groups running`
- `/actuator/health` (curled from inside Bot-1, port 8080): PASS — HTTP 200, body `{"status":"UP","components":{"diskSpace":{"status":"UP",…},"mongo":{"status":"UP","details":{"maxWireVersion":25}},"ping":{"status":"UP"},"ssl":{"status":"UP",…}}}`
- Image identity on Bot-1: PASS — `docker images vingame-bot:latest` returns `059ce7edd75b` matching local build SHA.

## Plan verification

No `docs/plans/TIP_DEPLOY.md § Verification` section exists for this deploy — release task explicitly states the user will run Tip-specific verification themselves through the staging UI/API. Releaser intentionally did not exercise `/api/v1/game/types`, did not start a Tip bot, and did not validate the new resolver wiring or the `ip`/`apVer` fields added in `1af2c47`.

## Verdict

PASS

Container came up healthy on first attempt. Spring Boot reports startup complete in 3.881s with 0 bot groups running (none configured to auto-start), MongoDB reachable (`maxWireVersion: 25`), actuator health `UP` across all components (diskSpace, mongo, ping, ssl). Image SHA on Bot-1 matches the locally-built image. Ready for user-driven Tip product verification.
