# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bot Manager is a Spring Boot application for orchestrating game bots that interact with mini-game servers via WebSocket connections. Built on top of the [WebSocket Parser](https://github.com/vingame/websocket-parser) library.

## Build & Run Commands

```bash
mvn clean install      # Build the project
mvn spring-boot:run    # Run the application
mvn package            # Package the application
```

The main entry point is `com.vingame.bot.Starter` (Spring Boot application).

## Technology Stack

- **Java 21** with Virtual Threads (Project Loom)
- **Spring Boot 3.4.0** with Spring Web (the parent `pom.xml` pins 3.4.0; this file
  said 4.0.0 for a long time and was simply wrong)
- **Maven** for dependency management
- **MongoDB** via Spring Data MongoDB
- **Lombok** for boilerplate reduction
- **MapStruct 1.6.3** for DTO/Entity mapping
- **Jackson** for JSON serialization/deserialization with polymorphism support
- **Log4j2** (via slf4j2) for logging
- **OpenAPI/Swagger** (SpringDoc) at `/swagger-ui.html`
- **WebSocket Parser** (custom library: `vingame:websocket-parser:1.0-SNAPSHOT`)

## Logging Guidelines

Normative levels for `com.vingame.bot.*`, restructured by
`docs/plans/LOG_VOLUME_TIERING.md` (Phase 1) around a **tier model**, and split
across **two output tracks** by Phase 4. The target is ~10 prod environments x 2-3k
bots each, where the previous shape projected to **46-124 GB/day** against a 10 GB
accumulated-file cap on a box that has already died once on ENOSPC (2026-06-30,
taking Mongo with it).

### Two tracks: what lands where (Phase 4)

Phases 0-3 made `com.vingame.bot` cheap and the release measured it: over one
~11-minute window on a 155-bot fleet our own loggers emitted **59** INFO lines and
`com.vingame.websocketparser` emitted **4,402** -- per bot, at INFO, under the
*root* logger, i.e. **98.7% of INFO volume**, outside every level this file
governs. (The *ratio* is the measured fact and is what justifies the split. The
per-second rates first derived from it were ~2.2x too high, because that
~11-minute count was divided by 300 s; plan Amendment B1 corrects them to
~6.6 lines/s at 155 bots, ~0.043/s/bot. Everything downstream was therefore
pessimistic, which is the safe direction, and no shipped cap changed.) So the
output is split in two, both rolling on the same 2 h modulated boundary:

| | Track 1 "main" | Track 2 "detail" |
|---|---|---|
| File | `logs/console.log` | `logs/detail/detail.log` |
| Layout | JSON (`JsonTemplateLayout`) | `PatternLayout` |
| Carries | `com.vingame.bot` **INFO+** and non-ws-parser root INFO+ | ws-parser in full, **all** our DEBUG/TRACE, plus a self-contained copy of the above |
| Loki / Grafana | **yes -- the only thing promtail ships** | **never** |
| Retention | 14 d or 10 GB | **12 h or 10 GB** |
| Read it | Grafana / Loki | on the box, or `logs/evidence/` after an alert |

- **Only track 1 reaches Loki, and it is capped at INFO+** by a `ThresholdFilter` on
  the `AsyncRolling` appender (AD-24). No DEBUG or TRACE line reaches Loki by any
  path -- including Phase 2's scoped-debug `ACCEPT`, which beats the *level* check
  but not an appender's own filter.
- **Track 2 stays out of Loki because it is a subdirectory.** promtail's
  `__path__: /logs/*.log` is non-recursive -- the same escape `logs/evidence/` uses.
  That is the entire mechanism; there is no filter behind it.
- **`docker logs bot-manager` no longer carries the ws-parser `AUTH [...]` flood.**
  Some operators used it as a liveness signal; the replacements are the ws-parser
  narrative in `logs/detail/detail.log` and `bot_messages_total` in Prometheus. Not
  a regression. Spring's startup lines and all third-party WARN/ERROR are still
  there (AD-27) -- the releaser's smoke test depends on that.
- **`WSPARSER_LOG_LEVEL` is the disk escape hatch** (compose passes
  `LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER=${WSPARSER_LOG_LEVEL:-INFO}`). `WARN`
  removes ~99% of track 2's volume with a restart and no rebuild. **Do not rename
  the `com.vingame.websocketparser` logger** in `log4j2.properties`: Spring Boot's
  `setLogLevel` against a name that does not exist creates a fresh appender-less
  LoggerConfig and silently deletes track 2.
- **`appender.asyncdetail.blocking = false` is deliberate and is the opposite of
  track 1's `true`.** It looks like the AD-3/AD-4 defect an earlier pass fixed and
  it is not: `log4j2.discardThreshold` is JVM-wide, so it cannot discard track 2's
  INFO without discarding track 1's, and track 2 carries the highest-volume tier in
  the system at INFO -- under `blocking = true` that parks a bot thread on a queue
  wait. Track 1 keeps `blocking = true` so its INFO+ signal is never lost.
  `AsyncQueuePolicyTest` asserts the pair together. **The buffers are different
  numbers on purpose**: track 1 `bufferSize = 8192`, track 2 `16384`.
- **A full track-2 queue drops events at every level, and that is now counted.**
  log4j2 reports it only through `DefaultErrorHandler` -- three messages, then one
  per five minutes, with no count, on stderr, so it reaches neither `console.log`
  nor Loki nor the detail file it is about. `AsyncQueueMetrics` publishes
  `log4j2_async_queue_remaining` / `_capacity` /
  `_pressure_samples_total` / `_full_samples_total` per appender and emits a
  throttled WARN carrying the running totals on **track 1**; the
  `LogQueueSaturated` rule alerts on the ratio. If that alert fires for
  `AsyncDetail`, **treat `logs/detail/detail.log` as incomplete for the window**
  (for `AsyncRolling` it means the opposite: bot threads parked on a queue put).
  **The converse does not hold** -- these are lower bounds. A silent rule proves
  only that no *sustained* saturation was sampled: `for: 5m` over a scrape-time
  gauge can miss an oscillating drop storm entirely, the sampler's own WARN rides
  `AsyncRolling` (`blocking = true`) so a stalling disk can freeze the counters
  exactly when they matter, and a *vanished* appender reports `-1/-1 = 1`, the
  healthiest reading there is. The three misreadings are spelled out on the rule
  in `prometheus/alerts.yml` and in `AsyncQueueMetrics`' javadoc.
- **`appender.detail.immediateFlush = false` risks the last <= 8 KB, not "the last
  few ms".** The bound is bytes (`appender.detail.bufferSize`, declared explicitly
  so it is a decision), and how much *time* 8 KB spans is inversely proportional to
  the log rate: ~17 ms at 20k bots, ~2 s on a quiet staging box, **minutes in a JVM
  whose log rate is collapsing**. That last case interacts with AD-21: an unclean
  kill is exactly what the shim's boot retro-promotion triggers on, so it can pin a
  detail file whose final, most diagnostic 8 KB never reached disk. Track 1 keeps
  `immediateFlush = true` and has no such gap.
- **The DEBUG-tier signals this file calls default-visible are now file-visible, not
  Grafana-visible** -- the 5 s `UpdateBet` aggregate with its strategy-decision
  histogram and the per-round session summaries (AD-8) live in track 2 on the box.

**The rule that decides every level question is unchanged:**

> **INFO must not contain anything whose rate is a function of bot count or round
> rate.** If a line fires once per bot, once per round, or once per message, it is
> DEBUG or TRACE, and its group-level aggregate is what goes to INFO.

Everything below follows from that. MDC (`botGroupId`, `botId`, `environmentId`,
`product`, `gameType`, `gameId`, `gameName`, `botUserName`) is on every per-bot and
every aggregated line -- keeping the tag is what makes a demotion safe.

### Level and how it is set

- **The shipped default is INFO** (`logger.app.level = info`, both
  `log4j2.properties` twins). It used to be `debug`, baked into the jar.
