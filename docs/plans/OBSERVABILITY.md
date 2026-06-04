# Observability Plan — Grafana Metrics for Bot Manager

Owner: gleb · Target demo: Friday 2026-06-05 (24-48h horizon as of 2026-06-04)

This plan was originally a 5-phase draft written before the agentic pipeline pattern was adopted. It has been re-shaped in place around the same pattern as `docs/plans/LOGGING_PIPELINE_FIX.md`: each phase below is sized for one Dev session, lists files-touched + acceptance criteria + verification commands + independence + demo-blocker status, and a canonical Releaser checklist lives in the `## Verification` section at the bottom. Existing Architecture Decisions are largely intact; refinements and one new decision (MDC-as-tags) are noted explicitly.

---

## Goal

Surface bot-health and game-server-health metrics in Grafana so the Friday demo can answer "what are the bots doing right now?" and "is the game server healthy?" without requiring back-office access. Pair the existing Loki/Promtail log pipeline (now ~97% MDC-labeled after `LOGGING_PIPELINE_FIX`) with a Prometheus + Micrometer metrics pipeline driven from the bot-manager itself, and provision both datasources + dashboards declaratively so a re-deploy never wipes them.

---

## Findings — Current State

### Already wired (verified at HEAD `a9ccb2d`, 2026-06-04)

| Component | Status | Evidence |
|---|---|---|
| Grafana 11.4 | Running in compose | `docker-compose.yml:54-66` |
| Loki 3.3.2 + Promtail 3.3.2 | Running, log aggregation working | `docker-compose.yml:37-52`, `promtail-config.yml` |
| Loki datasource (Grafana) | **Provisioned** in this repo since the last release attempt | `grafana/provisioning/datasources/loki.yml` (4 lines, was created by hand on Bot-1 then committed) |
| Spring Boot Actuator 3.4 | Exposes `health, info, metrics, loggers` on main port `:8085` (Compose maps host `8080` → container `8085`) | `application.properties:28` |
| `BotMdc` MDC keys | `botGroupId`, `botId`, `environmentId`, `gameType`, `botUserName` | `BotMdc.java:17-21` |
| MDC propagation to ~97% of bot log lines | Done as of `LOGGING_PIPELINE_FIX` Phase 3 | `Bot.java:60` (`mdcSnapshot`), `Bot.java:407-477` (`mdcWrap/mdcCall/mdcSupplier/mdcConsumer`), 14 callsites across `Bot.java` and `BettingMiniGameBot.java`, plus `OutputPrinter.java` signature change |
| Logs as host bind-mount (Bot-1) | `./logs:/app/logs` and `./logs:/logs:ro`, container UID via `${HOST_UID}:${HOST_GID}` from `.env` written by `deploy.sh` | `docker-compose.yml:18,28-29,46-49`; `deploy.sh:13-26` |
| `deploy.sh` uses Compose V2 | `docker compose up -d` (not `docker-compose`) | `deploy.sh:28` |
| Per-bot bet counters (in-memory) | `AtomicLong totalBetsPlaced`, `AtomicLong totalBetAmount`, `volatile long lastRoundWinnings` on `Bot.java:64-68`. Mutated only in `Bot.creditBalance(amount)` at `Bot.java:487-491`. `lastRoundWinnings` declared but never written | `Bot.java:64-68,487-491` |
| Watchdog (game-stream silence) | `BettingMiniGameBot.scheduleWatchdog()` / `onWatchdogExpired()` at lines 131-150 | — |
| `BotGroupBehaviorService.runningGroups` (ConcurrentHashMap) | Source of truth for active groups; `runningGroups.size()` is the trivial gauge for "running groups" | `BotGroupBehaviorService.java:112` |
| `BotGroupRuntime.botInstances` (per-group List<Bot>) | Sum gives "managed bots" gauge | `BotGroupRuntime.java:48` |
| Bot status enum | `AUTHENTICATING, AUTHENTICATED, CONNECTING, CONNECTED, AUTHENTICATING_CONNECTION, CONNECTION_AUTHENTICATED, STARTED, RECONNECTING, DEAD` | `BotStatus.java` |
| `transitionStatus()` (sole writer of bot status) | Private method in `Bot.java:260-264`. All status changes go through it. Single hook point for counters and downtime accumulators | `Bot.java:260-264` |
| Group-DEAD entry | Single chokepoint `runtime.markAsDead()` called from `BotGroupBehaviorService.handleBotGroupDeath()` at line 575-588 | `BotGroupBehaviorService.java:575-588`, `BotGroupRuntime.java:158-160` |
| Login / verify / deposit callsites for counters | `ApiGatewayClient.authenticate()` at `ApiGatewayClient.java:118-144`, `ApiGatewayClient.getBalance()` (the verify-token call) at `ApiGatewayClient.java:453-485`, `GameMsClient.deposit(token, amount, Consumer<Boolean>)` at `GameMsClient.java:72-120`, `Bot.deposit()` at `Bot.java:192-211` which already calls into `GameMsClient` with an `mdcConsumer`-wrapped callback | — |

### Not yet wired

- **`micrometer-registry-prometheus` dependency absent.** Confirmed at `pom.xml:78-169` — only Spring Boot starters, MapStruct, Jackson, Netty, websocket-parser. No micrometer-prometheus, no MeterFilter, no MeterRegistry references anywhere in `src/main`.
- **`management.endpoints.web.exposure.include=health,info,metrics,loggers`** at `application.properties:28` — missing `prometheus`.
- **No `prometheus` service in `docker-compose.yml`.** Services today: `mongo`, `bot-manager`, `loki`, `promtail`, `grafana`.
- **Only `loki.yml` is provisioned in Grafana.** `grafana/provisioning/datasources/loki.yml` is the only file; no Prometheus datasource; `grafana/provisioning/dashboards/` does not exist. Confirmed in `release.md` step 5.
- **No Micrometer counters/gauges anywhere in code.**
- **`lastRoundWinnings` is declared but never written.** RTP and money-drain cannot be computed today; bots' `BotGroupHealthDTO` shows `lastRoundWinnings: 0` even after 39 bets each (per `release.md` step 6).
- **Game-server totals (total user bets, total winnings, total players) are not collected.** For betting-mini games, the protocol's `bs` array carries per-position totals (e.g. `BomSubscribeMessage.BetInfoWithTotal.b` at line 99 + `BomEndGameMessage.BetInfo.v` at line 67) — extractable. For slot-type games, totals do not exist server-side per session.
- **`deposit` callsite already wrapped with `mdcConsumer`** — the new counter increment can ride the same callback without further wiring (`Bot.java:197-210`).
- **`BotGroupRuntime.markAsDead()`** is a single line (`this.actualStatus = BotGroupStatus.DEAD`). A stamp-on-enter pattern slots straight in.

### What `LOGGING_PIPELINE_FIX` left us that this plan benefits from

- Every counter increment that fires from a bot-owned thread (message processors, watchdog, countdown, reconnect, OutputPrinter pool, scenario `sendAsync` pool, Netty IO callbacks we wrapped) already has the bot's MDC re-applied. This makes "tag via MDC-aware `MeterFilter`" cheap and correct — see Architecture Decision 10 below.

---

## Per-Metric Readiness

### Bot metrics

