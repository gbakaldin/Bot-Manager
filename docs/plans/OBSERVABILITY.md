# Observability Plan — Grafana Metrics for Bot Manager

Owner: gleb · Target demo: Friday 2026-06-05 (24-48h horizon as of 2026-06-04)

This plan was originally a 5-phase draft written before the agentic pipeline pattern was adopted. It has been re-shaped in place around the same pattern as `docs/plans/LOGGING_PIPELINE_FIX.md`: each phase below is sized for one Dev session, lists files-touched + acceptance criteria + verification commands + independence + demo-blocker status, and a canonical Releaser checklist lives in the `## Verification` section at the bottom. Existing Architecture Decisions are largely intact; refinements and one new decision (MDC-as-tags) are noted explicitly.

**TL;DR (2026-06-04 amendment):** the demo's headline metric is **bot RTP per game** — not just "what are the bots doing." Phases 4 (bot winnings) and 5 (real-player share) are flipped from OPTIONAL/DEFERRED to **DEMO BLOCKER**. They share one code path (a capability-flag value-getter pattern on `BettingMiniGameBot` + new MDC-tagged counters in `BotMetrics`, wired in `BettingMiniGameBot.onEndGame` at line 187-196) and ship together as one Dev invocation. The minimum-viable demo set is now Phases 1+2+4+5+6+7.

---

## Goal

Surface bot-health, game-server-health, **and bot RTP / winnings / real-player share** metrics in Grafana so the Friday demo can answer the three questions stakeholders actually care about: (1) "are the bots running and behaving like real players?", (2) "is the game server healthy?", and (3) "what is the realized RTP per game and what share of the game-server load comes from bots vs real players?". The demo's headline panel is **bot RTP per game** — the entire purpose of running real-money-shaped bots is to model real-money behaviour, so a demo without RTP and winnings panels is hollow.

Pair the existing Loki/Promtail log pipeline (now ~97% MDC-labeled after `LOGGING_PIPELINE_FIX`) with a Prometheus + Micrometer metrics pipeline driven from the bot-manager itself, and provision both datasources + dashboards declaratively so a re-deploy never wipes them.

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
| Bot winnings | `BettingMiniGameBot.getWinnings()` (default 0), called in `onEndGame` (line 187-196) and forwarded to `BotMetrics.incBotWinnings(...)`. Per-game subclasses override the getter to read from EndGame payload. | ready (infrastructure); subclass overrides ship later, out of pipeline scope | Phase 4 |
| Bot jackpots | `BettingMiniGameBot.getJackpot()` (default 0), gated by `> 0` check; increments `bot_jackpots_total` (count) and `bot_jackpot_amount_total` (sum). | ready (infrastructure) | Phase 4 |
| RTP per game | `bot_winnings_total` ÷ `bot_bet_amount_total`, grouped by `gameType` in PromQL | derived in Grafana | Phase 4 |
| Daily money drain | `rate(bot_bet_amount_total)` − `rate(bot_winnings_total)` over 24h, in PromQL | derived in Grafana | Phase 4 |

### Game-server / real-player share metrics

| Metric | Source | Status | Phase |
|---|---|---|---|
| `game_total_winnings_total{gameType}` | `BettingMiniGameBot.getTotalWinnings()` (default 0), gated by `canCheckTotalWinnings()` (default false). Subclass overrides read from EndGame payload. | ready (infrastructure); subclass overrides land later | Phase 5 |
| `game_total_bet_amount_total{gameType}` | `BettingMiniGameBot.getTotalBetAmount()` (default 0), same gating | ready (infrastructure) | Phase 5 |
| Real-player bet share | `1 − (sum by (gameType) (bot_bet_amount_total) / game_total_bet_amount_total)` | derived in Grafana; renders only for games where subclass opts in | Phase 5 |
| Real-player RTP | `(game_total_winnings_total − sum by (gameType)(bot_winnings_total)) / (game_total_bet_amount_total − sum by (gameType)(bot_bet_amount_total))` | derived in Grafana | Phase 5 |

---

## Architecture Decisions (locked in)

1. **Stack — Prometheus alongside Loki.** Micrometer Prometheus registry on `/actuator/prometheus`. Grafana gets a second datasource. Loki stays for log search. No OpenTelemetry, no alternative. Locked.

2. **Winnings capture — capability-flag value getters on `BettingMiniGameBot`, defaulting to 0.** (REVISED 2026-06-04. Original AD 2 — pre-bet balance snapshot + delayed `getBalance(...)` refetch — is superseded.) Winnings are extracted from the protocol's EndGame payload by per-game subclasses, not derived from a balance delta. The base class `BettingMiniGameBot` declares the following methods with safe defaults:

    - `protected boolean canCheckTotalWinnings()` — default `false`. Returns true iff the game protocol exposes round-level aggregates (Bom/B52/Nohu `bs` arrays). Subclass overrides for protocols where totals are readable.
    - `protected long getWinnings()` — default `0L`. This bot's own winnings (gross payout) for the just-completed round. Subclass reads from the `EndGameMessage` payload it just received.
    - `protected long getJackpot()` — default `0L`. Jackpot value won this round; `0` means no jackpot. Subclass reads from protocol-specific jackpot fields (e.g. `tJpV` on `BomEndGameMessage`).
    - `protected long getTotalWinnings()` — default `0L`. Sum of all players' winnings for the just-completed round. Only meaningful when `canCheckTotalWinnings()` is true.
    - `protected long getTotalBetAmount()` — default `0L`. Sum of all players' bets for the just-completed round. Only meaningful when `canCheckTotalWinnings()` is true.

    **No balance-delta fallback ships in this pipeline.** The Phase-4-as-originally-written balance-delta mechanism is rejected because it costs one verify-token call per bot per round, depends on a 2-5 second refetch heuristic, and would conflate winnings with auto-deposit / drift correction. Instead: the default `getWinnings() == 0L` means **bots without a subclass override contribute 0 to RTP**. The demo audience cares about per-game RTP; if no specific game bot has implemented `getWinnings()` yet, that game's RTP panel renders 0/N (visible "0%") and the user narrates "this game is pending winnings instrumentation." Subclass overrides land incrementally after the pipeline.

    The user will implement the overrides for specific game bots (Bom, B52, Nohu) after this pipeline ships; that work is **out of scope** for the Architect plan and Dev should not attempt it. The pipeline must compile and run today with all overrides absent (all defaults returning 0 / false).