- **Change it without a rebuild:** `LOGGING_LEVEL_COM_VINGAME_BOT`, passed by
  compose as `${BOT_LOG_LEVEL:-INFO}`. Spring Boot applies `logging.level.*` over
  `log4j2.properties` and mutates the existing `com.vingame.bot` LoggerConfig in
  place, preserving `additivity=false` and all three appenderRefs. Verified in the
  build by `LoggingLevelOverrideTest` -- **do not rename that logger**, or the
  override silently starts creating a fresh appender-less LoggerConfig instead.
- **Staging sets `BOT_LOG_LEVEL=DEBUG`** in `secrets.env`/`.env`. Prod does not.
  Since Phase 4 that DEBUG lands in **track 2 only**: the `ThresholdFilter` on
  `AsyncRolling` keeps `console.log`, and therefore Loki, at INFO+ whatever the
  logger level says. The same is true of a global `/actuator/loggers` flip.
- `POST /actuator/loggers/com.vingame.bot {"configuredLevel":"DEBUG"}` still works
  and is still the escape hatch -- but it is **global**. On a 10-environment prod
  instance that is a **~5 GB/hour action**. Reach for **scoped per-group DEBUG**
  (`/api/v1/logging`, below) first; a global flip is an incident-scale decision.
- Log config is bind-mounted (`logging/log4j2.properties` ->
  `/app/config/log4j2.properties`, selected by `LOGGING_CONFIG`), so a level or
  retention change is a `docker compose restart`, not a fleet redeploy. The in-jar
  copy is the fallback and **must stay in sync** -- `Log4j2TwinConfigTest` fails the
  build if the twins drift.

### The tiers

- **INFO -- tier 1: group-level lifecycle.** Application startup, bot group
  create / start / stop / restart, group state transitions, scheduled-restart
  firing, periodic-logout scheduler started/stopped and cycle starting, coordinator
  / jackpot-scaler creation, and the **aggregated group lines** from
  `GroupLifecycleAggregator`:
  `group <id> (<name>): 47/50 bots initialized, game=..., strategy=...` and
  `group <id> (<name>): N bots auto-deposited, total <sum>` -- one line per group per
  start, replacing what used to be one line per bot.
- **INFO -- tier 2: the 5-minute fleet rollup** (`FleetRollupLogger`). One line per
  running environment (`env <id> (<name>, product <code>): groups=, bots=,
  connected=, dead=, deadGroups=, rounds=, staked=`), plus a detail line
  **only for a group that is not clean** (`group <id> (<name>): playing=,
  reconnecting=, dead=n/m, groupDead=, rounds=, staked=`). A group is clean when it
  is not DEAD and has no DEAD or RECONNECTING bot. **Volume scales with sickness,
  not with fleet size** -- that is the design, not an implementation detail. An idle
  instance emits nothing.
- **DEBUG -- per-bot and per-session detail.** Per-bot status transitions, per-bot
  balance fetch, HTTP request/response bodies, reconnect attempt success, per-bot
  periodic-logout completion, `Bot.restart`'s "restart requested", the per-bot
  `... initialized` and `triggering deposit` lines and BotFactory's
  `Successfully created bot` (all four now aggregated at INFO by tier 1),
  `BotGroupRuntime`'s "Bot starting in virtual thread", `ClientFactory`'s "Setting
  shared EventLoopGroup on client", `BotGroupBehaviorService`'s "assigned strategy"
  / "assigned slot strategy", the 5 s `SessionAggregationService` UpdateBet running
  summary with its strategy-decision histogram, the slot per-`(group, gameId)`
  window summary, **and (AD-8, see below) the per-round StartGame session-entry and
  EndGame results summaries.**
  - The last four were **missed by two successive enumerations** of the per-bot INFO
    sites. `ClientFactory`'s was the costly one: `newClient()` is called from
    `Bot.initialize`, `Bot.restart()` **and** the re-auth path, so at INFO it fired
    per bot at start, per periodic-logout cycle and per reconnect — exactly
    cancelling the demotion of "restart requested" three frames earlier. Their
    group-level replacements are `Bot group <id>: strategy mix {RANDOM=30, ...}`
    (one line at the assignment site) and, for the EventLoopGroup identity, the
    one-shot `NettyEventLoopConfig` line that prints the same hash once per JVM.
  - This **supersedes BETTING_STRATEGIES AD-14**, which argued *for* INFO on the two
    "assigned strategy" lines on the grounds that "N bots = N lines at start". That
    is precisely the shape this feature removes.
  - `PerBotInfoLogGuardTest` enforces the rule against the source: the classes whose
    logging is per-bot in its entirety may contain no `log.info(` at all, and the
    demoted messages above are pinned individually. Add a genuinely group-scoped
    line to its group-level caller rather than exempting a class.
- **WARN -- recoverable anomalies worth investigating if they persist.** Bot WS
  disconnect, watchdog expiry, partial registration result, deposit failure or
  non-200 for a single bot, periodic logout interrupted, session-cap eviction. Not
  for expected outcomes.
- **ERROR -- failures needing operator attention.** Bot group marked DEAD,
  re-authentication failed, 5xx upstream, failed to load display names, executor
  interrupted during shutdown. Page-on-ERROR is reasonable; keep volume low.
- **TRACE -- wire-level and per-bet drill-in.** All raw WS frame dumps from
  `OutputPrinter` (including the MDC-tagged per-bot `debugOutputPrinter` actually
  wired into `BettingMiniGameBot`/`SlotMachineBot`) and the per-bot, per-bet
  strategy-decision lines (`BettingMiniGameBot` "sending bet" / "strategy parked
  decision" / "strategy skipped tick", `SlotMachineBot` "parked spin" / "sending
  spin", `RandomBehaviorStrategy` / `MartingaleStrategySupport` "decide: bet" /
  skip-gate / "onRoundEnd" / "cap hit", `FixedBetStrategy` / `RandomBetStrategy`
  "chooseBet"). Genuine packet-level / per-decision detail only -- not a
  verbose-DEBUG junk drawer.

### AD-8 -- the per-round session summaries moved INFO -> DEBUG

**This reverses the previous guideline in this file**, which put the StartGame
session-entry and EndGame results lines at INFO on the grounds that they are
group-scoped rather than per-bot. That is still true, and it was not the binding
constraint: they were the **only** INFO class whose rate scales with round rate --
~13 lines/s, ~0.7 GB/day at 300 groups on 45 s rounds, more than every other INFO
class combined. Left at INFO they would have dwarfed tiers 1 and 2 and made "INFO
is the cheap default" false on arrival.

The signal is not lost. Staging runs at DEBUG, so they are default-visible there.
Prod covers the same facts with `bot_bets_placed_total` / `bot_bet_amount_total` /
`bot_winnings_total`. And `SessionAggregationService.drainRollup()` hands per-group
`rounds` and `staked` to the tier-2 rollup, which carries them at INFO every 5
minutes. Phase 2's scoped per-group DEBUG is how one group's per-round detail comes
back on demand.

`SessionAggregationServiceTest` asserts the DEBUG level explicitly, because this is
one level constant away from being reverted by accident.

### Scoped per-group DEBUG (Phase 2)

One bot group's lines can be raised to DEBUG through the INFO logger, for a bounded
time, without changing anything for any other group -- `POST
/api/v1/logging/debug/{botGroupId}?minutes=N` (see the REST API section). This is the
answer to "I need detail for *that* group", and it is the reason the default can be
INFO at all.

- **The TTL is mandatory** (max `bot.logging.scoped-debug.max-minutes`, default cap
  120). There is no permanent form. The concurrent-scope cap
  (`bot.logging.scoped-debug.max-scopes`, default 50) stops scoped DEBUG being
  reassembled into global DEBUG one call at a time.
- **It cannot reach TRACE.** The raw WS frame dumps stay a deliberate global
  `/actuator/loggers` action; a scope that promoted them would be the flood again.
- **How a scoped session is read since Phase 4 (AD-24).** Grafana tells you *that* a
  drill-in exists -- the arming and escalation lines are `com.vingame.bot` INFO, so
  they are in track 1 and in Loki. The **payload is on the box**, in track 2, where
  the MDC renders as `[botGroupId/botId/gameType]` and **not** as JSON fields:
  ```bash
  grep "\[<GROUP_ID>/" logs/detail/detail.log | tail -400          # live file
  grep -h "\[<GROUP_ID>/" logs/detail/detail-*.log | tail -400     # rolled siblings
  grep -h "\[<GROUP_ID>/" logs/evidence/detail-*.log               # if an alert pinned it
  ```
  Any older instruction to grep `"botGroupId":"<GID>"` in `console.log` is stale.
  Note `%-5level` pads to five characters, so prefer `grep -E ' (DEBUG|TRACE) '`.