| Metric | Source | Status | Notes |
|---|---|---|---|
| Total running bot groups | `BotGroupBehaviorService.runningGroups.size()` | ready (trivial gauge) | Phase 2 |
| Total managed bots | `sum(runtime.botInstances.size())` across `runningGroups.values()` | ready (trivial gauge) | Phase 2 |
| Bots by status | `runtime.botInstances` stream + group by `bot.getStatus()` | ready (gauge with `status` tag) | Phase 2 |
| Messages per second | `BettingMiniGameBot.onSubscribe / onStartGame / onUpdate / onEndGame` (lines 152-191) | ready (counter, tag `cmd=subscribe|startGame|updateBet|endGame`) | Phase 2 |
| Failure rate | `Bot.transitionStatus(DEAD)` (`Bot.java:260-264`, only `transitionStatus(DEAD)` callsite is line 361 inside `performReauth`) | ready (counter) | Phase 2 |
| Restart on failure rate | `Bot.triggerFullReconnect(reason)` at line 296 and the start of `runWsReconnectLoop` at line 309 | ready (counter, tag `reason=watchdog|ws-disconnect|reauth-cycle`) | Phase 2 |
| Auto-deposit frequency | `Bot.deposit()` callback at `Bot.java:197-210` | ready (counter, tag `outcome=success|failure`) | Phase 2 |
| Watchdog firings | `BettingMiniGameBot.onWatchdogExpired()` at line 145 | ready (counter) | Phase 2 |
| WebSocket connection events | `Bot.configureClient`'s `onWsStatusChange` switch at lines 272-278 + `onDisconnect` at line 279 | ready (counter `bot_ws_connections_total{event=connected|disconnected|authenticating}`) | Phase 2 |
| Currently-open WS gauge | `bot.isConnected()` across `runtime.botInstances` | ready (gauge) | Phase 2 |
| Login health | `ApiGatewayClient.authenticate()` at line 118 | ready (counter, tag `outcome=success|failure`) | Phase 2 |
| Verify-token health | `ApiGatewayClient.getBalance()` at line 453 (the verifytoken GET) | ready (counter, tag `outcome=success|failure`) | Phase 2 |
| Bets placed total | `Bot.creditBalance` at line 487-491 | ready (counter) | Phase 2 |
| Bet amount total | same callsite | ready (counter incrementing by `amount`) | Phase 2 |
| Bot DEAD downtime (cumulative seconds) | `Bot.transitionStatus` — stamp on entry to DEAD, accumulate on exit (or on terminal stop). Edge case: bot may terminate while DEAD; close out at `cleanup()` | ready, slightly fiddly | Phase 3 |
| Group DEAD downtime (cumulative seconds) | `BotGroupRuntime.markAsDead()` + `stopAllBots()` close-out | ready | Phase 3 |
| Bot winnings | New per-round balance snapshot + delayed refetch in `onEndGame` (see Architecture Decision 2) | needs new bot logic | Phase 4 |
| RTP per game | `bot_bet_amount_total` and `bot_winnings_total`, grouped by `gameType` in PromQL | derived | Phase 4 |
| Daily money drain | `rate(bot_bet_amount_total)` − `rate(bot_winnings_total)` over 24h, in PromQL | derived | Phase 4 |

### Game-server / real-player share metrics

| Metric | Source | Status | Phase |
|---|---|---|---|
| Bot bet share | bot total ÷ user-reported total (game protocol dependent) | partial coverage; betting-mini protocols only | Phase 5 |
| Bot win share | same | partial | Phase 5 |
| Bot jackpot win share | `iJp`, `tJpV` per-message in `BomEndGameMessage` (lines 21-26) | partial | Phase 5 |
| Bot user share | unique bots ÷ unique players (subscribe message `bs` array carries per-position totals) | partial | Phase 5 |

---

## Architecture Decisions (locked in)

1. **Stack — Prometheus alongside Loki.** Micrometer Prometheus registry on `/actuator/prometheus`. Grafana gets a second datasource. Loki stays for log search. No OpenTelemetry, no alternative. Locked.

2. **Winnings capture — pre-bet snapshot + delayed post-EndGame refetch.** After each `onEndGame`, schedule a short-delayed task on the bot's existing watchdog/countdown-style scheduler to call `apiGatewayClient.getBalance(...)`. Compute `winnings = newBalance − (oldBalanceBeforeRound − betAmountThisRound)`. The pre-round balance snapshot must be captured at `onStartGame` (or at the first `creditBalance` of the round) *before* `expectedCurrentBalance` is decremented. Update `lastRoundWinnings` and increment `bot_winnings_total` by `max(winnings, 0)`. Losing rounds contribute 0 to winnings; bets are the drain source. Cost ~1 verify-token call per bot per round; at 100 bots × 30s rounds → 3.3 req/s. Acceptable; add `bot.winnings.sample-rate` property if load testing pushes this higher (out of scope for demo).

