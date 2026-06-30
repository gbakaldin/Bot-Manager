# Release — LOGGING_AUDIT

Mode: bot
Branch: feat/logging-audit
Image: vingame-bot:latest (built at 2026-06-16 ~15:17 local)
Date: 2026-06-16T11:21:00Z (deploy timestamp on Bot-1)
HEAD: 8037622f0ba28a7ebda9b3a21c65de747a2f219d

## Shipping

3 commits (no behavioral changes — log levels + new log lines + CLAUDE.md doc):
- `68ad8e2` refactor(logging): demote per-bot lifecycle and HTTP-dump lines to DEBUG (32 demotions across Bot, BotGroupBehaviorService, ApiGatewayClient, GameMsClient)
- `2149061` feat(logging): add curated lifecycle / boundary log lines (4 new lines)
- `8037622` fix: reviewer findings (GameMsClient deposit non-200 WARN, periodic-logout context INFO, CLAUDE.md Logging Guidelines)

## Pre-deploy state

- Container: `bot-java-bot-manager-1` — `Up 5 days (healthy)` (image sha256:0c3db793…)
- Active bot groups (from `/api/v1/bot-group/`):
  - `b1e80470…` — `116 Demo group` — ACTIVE — 30 bots
  - `40fa3749…` — `XD game test` — ACTIVE — 20 bots
  - `4d7f6ac9…` — `Fruit shop Bots` — ACTIVE — 40 bots
- `5a1cc162…` `Auth test 22` — DEAD (pre-existing, not in scope)

## Build

- `mvn clean install`: PASS (BUILD SUCCESS, Tests run: 445, Failures: 0, Errors: 0, Skipped: 0)
- `docker build --no-cache --platform linux/amd64`: PASS (sha256:2f829ea9b285…)
- `docker save -o bot.tar`: PASS (392,165,888 bytes)

## Ship

- `sftp put bot.tar`: PASS (392,165,888 bytes verified on Bot-1)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (deleted old sha256:0c3db793…)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers created and started: bot-manager, mongo, loki, promtail, prometheus, grafana)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 Up 39 seconds (healthy)`
- Spring Boot ready log: PASS — `11:21:10.956 [main] INFO Starter - Started Starter in 9.436 seconds (process running for 10.578)`
- Auto-start log: PASS — three groups auto-started:
  - `11:21:05.829 INFO BotGroupBehaviorService - Auto-starting bot group: 116 Demo group`
  - `11:21:07.784 INFO BotGroupBehaviorService - Auto-starting bot group: XD game test`
  - `11:21:08.142 INFO BotGroupBehaviorService - Auto-starting bot group: Fruit shop Bots`
  - `11:21:08.949 INFO BotGroupBehaviorService - Bot Manager startup complete. 3 bot groups running`

## Post-deploy verification

- All three groups recovered to `targetStatus=ACTIVE` (queried `/api/v1/bot-group/` after deploy)
- Live message traffic confirmed (OutputPrinter SENT/RECEIVED across all 3 groups, sids advancing — `2794149` for 116 Demo, `3069249` for XD, `1930855` for Fruit Shop)
- No `Cannot send message, not connected` WARNs
- No `markAsDead` WARNs
- `Auth test 22` still DEAD — same as pre-deploy, unrelated

## Optional Actuator spot check

Performed: flipped `com.vingame.bot` logger from DEBUG → INFO at 11:23:07Z, captured 30s, flipped back to DEBUG at 11:23:45Z.

- `GET /actuator/loggers/com.vingame.bot` initial: `{"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"}` ✓ matches production pinned state (log4j2.properties:8)
- `POST {"configuredLevel":"INFO"}` → HTTP 204; verify GET returned `INFO/INFO` ✓
- After 30s: `POST {"configuredLevel":"DEBUG"}` → HTTP 204; verify GET returned `DEBUG/DEBUG` ✓

Observations during the INFO window (11:23:07Z – 11:23:39Z, 4077 log lines total):
- **Non-`OutputPrinter` lifecycle lines: 0** — steady-state groups, all bots authenticated and trading. Demoted-to-DEBUG lines (per-bot lifecycle, HTTP-dump, periodic-logout-per-bot) are silent at INFO, as the audit intended.
- **`OutputPrinter` per-message SENT/RECEIVED INFO traces: ~4077** — out of scope for this audit (Phase 2 audit explicitly covered lifecycle and HTTP-dump lines, not wire traces). `OutputPrinter.defaultOutputPrinter` uses `log.info(...)` and continues to log at INFO. This is **not a regression** introduced by this branch; behaviour matches main.

Lifecycle reconstructibility check (against the earlier startup window 11:21:00Z–11:21:30Z, captured before the buffer rotated):
- Group-level lifecycle lines present at INFO: `Auto-starting bot group ...`, `Creating N bots for group ... with parallel execution`, `Successfully created shared clients for environment ...`, `Authenticating user ... at ...`, `User ...: Agency token: ... | auth token ...`, `Bot Manager startup complete. N bot groups running`, `Started Starter in 9.436 seconds`, `Tomcat started on port 8085`.
- Group-level operator can fully reconstruct what happened during startup at INFO. ✓

Followup (not in scope for this release): if the operator wants to flip `com.vingame.bot` to INFO permanently, `OutputPrinter` should also be demoted (or its own logger pinned to DEBUG via `log4j2.properties`) to avoid drowning at the wire-trace rate (~136 lines/sec across 3 groups = ~12M lines/day). The audit's stated value ("a future flip from DEBUG → INFO becomes meaningful") is currently bottlenecked by this one class.

## Plan verification

Plan `docs/plans/LOGGING_AUDIT.md` (Phase 2) defines no machine-checkable verification section — the deploy notes specified a light verification (smoke + groups recover + optional Actuator spot check). All items above passed.

## Verdict

SHIPPED

Notes for follow-up:
1. `OutputPrinter` SENT/RECEIVED INFO traces remain — explicit non-goal of Phase 2 but is the rate-limiting factor for a future "flip to INFO" operator workflow. File a Phase 3 ticket if the operator wants to enable that workflow.
2. No rollback required. Production logger pinned at DEBUG (log4j2.properties:8) so default-state behaviour is unchanged.