- **It also arms itself** on early-warning signals, *before* a group dies (AD-12):
  first watchdog expiry, a reconnect burst
  (`escalation.reconnect-threshold` in `escalation.reconnect-window-minutes`), and a
  `dead/total` crossing **half** of `bot.group.dead.threshold` while still under it.
  Each escalation logs one INFO line -- `scoped debug escalated for group <id> --
  trigger: ..., expires ...`.
- **The escalation cooldown is a quiet period measured from scope *expiry*, not from
  the escalation.** A group may not re-arm until `escalation.minutes +
  escalation.cooldown-minutes` (15 + 45 by default), so the bound to reason about is
  a **duty cycle**: an unattended group cannot hold scoped DEBUG for more than
  `ttl / (ttl + cooldown)` = **25%** of any window, however hard it flaps. Measuring
  the cooldown from the escalation — which the plan's wording asks for, and which
  the first implementation did — makes it re-arm on the next 30 s health tick after
  every expiry whenever the two values are equal, i.e. ~96% unattended DEBUG for a
  group parked mid-band. `ScopedDebugEscalatorTest` asserts the duty cycle, not the
  fact that a re-arm eventually succeeds.
- **Implementation note that contradicts the plan.** AD-9 says to attach the filter
  to the `com.vingame.bot` LoggerConfig. That does not work: the filter consulted
  *before* the level check is the **`Configuration`**'s
  (`Logger.PrivateConfig.filter` -> `config.getFilter()`), while a LoggerConfig's own
  filter runs only after the level gate has already dropped the event. The filter is
  therefore installed on the `Configuration`, and `ScopedDebugFilter`'s
  `SCOPED_LOGGER_PREFIX` keeps the blast radius to `com.vingame.bot.*` so a scoped
  group does not also surface the Mongo driver's DEBUG.
  `ScopedDebugFilterInstallationTest` pins the whole chain end to end.

### Evidence promotion (Phase 3)

Cheap retention has exactly one failure mode: the window that mattered ages out
before anyone reads it. `evidence-shim` -- a separate `python:3.12-alpine`
container with `evidence-shim/shim.py` bind-mounted, no `depends_on: bot-manager`
(the app cannot preserve its own logs when the app is what died) -- takes
Alertmanager's webhook and **hardlinks** the newest log files into
**`logs/evidence/`**, outside both sweepers.

- **`ln`, never `cp` (AD-14).** A copy doubles the bytes exactly when disk is the
  constraint. A hardlink costs zero blocks and still defeats log4j2's `Delete`:
  unlink removes a *name*, not the inode. The **live `console.log` is linked too**
  -- the link follows the original inode, so it keeps growing until rollover
  renames the file, which is precisely the wanted post-incident tail.
- **Newest two at execution time (AD-15)**, never arithmetic on the alert
  timestamp -- that re-centres the window by itself at a rollover boundary.
  **Per track since Phase 4 (AD-28)**: six names, at most three inodes each for
  `console*` and `detail*`. Both tracks roll on the same boundary, so one tail
  pass still closes both live files. A **missing `logs/detail/` is a no-op**, not
  an error -- the shim predates nothing and degrades to its old behaviour.