3. **Downtime semantics — DEAD only, STOPPED excluded.** Count bot-DEAD-seconds and group-DEAD-seconds only. Group `STOPPED` is intentional. A group cannot enter `DEAD` without first having been deliberately started, so any DEAD time is a real incident. Closing-out the accumulator: on transition out of DEAD (only `runWsReconnectLoop`'s success path can clear `reconnecting` after a DEAD state was reached, but DEAD → anything is currently a no-op in code — see Implementation Note below), and on `cleanup()` while DEAD. Same shape for groups: close out at `stopAllBots`.

4. **Real-player share metrics — partial-coverage-by-design.** Add `updateTotalUserBet(long)` and `updateTotalUserWinnings(long)` to `Bot` base class. Each game-specific bot script (e.g., a future `BomBot`-style subclass or, more realistically, the message-handlers in `BettingMiniGameBot` for protocols where totals are available) calls these from `onEndGame`/`onSubscribe` only if totals are present. Slot-type bots never call them — share metrics legitimately don't exist for those. Non-bot share = total − bot-side, computed in Grafana via PromQL. Since today's only implementation is `BettingMiniGameBot` and the Bom/B52/Nohu protocols all expose `bs` arrays with `b` (total bet) and `v` (total win) per position, Phase 5 wires extraction from these protocols only.

5. **Cardinality cap — `groupId × environmentId × gameType` only.** Do NOT tag by `username`, `botIndex`, `botId`, or any per-bot identifier. With ~10 bots per group and a handful of environments × gameTypes, the cardinality is bounded by ~(groups × games × envs) ≈ tens to low hundreds, regardless of bot count. Reaffirmed from the original draft.

6. **Per-`cmd` tag is bounded.** `cmd ∈ {subscribe, startGame, updateBet, endGame}` — 4 values. Safe to tag.

7. **Per-`outcome` and `reason` tags are bounded.** `outcome ∈ {success, failure}`. `reason ∈ {watchdog, ws-disconnect, reauth-cycle}` — keep these as a small string enum; if Dev needs to add a new reason, update the enum literal explicitly. Architect will gate any expansion.

8. **Grafana provisioning — declarative, no manual UI clicks.** Both Loki and Prometheus datasources, plus all dashboards, live in `grafana/provisioning/` checked into the repo. A redeploy must not wipe state. The hand-added Loki UI datasource on Bot-1 is replaced by the already-committed `grafana/provisioning/datasources/loki.yml`; Phase 1 adds `prometheus.yml` next to it and the dashboards/ subdirectory.

9. **Host bind-mount pattern for new infra files.** Any `prometheus` container added in Phase 1 mounts its config as a bind-mount (`./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro`) so `sgame` can edit on Bot-1 without root, matching the logs/ pattern. The `grafana/provisioning` directory is already a bind-mount (`docker-compose.yml:64`). No `user:` directive needed on prometheus or grafana — they only read config; their write paths are named/volume-mounted (`grafana-data` volume).

10. **(NEW, AMENDED 2026-06-04) Metric tags via per-callsite MDC read on `Counter.Builder`; `MeterFilter` is defense-in-depth only.** Since `LOGGING_PIPELINE_FIX` Phase 3 propagates MDC to virtually every thread that increments a counter, the bot-identity tags (`botGroupId`, `environmentId`, `gameType`) are read from MDC at each `BotMetrics.inc*()` call and attached to the `Counter.Builder` BEFORE `register(registry)`. Rationale (revised — see Amendment block at the bottom of this plan):

    - **Why per-callsite, not filter-only.** Micrometer's `AbstractMeterRegistry#getOrCreateMeter` consults its `preFilterIdToMeterMap` cache by the *pre-filter* `Meter.Id` before running the filter chain (verified at `micrometer-core 1.14.5` `MeterRegistry.java:637-647`). If two callsites register the same name+tags under different MDC values, both resolve to the same cached Counter and the filter only ever runs for the first one. A filter-only mechanism therefore cannot produce per-group time series for counters that share a name+tags shape. The correct mechanism is for `BotMetrics` to call `MDC.get(...)` per increment and stamp the MDC values onto the `Counter.Builder` so the pre-filter id is distinct per group.
    - **`MeterFilter` is retained as defense-in-depth + aggregate-gauge allow-list.** Any future `bot_*` meter created outside `BotMetrics` still picks up MDC tags via the filter. The filter also enforces that aggregate gauges (`bot_groups_running`, `bots_managed`, `ws_connections_open`, `bots_by_status`) do NOT receive MDC-derived tags even if registered from a thread that happens to have MDC populated.
    - Avoids passing `groupId`/`envId`/`gameType` through every method signature: `BotMetrics` does the MDC read internally.
    - Empty / missing MDC values are skipped (no `{botGroupId=""}` series) — they would be noisy and non-queryable in practice.

    **Counters where MDC is unreliable** (gauges sampled by Spring/Actuator's poll thread, login counters fired before the bot snapshot is captured): pass the tags explicitly via `Tags.of(...)`. These are a small minority and Dev will flag any case during implementation.

11. **Metric naming convention.** All bot-manager-emitted meters start with `bot_*` (per-bot semantics), `bots_*` (aggregate gauge), or `group_*` (per-group semantics). All counters end in `_total`. All durations in seconds end in `_seconds_total`. Phases below use these consistently. Spring Boot's built-in Micrometer meters (`jvm_*`, `http_server_requests_*`, etc.) are not renamed; they are part of the same scrape and remain useful for the demo's "is the bot-manager itself healthy" panel.

12. **`/actuator/prometheus` exposure scope.** Exposed on the same port as the rest of Actuator (`8085` inside container, `8080` on host). No separate management port. Acceptable per existing decision in `CLAUDE.md` (Actuator is internal-only, host port is reachable from Prometheus inside the compose network as `bot-manager:8085/actuator/prometheus`).

---

## Plan

The phases below are ordered for the Friday demo. Each phase is labeled with a demo-blocker status:

- **DEMO BLOCKER** — must ship for the Friday demo to land
- **DEMO OPTIONAL** — improves the demo but the demo's core message survives without it
- **DEFERRED** — explicitly post-demo

The **minimum viable demo** is Phases 1 + 2 + 6 + 7. Without Phase 2 there are no bot-emitted metrics; without Phase 1 there is no scrape endpoint and no provisioned datasource; without Phase 6 there is no panel; without Phase 7 the user can't show it to stakeholders with confidence. Phases 3, 4, 5 add depth (downtime, RTP, share metrics) but are not load-bearing for "we can see what bots are doing." Architect recommendation: ship 1+2+6 by Thursday EOD, do 7 Friday morning, attempt 3 if there's time, defer 4 and 5 explicitly.

---

### Phase 1 — Prometheus infrastructure + datasource provisioning — **DEMO BLOCKER**

**Scope:** Add Micrometer's Prometheus registry, expose `/actuator/prometheus`, add a `prometheus` service to Compose that scrapes it, and provision both Loki + Prometheus datasources in Grafana from the repo so a redeploy never wipes them. No bot-side instrumentation code yet — this phase only stands up the pipe.

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/pom.xml` — add `io.micrometer:micrometer-registry-prometheus` (no version; Spring Boot BOM pins it).
- `/Users/gleb/IdeaProjects/Bot/src/main/resources/application.properties` — change line 28 to `management.endpoints.web.exposure.include=health,info,metrics,loggers,prometheus` and add `management.metrics.tags.application=bot-manager` plus `management.prometheus.metrics.export.enabled=true` (the latter is Spring Boot 3.x convention; safe even if Boot 3.4 defaults it).
- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml` — add a `prometheus` service:
  ```
  prometheus:
    image: prom/prometheus:v2.55.0
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    depends_on:
      - bot-manager
  ```
  Add `prometheus-data:` to the top-level `volumes:` block.
- `/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml` (new) — minimal config: global scrape interval 15s, one job `bot-manager` scraping `bot-manager:8085/actuator/prometheus` via the compose network's internal DNS.
- `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/datasources/prometheus.yml` (new) — sibling of the existing `loki.yml`. URL `http://prometheus:9090`, type `prometheus`, `isDefault: false` (Loki stays default).
- `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/dashboards.yml` (new) — provisioning provider pointing at `/etc/grafana/provisioning/dashboards/` for dashboard JSON files. Empty for now; Phase 6 fills the JSONs.
- Bind-mount the dashboards directory: add `./grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards` to the grafana service's `volumes:` (the parent `./grafana/provisioning:/etc/grafana/provisioning` mount already covers it, so a second mount is unnecessary — leave the existing mount as-is and just create the subdirectory in-repo).

**Acceptance criteria:**
- `mvn clean install` passes on the dev machine with `JAVA_HOME` set to OpenJDK 21.
- `curl -fsS http://localhost:8080/actuator/prometheus | head -5` (running locally via `docker compose up -d` against `vingame-bot:latest`) returns text starting with `# HELP` / `# TYPE` lines.
- `curl -fsS http://localhost:9090/api/v1/targets` shows the `bot-manager` job in state `UP`.
- After re-deploy, both `Loki` and `Prometheus` datasources appear in Grafana's datasource list. Querying `up{job="bot-manager"}` from the Grafana Explore panel returns 1.
- No bot-side metric is registered yet — `up`, JVM defaults, and Spring HTTP server metrics are the only data.

**Verification commands (local):**
```bash
# Build
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn -DskipTests clean package
# Deploy locally (or stop existing then up)
docker compose up -d --build
# Prometheus endpoint
curl -fsS http://localhost:8080/actuator/prometheus | head -5
# Prometheus container scraping
curl -fsS http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job, health}'
# Grafana datasources via API (basic auth admin:admin)
curl -fsS -u admin:admin http://localhost:3000/api/datasources | jq '.[].name'
# Expect: ["Loki","Prometheus"]
```

**Independence:** No dependency on any other phase. Unblocks Phases 2–6.

**Demo-blocker:** YES. Without a scrape endpoint and a Prometheus datasource, dashboards in Phase 6 have nothing to render.

---

### Phase 2 — Bot-side counters + gauges (no winnings, no downtime) — **DEMO BLOCKER**

**Scope:** Register the full set of "easy" Micrometer meters that do NOT require new bot logic. Wire counter increments at existing callsites identified in the Per-Metric Readiness table. Register gauges that sample existing in-memory state. Install the MDC-aware `MeterFilter` (Architecture Decision 10) so tags are populated automatically. No new control flow in `Bot` or `BettingMiniGameBot` — every increment rides an existing callback or state transition.

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java` (new, package-level) — central registry of counter and gauge names + lookup helpers. Holds `Counter`/`Gauge` builders that delegate tag attachment to the `MeterFilter`. Public methods: `incBotMessage(String cmd)`, `incBotFailure()`, `incBotReconnect(String reason)`, `incBotAutoDeposit(boolean success)`, `incBotWatchdogExpired()`, `incBotWsEvent(String event)`, `incBetPlaced(long amount)`, `incLogin(boolean success)`, `incVerifyToken(boolean success)`. All call into `MeterRegistry` lazily by name (Micrometer interns by name+tags). Inject `MeterRegistry` via constructor (Spring `@Component`).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java` (new) — implements `io.micrometer.core.instrument.config.MeterFilter`. `accept` checks meter name prefix (`bot_*` or `group_*`) and on every meter creation, reads `MDC.get("botGroupId")`, `MDC.get("environmentId")`, `MDC.get("gameType")` and attaches them as tags. Aggregate gauges (`bots_managed`, `bot_groups_running`, `ws_connections_open`, `bots_by_status`) are excluded by an explicit name allow-list. Registered as a Spring `@Bean` returning `MeterFilter` in a new `@Configuration` class `ObservabilityConfig`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/ObservabilityConfig.java` (new) — `@Configuration` exposing the `MeterFilter` bean and the aggregate `Gauge` registrations (see below).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
  - Wire `BotMetrics` via setter or constructor injection through the existing `setClients(...)` builder. Cleanest: add `protected BotMetrics metrics` field and a `setMetrics(BotMetrics)` setter; `BotFactory` injects it (one Spring-managed bean) on creation alongside `setClients`/`setConfiguration`.
  - In `creditBalance(long amount)` (line 487) — call `metrics.incBetPlaced(amount)` after the existing AtomicLong updates.
  - In `transitionStatus(BotStatus next)` (line 260-264) — on `next == DEAD`, call `metrics.incBotFailure()`.
  - In `triggerFullReconnect(String reason)` (line 296) — call `metrics.incBotReconnect(reason)` after the status transition. Map the free-form `reason` string to a small enum-like string normalization here (e.g., starts-with "watchdog" → "watchdog"; default → "ws-disconnect").
  - In `runWsReconnectLoop` (line 309) start — `metrics.incBotReconnect("ws-disconnect")` once per loop entry. (See Amendment 2026-06-04 below — the original phrasing here said "reauth-cycle", which inverted the two loops; the corrected mapping is `runWsReconnectLoop` → `ws-disconnect`, `runAuthThenWsLoop` → `reauth-cycle`.)
  - In `runAuthThenWsLoop` start — `metrics.incBotReconnect("reauth-cycle")` once per loop entry.
  - In `deposit()` (line 192) `mdcConsumer` callback — `metrics.incBotAutoDeposit(success)`.
  - In `configureClient`'s `onWsStatusChange` switch (line 272-278) — `metrics.incBotWsEvent("connected")` on `CONNECTED`, `metrics.incBotWsEvent("authenticating")` on `AUTHENTICATING_WS`. In `onDisconnect` (line 279) — `metrics.incBotWsEvent("disconnected")`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`:
  - `onSubscribe` (line 152) — `metrics.incBotMessage("subscribe")`.
  - `onStartGame` (line 160) — `metrics.incBotMessage("startGame")`.
  - `onUpdate` (line 174) — `metrics.incBotMessage("updateBet")`.
  - `onEndGame` (line 183) — `metrics.incBotMessage("endGame")`.
  - `onWatchdogExpired` (line 145) — `metrics.incBotWatchdogExpired()` before the existing `triggerFullReconnect(...)` call so the "before reconnect overwrites the signal" concern from the original draft is addressed.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java`:
  - `authenticate(...)` (line 118): wrap success/failure paths. Today there's no try/catch around `AuthClient(...).authenticate()`. Add a try/catch: success → `metrics.incLogin(true)`; on any exception → `metrics.incLogin(false)` then rethrow. Same for `getBalance(...)` (line 453) → `metrics.incVerifyToken(success)`.
  - Inject `BotMetrics` via constructor — `ApiGatewayClient` is `@Scope("prototype")` so it gets a fresh Spring-resolved instance per environment client; `BotMetrics` is singleton, safe to inject.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java` (verify path) — ensure the `BotMetrics` bean is injected when constructing `Bot` instances. Read the file in Phase 2 to confirm the existing builder shape before deciding between constructor injection vs `setMetrics(...)`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java` — no changes needed; the aggregate gauges read `runningGroups` and `runtime.getBotInstances()` directly from the bean.

**Aggregate gauge registrations** (in `ObservabilityConfig`):
- `bot_groups_running` — `Gauge.builder("bot_groups_running", botGroupBehaviorService, s -> s.getRunningGroupCount())` (add a public accessor in `BotGroupBehaviorService` that returns `runningGroups.size()`).
- `bots_managed` — sum of `runtime.getBotInstances().size()` across `runningGroups.values()`. Add a `getTotalManagedBots()` accessor.
- `ws_connections_open` — count of `bot.isConnected()` across all bots in all runtimes. Add `getOpenWsConnectionCount()`.
- `bots_by_status{status=...}` — multi-gauge. One gauge per `BotStatus` value, summing bots whose `getStatus() == s`. Use `Gauge.builder(... ).tags("status", s.name())` for each.

These four are excluded from the MDC-aware `MeterFilter` (no `botGroupId` tag).

**Acceptance criteria:**
- `mvn test` passes. Add a `BotMetricsRegistrationTest` that boots Spring + Actuator with an in-memory `SimpleMeterRegistry`, calls each `BotMetrics.inc*` method, and asserts the meter is registered with the expected name and tag keys.
- After deploy + start of one bot group of 5 bots, `/actuator/prometheus` returns lines matching the regexes in the Verification section below.
- `bot_messages_total` grows by ~5×4 per round (5 bots × 4 message types) for a steady-state betting-mini game.
- `bot_bet_amount_total` matches the sum of `totalBetAmount` reported by `GET /api/v1/bot-group/{id}/health`.
- No new errors in container logs.

**Verification commands:**
```bash
# Endpoint exists and lists bot meters
curl -fsS http://localhost:8080/actuator/prometheus \
  | grep -E '^(bot_groups_running|bots_managed|bot_messages_total|bot_failures_total|bot_reconnects_total|bot_bets_placed_total|bot_bet_amount_total|bot_auto_deposits_total|bot_watchdog_expired_total|bot_ws_connections_total|bot_login_total|bot_verify_token_total|ws_connections_open|bots_by_status) '
# Expect: at least one line per metric name.

# Tags applied
curl -fsS http://localhost:8080/actuator/prometheus \
  | grep '^bot_messages_total{' \
  | head -3
# Expect: lines like bot_messages_total{application="bot-manager",botGroupId="...",cmd="endGame",environmentId="...",gameType="..."} N.0

# Aggregate gauges have NO botGroupId tag
curl -fsS http://localhost:8080/actuator/prometheus | grep '^bot_groups_running '
# Expect a single line with no botGroupId tag, value >= 1 if a group is running.

# Counter movement during a 60s run
START=$(curl -fsS http://localhost:8080/actuator/prometheus | grep -oP '(?<=^bot_messages_total\{[^}]*cmd="endGame"[^}]*\} )\d+(\.\d+)?' | head -1)
sleep 60
END=$(curl -fsS http://localhost:8080/actuator/prometheus | grep -oP '(?<=^bot_messages_total\{[^}]*cmd="endGame"[^}]*\} )\d+(\.\d+)?' | head -1)
# Expect END > START (more endGame messages received).
```

**Independence:** Depends on Phase 1 (scrape endpoint and Prometheus container). Does NOT depend on Phases 3/4/5. Ships independently of dashboards (Phase 6).

**Demo-blocker:** YES. Without counters and gauges, dashboards have no time series.

---

### Phase 3 — Downtime accumulators (bot + group DEAD seconds) — **DEMO OPTIONAL**

**Scope:** Track cumulative seconds bots and groups spend in DEAD state. Expose as Prometheus counters (`bot_dead_seconds_total`, `group_dead_seconds_total`) and a corresponding "currently dead" gauge. Hooks live inside the existing `transitionStatus()` chokepoint (`Bot.java:260`) and `markAsDead()` / `stopAllBots()` chokepoints (`BotGroupRuntime.java:158`, `185`).

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
  - Add `private volatile Instant deadSince` field.
  - In `transitionStatus(BotStatus next)`:
    - If `next == DEAD` and `deadSince == null`, set `deadSince = Instant.now()`.
    - If `prev == DEAD` and `next != DEAD` and `deadSince != null`, increment `metrics.addBotDeadSeconds(secondsSince(deadSince))`, set `deadSince = null`. **Caveat:** today `BotStatus.DEAD` is terminal in code (no transition out of DEAD anywhere in `Bot.java`). The closure path is therefore `cleanup()` — see next bullet. Architect's intent: leave the transition-out branch in place anyway, in case future code (e.g., manual revival from REST) adds a recovery path.
  - In `cleanup()` (line 140) — if `status == DEAD` and `deadSince != null`, accumulate `Duration.between(deadSince, now)`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java`:
  - Add `private volatile Instant groupDeadSince`.
  - In `markAsDead()` — set `groupDeadSince = Instant.now()`.
  - Add a `stopGroupDeadAccumulator(BotMetrics metrics)` method called from `stopAllBots()` (line 185) that closes out cumulative seconds if `groupDeadSince != null`. `BotGroupRuntime` does not have a `BotMetrics` reference today; pass it in via constructor or via `stopAllBots(BotMetrics)` overload — Dev picks the cleaner shape.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`:
  - Add `addBotDeadSeconds(double seconds)` and `addGroupDeadSeconds(double seconds)`.
- `ObservabilityConfig.java` — add gauges `bots_dead_currently` (count of bots whose `deadSince != null`) and `groups_dead_currently` (count of runtimes whose `groupDeadSince != null`). Aggregate; no MDC tags.

**Acceptance criteria:**
- A `BotDeadAccumulatorTest` exercises `transitionStatus(... DEAD)` → wait → `cleanup()` and asserts `bot_dead_seconds_total` increased by approximately the wait duration.
- During a live demo, killing the auth gateway (or just letting a bot hit `performReauth` failure → DEAD) accumulates seconds visible in Prometheus.

**Verification commands:**
```bash
curl -fsS http://localhost:8080/actuator/prometheus \
  | grep -E '^(bot_dead_seconds_total|group_dead_seconds_total|bots_dead_currently|groups_dead_currently)'
# Expect: each name registered. Counters at 0 if nothing died yet.
```

**Independence:** Depends on Phases 1 + 2. Independent of Phases 4/5.

**Demo-blocker:** OPTIONAL. The dashboard panel for downtime can stay empty without breaking the demo's story. If Phase 4 looks easier to land, prioritize that over Phase 3.

---

### Phase 4 — Winnings capture + RTP — **DEMO OPTIONAL** (recommend defer)

**Scope:** Populate `lastRoundWinnings` and emit `bot_winnings_total`. Architecturally simple but touches bot game-flow logic and adds a per-round verify-token call.

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
  - Add `protected volatile long preRoundBalance` field and `protected volatile long betAmountThisRound` (AtomicLong if reentrancy concerns).
  - In `creditBalance(long amount)` — also `betAmountThisRound.addAndGet(amount)`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`:
  - In `onStartGame` (line 160) — snapshot `preRoundBalance = expectedCurrentBalance.get()`; reset `betAmountThisRound = 0`.
  - In `onEndGame` (line 183) — schedule a delayed refetch on the existing `watchdogScheduler` (it's already a virtual-thread `ScheduledExecutorService`): after ~2 seconds, call `apiGatewayClient.getBalance(...)`, compute `winnings = newBalance - (preRoundBalance - betAmountThisRound)`, set `lastRoundWinnings = winnings`, call `metrics.addBotWinnings(Math.max(winnings, 0))`. Wrap the scheduled task in `mdcWrap(...)` (already available).
- `BotMetrics` — add `addBotWinnings(long amount)`.

**Architecture caveats** (already in Decision 2):
- Refetch must happen post-EndGame, not synchronously on the message-processor thread (would block the WS pipeline).
- The 2-second delay is a heuristic; the server's balance write-back latency is variable. If demo shows `lastRoundWinnings` is wrong by one round, bump to 5s.

**Acceptance criteria:**
- `lastRoundWinnings` in `BotGroupHealthDTO` is nonzero after a winning round.
- `bot_winnings_total` increases monotonically.
- No spike in `bot_verify_token_total{outcome="failure"}` from the new refetch traffic.

**Verification commands:**
```bash
# After a 2-minute group run
curl -fsS http://localhost:8080/api/v1/bot-group/<id>/health \
  | jq '.bots[].lastRoundWinnings' | sort -u | head
# Expect: a mix of 0 and positive values (not all 0 like in release.md step 6).

curl -fsS http://localhost:8080/actuator/prometheus \
  | grep -E '^bot_winnings_total '
# Expect: positive value.
```

**Independence:** Depends on Phases 1 + 2. Independent of Phases 3/5.

**Demo-blocker:** OPTIONAL — architect recommends defer. Reasoning: winnings is the most "wow" panel but it's the only phase that touches bot game-flow logic, raises a per-round verify-token call, and depends on a 2-second timing heuristic. If demo runs into the wire, the demo story "we can see what bots are doing" still lands with bet counts, message rates, and bot-by-status without a winnings panel. If demo plus 1 day, ship this.

---

### Phase 5 — Real-player share metrics — **DEFERRED**

**Scope:** Extract `bs` arrays from `BomSubscribeMessage` / `BomEndGameMessage` (and equivalents for B52, Nohu) to compute `game_total_bet_amount_total{gameType}` and `game_total_winnings_total{gameType}`. Grafana computes non-bot share via PromQL.

Per the original draft and Architecture Decision 4, this needs:
- `updateTotalUserBet(long)` and `updateTotalUserWinnings(long)` on `Bot`.
- Protocol-specific extraction in each `onSubscribe`/`onEndGame` handler — currently a single `BettingMiniGameBot` handler that doesn't have a hook for per-protocol totals extraction. Either subclass per game type (heavy; touches `BotFactory`) or add a `MessageType-> extractor` map keyed off `messageTypes.getClass()` (lighter, fits the existing `GameMessageTypes` abstraction at `domain/bot/message/GameMessageTypes.java`).

**Files touched:** TBD. Architect's preference: add `extractUserTotals(SubscribeMessage)` and `extractUserTotals(EndGameMessage)` defaults on `GameMessageTypes` interface (returning `Optional.empty()`), overridden in `BomGameMessageTypes` / `B52GameMessageTypes` / `NohuGameMessageTypes` to read the `bs` arrays. `BettingMiniGameBot.onSubscribe`/`onEndGame` calls the extractor and forwards to `updateTotalUserBet`/`updateTotalUserWinnings`.

**Acceptance criteria:** Per `bs`-carrying gameType, `game_total_bet_amount_total{gameType=...}` is populated post-EndGame and is strictly ≥ `bot_bet_amount_total{gameType=...}`.

**Demo-blocker:** DEFERRED. Three reasons:
1. Scope. Touches the protocol abstraction and three game-message classes. One Dev session each at minimum; likely two.
2. Demo value. "Bots are ~5% of real bet volume on this game" is a compelling number, but the demo's primary story is the bots' own behavior, not market-share insights.
3. Risk. Misreading the `bs` `b` and `v` semantics ships incorrect numbers to the dashboard — worse than no number.

Out of scope for Friday. Architect will re-plan post-demo.

---

### Phase 6 — Grafana dashboards (provisioned JSON) — **DEMO BLOCKER**

**Scope:** Two provisioned dashboards, JSON files in `grafana/provisioning/dashboards/`.

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/bots.json` (new) — panels:
  1. Running bot groups (stat, `bot_groups_running`).
  2. Managed bots (stat, `bots_managed`).
  3. Bots by status (stacked time series, `bots_by_status` by `status` tag).
  4. Bet rate (time series, `rate(bot_bets_placed_total[1m])` by `gameType`).
  5. Bet amount rate (time series, `rate(bot_bet_amount_total[1m])` by `gameType`).
  6. Auto-deposit frequency (time series, `rate(bot_auto_deposits_total[5m])` by `outcome`).
  7. Failure rate (time series, `rate(bot_failures_total[5m])`).
  8. Reconnect rate (time series, `rate(bot_reconnects_total[5m])` by `reason`).
  9. Currently-open WS connections (stat, `ws_connections_open`).
  10. (Phase 4 if shipped) RTP per game (time series, `rate(bot_winnings_total[5m]) / rate(bot_bet_amount_total[5m])` by `gameType`).
  11. (Phase 3 if shipped) Bot DEAD seconds in window (stat, `increase(bot_dead_seconds_total[1h])`).
- `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/game-server.json` (new) — panels:
  1. Login success rate (time series, `rate(bot_login_total{outcome="success"}[5m]) / rate(bot_login_total[5m])`).
  2. Verify-token success rate (same pattern with `bot_verify_token_total`).
  3. Message receive rate by `cmd` (time series, `rate(bot_messages_total[1m])` by `cmd`).
  4. Watchdog firings (time series, `rate(bot_watchdog_expired_total[5m])` — the "game is silent" signal).
  5. WS event rate by `event` (time series, `rate(bot_ws_connections_total[5m])` by `event`).
  6. (Phase 3) Group DEAD seconds (stat, `increase(group_dead_seconds_total[1h])`).
  7. (Phase 5) Bot bet share (time series, `bot_bet_amount_total / game_total_bet_amount_total` by `gameType`).

**Acceptance criteria:**
- Both dashboards appear in Grafana's dashboard list after a redeploy, without manual import.
- Every panel either renders a value or shows "No data" gracefully (no datasource errors, no syntax errors).
- Dashboards reference the provisioned `Prometheus` datasource by name (not by UID) so they survive a datasource-recreate.

**Verification commands:**
```bash
# Dashboards exist via API
curl -fsS -u admin:admin http://localhost:3000/api/search?type=dash-db \
  | jq '.[] | .title'
# Expect: "Bots", "Game server"

# Render a panel datasource query
curl -fsS -u admin:admin "http://localhost:3000/api/datasources/proxy/uid/prometheus/api/v1/query?query=bot_groups_running"
# Expect: a JSON result, not a 404.
```

**Independence:** Depends on Phase 1 (datasource provisioning) and at minimum Phase 2 (most panels query bot_* metrics). Phase 3/4/5 panels gracefully degrade to "No data" if those phases are skipped.

**Demo-blocker:** YES. The dashboards are the demo.

---

### Phase 7 — Live verification on Bot-1 — **DEMO BLOCKER**

**Scope:** Releaser runs the canonical post-deploy verification checklist (`## Verification` section at the bottom of this plan) on the staging Bot-1 host after deploying the changes from Phases 1+2+6 (minimum) and 3+4 (if shipped). No code. This is the Releaser's role per `docs/process/AGENTIC_WORKFLOW.md`.

**Acceptance criteria:** Every numbered step in `## Verification` returns the expected output.

**Demo-blocker:** YES. Without live verification the user can't demo with confidence.

---

## Implementation Notes / Concerns

- **`expectedCurrentBalance` is decremented eagerly on bet placement.** Phase 4's winnings calc must snapshot pre-round balance *before* the first `creditBalance` of the round, and snapshot `betAmountThisRound` independently. Don't try to derive bets from balance delta.

- **`transitionStatus` is the single chokepoint for status changes** (`Bot.java:260-264`). All counters that key off bot state (failures, downtime accumulator entry/exit) belong here. Don't sprinkle increments across individual transition callsites — the chokepoint is the contract.

- **`BotStatus.DEAD` is currently terminal in code.** No `transitionStatus(DEAD → anything)` exists today (only `performReauth` failure at line 361 enters DEAD, after which `reconnecting` is reset and the loop returns). The "DEAD downtime closes on exit" branch in Phase 3 is defensive; in practice today the closure happens at `cleanup()`. Keep the exit branch anyway in case a future revive-from-DEAD path is added.

- **`mdcSnapshot` is captured at `Bot.initialize()` line 111.** Any counter increment that fires from a bot-owned thread reaches `MeterFilter` with the snapshot already re-applied (per `LOGGING_PIPELINE_FIX` Phase 3). The exception is the `bot_login_total` counter inside `ApiGatewayClient.authenticate()` — that call runs *before* `mdcSnapshot` is set (called by `Bot.initialize()` line 116 before line 111 has stored anything). Login MDC at that point is whatever `BotMdc.set(...)` populated at line 99-105, which includes all five keys. So the `MeterFilter` correctly tags login meters via MDC. The narrow exception: `performReauth` (line 352) calls `authenticate` after the snapshot is restored, also fine. Double-checked: no broken case.

- **Login failure (`incLogin(false)`) needs care.** Today `authenticate(...)` does not try/catch around the `AuthClient` call. Adding the catch in Phase 2 must rethrow, not swallow — bot creation depends on the exception propagating up so `BotFactory` knows the bot failed. Counter increments must not change error semantics.

- **The `mdcSnapshot` mechanism does NOT cover the JVM/Spring HTTP timer threads** that Spring Boot's `WebMvcMetricsFilter` and `MetricsHttpServerCustomizer` instrument. Those meters won't get `botGroupId` tags — by design (they're HTTP-request-level, not bot-level). The `MeterFilter` allow-list (Architecture Decision 10) covers this implicitly: only `bot_*`, `bots_*`, and `group_*` get MDC tags.

- **`bot_bet_amount_total` is in raw units** (whatever `creditBalance(amount)` receives). Match `BotGroupHealthDTO.totalBetAmount` semantics — both are the same number.

- **Cardinality watch.** With Architecture Decision 5 (`groupId × environmentId × gameType` only), max time series per counter ≈ (groups) × (envs) × (gameTypes). Realistic ceiling for demo: 5 × 3 × 5 = 75 per counter. Add `cmd` (×4) or `outcome` (×2) or `reason` (×3) and you're at low hundreds per counter. Total active series across all counters: ~2k. Prometheus' default head-block limit (1M series) is well clear. No sampling needed.

- **Grafana dashboards reference the datasource by name (`Prometheus`), not UID.** Provisioning generates a stable UID from the datasource name; dashboards that hardcode a UID break when the datasource is recreated. Use `${datasource}` panel variables or by-name references.

- **`prometheus` container's data is in a named Docker volume** (`prometheus-data`). Not a bind-mount, because Prometheus' tsdb is sensitive to host filesystem semantics. `sgame` operator doesn't need to inspect TSDB files; the Prometheus UI on `:9090` is sufficient for debugging.

- **`docker compose up -d --build`** rebuilds images locally. On Bot-1 the operator follows the existing `deploy.sh` flow which loads a pre-built `vingame-bot:latest` tarball; no `--build` flag needed there. The `prometheus` image is pulled fresh from `prom/prometheus:v2.55.0`.

- **`grafana/provisioning/dashboards/` directory must exist before grafana container starts**; otherwise the bind-mount fails. `deploy.sh` already does `mkdir -p logs`; consider adding `mkdir -p grafana/provisioning/dashboards prometheus` in the same pattern. Not strictly required if the directory is checked into git with a `.gitkeep` placeholder.

- **MDC inheritance for the Actuator scrape thread.** Spring's Tomcat `http-nio-*` threads have no bot MDC. The `MeterFilter` reads MDC at meter *creation* time (not at scrape time); creation happens on the bot's own threads. Scrape is a read-only enumeration of pre-created meters. So scrape thread MDC absence is irrelevant.

- **`BotFactory` injection of `BotMetrics`.** Read `BotFactory.java` first thing in Phase 2; the existing builder shape determines whether `setMetrics(...)` or constructor injection is cleanest. The Architect has not opened `BotFactory.java` for this revision — Dev should confirm during implementation.

---

## Open Items

- **`BotFactory.java` shape** — Dev to inspect at start of Phase 2 and pick the cleanest `BotMetrics` injection path.
- **Bot-1 Prometheus retention** — default `prom/prometheus` settings give ~15 days at 15s scrape interval; sufficient for demo. Tune later if needed.
- **Auth flow for Grafana on Bot-1** — currently `admin/admin`. Out of scope; demo runs as admin.
- **Slot-type bots' share metrics** — explicitly out of scope per Architecture Decision 4.
- **Drift between `expectedCurrentBalance` and actual server balance** — Phase 4 refetch is independent of the existing 1M-delta refresh in `checkBalance()`; both can coexist.
- **`bot.winnings.sample-rate` config knob** — deferred; only matters at scales the demo won't hit.
- **Watchdog reason taxonomy** — current `triggerFullReconnect(reason)` string is free-form. Phase 2 normalizes "watchdog timeout..." → "watchdog", everything else → "ws-disconnect" or "reauth-cycle" at the increment site. Formal enum out of scope.
- **Dashboards beyond demo** — JVM dashboard, HTTP request dashboard. Not in this plan.

---

## Verification

Canonical post-deploy verification, run by the Releaser on Bot-1 after the deploy lands. Each step has an exact command and expected result.

The verification runs against host `Bot-1` with `http://Bot-1:8080` (bot-manager), `http://Bot-1:9090` (Prometheus), `http://Bot-1:3000` (Grafana, admin/admin).

The minimum-viable demo set is steps **1–8** (covers Phases 1, 2, 6). Steps **9–10** are conditional on Phases 3/4 having shipped.

### Step 1 — Prometheus actuator endpoint exists

```bash
curl -fsS http://Bot-1:8080/actuator/prometheus | head -5
```
Expect: text starting with `# HELP` / `# TYPE` lines. (Validates Phase 1: dependency + `application.properties` exposure.)

### Step 2 — Prometheus container scrapes successfully

```bash
curl -fsS http://Bot-1:9090/api/v1/targets \
  | jq '.data.activeTargets[] | select(.labels.job=="bot-manager") | .health'
```
Expect: `"up"`. (Validates Phase 1: compose service and prometheus.yml.)

### Step 3 — Grafana datasources auto-provisioned

```bash
curl -fsS -u admin:admin http://Bot-1:3000/api/datasources | jq '[.[].name] | sort'
```
Expect: `["Loki","Prometheus"]`. (Validates Phase 1 datasource provisioning, and confirms the previously-manual Loki datasource has been replaced by the provisioned one.)

### Step 4 — Bot meters registered

```bash
curl -fsS http://Bot-1:8080/actuator/prometheus \
  | grep -cE '^(bot_groups_running|bots_managed|bot_messages_total|bot_failures_total|bot_reconnects_total|bot_bets_placed_total|bot_bet_amount_total|bot_auto_deposits_total|bot_watchdog_expired_total|bot_ws_connections_total|bot_login_total|bot_verify_token_total|ws_connections_open|bots_by_status) '
```
Expect: an integer ≥ 14 (at least one HELP/TYPE-stripped line per metric name). (Validates Phase 2 meter registration.)

### Step 5 — Start a bot group; watch counters move

```bash
GID=<bot-group-id>
curl -X POST -fsS http://Bot-1:8080/api/v1/bot-group/$GID/start  # expect HTTP 200
sleep 90  # 3 game rounds
curl -fsS http://Bot-1:8080/actuator/prometheus | grep -E '^(bot_groups_running |bots_managed |bot_messages_total\{[^}]*cmd="endGame")'
```
Expect: `bot_groups_running 1.0` (or larger), `bots_managed N.0` matching the configured bot count, `bot_messages_total{...cmd="endGame"...}` > 0.

### Step 6 — Tag set on bot meters

```bash
curl -fsS http://Bot-1:8080/actuator/prometheus \
  | grep '^bot_messages_total{' | head -2
```
Expect: each line contains `botGroupId="..."`, `environmentId="..."`, `gameType="..."`, and `cmd="..."`. No `username` or `botId` tags. (Validates Architecture Decisions 5 and 10.)

### Step 7 — Aggregate gauges have no per-group tags

```bash
curl -fsS http://Bot-1:8080/actuator/prometheus | grep -E '^(bot_groups_running |bots_managed |ws_connections_open ) '
```
Expect: each line has no `botGroupId` tag. (Validates the `MeterFilter` allow-list.)

### Step 8 — Grafana dashboards render

```bash
curl -fsS -u admin:admin http://Bot-1:3000/api/search?type=dash-db | jq '[.[].title] | sort'
```
Expect: contains `"Bots"` and `"Game server"`.

```bash
curl -fsS -u admin:admin "http://Bot-1:3000/api/datasources/proxy/uid/prometheus/api/v1/query?query=bot_groups_running" \
  | jq '.data.result | length'
```
Expect: `≥ 1`. (Validates Phase 6: dashboards are provisioned AND the Prometheus datasource is queryable through Grafana.)

Open `http://Bot-1:3000/d/bots` in a browser as `admin/admin`; expect every panel either renders data within 30 seconds of opening or shows "No data" without errors.

### Step 9 — Winnings path actually fires *(only if Phase 4 shipped)*

```bash
curl -fsS http://Bot-1:8080/api/v1/bot-group/$GID/health \
  | jq '[.bots[].lastRoundWinnings] | (max,min)'
curl -fsS http://Bot-1:8080/actuator/prometheus | grep '^bot_winnings_total '
```
Expect: `lastRoundWinnings` shows a mix of zero and positive values (not all zero like in `release.md` step 6). `bot_winnings_total` reports a positive number.

### Step 10 — Downtime accumulators *(only if Phase 3 shipped)*

```bash
curl -fsS http://Bot-1:8080/actuator/prometheus | grep -E '^(bot_dead_seconds_total|group_dead_seconds_total|bots_dead_currently|groups_dead_currently)'
```
Expect: each name registered. Counters at 0 if nothing died in the verification window — which is fine; the existence-of-meter check is the point.

### Cleanup

```bash
curl -X POST -fsS http://Bot-1:8080/api/v1/bot-group/$GID/stop
```
Expect: HTTP 200.

If any of steps 1–8 fail, the demo is at risk and Architect must be re-engaged before Friday. Steps 9 and 10 are informational only.

---

## Amendment — 2026-06-04

Two surgical corrections applied during Phase 2 compliance review (verdict PLAN_AMENDED).

### Amendment 1 — Architecture Decision 10 corrected: per-callsite MDC read, not filter-only

**Original AD 10 claim:** "register a single Micrometer `MeterFilter` at startup that reads … from MDC (`MDC.get(...)`) and attaches them as common tags on every meter touched in that thread."

**Why it was wrong:** Micrometer's `AbstractMeterRegistry#getOrCreateMeter` (verified in `micrometer-core 1.14.5` `MeterRegistry.java:637-647`) caches Counter handles by the **pre-filter** `Meter.Id`. Once a Counter named `bot_messages_total{cmd=endGame}` is registered, subsequent calls with the same pre-filter id resolve to the cached Counter without re-running the filter chain. This means a filter that reads MDC produces the MDC-derived tags only for the **first** registration; every later increment under a different MDC reuses the same cached Counter, collapsing per-group time series into one. Dev caught this with a failing test (`BotMetricsTest.differentGroupsCreateSeparateTimeSeries`).

**Corrected mechanism (now in AD 10 above):**
- `BotMetrics` reads `MDC.get(botGroupId / environmentId / gameType)` per `inc*()` call and attaches the values to the `Counter.Builder` via `.tags(...)` before `.register(registry)`. This makes the pre-filter id distinct per group, so the registry caches one Counter per `(name × tags × group × env × game)` tuple — which is exactly the per-group time series shape the dashboards need.
- `BotMdcTagsMeterFilter` is retained as defense-in-depth (catches any future `bot_*` meter that bypasses `BotMetrics`) and to enforce the aggregate-gauge exclusion allow-list (`bot_groups_running`, `bots_managed`, `ws_connections_open`, `bots_by_status` never get MDC tags).

**Status:** This is a genuine Micrometer-API quirk, not a Dev preference. The plan's original mechanism was structurally incapable of producing per-group time series for shared-name counters; the correction is required, not optional.

### Amendment 2 — Reconnect reason mapping corrected (Phase 2)


The Phase 2 body originally said:
> In `runWsReconnectLoop` (line 309) start — `metrics.incBotReconnect("reauth-cycle")` once per loop entry.

That mapping is inverted relative to the code semantics. Verified in `Bot.java`:
- `runWsReconnectLoop` is started by `onWsDisconnected` (line 315) — a WebSocket disconnect triggered the loop. Correct reason: **`ws-disconnect`**.
- `runAuthThenWsLoop` is started by `triggerFullReconnect` (line 332) — a full reauth + WS reconnect cycle. Correct reason: **`reauth-cycle`**.
- `triggerFullReconnect("watchdog timeout …")` normalizes via `normalizeReconnectReason` (starts-with "watchdog") → **`watchdog`**.

The Phase 2 body bullet has been corrected in place to reflect the right mapping. Dev's implementation already matches the corrected mapping; this amendment makes the plan match Dev's code (which is semantically correct).

---

## Amendment — 2026-06-04 (Phase 6 compliance review)

Two further surgical corrections applied during the Phase 6 compliance review (verdict PLAN_AMENDED). Both close gaps between the originally-drafted Phase 6 panel list / acceptance criteria and the actual state of the pipeline after the Phase 1 polish (`caaf0e1`) and the Phase 3 gauge additions.

### Amendment 3 — Datasource referenced by pinned UID, not by name (Phase 6 acceptance)

**Original Phase 6 acceptance bullet:**
> Dashboards reference the provisioned `Prometheus` datasource by name (not by UID) so they survive a datasource-recreate.

**And the matching Implementation Notes bullet:**
> Grafana dashboards reference the datasource by name (`Prometheus`), not UID. Provisioning generates a stable UID from the datasource name; dashboards that hardcode a UID break when the datasource is recreated. Use `${datasource}` panel variables or by-name references.

**Why this is now stale.** The Phase 1 polish at commit `caaf0e1` (`Phase 1 polish: pin stable uids on Loki and Prometheus datasources`) explicitly pinned `uid: prometheus` in `grafana/provisioning/datasources/prometheus.yml` and `uid: loki` in `loki.yml`. With pinned UIDs, dashboards that hardcode `"uid": "prometheus"` survive a recreate (the UID is fixed in the provisioning YAML, not generated from a hash). The "by name to survive recreate" guidance was correct under Grafana's default UID-from-hash behaviour but is no longer the right default after the Phase 1 polish.

**Corrected guidance:**
- Phase 6 dashboards reference the Prometheus datasource by the pinned UID `prometheus` (and, where Loki panels are added later, by the pinned UID `loki`). The pin is what guarantees recreate-resilience; no panel variable indirection is required.
- The Implementation Notes bullet is superseded: hardcoded UID references against pinned UIDs are the preferred pattern. If a future datasource is added without a pinned UID, then-and-only-then revert to `${datasource}` variables for those panels.

**Status:** Dev's Phase 6 JSONs already use the pinned-UID pattern; this amendment makes the plan match the (now-correct) implementation. No code change needed.

### Amendment 4 — Phase 6 panel list extended with Phase 3 aggregate gauges

**Gap.** Phase 3 (commit `44ba23b`) registered two aggregate gauges that the Phase 6 panel list (drafted before Phase 3) does not mention:
- `bots_dead_currently` — count of bots whose `deadSince != null`.
- `groups_dead_currently` — count of runtimes whose `groupDeadSince != null`.

These are aggregate gauges (no MDC tags by AD 10), they answer a question the demo absolutely wants to be able to answer at a glance ("how many bots / groups are dead right now?"), and they shipped in Phase 3 without a corresponding Phase 6 panel entry. The Phase 6 commit `ba33234` faithfully implements the panel list as written, but the list itself is incomplete.

**Corrected Phase 6 panel additions:**
- `grafana/provisioning/dashboards/bots.json` — add stat panel "Currently DEAD bots" with expression `bots_dead_currently`. Same threshold pattern as the existing "Bot DEAD seconds (last 1h)" stat (green 0, yellow ≥1, red ≥5 — Dev to tune).
- `grafana/provisioning/dashboards/game-server.json` — add stat panel "Currently DEAD groups" with expression `groups_dead_currently`. Same threshold pattern.

**Status:** This is a follow-up micro-task for Dev (one short JSON edit per dashboard). It is NOT a SEND_BACK_TO_DEV against the Phase 6 commit because Dev faithfully implemented the panel list as it was written at the time. The amendment closes the Phase-3/Phase-6 sync gap so the follow-up edit is explicit.