3. **Downtime semantics — DEAD only, STOPPED excluded.** Count bot-DEAD-seconds and group-DEAD-seconds only. Group `STOPPED` is intentional. A group cannot enter `DEAD` without first having been deliberately started, so any DEAD time is a real incident. Closing-out the accumulator: on transition out of DEAD (only `runWsReconnectLoop`'s success path can clear `reconnecting` after a DEAD state was reached, but DEAD → anything is currently a no-op in code — see Implementation Note below), and on `cleanup()` while DEAD. Same shape for groups: close out at `stopAllBots`.

4. **Real-player share metrics — capability-gated, partial-coverage-by-design.** (REVISED 2026-06-04 to align with AD 2's value-getter pattern.) Phase 5 wires two new MDC-tagged counters in `BotMetrics` (`game_total_winnings_total`, `game_total_bet_amount_total`) which are incremented from `BettingMiniGameBot.onEndGame` **only if `canCheckTotalWinnings()` returns `true`**. The base class default is `false`, so today no bot contributes to these counters; per-game subclasses opt in by overriding `canCheckTotalWinnings()` and the two getters. Grafana derives:
    - real-player bets = `game_total_bet_amount_total − bot_bet_amount_total` (PromQL `sum by (gameType)`),
    - real-player winnings = `game_total_winnings_total − bot_winnings_total`,
    - real-player share = real-player bets ÷ game total bets,
    - real-player RTP = real-player winnings ÷ real-player bets.

    Subscribe-time totals are no longer used as a Phase 5 input — winnings + total-bet aggregates are read from the EndGame payload in the subclass override, which keeps Phase 5's wiring symmetric with Phase 4 (one callsite, one capability check).

5. **Cardinality cap — `groupId × environmentId × gameType` for `bot_*` meters; `gameType` ONLY for `game_total_*` meters.** Do NOT tag by `username`, `botIndex`, `botId`, or any per-bot identifier. With ~10 bots per group and a handful of environments × gameTypes, the cardinality is bounded by ~(groups × games × envs) ≈ tens to low hundreds, regardless of bot count.

    **Amendment 2026-06-04 — `gameType`-only allow-list for game-aggregate meters.** The two Phase 5 counters (`game_total_winnings_total`, `game_total_bet_amount_total`) are **game-aggregate metrics, not per-bot metrics**. They MUST carry only the `gameType` tag — not `botGroupId` or `environmentId` (which would produce N×G series of the same aggregate). `BotMetrics` reads `MDC.get(BotMdc.GAME_TYPE)` and stamps only that tag on these meters; the `BotMdcTagsMeterFilter` allow-list is extended to skip MDC tagging for `game_total_*` names (defense-in-depth, same shape as today's aggregate-gauge exclusion). The PromQL subtraction `game_total_X − sum by (gameType)(bot_X)` still works: bot counters carry `gameType` so a `sum by (gameType)` projects to a comparable shape. The `BotMdcTagsMeterFilter` `AGGREGATE_METER_NAMES` set (currently 6 entries) grows to 8.

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

11. **Metric naming convention.** All bot-manager-emitted meters start with `bot_*` (per-bot semantics), `bots_*` (aggregate gauge), `group_*` (per-group semantics), or `game_*` (per-game-aggregate semantics, added 2026-06-04). All counters end in `_total`. All durations in seconds end in `_seconds_total`. New meter names introduced by Phases 4 and 5:
    - `bot_winnings_total` — per-bot gross winnings (Phase 4). Carries `botGroupId`, `environmentId`, `gameType` tags.
    - `bot_jackpots_total` — count of jackpot-winning rounds, incremented by 1 each time `getJackpot() > 0` (Phase 4). Same tags.
    - `bot_jackpot_amount_total` — total jackpot value won (Phase 4). Same tags.
    - `game_total_winnings_total` — aggregate per-round total winnings across all players for games where `canCheckTotalWinnings()` is true (Phase 5). Carries `gameType` tag ONLY (see AD 5).
    - `game_total_bet_amount_total` — aggregate per-round total bets across all players, same gating (Phase 5). Carries `gameType` tag ONLY.

    Spring Boot's built-in Micrometer meters (`jvm_*`, `http_server_requests_*`, etc.) are not renamed; they are part of the same scrape and remain useful for the demo's "is the bot-manager itself healthy" panel.

12. **`/actuator/prometheus` exposure scope.** Exposed on the same port as the rest of Actuator (`8085` inside container, `8080` on host). No separate management port. Acceptable per existing decision in `CLAUDE.md` (Actuator is internal-only, host port is reachable from Prometheus inside the compose network as `bot-manager:8085/actuator/prometheus`).

13. **(NEW 2026-06-04) Capability-flag value getters for per-game variability.** The Phase 4 / Phase 5 wiring is a **plain method-on-base-class pattern**, NOT a strategy / capability interface / plugin architecture. `BettingMiniGameBot` declares the five methods listed in AD 2 with safe defaults (`canCheckTotalWinnings()` → `false`, the four `long` getters → `0L`). The wiring code in `onEndGame` calls them unconditionally; safe defaults mean unrelated bots that never override them contribute 0 to RTP / share metrics without breaking. Per-game subclasses (added later, outside this pipeline) override the getters to read the relevant fields from the EndGame protocol payload they handle. Architecturally: do NOT introduce a `WinningsCapability` interface, a strategy bean, or a per-game lookup table. Plain virtual methods are what was asked for and what the codebase already uses for game-specific behavior (cf. `shouldBet`, `resolveBetAmount`, `resolveBetCondition` on `Bot.java`).

14. **(NEW 2026-06-04) STOPPED ≠ DEAD invariant unchanged by Phases 4-5.** Phase 4 and Phase 5 wiring lives strictly inside `BettingMiniGameBot.onEndGame` and inside `BotMetrics`. Neither touches `transitionStatus`, `markAsDead`, `stopAllBots`, or any other state-machine code path. Architecture Decision 3 (DEAD-only downtime, STOPPED excluded) is unaffected. Confirmed by inspection: `onEndGame` runs on `netty-ws-message-processor-ws-<userName>` threads wrapped by `mdcConsumer(this::onEndGame)` at `BettingMiniGameBot.java:330` — MDC is populated, status machine is untouched.

---

## Plan

The phases below are ordered for the Friday demo. Each phase is labeled with a demo-blocker status:

- **DEMO BLOCKER** — must ship for the Friday demo to land
- **DEMO OPTIONAL** — improves the demo but the demo's core message survives without it
- **DEFERRED** — explicitly post-demo

**Minimum viable demo (revised 2026-06-04):** Phases 1 + 2 + 4 + 5 + 6 + 7. Phases 4 and 5 are now demo blockers — the headline panel is bot RTP per game, and a demo without it does not land. Phases 4 and 5 share one code path (capability-flag value getters on `BettingMiniGameBot` + new `BotMetrics` methods, wired in `BettingMiniGameBot.onEndGame`) and ship as **one Dev invocation**. Phase 3 (downtime) remains DEMO OPTIONAL: attempt only if there is slack after Phases 1, 2, 4, 5, 6 are merged and Phase 7 is green. Architect recommendation: ship 1+2 first (already underway / done at HEAD), then ship 4+5 together (single Dev session), then Phase 6 dashboards (panels for the new metrics included), then Phase 7 verification Friday morning. If a Dev session for 4+5 reviews larger than expected on first attempt, split: Phase 4 (winnings + jackpot) ships first; Phase 5 (game-totals) ships second.

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

### Phase 4 — Bot winnings + jackpots + RTP (capability-flag pattern) — **DEMO BLOCKER**

**Scope (REVISED 2026-06-04):** Add three capability-flag value getters to `BettingMiniGameBot` (defaulting to 0 / false), add three new MDC-tagged counters in `BotMetrics`, and wire the increments in `BettingMiniGameBot.onEndGame` (which is already `mdcConsumer`-wrapped at line 330, so MDC is populated when the wiring fires). No balance refetch, no scheduler, no per-round verify-token call. No changes to `transitionStatus` / `markAsDead` / state machine — AD 14.

**Files touched:**

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`:
  - Add three protected methods with safe defaults. **Exact signatures and default bodies** (Dev must use these names verbatim — they are the contract):
    ```java
    /** This bot's gross winnings (payout) for the just-completed round.
     *  Default 0; subclasses override to read from the EndGame payload. */
    protected long getWinnings() { return 0L; }

    /** Jackpot value this bot won in the just-completed round; 0 if none.
     *  Default 0; subclasses override to read protocol-specific jackpot fields. */
    protected long getJackpot() { return 0L; }
    ```
    Place these next to the other `protected`-on-`Bot` overrides (`shouldBet`, `resolveBetAmount`, `resolveBetCondition`, `botBehaviorScenario`, `onStart`) — i.e. between `resolveBetAmount` (line 273) and `resolveIntervalBetweenBets` (line 286) is the most natural location. Pure Java methods, no annotations.
  - In `onEndGame` (lines 187-196 of `BettingMiniGameBot.java`), after the existing `metrics.incBotMessage("endGame")` call at line 188 and BEFORE `scheduleWatchdog()` at line 194, add:
    ```java
    if (metrics != null) {
        long winnings = getWinnings();
        if (winnings > 0) {
            metrics.incBotWinnings(winnings);
            lastRoundWinnings = winnings;  // updates Bot.lastRoundWinnings for the health DTO
        }
        long jackpot = getJackpot();
        if (jackpot > 0) {
            metrics.incBotJackpot(jackpot);
        }
    }
    ```
    Reads happen on the netty-ws-message-processor pool; subclass implementations of `getWinnings()` / `getJackpot()` must be cheap (read a field set during message decode, no blocking I/O). The existing `mdcConsumer(this::onEndGame)` wrap at line 330 guarantees MDC is populated; AD 10 means the counters get group/env/game tags automatically.

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`:
  - Add three new constants:
    ```java
    public static final String BOT_WINNINGS_TOTAL       = "bot_winnings_total";
    public static final String BOT_JACKPOTS_TOTAL       = "bot_jackpots_total";
    public static final String BOT_JACKPOT_AMOUNT_TOTAL = "bot_jackpot_amount_total";
    ```
  - Add two new methods (exact signatures):
    ```java
    /** Per-bot gross winnings counter; increments by the round payout amount.
     *  Caller guards on amount > 0. */
    public void incBotWinnings(long amount) {
        Counter.builder(BOT_WINNINGS_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment(amount);
    }

    /** Per-bot jackpot: increments {@code bot_jackpots_total} by 1 (round count)
     *  and {@code bot_jackpot_amount_total} by amount (sum of jackpot value).
     *  Caller guards on amount > 0. */
    public void incBotJackpot(long amount) {
        Tags tags = mdcTags();
        Counter.builder(BOT_JACKPOTS_TOTAL)
                .tags(tags)
                .register(registry)
                .increment();
        Counter.builder(BOT_JACKPOT_AMOUNT_TOTAL)
                .tags(tags)
                .register(registry)
                .increment(amount);
    }
    ```
    Pattern is the same as the existing `incBetPlaced(long)` (line 153-163): two counters per call, same tag set, atomic at the call.

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
  - **No new fields.** The existing `protected volatile long lastRoundWinnings` at line 74 is repurposed — `BettingMiniGameBot.onEndGame` writes to it when winnings > 0. The field already has a `@Getter` and is read by `BotHealthDTO` (verify by grep before implementation), which is the UI's winnings display. No new accessor needed.
  - The plan from the original draft to compute winnings from a balance delta is dropped (AD 2 revision). No new `preRoundBalance` / `betAmountThisRound` fields.

- **No changes to `BotMdcTagsMeterFilter.java`** for Phase 4 — the new meters start with `bot_*` and SHOULD pick up MDC tags via both the per-callsite `mdcTags()` read and the filter's defense-in-depth path.

**Acceptance criteria:**
- `mvn test` passes. Add unit tests:
  1. `BotMetricsTest.incBotWinnings_attachesMdcTags()` — populate MDC with `botGroupId=g1`, `environmentId=e1`, `gameType=BomMd5`; call `incBotWinnings(500)`; assert the registry has `bot_winnings_total{botGroupId="g1",environmentId="e1",gameType="BomMd5"} = 500.0`.
  2. `BotMetricsTest.incBotJackpot_incrementsBothCounters()` — call `incBotJackpot(10000)`; assert `bot_jackpots_total = 1.0` AND `bot_jackpot_amount_total = 10000.0`.
  3. `BettingMiniGameBotTest.onEndGame_zeroWinnings_doesNotEmitWinningsCounter()` — verify default `getWinnings() == 0` path does NOT register `bot_winnings_total` (or registers with value 0; either is acceptable as long as no `0` increment fires).
  4. `BettingMiniGameBotTest.onEndGame_overriddenWinnings_emitsCounterAndUpdatesField()` — anonymous subclass overrides `getWinnings()` to return 750; assert counter increments by 750 and `lastRoundWinnings == 750` after `onEndGame` returns.
- After deploy + group run with **no subclass override yet**, `/actuator/prometheus` shows `bot_winnings_total` either absent or at 0 (acceptable — no bot has implemented `getWinnings()`).
- `BotGroupHealthDTO.bots[].lastRoundWinnings` remains 0 across all bots (no override = no writes) — this is correct, expected behavior, and matches the demo narrative ("subclass overrides ship later").

**Verification commands:**
```bash
# Counter registration (may be absent if no winnings have fired yet — both are acceptable)
curl -fsS http://localhost:8080/actuator/prometheus | grep -E '^(bot_winnings_total|bot_jackpots_total|bot_jackpot_amount_total)'
# Expect: 0 lines OR lines with value 0 OR positive values, all acceptable. Failure mode: server-500 on the scrape, or "Permission denied" trace.

# Tag set when present
curl -fsS http://localhost:8080/actuator/prometheus | grep '^bot_winnings_total{' | head -1
# Expect: contains botGroupId, environmentId, gameType tags. No username/botId.
```

**Independence:** Depends on Phases 1 + 2 (scrape pipe + `BotMetrics` infrastructure). Independent of Phase 3 (downtime). Phase 5 depends on Phase 4 having added the value-getter pattern to `BettingMiniGameBot` — recommended to ship in the same Dev session.

**Demo-blocker:** YES. The headline RTP-per-game panel queries `rate(bot_winnings_total[5m]) / rate(bot_bet_amount_total[5m])`. Without `bot_winnings_total` registered (even at 0), the panel shows a datasource error rather than a clean "0%" — and the user has explicitly said this is the demo's primary story.

---

### Phase 5 — Real-player share metrics — **DEMO BLOCKER**

**Scope (REVISED 2026-06-04, originally DEFERRED):** Same shape as Phase 4. Add three more capability-flag methods to `BettingMiniGameBot` (one boolean gate + two `long` value getters), add two new MDC-tagged counters to `BotMetrics` carrying ONLY the `gameType` tag, wire them in `onEndGame` behind the `canCheckTotalWinnings()` gate, and extend the `BotMdcTagsMeterFilter` allow-list to skip MDC tagging for the new `game_total_*` meters.

**Files touched:**

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`:
  - Add three protected methods (exact signatures):
    ```java
    /** True if the game protocol exposes round-level aggregates (e.g., Bom/B52/Nohu
     *  `bs` arrays). Default false; subclasses override when totals are readable. */
    protected boolean canCheckTotalWinnings() { return false; }

    /** Sum of all players' winnings for the just-completed round.
     *  Only meaningful when {@link #canCheckTotalWinnings()} is true. Default 0. */
    protected long getTotalWinnings() { return 0L; }

    /** Sum of all players' bets for the just-completed round.
     *  Only meaningful when {@link #canCheckTotalWinnings()} is true. Default 0. */
    protected long getTotalBetAmount() { return 0L; }
    ```
    Place adjacent to the Phase 4 getters.
  - Extend the `onEndGame` block added in Phase 4 with the gated path:
    ```java
    if (metrics != null && canCheckTotalWinnings()) {
        long totalWin = getTotalWinnings();
        long totalBet = getTotalBetAmount();
        if (totalWin > 0) metrics.incGameTotalWinnings(totalWin);
        if (totalBet > 0) metrics.incGameTotalBetAmount(totalBet);
    }
    ```
    Place after the Phase 4 winnings/jackpot block, still before `scheduleWatchdog()`.

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`:
  - Add two new constants:
    ```java
    public static final String GAME_TOTAL_WINNINGS_TOTAL   = "game_total_winnings_total";
    public static final String GAME_TOTAL_BET_AMOUNT_TOTAL = "game_total_bet_amount_total";
    ```
  - Add a private helper that reads ONLY the `gameType` tag from MDC (do not reuse `mdcTags()`, which would also stamp `botGroupId` and `environmentId`):
    ```java
    private Tags gameTypeTagOnly() {
        String gameType = MDC.get(BotMdc.GAME_TYPE);
        return (gameType == null || gameType.isEmpty()) ? Tags.empty() : Tags.of(BotMdc.GAME_TYPE, gameType);
    }
    ```
  - Add two new methods (exact signatures):
    ```java
    /** Per-game-aggregate total winnings for the just-completed round.
     *  Carries only the {@code gameType} tag (see AD 5). Caller guards on amount > 0. */
    public void incGameTotalWinnings(long amount) {
        Counter.builder(GAME_TOTAL_WINNINGS_TOTAL)
                .tags(gameTypeTagOnly())
                .register(registry)
                .increment(amount);
    }

    /** Per-game-aggregate total bet amount for the just-completed round.
     *  Carries only the {@code gameType} tag (see AD 5). Caller guards on amount > 0. */
    public void incGameTotalBetAmount(long amount) {
        Counter.builder(GAME_TOTAL_BET_AMOUNT_TOTAL)
                .tags(gameTypeTagOnly())
                .register(registry)
                .increment(amount);
    }
    ```

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java`:
  - Extend the `AGGREGATE_METER_NAMES` set (currently 6 entries; grows to 8):
    ```java
    "game_total_winnings_total",
    "game_total_bet_amount_total"
    ```
    Rationale: these meters MUST NOT receive `botGroupId` / `environmentId` tags (AD 5). They carry only `gameType`, which `BotMetrics` applies directly via `gameTypeTagOnly()`. The filter allow-list is defense-in-depth: should any code path register a `game_total_*` meter outside `BotMetrics`, the filter ensures MDC-derived `botGroupId` / `environmentId` are not auto-stamped.
  - **Important nuance for the filter:** the filter's name check is `name.startsWith("bot_")`, so a `game_*` name already bypasses MDC tagging by virtue of the prefix check (lines 62-64). Adding the names to `AGGREGATE_METER_NAMES` is belt-and-braces — if the prefix policy ever flips to include `game_*`, the allow-list is the second line of defense. Dev should add both the allow-list entries AND a code comment in `BotMdcTagsMeterFilter` documenting that the prefix check already excludes `game_*`.

**Acceptance criteria:**
- `mvn test` passes. Unit tests:
  1. `BotMetricsTest.incGameTotalWinnings_attachesOnlyGameTypeTag()` — populate MDC with all three of `botGroupId`, `environmentId`, `gameType`; call `incGameTotalWinnings(1000)`; assert the registry has `game_total_winnings_total{gameType="..."} = 1000.0` AND NO `botGroupId` / `environmentId` tags.
  2. `BotMetricsTest.incGameTotalBetAmount_attachesOnlyGameTypeTag()` — symmetric.
  3. `BettingMiniGameBotTest.onEndGame_canCheckTotalsFalse_skipsGameTotalsCounters()` — verify default gate path does not call `incGameTotal*`.
  4. `BettingMiniGameBotTest.onEndGame_canCheckTotalsTrue_emitsBothCounters()` — anonymous subclass overrides `canCheckTotalWinnings() → true`, `getTotalBetAmount() → 5000`, `getTotalWinnings() → 4750`; assert both counters increment by their respective amounts.
- After deploy with no subclass overrides, `game_total_*` counters are absent or at 0 from the scrape (acceptable; nothing has opted in).

**Verification commands:**
```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep -E '^(game_total_winnings_total|game_total_bet_amount_total)'
# Expect: 0 lines OR lines with value 0. Once a subclass opts in, expect positive values.

# Tag shape — when present, ONLY gameType
curl -fsS http://localhost:8080/actuator/prometheus | grep '^game_total_winnings_total{' | head -1
# Expect: line of form game_total_winnings_total{application="bot-manager",gameType="..."} N.0
# Must NOT contain botGroupId or environmentId tags.
```

**Independence:** Depends on Phase 4 (the value-getter pattern is added to `BettingMiniGameBot` in Phase 4 and extended here). Ship in the same Dev session as Phase 4 unless the review surface is too large — see TL;DR for split guidance.

**Demo-blocker:** YES. The real-player-share panel is the demo's "bots are realistic relative to real traffic" story. Counter registration (even at 0) is required for the dashboard panel to render without a datasource error.

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
  10. **(Phase 4, demo headline) Bot RTP per game** — time series, `sum by (gameType) (rate(bot_winnings_total[5m])) / sum by (gameType) (rate(bot_bet_amount_total[5m]))`. Display as percentage. Y-axis `[0%, 200%]`; reference line at `100%`. Panel title: "Bot RTP per game (5m rate)". Null-handling: render gracefully when numerator is zero (subclass override not yet shipped) — should display 0% rather than NaN. Use `or vector(0)` in the PromQL if needed.
  11. **(Phase 4, demo headline) Bot RTP overall** — stat panel, `sum(rate(bot_winnings_total[5m])) / sum(rate(bot_bet_amount_total[5m]))`. Single big number. Same null-handling.
  12. **(Phase 4) Bot winnings rate per game** — time series, `sum by (gameType) (rate(bot_winnings_total[5m]))`. Y-axis: gold/credits/sec.
  13. **(Phase 4) Bot money drain per game** — time series, `sum by (gameType) (rate(bot_bet_amount_total[5m])) - sum by (gameType) (rate(bot_winnings_total[5m]))`. Positive = bots are net-losing (RTP < 100%). Panel title: "Net cost to fund bots (per game, 5m)".
  14. **(Phase 4) Bot jackpot wins rate** — time series, `sum by (gameType) (rate(bot_jackpots_total[5m]))`. Rare events; expect mostly 0.
  15. **(Phase 4) Bot jackpot total amount** — time series, `sum by (gameType) (rate(bot_jackpot_amount_total[5m]))`. Stacked area is fine.
  16. (Phase 3, if shipped) Bot DEAD seconds in window (stat, `increase(bot_dead_seconds_total[1h])`).
  17. (Phase 3, if shipped) Currently DEAD bots (stat, `bots_dead_currently`).

- `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/game-server.json` (new) — panels:
  1. Login success rate (time series, `rate(bot_login_total{outcome="success"}[5m]) / rate(bot_login_total[5m])`).
  2. Verify-token success rate (same pattern with `bot_verify_token_total`).
  3. Message receive rate by `cmd` (time series, `rate(bot_messages_total[1m])` by `cmd`).
  4. Watchdog firings (time series, `rate(bot_watchdog_expired_total[5m])` — the "game is silent" signal).
  5. WS event rate by `event` (time series, `rate(bot_ws_connections_total[5m])` by `event`).
  6. (Phase 3) Group DEAD seconds (stat, `increase(group_dead_seconds_total[1h])`).
  7. (Phase 3) Currently DEAD groups (stat, `groups_dead_currently`).
  8. **(Phase 5) Bot bet share per game** — time series, `sum by (gameType) (rate(bot_bet_amount_total[5m])) / sum by (gameType) (rate(game_total_bet_amount_total[5m]))`. Display as percentage. Panel description: "Share of game's total bet volume attributable to bots; N/A for games without subclass winnings instrumentation."
  9. **(Phase 5) Real-player bet share per game** — time series, `1 - (sum by (gameType) (rate(bot_bet_amount_total[5m])) / sum by (gameType) (rate(game_total_bet_amount_total[5m])))`. Companion to panel 8.
  10. **(Phase 5) Real-player RTP per game** — time series, `(sum by (gameType) (rate(game_total_winnings_total[5m])) - sum by (gameType) (rate(bot_winnings_total[5m]))) / (sum by (gameType) (rate(game_total_bet_amount_total[5m])) - sum by (gameType) (rate(bot_bet_amount_total[5m])))`. Reference line at `100%`. Compare side-by-side with the bot-RTP panel on the `Bots` dashboard.

**Null / "no data" handling for the new panels.** Today no subclass overrides `getWinnings()` / `canCheckTotalWinnings()`, so the new counters may not be registered at all when the dashboard first opens. Two acceptable strategies — Dev picks whichever Grafana version supports cleanly:
- (a) Use `or vector(0)` in the PromQL to fall back to 0 when a series is absent. Renders "0%" cleanly.
- (b) Set panel `noValue` to `"0"` (Grafana 11.4 supports this). Cleaner JSON.
Either is fine; the bar is "panel renders without a red-banner datasource error during demo." Test by opening the dashboard against a freshly-deployed instance with no override shipped.

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

**Scope:** Releaser runs the canonical post-deploy verification checklist (`## Verification` section at the bottom of this plan) on the staging Bot-1 host after deploying the changes from Phases 1+2+4+5+6 (minimum-viable demo set) and Phase 3 (if shipped). No code. This is the Releaser's role per `docs/process/AGENTIC_WORKFLOW.md`.

**Acceptance criteria (REVISED 2026-06-04):** Every numbered step in `## Verification` returns the expected output. Specifically:
- Steps 1-8 (Phase 1 + 2 + 6 baseline) all green.
- Steps 9-10 (Phase 4 + 5 winnings / share counters) all green — counters are **registered** (presence is the test; values may be 0 if no subclass override has shipped yet, and that is the correct expected state at demo time).
- Step 11 (dashboards render the new panels without datasource error) green.
- Step 12 (Phase 3, if shipped) green or N/A.

**Demo-blocker:** YES. Without live verification the user can't demo with confidence.

---

## Implementation Notes / Concerns

- **(SUPERSEDED 2026-06-04) `expectedCurrentBalance` balance-delta winnings calc.** The original draft pre-snapshotted balance at `onStartGame` and refetched at `onEndGame` to derive winnings. Replaced by the capability-flag value-getter pattern (AD 2 / AD 13). No new fields on `Bot`; `lastRoundWinnings` is repurposed but no balance snapshot logic is needed.

- **Phase 4 / Phase 5 wiring is purely additive inside `onEndGame`.** Both phases add code blocks between the existing `metrics.incBotMessage("endGame")` at `BettingMiniGameBot.java:188` and `scheduleWatchdog()` at line 194. They do NOT touch the state machine, `transitionStatus`, `markAsDead`, or any reconnect / cleanup path. AD 14 — invariant unchanged.

- **MDC is guaranteed at the Phase 4/5 callsite.** `BettingMiniGameBot.onEndGame` is registered via `mdcConsumer(this::onEndGame)` at line 330. That wrap re-applies `mdcSnapshot` before dispatch. So `BotMetrics.incBotWinnings(...)` / `incBotJackpot(...)` / `incGameTotalWinnings(...)` / `incGameTotalBetAmount(...)` all read MDC successfully and emit the expected tags. No manual tag-passing required.

- **`getWinnings()` / `getJackpot()` / `getTotalWinnings()` / `getTotalBetAmount()` must be cheap and non-blocking.** They run on the netty-ws-message-processor pool. Subclass implementations should read from a field populated during message decoding (e.g. a `volatile long lastWinnings` set in the EndGame message handler), not from an I/O call. This constraint is informational for the subclass-implementation work that ships after this pipeline; Dev does NOT implement any subclass here.

- **No new field needed for the Phase 5 game-totals.** Unlike Phase 4 (which writes `lastRoundWinnings` for the health DTO), Phase 5 only emits counters. No `Bot` / `BotHealthDTO` / `BotGroupHealthDTO` field is touched.

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
- **Drift between `expectedCurrentBalance` and actual server balance** — (NOTE 2026-06-04) no longer relevant to Phases 4/5; winnings come from EndGame payload, not balance delta. The existing 1M-delta refresh in `checkBalance()` is untouched by this plan.
- **`bot.winnings.sample-rate` config knob** — (NOTE 2026-06-04) no longer needed: no refetch traffic is added. Item closed.
- **Per-game subclass overrides for the new value getters** — out of scope for this pipeline. The user will implement `getWinnings()`, `getJackpot()`, `canCheckTotalWinnings()`, `getTotalWinnings()`, `getTotalBetAmount()` in `BomBot` / `B52Bot` / `NohuBot` (or as inline behavior inside `BettingMiniGameBot` for specific protocols, design TBD) after this pipeline lands. The infrastructure pipeline must compile and run with all defaults; the demo narrates "subclass instrumentation rolls out per game, starting Monday."
- **Watchdog reason taxonomy** — current `triggerFullReconnect(reason)` string is free-form. Phase 2 normalizes "watchdog timeout..." → "watchdog", everything else → "ws-disconnect" or "reauth-cycle" at the increment site. Formal enum out of scope.
- **Dashboards beyond demo** — JVM dashboard, HTTP request dashboard. Not in this plan.

---

## Verification

Canonical post-deploy verification, run by the Releaser on Bot-1 after the deploy lands. Each step has an exact command and expected result.

The verification runs against host `Bot-1` with `http://Bot-1:8080` (bot-manager), `http://Bot-1:9090` (Prometheus), `http://Bot-1:3000` (Grafana, admin/admin).

The minimum-viable demo set (REVISED 2026-06-04) is steps **1–11** (covers Phases 1, 2, 4, 5, 6). Step **12** is conditional on Phase 3 having shipped. Steps 9-11 test that the new Phase 4 / Phase 5 counters are **registered** in the scrape; values may be 0 because subclass overrides (per AD 2 / AD 13) ship after this pipeline — the verification's job is to confirm the infrastructure is in place, not to validate values that depend on later subclass work.

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

### Step 9 — Phase 4 winnings + jackpot counters registered

```bash
curl -fsS http://Bot-1:8080/actuator/prometheus \
  | grep -cE '^# (HELP|TYPE) (bot_winnings_total|bot_jackpots_total|bot_jackpot_amount_total) '
```
Expect: integer ≥ 6 (HELP + TYPE for each of the three new meters) OR a positive value of `0` if the counters are present without HELP/TYPE (Micrometer convention varies; the existence-of-name check below is the canonical assertion).

Alternative existence check (works even when meters have not been incremented):
```bash
curl -fsS http://Bot-1:8080/actuator/prometheus \
  | grep -cE '^bot_winnings_total|^bot_jackpots_total|^bot_jackpot_amount_total'
```
Expect: integer ≥ 3 once any subclass override fires. Until then: 0 lines, which is **acceptable** — the architectural contract (AD 2, AD 13) is that the counters are registered lazily on first increment. The functional test is that the Grafana panel renders cleanly without datasource error (Step 11). Releaser: if no `bot_winnings_total` lines appear, run a 5-minute load and re-check; if still absent, that confirms no subclass has overridden `getWinnings()` yet — expected at demo time.

### Step 10 — Phase 5 game-total counters registered AND carry only `gameType` tag

```bash
# Existence (may be 0 lines until a subclass opts in via canCheckTotalWinnings() — acceptable)
curl -fsS http://Bot-1:8080/actuator/prometheus \
  | grep -E '^(game_total_winnings_total|game_total_bet_amount_total)'
```

Tag-shape assertion (only fires if the meter has been registered — skip if Step 10a returned 0 lines):
```bash
curl -fsS http://Bot-1:8080/actuator/prometheus \
  | grep '^game_total_winnings_total{' | head -1
```
Expect (when present): line contains `gameType="..."`. **MUST NOT** contain `botGroupId` or `environmentId` tags (AD 5). If either of those tags appears on a `game_total_*` line, the deploy is broken — Dev must be re-engaged.

### Step 11 — Grafana RTP + share panels render

Open `http://Bot-1:3000/d/bots` and verify:
- "Bot RTP per game (5m rate)" panel renders. Value displayed as `0%` or a real percentage; NO red datasource-error banner.
- "Bot RTP overall" stat renders.
- "Bot money drain per game" renders.
- "Bot jackpot wins rate" + "Bot jackpot total amount" render (likely flat 0).

Open `http://Bot-1:3000/d/game-server` and verify:
- "Bot bet share per game" renders.
- "Real-player bet share per game" renders.
- "Real-player RTP per game" renders.

If any panel shows a red datasource error (not "No data" / "0%"), the deploy has a panel JSON syntax problem — Dev fix required. "No data" / "0" is **acceptable** at demo time (subclass overrides ship later).

### Step 12 — Downtime accumulators *(only if Phase 3 shipped)*

```bash
curl -fsS http://Bot-1:8080/actuator/prometheus | grep -E '^(bot_dead_seconds_total|group_dead_seconds_total|bots_dead_currently|groups_dead_currently)'
```
Expect: each name registered. Counters at 0 if nothing died in the verification window — which is fine; the existence-of-meter check is the point.

### Cleanup

```bash
curl -X POST -fsS http://Bot-1:8080/api/v1/bot-group/$GID/stop
```
Expect: HTTP 200.

If any of steps 1–11 fail, the demo is at risk and Architect must be re-engaged before Friday. Step 12 is informational only.

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

---

## Amendment — 2026-06-04 (RTP / winnings / share — Phase 4 + 5 demo-blocker promotion)

Largest revision to date. User feedback during the Phase 6 retrospective: Phases 4 (winnings) and 5 (real-player share) are not optional — they are the demo's headline. The whole point of running real-money-shaped bots is to model real-money behavior, so a demo without bot RTP / winnings / real-player share panels is hollow. The original draft had these phases as OPTIONAL / DEFERRED; this amendment flips them both to DEMO BLOCKER and redesigns them around a capability-flag value-getter pattern that the user can incrementally implement per-game subclass after this pipeline ships.

### Amendment 5 — Phases 4 and 5 promoted to DEMO BLOCKER

The minimum-viable demo set is now Phases **1 + 2 + 4 + 5 + 6 + 7**. Phase 3 (downtime) remains DEMO OPTIONAL. The TL;DR at the top of the plan, the Goal paragraph, and the "Minimum viable demo" paragraph under `## Plan` are all updated to reflect this. Phase 7's acceptance criteria is updated to cover the new counters.

### Amendment 6 — Phase 4 design replaced (balance-delta refetch → capability-flag value getter)

**Original Phase 4 design (now superseded):** snapshot `expectedCurrentBalance` at `onStartGame`, refetch via `apiGatewayClient.getBalance(...)` ~2 seconds post-`onEndGame`, derive `winnings = newBalance - (preRoundBalance - betAmountThisRound)`. Cost: 1 extra verify-token call per bot per round; depends on a timing heuristic; introduces drift / auto-deposit conflation risk.

**New Phase 4 design:** `BettingMiniGameBot` declares `protected long getWinnings() { return 0L; }` and `protected long getJackpot() { return 0L; }`. `BotMetrics` gains `incBotWinnings(long)` and `incBotJackpot(long)` (the latter increments both a count and an amount counter, same shape as the existing `incBetPlaced`). Wiring lives in `BettingMiniGameBot.onEndGame` between line 188 and line 194; reads are gated on `> 0`. Per-game subclasses override the getters to read fields populated during EndGame message decode. **Subclass overrides ship after this pipeline — out of scope for the Architect plan and Dev session.**

**Architecture Decision 2 rewritten** to document the new design. Architecture Decision 13 added to lock in the capability-flag value-getter pattern (plain methods on base class; explicitly NOT a `WinningsCapability` interface or strategy bean). Architecture Decision 14 added to confirm STOPPED ≠ DEAD invariant (AD 3) is unaffected.

### Amendment 7 — Phase 5 design crystallized (game-aggregate counters with `gameType`-only tags)

The original Phase 5 was DEFERRED with TBD files. The new Phase 5 is symmetric with Phase 4: three more methods on `BettingMiniGameBot` (`canCheckTotalWinnings() → false`, `getTotalWinnings() → 0L`, `getTotalBetAmount() → 0L`), two new counters in `BotMetrics` (`game_total_winnings_total`, `game_total_bet_amount_total`), wired in the same `onEndGame` block as Phase 4 but gated on `canCheckTotalWinnings()`. **Critically: these new counters carry ONLY the `gameType` tag** — not `botGroupId` or `environmentId` (AD 5 amendment). `BotMetrics` adds a private `gameTypeTagOnly()` helper that reads only `MDC.get(BotMdc.GAME_TYPE)`. `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES` grows from 6 to 8 entries (defense-in-depth — the name-prefix policy already excludes `game_*` from the filter's MDC tagging, but the allow-list is belt-and-braces).

### Amendment 8 — Phase 6 dashboard panel list extended with RTP / winnings / jackpot / share panels

`bots.json` gains panels 10-15 (Bot RTP per game, Bot RTP overall, Bot winnings rate per game, Bot money drain per game, Bot jackpot wins rate, Bot jackpot total amount). `game-server.json` gains panels 8-10 (Bot bet share per game, Real-player bet share per game, Real-player RTP per game). Null/no-data handling: use `or vector(0)` or `noValue: "0"` to render cleanly when subclass overrides have not shipped — the demo bar is "no red datasource-error banner."

### Amendment 9 — `## Verification` extended with steps 9-11

Step 9 verifies Phase 4 counters are registered. Step 10 verifies Phase 5 counters are registered AND carry only the `gameType` tag (critical correctness check — if a `botGroupId` tag appears on `game_total_*`, the deploy is broken). Step 11 verifies the new Grafana panels render without datasource errors. Step 12 (Phase 3 downtime) is renumbered from the old Step 10.

### Bundling guidance for Dev

Phases 4 and 5 should be ONE Dev invocation. They share the same wiring callsite (`BettingMiniGameBot.onEndGame`), the same code path, the same test infrastructure, and the same review surface. Splitting them would force Dev to re-Read `BettingMiniGameBot.java` and re-read `BotMetrics.java` twice; the combined review surface is on the order of ~100 lines added across 3 files. If the first review surfaces unexpected complexity (e.g. test-harness flakiness around MDC + Counter caching), split into two: Phase 4 first (winnings + jackpot), Phase 5 second (game-totals). Default: ONE invocation.

---

## Amendment — 2026-06-04 (Phase 4 + 5 compliance review)

Two surgical corrections applied during the Phase 4 + 5 compliance review (verdict PLAN_AMENDED at commit `07d6e28`). Both reflect codebase constraints the Architect missed when drafting Phase 4 + 5 / AD 11.

### Amendment 10 — Phase 5 method renamed `getTotalBetAmount()` → `getRoundTotalBetAmount()`

**Why the original name was wrong.** `Bot.java:71-72` declares:

```java
@Getter
protected final AtomicLong totalBetAmount = new AtomicLong(0);
```

Lombok generates a `public AtomicLong getTotalBetAmount()` on `Bot`. The plan's `protected long getTotalBetAmount()` on `BettingMiniGameBot extends Bot` is not a valid Java override — both the access modifier (protected vs public) and the return type (`long` vs `AtomicLong`) are incompatible. The compiler rejects the declaration outright. The original name is structurally impossible.

**Corrected method signature in AD 11 and Phase 5:**

```java
/** Sum of all players' bets for the just-completed round.
 *  Only meaningful when {@link #canCheckTotalWinnings()} is true. Default 0.
 *
 *  Renamed from getTotalBetAmount() to avoid collision with the Lombok-generated
 *  Bot.getTotalBetAmount() (returns AtomicLong for the per-bot lifetime accumulator
 *  read by BotHealthDTO). The two are semantically distinct: per-round game total
 *  vs. per-bot lifetime total. Keeping the Bot-level accessor stable preserves
 *  the BotHealthDTO contract.
 */
protected long getRoundTotalBetAmount() { return 0L; }
```

**Status:** Real codebase constraint, not a Dev preference. Dev's chosen name is also more semantically precise. The plan's AD 11 "new meter names" list is unaffected — only the method name on `BettingMiniGameBot` changes.

### Amendment 11 — `lastRoundWinnings` is written unconditionally in Phase 4

The Phase 4 body originally specified:

```java
if (winnings > 0) {
    metrics.incBotWinnings(winnings);
    lastRoundWinnings = winnings;
}
```

The shipped implementation writes both unconditionally (the `> 0` gate is dropped). This is a deliberate user-endorsed choice and is semantically correct: `lastRoundWinnings` means "the latest round's winnings" — when the bot didn't win this round, the field should reflect 0, not stick at the previous round's max. The behavior is also consistent with the field name and with how `BotHealthDTO.lastRoundWinnings` is consumed (a snapshot of the most recent round). Jackpot remains gated on `> 0` because emitting a jackpot count of 0 would still consume a meter slot per round.

**Status:** Plan body bullet at the Phase 4 wiring snippet is hereby loosened — the `> 0` guard around the winnings increment + `lastRoundWinnings` write is OPTIONAL. The jackpot gate stays mandatory.