- **Three passes (AD-16):** immediately (the box may still be going down), at
  +5 min (Alertmanager's `group_interval`), and at the next rollover boundary
  + 120 s so the file that was live at T+0 is pinned after it closes. All three
  are the same idempotent operation. **`EVIDENCE_ROLLOVER_HOURS` must track
  `appender.rolling.policies.time.interval`** (2 h since Phase 0); the build fails
  if they disagree.
- **One deadline per incident key (AD-17)** -- the webhook's `groupLabels`
  rendered canonically. Alerts carry `product`/`environmentId`/`gameId` but **not
  `botGroupId`**, so per-group deadlines are not expressible; a deteriorating env
  redelivering every 5 min refreshes one deadline instead of stacking timers.
- **`logs/evidence/` is a subdirectory on purpose (AD-18).** Same filesystem is
  mandatory for hardlinks; a subdirectory is what escapes both promtail's
  non-recursive `__path__: /logs/*.log` (else promoted files are re-ingested into
  Loki) and log4j2's `Delete` (`basePath /app/logs`, `maxDepth = 1`).
  `EvidenceRetentionEscapeTest` fails the build if either changes.
- **It sweeps itself (AD-19, AD-28)** -- **two ages**: `EVIDENCE_MAX_AGE_DAYS`
  (14) for the aggregate track and `EVIDENCE_DETAIL_MAX_AGE_DAYS` (**3**) for the
  detail track, then `EVIDENCE_MAX_BYTES` (**12 GB**), on every pass and hourly.
  Under the byte guard **detail files are evicted before aggregate files**
  whatever their promotion times, then oldest-promotion first within each class:
  a pinned 2 h detail file is ~1.5-2.3 GB at 20-30k bots, so the alternative sheds
  an old incident's 7 MB timeline to make room for a new incident's payload.
  **The guard counts inodes, not names, and evicting one unlinks all of its
  names.** Every incident leaves one inode per track under two names (pass 1 pins
  the live file, pass 3 pins the same inode under its rolled name), so per-name
  accounting inflated an incident's footprint by a whole detail file and
  "freeing" a duplicate name freed nothing at all. Within a pass, every file
  carries the same promotion stamp, so the order falls to the log's own mtime --
  closed archives go before the still-growing live tail, which is the one file
  that cannot be reconstructed.
- **Age-out is not the same as containment (AD-30, corrected).** The 3 d detail age
  is chosen against the aggregate's 14 partly because ws-parser logs
  **agency-token material** at INFO. After Phase 4 no *new* token material reaches
  Loki, `docker logs` or Grafana -- but AD-30's original claim that it then "exists
  in exactly one place" is **false for up to 30 days after the deploy**: Loki's
  `retention_period` is `720h`, so every ws-parser INFO line ingested *before*
  Phase 4 stays queryable until then. Phase 4b's one-time promtail re-ingest makes
  that worse unless the pre-deploy step in the plan's Phase 4b is run -- it re-reads
  the retained pre-Phase-4 `console-*.log` archives from byte 0 and re-ingests that
  material **with a fresh 720 h clock**. The re-ingested window is **at most 7 days**
  -- those archives were written under the *pre*-Phase-4 `ifLastModified.age = 7d`,
  not the 14 d this phase raises track 1 to -- and the only measurement taken on the
  box found ~12 archives ≈ **24 h**. Contained, not fixed; the fix is in the library.
  Without any of this the fix for unbounded growth is unbounded growth.
- **Unclean start retro-promotes (AD-21).** In a full-stack failure Alertmanager
  may be dead too, so a start that finds no `logs/evidence/.clean-shutdown` marker
  promotes immediately, tagged `boot`.
- Alertmanager routes `BotManagerDown` and `EnvironmentGroupDead` to the
  `evidence` receiver with `continue: true` **plus a mandatory `viptalk` sibling**
  -- a matching child route *consumes* the alert, so an evidence route without the
  sibling silently kills that alert's VipTalk delivery. `AlertmanagerRoutingTest`
  guards it.

## Package Structure

```
com.vingame.bot/
├── config/                        # Spring configuration
│   ├── bot/                       # BotConfiguration, BotBehaviorConfig, BotCredentials
│   └── client/                    # EnvironmentClientRegistry, EnvironmentClients
├── common/                        # Shared exceptions, utilities
├── domain/
│   ├── bot/
│   │   ├── core/                  # Bot, BettingMiniGameBot
│   │   ├── implementation/        # BauCuaBot, BauCuaMiniBot, TaiXiuSevenBot
│   │   ├── message/               # Message interfaces
│   │   │   ├── request/           # Request messages (Bet, Chat, Subscribe, etc.)
│   │   │   ├── g2/bom/            # BOM product message implementations
│   │   │   └── g4/nohu/           # Nohu product message implementations
│   │   ├── service/               # BotFactory
│   │   └── util/                  # SessionIdStore, GameState, OutputPrinter
│   ├── botgroup/                  # BotGroup domain (controller, service, model, dto)
│   ├── environment/               # Environment domain
│   └── game/                      # Game domain (offset, pluginName, numberOfOptions)
└── infrastructure/
    ├── client/                    # ApiGatewayClient, GameMsClient, ClientFactory
    └── runtime/                   # BotGroupRuntime
```

## Architecture

### Bot Lifecycle Flow

```
BotFactory.createBot() → build with credentials → authenticate()
→ get [authToken, agencyToken] → build client with tokens → start()
```

### Key Architecture Decisions

- **Bots are POJOs**: Created via builders and factories, no custom Spring scope
- **Stateless clients**: One `ApiGatewayClient`/`GameMsClient` per Environment (shared by all bots)
- **Two-token system**: Auth token (WebSocket) + Agency token (monetary ops) - non-interchangeable
- **Configuration-driven bots**: `BettingMiniGameBot` is concrete; game types handled via `Game` entity and `GameMessageTypes`
- **Virtual threads everywhere**: Schedulers, bot creation, health monitoring all use virtual threads for lightweight concurrency

### Plugin registries — product implementations are resolved by `String` key

`docs/plans/PLUGIN_HOT_RELOAD.md` Phase 2 replaced static, enum-typed resolution
with three string-keyed Spring registries. Nothing is loaded from a new
classloader yet and **behaviour is identical** (AD-23) — what changed is that a
product implementation is now addressable by a name the engine does not have to
declare.

| Registry | Key | Discovery |
|---|---|---|
| `BettingStrategyFactory` | strategy key, e.g. `"RANDOM"` | `@StrategyImpl("RANDOM")` on a prototype bean |
| `SlotStrategyFactory` | slot strategy key | `@SlotStrategyImpl("FIXED")` |
| `MessageTypesRegistry` | `(GameType, productCode)`, e.g. `(BETTING_MINI, "116")` | `@MessageTypesImpl(gameType=…, products={"097","098"})` |

All three registries live in **`bot-engine`** (moved there, FQNs unchanged, by
`docs/plans/PLUGIN_HOT_RELOAD_3_4.md` Phase 3a); what they resolve stays in
`bot-strategies` / `bot-messages`, and the engine names no concrete class from either.
Since Phase 4a they are **not Spring beans**: `PluginRegistries.build(bundle)` builds all
three from one plugin bundle, and the only root-held reference is `PluginRuntime`. Inject
it and call `current()` once per operation; never keep a registry, the bundle or its
`TypeFactory` in a field (`RootContextHoldsNoPluginRefsTest` fails the build). Every
per-bot mapper is built on the bundle's `TypeFactory` (`Bot.newMessageMapper()`).

- **`StrategyId` / `SlotStrategyId` survive, demoted to the built-in catalogue**
  (AD-12). They are the compile-time home of the canonical key strings and of the
  `displayName` / `description` the UI picker renders — **no runtime code path may
  switch on them or use them as a map key**. `StrategyCatalogParityTest` pins every
  constant name to a registered bean; that test is what replaced the annotation's
  lost type check, so `@StrategyImpl("RANDOM ")` fails the build rather than
  registering a phantom key. `ProductCode` is *not* de-enum-ed (AD-16) — it is
  brand metadata (`appId`, `usernameMaxLength`, `vipTalkRoomId`), not a plugin key.
- **The persisted and wire shapes did not change.** BSON was already the enum
  `name()` string and no `MongoCustomConversions` exist, so
  `strategyMix[].strategyId` and `slotStrategyId` read back unchanged. No
  migration. `slotStrategyId == null` still means "fall back to `FIXED` at
  bot-build time" and must stay null through the entity and DTO layers.
- **`BotGroup.slotStrategyId` has no effect on any bot, whatever it holds.**
  `BotGroupBehaviorService:791-795` sets the per-bot value to `FIXED` for every
  SLOT group and to `null` otherwise, ignoring the persisted field entirely — a
  silent override, not a rejection (slot strategy is not selectable; slot play is
  invisible to other players). So the null-means-`FIXED` rule above is true but
  does **not** imply a non-null value is honoured; it is not. Combined with
  review-2b's finding that a bad persisted value can never be PATCHed back to
  `null`, the field is validated (400 on an unregistered key), persisted and
  exposed while changing nothing about how a bot plays. Do not build on it.
- **Jackson's implicit key validation was replaced explicitly**, same 400
  (`BotGroupConfigValidationService`, AD-15). Note what it does *not* buy
  (Amendment A5): `validate` is **not** on the group-start path, so a group whose
  strategy bean vanished still dies at `BettingStrategyFactory.create` on a bot
  thread; and because `validate` runs **post-merge over the whole entity** on
  PATCH, a group holding an unregistered key fails *any* PATCH until its mix is
  replaced. It stays startable, stoppable and deletable, and the 400 names the key
  and lists the catalogue.
- **`GET /api/v1/strategy/` is registry-backed but its response is contract-frozen**
  (AD-21). `StrategyCatalog` joins registered keys to display metadata: a built-in
  takes the enum's copy, any other key falls back to the key itself as
  `displayName` with an empty description. Order is an **explicit sort** —
  built-ins in `StrategyId` declaration order, then everything else alphabetically.
  A registry's own iteration order is bean-discovery order (alphabetical by class
  file within package, Amendment A4) and **must never be used as a display order**
  — it agrees with `StrategyId.values()` on `RANDOM` alone and differs in **all
  eight** remaining positions; operator-facing key lists in boot lines and
  exception tails are sorted at render time for the same reason.
- **A built-in whose bean goes missing is absent from the picker, and says so
  once.** Listing the registry means the endpoint can now disagree with the enum,
  which it could not before. `StrategyCatalog`'s `@PostConstruct` logs one WARN
  naming `BUILTINS.keySet() - registeredKeys()` — once per JVM, so it is tier-1
  admissible and Loki-visible. Without it the same fault surfaces only as a
  shorter dropdown, a 400 on *every* PATCH of an affected group, and bots dying on
  a bot thread. The build-time guard for the same divergence is
  **`ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated`** (exact set
  equality both ways, under the real `Starter` scan), *not*
  `StrategyCatalogParityTest`, which proves only enum ⊆ registry under a bare
  package scan.
- **A duplicate key fails context refresh** in all three registries, which is right
  while every key is ours and becomes wrong once a third-party plugin can collide
  with a built-in. `docs/plans/PLUGIN_HOT_RELOAD_3_4.md` settles both halves of
  Amendment A8: keys get no version dimension, two versions are two registry sets
  (D-9), and a bad bundle is rejected as a whole — a refresh failure in classpath
  mode, a fallback to the next candidate in isolated mode (D-10).

### A DEAD bot group restarts itself — `bot.recovery.enabled`

`docs/plans/DEAD_GROUP_AUTO_RECOVERY.md`. A transient upstream outage longer than the
per-bot reconnect budget (7 backoffs x 10 cycles ~= **51 minutes**, `Bot.java:38,47`)
used to convert into an **indefinite** one: every bot goes DEAD, the group crosses
`bot.group.dead.threshold` (0.80), `handleBotGroupDeath` persists `targetStatus=DEAD`,
and **nothing in the codebase ever retried** — the activation reconciler resolves `NONE`
for a dead target and `onStartup` only auto-starts `ACTIVE`. That is how TIP/116 staging
sat dead for **three days** ending in a manual `/restart` that took 60 seconds. The
mechanism was never missing; only the trigger was.

- **`DeadGroupRecoveryScheduler`** (60 s virtual-thread tick) is driven by the
  **persisted `targetStatus == DEAD`**, not by `runningGroups`. That is deliberate: a
  group that died *before* the current JVM started is absent from the in-memory map, so
  it reports `groups_dead_by_env = 0` and never fires `EnvironmentGroupDead` — the case
  with the longest downtime is exactly the one a memory-driven reconciler would miss.
  In-memory DEAD runtimes are unioned in for the case where the death's DB write threw.
- **It only ever calls the path an operator's `/restart` calls.**
  `BotGroupBehaviorService.startForRecovery` takes the same per-group `ReentrantLock`,
  **re-reads the group inside the lock** and re-asserts eligibility (closing the window
  where a `/stop` lands between decision and action), then calls the existing
  `startLocked`. Existing accounts are **re-authenticated**; nothing registers users,
  no DB group is recreated. There is no second lifecycle path. **On deposits, say it
  precisely**: the recovery *code* moves no money, but `startLocked` rebuilds the
  group's `BotBehaviorConfig` with its own `autoDepositEnabled`, so a recovered
  auto-deposit group tops its bots up from their own play loop
  (`BettingMiniGameBot.onNewSession`) exactly as it would after a manual `/restart`.
  "Recovery never deposits" is false read literally, and it is written that way in
  `startForRecovery`'s javadoc for the same reason.
- **It is gated on positive evidence, not on a timer.** `EnvironmentWsProbe` does an
  **anonymous** JDK-`HttpClient` WebSocket upgrade against `Environment.webSocketMiniUrl`
  — the URL *every* bot uses whatever the game type (`BotFactory.java:146`;
  `webSocketCardUrl` is never used to build a client). Healthy = an HTTP 101 **or any
  completed response < 500**, because the fault being detected is "origin gone, edge
  synthesising a 502", and a well-formed 401/403 proves something is parsing our request
  (AD-2). `bot.recovery.probe.healthy-streak=2` means ~60 s of sustained health, and an
  ACTIVE sibling group on the same environment with an open socket short-circuits the
  probe entirely (outcome `live_sibling`, AD-10). The probe touches no bot account, no
  token, no money — if anything auth-shaped becomes reachable from it, that is a defect.
- **The probe runs whether or not `bot.recovery.enabled` is set**; the flag gates the
  *reconciler* only. So an instance with recovery off still publishes
  `env_ws_probe_total` / `env_ws_probe_healthy` and shows what recovery would have done.
  Zero probe traffic when there are no candidates, which is the normal state.
- **`STOPPED` is the opt-out, and it is the only one.** `POST /{id}/stop` →
  `targetStatus=STOPPED` → never a candidate again, whatever the runtime says (the veto
  is explicit in `RecoveryEligibility`, not inferred). `MANUAL_OFF` and a `SCHEDULED`
  group outside its window are equally ineligible. There is **no per-group or
  per-environment opt-out field** and deliberately so (AD-5) — "keep this group running
  but never recover it" is not a requirement anyone has. Two consequences of making
  `STOPPED` load-bearing: `stop()` now **persists `STOPPED` for a runtime-less group**
  instead of returning a bare WARN, and `restart()`'s internal stop does **not** (a
  teardown step is not a statement of intent) and restores the prior status if its start
  half throws — otherwise a failed `/restart`, which is exactly what the exhaustion ERROR
  tells an operator to run, would silently opt the group out for good.
- **The attempt budget is what bounds the blast radius**: `max-attempts=6`,
  `backoff-minutes=2,5,15,30,60,60` (~2 h across five gaps, not six), `max-per-tick=1`.
  Budget is spent **only on attempts**, and an attempt happens only while the probe reads
  healthy — an environment down for six hours costs **zero** budget and simply waits.
  What the budget bounds is "the environment answers and the group still will not come
  up", the group-scale analogue of `MAX_RECONNECT_CYCLES`. A **success also charges** the
  budget (it resets after `settle-minutes=10` of staying up), so exhaustion can follow a
  flap; both the failure path and the exhausted-skip path emit the ERROR +
  `group_recovery_exhausted_total`, because exhausting silently is the one outcome that
  must be impossible. Any manual `/start` / `/restart` resets it, as does a JVM restart —
  state is in-memory by design (AD-11).
- **Staggering is structural, not a rate limiter.** One single-threaded reconciler, one
  attempt per tick, earliest-due first: ten dead groups recover over ten minutes and a
  permanently failing group cannot starve the others. The tick **blocks** for the whole
  duration of one group start — that *is* the serialisation, so nothing time-sensitive
  may be added to it. That is why the probe has its own scheduler.
- **`EnvironmentGroupDead` now often resolves on its own**, and its `for: 5m` is
  unchanged on purpose (AD-15): a self-heal inside 5 minutes silently prevents the page,
  which is the point, and one after 5 minutes fires-then-resolves, which is the honest
  record. Repetition is what must stay visible, so `prometheus/alerts.yml` adds
  `EnvironmentGroupRecoveryFlapping` (>= 3 successes in 6 h, warning) and
  `EnvironmentGroupRecoveryExhausted` (critical — nothing will try again until a human
  acts). Neither needs an Alertmanager route: with no matching child they fall through to
  the `viptalk` receiver, and only a `continue: true` child would have needed a mandatory
  `viptalk` sibling.
- **Both of those rules only work because the counters are pre-registered at zero.**
  `BotMetrics.initGroupRecoverySeries`, called at the top of every attempt under the
  group MDC, materialises all four `group_recovery_*` series at `0` before anything can
  increment one. A Micrometer counter registered lazily at increment time first appears
  at `1` and stays at `1`; `increase()` over samples that are all `1` is `last - first
  = 0` and Prometheus' counter-start extrapolation is gated on `resultValue > 0`, so
  `EnvironmentGroupRecoveryExhausted` (`> 0`) could **never** fire on a group's first
  exhaustion — the only one that normally happens, since the budget is in-memory — and
  `EnvironmentGroupRecoveryFlapping`'s documented `>= 3` silently meant 4. **Do not
  make these counters lazy again**, and do not "simplify" `initGroupRecoverySeries`
  away: the tags must come from the same MDC the increments use, or it registers a
  second series and fixes nothing.
- **"Recovered" means ACTIVE with at least one bot, and a partial rebuild is one of
  them.** `startForRecovery` returns true for an ACTIVE runtime with
  `runningBotCount > 0`, so a 50-bot group that authenticates one bot is
  `outcome="success"` and the INFO line reads `1/50 bots up`. A proportional predicate
  (`> (1 - bot.group.dead.threshold) * botCount`) was written and **reverted**, because
  it changes the label and nothing else: a partial start is a *successful*
  `startLocked`, which persists `targetStatus=ACTIVE` and leaves an ACTIVE runtime, so
  `RecoveryEligibility` vetoes on ACTIVE and `RecoveryCandidateSelector` stops selecting
  the group. There is no next attempt to charge the budget to, no exhaustion and no
  hand-off ERROR — `expireStates` even refunds the attempt, since the settle window is
  keyed on `lastSuccess` and `recordFailure` never sets it. **Do not "fix" this in the
  predicate again.** The barrier is the ACTIVE persist, not the success test; a real fix
  has to tear the partial rebuild down or retain recovery state past it, and it is
  written up as P10 in `docs/plans/FOLLOWUPS.md` together with why nothing else notices
  (`monitorHealth` divides by the bots that exist, so 1/1 alive reads healthy forever).
- **Shipped off, switched outside the jar.** `bot.recovery.enabled=false` in
  `application.properties`; `docker-compose.yml` passes
  `BOT_RECOVERY_ENABLED=${BOT_RECOVERY_ENABLED:-false}` (Spring relaxed-binds it), so
  turning recovery on or off on one box is a `docker compose up -d bot-manager`, not a
  rebuild. Set it in the uncommitted `secrets.env`/`.env` merge, never in the compose
  file.
- **A group parked below the threshold is not covered.** At `dead.threshold=0.80` a group
  can sit at 79% DEAD forever without being DEAD, so it is not a recovery candidate;
  `EnvironmentDeadBotRatioHigh` is the signal for that shape. Unchanged by this feature.

### Crash bot — `GameType.CRASH` / `CrashBot`

`docs/plans/AVIATOR_BOT.md` (119 Avatar, `aviatorPlugin`). One `Game` row, shared rounds,
several runners; event-driven over one `CrashRoundStateMachine` (one CAS, no side flags).

- **The runner is protocol metadata, not a `Game` field** (AD-2): `CrashMessageTypes` says
  how many there are; 119 has eid 1 = Jake (`jOdd`), eid 2 = Neytiri (`nOdd`). The stake,
  target and eid are drawn once per bet and frozen.
- **Check the crashed flag before the multiplier** (F-1). A crashed runner's value freezes
  at a real number that can be >= the target (2.86 vs 2.85), so the cash-out gate is
  `!crashedFor(eid) && multiplierFor(eid) >= target`. A cash-out decided on the last tick
  and refused after the crash records `crash` — correct, not an error.
- **Never bet off the snapshot** (AD-12). The subscribe reply only resets the machine; the
  first bet is on the next round start, scheduled 0-4.5 s after it. Ticks send the cash-out.
- **Silence watch armed in `onStart`, not on the subscribe reply** (AD-9), so a *refused*
  subscribe is caught: one WARN per silence episode with `subscribed=false`, reconnects at
  1, 2, 4, 8, 16, 32 silent windows then every 32 (window = `bot.watchdog.timeout.seconds`,
  180 s). **CASHOUT's watchdog does not catch a refused subscribe** — it is per-bet, armed
  at bet send, and a refused subscribe sends no bet. Earlier drafts claimed otherwise.
- **`bot_crash_bets_total{outcome=cashout|crash|unacked}`**, pre-registered at 0 in
  `initializeSubclass` under the same MDC as the increments, so `CrashBetsUnacked` (> 50%
  unacked for 15 m, internal) fires on the first occurrence. Placed = counted on the 1702
  ack; winnings = the 1703 `wm` (gross). A reconnect abandons a live bet **without** an
  outcome (do not invent one); a stale 1707 is ignored.
- 119 staging crashes at fixed points (2.86 / 11.59), so **staging RTP is meaningless**.
  Run it on the Club env (`websocket_mini`), not the proxy env.

### Token Naming Reference

The same token is called different things in different contexts — this is a known mess:

| Internal name | Register response field | `verifytoken` `?token=` param | Role |
|---|---|---|---|
| `authToken` | `session_id` | `token` | `verifytoken` balance reads, `X-TOKEN` for user update |
| `agencyToken` | `token` | — | Monetary ops **and the WebSocket AUTH frame** |
| `jwtToken` (token2) | _(not yet in register response)_ | — | Merging JWT; will eventually replace both above |

`token2` / `jwtToken` is in active development and currently works alongside the other two. Once migration is complete it will replace both `authToken` and `agencyToken`.

**The WS AUTH frame carries `agencyToken`, not `authToken`.** This row said
"WebSocket authentication" against `authToken` for a long time and was simply
wrong — `VingameWebSocketClient:341` builds
`AuthMessage.builder().accessToken(agencyToken)`, and a live 119 exchange
confirms it: the accepted `accessToken` is the `18-…`-prefixed agency value.
Sending `session_id` instead is rejected with `[1,false,100,"",null,null]`.
The name collision is what makes this so easy to get backwards — the register/login
response calls the agency token `token` and the auth token `session_id`, i.e. the
*opposite* of the internal names.

The exact accepted frame shape, for reference (119, 2026-09-10):

```
OUT [1,"MiniGame","","",{"agentId":"1","accessToken":"18-…","reconnect":false}]
IN  [1,true,0,"62w3al2R","MiniGame",null]
```

`agentId` is the **string** `"1"`, not the number — that is what `AuthMessage`
emits and what the server accepts. Note the reference client
`dev-w79-avatar.js` additionally sends `ib:true` and an unquoted `agentId`; the
exchange above proves **neither is required**, so no ws-parser change is needed
to reach a 119 socket. `ib` appears nowhere in this codebase and its meaning is
not documented anywhere we control.

### Parallel Execution

Bot creation and user registration use parallel execution with Semaphore-based rate limiting:

```
┌─────────────────────────────────────────────────┐
│  Semaphore (N permits)                          │
│  Controls max concurrent requests to server     │
│         ↓ acquire() / release() ↑              │
│  ┌─────────────────────────────────────────┐   │
│  │   Virtual Thread Pool (unbounded)       │   │
│  │   100 tasks submitted, N run at a time  │   │
│  └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

**Configuration** (`application.properties`):
```properties
bot.creation.parallelism=10       # Max concurrent bot authentications during group start
```

This pattern provides explicit rate limiting to avoid overwhelming the game server's auth endpoint, while virtual threads handle the I/O-bound waiting efficiently.

**`user.registration.parallelism` no longer exists** (GATEWAY_REQUEST_BUDGET Phase 4). It sized a
`Semaphore` around a registration fan-out that has been deleted: registration is now a single
serial `RegistrationWorker` running off the request thread, and `registeredCount = k` only means
"accounts 1..k exist" while that stays true. Setting the property today does nothing.

### Message System

Messages use Jackson JSON polymorphism with dynamic type registration:

- **CODE** = Message type identifier (3000=subscribe, 3002=updateBet, 3005=startGame, 3006=endGame)
- **OFFSET** = Game identifier (2000=BauCua, 8000=TaiXiuSeven)
- **CMD** = CODE + OFFSET (actual JSON value)

```java
mapper.registerSubtypes(messageTypes.getTypeRegistrations(offset, game.isMd5()));
```

## REST API

### BotGroupController - `/api/v1/bot-group`

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/{id}` | Find bot group by ID |
| GET | `/sort-keys` | Sortable field names for the filter body |
| POST | `/{envId}/filter` | Filter/list an environment's bot groups |
| POST | `/` | Create new bot group |
| PATCH | `/{id}` | Update bot group |
| DELETE | `/{id}` | Delete bot group |
| POST | `/{id}/start` | **Accept** a start — 200 + `BotGroupStatusDTO`, bots come up in the background |
| POST | `/{id}/stop` | Stop all bots in group |
| POST | `/{id}/restart` | **Accept** a restart — 200 + `BotGroupStatusDTO`, same async shape as `/start` |
| POST | `/{id}/registration/retry` | Resume a `REGISTRATION_FAILED` group from `registeredCount + 1` |
| POST | `/{id}/schedule-restart` | Schedule a restart |
| GET | `/{id}/health` | Per-group bot health (public-facing UI feature) |
| GET | `/{id}/status` | Target vs actual status |

**`/start` and `/restart` are asynchronous** since GATEWAY_REQUEST_BUDGET Phase 2. The
200 means *accepted*, not *finished*: the group's `actualStatus` becomes `STARTING` and
the caller polls `GET /{id}/status`, whose `botsUp` / `botCount` / `lastError` fields
carry the progress. Under the request budget a large group legitimately takes many
minutes to come up, which is why the HTTP call no longer waits for it. `STARTING` is an
**in-memory `actualStatus` value only and is never persisted** — a `targetStatus`
document holding it would fail an older jar's `findByTargetStatus(ACTIVE)` on boot and
break rollback. The synchronous 404 and the two 400s still happen before acceptance.

**`POST /` is asynchronous too** since GATEWAY_REQUEST_BUDGET Phase 4. Creating a group no longer
registers its accounts on the request thread — it answers `200` with `targetStatus:
"REGISTRATION_PENDING"` and `registeredCount: 0`, and `RegistrationWorker` creates the accounts one
at a time in the background. Poll `GET /{id}` or `GET /{id}/status` for `registeredCount` /
`namedCount` climbing toward `botCount`. A group in `REGISTRATION_PENDING` or
`REGISTRATION_FAILED` **cannot be started** (400, naming the counts and the way out); the way
forward from a failure is either the retry endpoint above or PATCHing `botCount` down to what
registered. PATCHing `botCount` **up** extends the target and resumes; down never un-registers
anything. `existingGroup=true` is unchanged and still synchronous — it makes no upstream call.

There is **no `GET /api/v1/bot-group/`** and no `POST /filter/` — listing is
`POST /{envId}/filter` with a JSON body, and the old rows in this table returned
**405** (found by LOG_VOLUME_TIERING's release verification, which used one of them
as a smoke test).

### EnvironmentController - `/api/v1/environment`

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/{id}` | Find environment by ID |
| GET | `/` | List all environments |
| POST | `/filter/` | Filter environments |
| POST | `/` | Create new environment |
| PATCH | `/` | Update environment |
| DELETE | `/{id}` | Delete environment |

### GameController - `/api/v1/game`

CRUD operations for game configurations.

### AlertController - `/api/v1/alerts`

Outbound alerting / announcements into **VipTalk** (Matrix-backed internal
messenger). See `docs/plans/VIPTALK_ALERTING.md`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/product/{product}` | Publish to one product's room (`116` / `P_116` / `TIP` all accepted) |
| POST | `/broadcast` | Publish to every wired room — the maintenance-notice path |
| POST | `/alertmanager` | Prometheus Alertmanager webhook receiver |
| GET | `/rooms` | Which products have a room wired (room IDs masked) |

- **Room IDs live on `ProductCode.vipTalkRoomId`**, not in config — one room per
  product, `@JsonIgnore`d so they never leak through `/api/v1/brand`. A `null`
  room means "not wired yet" and is skipped, never an error.
- **Bot token is config only**: `viptalk.bot-token` / `VIPTALK_BOT_TOKEN`.
  `viptalk.enabled=false` by default; enabled-with-blank-token self-disables with
  an ERROR rather than failing startup.
- **Alertmanager owns dedup/grouping/repeat**, not the app — that is why alerts
  route through `prometheus/alerts.yml` → Alertmanager → this webhook rather than
  from `AlertService` call sites. Webhook answers **502** on VipTalk failure so
  Alertmanager retries, **200** when the channel is disabled.
- Alert → room resolution: `product` label → `environmentId` label (resolved to a
  product via Mongo) → `viptalk.ops-room-id` fallback.
- `viptalk.instance-label` is stamped into every message: prod / loadtest /
  staging run the same artifact into the same rooms.

### LogLevelController - `/api/v1/logging`

Scoped per-group DEBUG (LOG_VOLUME_TIERING Phase 2). See the Logging Guidelines.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/debug/{botGroupId}?minutes=N` | Raise this group to DEBUG for N minutes (default 15, cap 120) |
| DELETE | `/debug/{botGroupId}` | Turn a scope off early (404 if there is none) |
| GET | `/debug` | Armed?, the TTL policy, and the scopes currently open |

- **The TTL is mandatory** — a request that omits `minutes` gets the configured
  default; one that exceeds the cap is a **400, not a silent clamp**.
- The group id is not validated against Mongo: the endpoint raises verbosity, it does
  not address a resource, and an unknown id simply matches nothing.
- Unauthenticated, like `/api/v1/metrics/**` and `/api/v1/alerts/**`. It cannot leak
  data but it can generate load — folded into the Spring Security + Keycloak item.

## Core Classes

### Bot (`domain/bot/core/Bot.java`)

Abstract base class for all bots:
- Authentication via `ApiGatewayClient`
- WebSocket lifecycle (connect, restart, stop)
- Balance management with auto-deposit
- Abstract methods: `shouldBet()`, `resolveBetAmount()`, `resolveBetCondition()`, `botBehaviorScenario()`, `onStart()`

### BettingMiniGameBot (`domain/bot/core/BettingMiniGameBot.java`)

Concrete bot for all BettingMini game types. Configured via `Game` entity and `BotBehaviorConfig`:
- Game state management (BET vs PAYOUT phases)
- Session tracking via `SessionIdStore`
- Countdown timer prevents late bets
- Scenario-based message flow (subscribe → start → bet → end)

### Key Patterns

**Betting Logic Flow:**
1. `canBet()`: session exists, BET phase, time remaining
2. `shouldBet()`: bot-specific decision logic
3. `resolveBetAmount()`: calculate bet size
4. `resolveNextEntryToBet()`: select position
5. Send bet via `Request.bet()`
6. `creditBalance(amount)`: decrement local balance

**Dual Status Tracking:**
- `targetStatus` in entity (DB) - what admin wants
- `actualStatus` in `BotGroupRuntime` (memory) - current state

## OpenAPI Documentation

- Swagger UI: http://localhost:8080/swagger-ui.html
- API Docs: http://localhost:8080/v3/api-docs

---

## Known Bugs

### WebSocket AUTH Race Condition (VingameWebSocketClient)

**Symptom:** Some bots silently fail to authenticate with the WebSocket server. They show `WARN "Cannot send message, not connected"` immediately after logging the AUTH message during startup, then get dropped by the server ~30 seconds later. Their scheduler threads (`pool-X-thread-1`) then spin indefinitely with the same WARN until the health monitor marks the group DEAD.

**Root cause:** The `connected` flag is set by the bot-creation thread, but the AUTH message is sent from the Netty IO thread (`multiThreadIoEventLoopGroup`) inside the `userEventTriggered(WebSocketHandshakeCompletionEvent)` handler. If the IO thread fires the handshake event before the bot-creation thread sets `connected = true`, the AUTH send is blocked. The flag check silently drops the message with only a WARN — no retry, no reconnect, no exception.

**How to confirm in logs:** Look for this pattern on the same IO thread in rapid succession:
```
[multiThreadIoEventLoopGroup-2-N] INFO  VingameWebSocketClient - Client ws-client-XXXX: WebSocket handshake completed
[multiThreadIoEventLoopGroup-2-N] INFO  VingameWebSocketClient - AUTH [1,"MiniGame3","","",{"accessToken":"..."}]
[multiThreadIoEventLoopGroup-2-N] WARN  VingameWebSocketClient - Client ws-client-XXXX: Cannot send message, not connected
```
Every client that shows this 3-line pattern will be dropped by the server. Clients where AUTH succeeds show no WARN and their bot threads log "Connected to server" before the IO thread fires the handshake event.

**Fix:** Set `connected = true` in the handshake completion handler on the IO thread itself, before sending AUTH. Do not rely on the bot-creation thread to set the flag — by the time it runs, the IO thread may have already attempted (and dropped) the AUTH message.

**Secondary issue:** No reconnection logic exists for any bot. When `channelInactive` fires (server closes connection), nothing triggers a reconnect. The bot's scheduler threads keep trying to send messages in a tight loop until the health monitor intervenes. This affects both the 10 AUTH-failed bots and any bots that disconnect mid-session.

### Bets Never Settle — Host IP Not Whitelisted With Back Office

**Symptom:** Everything looks healthy. Bots authenticate, connect, subscribe, and
send bets. Rounds enter and settle. But `confirmed staked` reads **0** on every
EndGame, `total win` reads 0, and account balances never move — they stay at
exactly the deposited amount no matter how many thousands of bets are placed.
Funding works fine, which is what makes it so misleading.

**Root cause:** The bot host's egress IP is not whitelisted with the back-office
team **for that brand/product**. The gateway accepts the connection and the bet
frames, but nothing is settled server-side.

**This looks exactly like a wallet-partition bug and has now been misdiagnosed as
one twice** — once on staging, once on P_116 prod (2026-08-12). The partition
theory is superficially compelling because `ApiGatewayClient.deposit`'s javadoc
does describe a real agency-vs-game-spendable split, and `verifytoken.aspx`
reports a different partition than the game engine debits. Resist it. **Check
whitelisting first — it is cheap to rule out and it has been the answer both
times.**

**How to confirm:** The tell is that the zero-effect is **fleet-wide and
pre-existing**, not specific to the new group or game. Check a long-running
incumbent group on the same brand: if it has staked many times its own balance
over days and its balance is still pristine, no bot on that brand is settling.
A single group looking wrong is a game/config problem; every group looking wrong
is whitelisting.

**Fix:** Ask the back-office team to whitelist the bot host IP for that brand.
Note it is **per-brand** — a host whitelisted for one product is not whitelisted
for another, so this recurs on every new brand brought up.

**After the fix:** settlement is immediate — server balances start drifting off
the deposited figure within a few rounds. Note that most bots will still *report*
the original figure for a while, because `checkBalance()` only re-reads the
server when local drift exceeds `BALANCE_SYNC_PERCENT_OF_DEPOSIT` (1% of the
deposit amount). Look for a **spread** of distinct `lastFetchedBalance` values
rather than expecting all of them to move at once.

### Server-Side Subscriber Pruning (Silent Zombie Bots)

**Symptom:** A subset of bots stops receiving game messages mid-session while their WebSocket connection remains alive. No disconnect event fires, no error is logged, the bot's status stays `CONNECTION_AUTHENTICATED`. From the frontend, user count appears to drop gradually over the first few rounds.

**Observed in logs:** With 15 bots, all 15 received StartGame/EndGame for rounds sid:422069 and sid:422070. After the EndGame for sid:422070 (10:24:33), the game server sent StartGame for sid:422071 to only 10 bots — the other 5 received nothing further for the rest of the session (10+ minutes). The 5 silent bots had intact WS connections (`onDisconnect` never fired) and were otherwise healthy.

**Root cause (suspected):** Game server enforces a subscriber limit per game channel (~10 based on observed behavior). When the limit is exceeded, the server silently evicts excess subscribers without closing their WebSocket connection or sending any error frame. The bots have no way to detect this — they are connected but invisible to the game.

**How to confirm:** Run with N > suspected limit. After 1–2 rounds, count unique usernames in StartGame log entries. If fewer than N bots appear, the others have been pruned. Verify with `grep "cmd.*11005" logs | grep -o "authtestws[0-9]*" | sort -u | wc -l`.

**Fix (bot side — pending server investigation):** Add a watchdog timer in `BettingMiniGameBot`. After `onSubscribe` fires, start a timer that resets on every received game message (`onStartGame`, `onUpdate`, `onEndGame`). If the timer expires without a message (e.g., 2× expected round duration with no activity), the bot should re-subscribe or reconnect. This turns a silent zombie into a recoverable state.

**Note:** Investigate server behavior first — the limit may be configurable or may be a bug on the server side. If the server limit is intentional, the bot-side watchdog is still needed to detect and recover from eviction.

---

## Current Status (Jan 2026)

### Completed Features

- ✅ Spring Boot REST API with OpenAPI documentation
- ✅ Environment/BotGroup/Game CRUD via REST API
- ✅ Bot group start/stop/restart/status
- ✅ `BotFactory` for dynamic bot creation
- ✅ Dual-status tracking (target vs actual)
- ✅ Health monitoring (30s interval)
- ✅ Concrete `BettingMiniGameBot` with configuration-driven game types
- ✅ `GameMessageTypes` for dynamic JSON polymorphism
- ✅ Stateless clients per Environment
- ✅ Random username generation from 5k name file
- ✅ UI for environment and bot group management
- ✅ Virtual threads throughout the application (schedulers, bot creation, health monitoring)
- ✅ Parallel user registration and bot authentication with configurable concurrency

### Backlog

**Priority (Internal Testing Feedback):**
- [x] Direct migration of existing bots - when `existingGroup = true` flag is set in POST `/api/v1/bot-group`, skip user registration and add bots directly to database
- [x] Configurable betting options per game - replace linear `0..maxOptions` with predefined lists (e.g., `[1, 10, 100]`) stored in `Game` entity
- [ ] Configurable betting values per game - define allowed bet amounts (e.g., `[100, 500, 1000]`) instead of arbitrary values to pass server validation

**Infrastructure:**
- [x] MongoDB integration (replace in-memory storage)
- [ ] CI/CD pipeline setup
- [x] Review and improve Docker configuration
- [ ] Add Grafana for observability, connect Loki for log aggregation

**Architecture:**
- [ ] Spring Plugin Support Framework - move bot scripts and messages to separate
  plugin module/repository for hot-reload without full restart. Planned as a
  seven-step sequence in `docs/plans/PLUGIN_HOT_RELOAD.md`; **steps 1-2 are done**
  (observability baseline + string-keyed registries — see "Plugin registries"
  under Architecture). Steps 3-7 (separate jars, child classloader, two coexisting
  versions, drain + classloader release, reload endpoint + forced cutover) are not
  started and are gated on a classloader-GC spike. The root `PLUGIN_PLAN.md` that
  used to describe this was **deleted** (AD-22) — it put the engine in the
  reloadable layer, which is the inverse of the shipped design.
- [x] Time-based activation — recurring time-of-day windows on `BotGroup` via
  `activationMode` (`SCHEDULED`/`MANUAL_ON`/`MANUAL_OFF`, null = legacy) +
  `activationWindow` (`{from, to, days}`), reconciled every minute by
  `ActivationScheduler` driving the existing start/stop lifecycle. **Supersedes**
  the dormant `timeBased`/`timeFrom`/`timeUntil` fields (removed). See
  `docs/plans/TIMED_ACTIVATION.md`. REST surface unchanged — activation is set on
  create/PATCH; `start`/`stop` are unchanged (manual actions park a SCHEDULED group
  as `MANUAL_ON`/`MANUAL_OFF`).
- [x] Periodic logout logic - one bot per group logs out per hour (round-robin), configurable via `application.properties` (environment-dependent)
- [x] DEAD groups recover without an operator — `DEAD_GROUP_RESTART` made a DEAD group
  one-click restartable (the `start()` reclaim path); `DEAD_GROUP_AUTO_RECOVERY` supplies
  the missing **trigger**, so a group that died during an upstream outage restarts itself
  once an anonymous WebSocket probe says the environment is serving again, bounded by a
  6-attempt budget and opted out of by `STOPPED`. See "A DEAD bot group restarts itself"
  under Architecture. Reading the old framing — that a DEAD group is restored only by a
  human pressing `/restart` — as still-current is what a three-day staging outage cost.

**Code Quality:**
- [ ] Add more unit and component tests
- [ ] Add clearer Exception system with proper hierarchy
- [ ] Improve logging - clearer separation between INFO and DEBUG levels
- [ ] Replace all deprecated API usage, remove deprecated classes and methods
- [ ] Review `Bot.java` methods (`connectToSocket`, `restart`, etc.) - determine if still needed or can be simplified
- [ ] Pre-flight username length validation in `BotGroupService.save` — auth gateway caps usernames per product (Tip/P_116 = 12 chars). Reject `namePrefix.length() + String.valueOf(botCount).length() > cap` before fan-out to save N wasted auth calls and surface a clean 400 instead of forwarding all N upstream errors.
- [ ] Restart lifecycle bug — bots that authenticated cleanly on initial auto-start fail on `/restart` with `ValidationException: Authentication configuration is required`. Observed 2026-06-09 on group `0c9a93cb-20d6-4f57-9dbc-5c315dcf52e2`: 18 bots succeeded at 10:01:09 auto-start, all 18 failed at 10:02:55 restart (same code path that created them 1m46s earlier). Likely cause: `EnvironmentClientRegistry` or `BotCredentials` not rebuilt after `BotGroupRuntime.shutdown`. Investigate the restart path in `BotGroupBehaviorService`.
- [ ] Remove `Environment.appId` field — once `ProductCode.appId` is populated for all 10 products (P_097/P_098/P_116 done as of 2026-06-09; remaining: P_066, P_103, P_105, P_114, P_118, P_119, P_222), drop the field from `Environment`, `EnvironmentDTO`, the mapper, and the fallback at `EnvironmentClientRegistry.java:124`. Mongo will keep the stale field as harmless extra data. Same applies for hardcoded appId values inside `TipLoginRequest` / `BomLoginRequest` — they should read from the resolved appId on the login context instead of duplicating brand knowledge.

**Monitoring:**
- [ ] Add API endpoints for monitoring
- [x] Actuator endpoints (health, info, metrics, loggers) on main port
- [ ] Advanced health monitoring with diagnostics (see Health Diagnostics below)

### Health Diagnostics (Planned)

Diagnostic checks to surface why bots can't start or aren't performing correctly. Some overlap with back office, but not everyone who needs these metrics has back office access.

| Check | Condition | Severity |
|---|---|---|
| **Game server down** | Bots cannot connect to WebSocket | Critical |
| **Auth down** | Bots cannot authenticate or register | Critical |
| **Game down** | WS connected but no messages for >1 min, or cannot subscribe to game subchannel | Critical |
| **Bot down** | Thread dead or unresponsive | Error |
| **Balance low** | Bot out of balance or short on balance | Warning |
| **RTP anomaly** | Bots making net gains over prolonged period (RTP >100%) | Critical — indicates game logic issue |
