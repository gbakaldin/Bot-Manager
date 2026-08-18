# Log Volume Tiering — Surviving 10 Prod Environments × Thousands of Bots

## Goal

Restructure Bot Manager's logging so the default log level is cheap and correct at
fleet scale (~10 prod environments × 2–3k bots each), while keeping the ability to
get per-bot detail exactly when and where it is needed. Today the baked-in default
is `DEBUG` for `com.vingame.bot`, every line is written twice (console + rolling
file) by two *synchronous* appenders, and the surviving per-bot DEBUG classes
project to **46–124 GB/day** at 20–30k bots — against a 10 GB accumulated-file cap,
a 7-day Loki retention over a disk of unknown size, and a box that already died once
(2026-06-30) on ENOSPC at 33.7 GB, taking Mongo with it. The plan lands in four
independently shippable phases: make logging asynchronous and its retention
level-aware (config only), make the default level a sparse group-level tier model,
replace "global DEBUG kept briefly" with **scoped per-group DEBUG with a TTL and
auto-escalation**, and add an out-of-band **evidence shim** that hardlink-promotes
the log files around an incident so a cheap default retention never costs us the
one window that mattered.

---

## Findings — Current State

### The logging configuration

- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/log4j2.properties:8` —
  `logger.app.level = debug`. This is **baked into the jar**, so today *any* level
  change requires a rebuild + redeploy.
- Same file `:3-4` and `:10-11` — both the root logger and `com.vingame.bot` write to
  **ConsoleAppender and RollingFileAppender**. Every application line is serialized
  and written twice: once as a pattern line to stdout (→ docker json-file, capped
  50m×5 at `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:5-9`), once as JSON to
  `/app/logs/console.log`.
- Same file `:14-25` — **neither appender is async**. There is no `AsyncAppender` and
  no `AsyncLoggerContextSelector`; `com.lmax:disruptor` is **not** on the classpath
  (verified: `mvn dependency:tree -Dincludes='org.apache.logging.log4j:*,com.lmax:*'`
  on `bot-app` resolves `log4j-core:2.24.1`, `log4j-slf4j2-impl:2.24.1`,
  `log4j-layout-template-json:2.24.1` and **no disruptor**). Both appends happen on
  the calling bot thread.
- Same file `:28-31` — hourly rollover (`interval = 1`, `modulate = true`,
  filePattern `console-%d{yyyy-MM-dd-HH}.log`).
- Same file `:36-45` — `Delete` with `basePath = /app/logs`, `maxDepth = 1`,
  `ifFileName.glob = console-*.log`, deleting on `age = 7d` **or**
  `ifAccumulatedFileSize.exceeds = 10GB`.
- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/log4j2-json-template.json`
  — 5 MDC fields (`botGroupId`, `botId`, `environmentId`, `gameType`, `botUserName`)
  plus timestamp/level/logger/thread/message/exception. This is the ~400 B/line the
  projections are built on.
- Runtime version reality: parent is **Spring Boot 3.4.0**
  (`/Users/gleb/IdeaProjects/Bot/pom.xml:9`), not 4.0.0 as `CLAUDE.md` claims. Log4j2
  is **2.24.1**. Java 21 (`/Users/gleb/IdeaProjects/Bot/pom.xml:35-37`).

### The pipeline

- `/Users/gleb/IdeaProjects/Bot/promtail-config.yml:18` — `__path__: /logs/*.log`.
  **Non-recursive** — a subdirectory of `logs/` is not scraped. Confirmed.
- Same file `:19-34` — a `json` stage extracting `level`, `botGroupId`,
  `environmentId`, `gameType`, then a `labels` stage promoting all four. So
  `{level="DEBUG"}` **is** a valid Loki stream selector today. There is **no `drop`
  stage**.
- `/Users/gleb/IdeaProjects/Bot/loki/loki-config.yaml:38-39` — flat
  `limits_config.retention_period: 168h`, no `retention_stream`. Compactor
  retention is already enabled (`:44-49`).
- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:85` — promtail mounts `./logs`
  **read-only**; `:61` — bot-manager mounts `./logs:/app/logs` read-write and runs as
  `${HOST_UID}:${HOST_GID}` (`:30`).

### The surviving per-bot DEBUG classes (all verified at the cited lines)

| Line | What | Rate |
|---|---|---|
| `/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:354` | `checkBalance() ENTRY` | 1/round/bot |
| `…/Bot.java:365`, `…/Bot.java:368` | `checkBalance() fetched` / `using cached` (mutually exclusive; `:359` adds a third on the fetch path) | 1/round/bot |
| `…/BettingMiniGameBot.java:335` | `session balance` (via `onNewSession`, `:328-337`) | 1/round/bot |
| `…/SlotMachineBot.java:166` | `session balance` (slot twin of the above) | 1/session/bot |
| `…/SlotMachineBot.java:261` | `spin result` | 1/**spin**/bot |
| `…/SlotMachineBot.java:300` | `below spin cost — skipping tick` | **every 3 s, forever**, for any bot under the spin cost |
| `…/TaiXiuGameBot.java:143` | single-entry lock remap | per extra bet/round |

Spin cadence is a hardcoded 3000 ms —
`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:364-366`
(`resolveSpinInterval()` returns `3_000L`), wired at `:398`. A slot bot is therefore
~5× a betting bot's line rate, and `:300` is an unbounded spam trap independent of
round rate.

### The per-bot INFO classes that must be folded (all verified)

| Line | What |
|---|---|
| `…/BettingMiniGameBot.java:192` | `BettingMiniGameBot initialized: …` — one per bot at group start |
| `…/SlotMachineBot.java:155` | `SlotMachineBot initialized: …` — same |
| `…/BettingMiniGameBot.java:332` | `balance … below minimum …, triggering deposit` |
| `…/SlotMachineBot.java:163` | same, slot twin |
| `…/Bot.java:297` | `Bot {}: restart requested` — fires per bot per periodic-logout cycle |

A 30k-bot fleet start emits 30k `initialized` lines. Periodic logout
(`bot.periodic-logout.interval-minutes=60`,
`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/application.properties`)
turns `Bot.java:297` into a standing ~8 lines/s at 30k bots.

### The one INFO class that scales with round rate

`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/SessionAggregationService.java:305`
(session entry) and `:418` (session end) — one INFO line each per round per group.
At 300 groups on 45 s rounds that is ~13 lines/s ≈ **0.7 GB/day**, i.e. it dominates
every other INFO class combined. `:230` is the 5 s DEBUG flush. The service already
owns a virtual-thread scheduler (`:118-129` start, `:135-141` stop) — the project
idiom for this kind of component.

### Data already available for a fleet rollup (Phase 1 tier 2)

- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1789-1805`
  — `monitorHealth` computes `playing / reconnecting / dead / total` per group on a
  30 s cadence (scheduled at `:1777-1783`) and logs it at **DEBUG** (`:1799`).
- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/infrastructure/observability/InfoGaugeRefresher.java:183-232`
  — already calls `countBotsByEnvAndStatus()`, `countManagedBotsByEnv()`,
  `countOpenWsByEnv()`, `countDeadGroupsByEnv()`, `listRunningEnvironmentInfo()`,
  `listGroupBalances()` on a 10 s virtual-thread scheduler (`:88-95`). **Tier 2 is a
  read over these existing accessors, not a new subsystem.**

### Early-warning signals already computed (Phase 2 auto-escalation)

- Watchdog expiry —
  `…/BettingMiniGameBot.java:371` (`no game message in {}s — triggering full reconnect`),
  metered as `bot_watchdog_expired_total`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:72,181`).
- Reconnects — `…/Bot.java:569` / `:585`, metered as `bot_reconnects_total`
  (`BotMetrics.java:66,148`).
- Dead ratio — `monitorHealth` at `BotGroupBehaviorService.java:1793-1802`, gated by
  `bot.group.dead.threshold=0.80`.
- `BotMdc` lives in **`bot-api`** —
  `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/common/logging/BotMdc.java`
  — which both `bot-engine` and `bot-app` depend on
  (`/Users/gleb/IdeaProjects/Bot/bot-app/pom.xml:16-35`). That is the correct home
  for a scoped-debug registry both modules must reach.

### Alerting pipeline (Phase 3 wiring)

- `/Users/gleb/IdeaProjects/Bot/alertmanager/alertmanager.yml:41-46` — `group_wait: 30s`,
  `group_interval: 5m`, `repeat_interval: 4h`.
- Same file `:59-66` — the `BotManagerDown` **two-sibling** routing, with the comment
  explaining that a matching child *consumes* the alert and `continue: true` only
  continues to the next **sibling**, never back to the parent's receiver.
- `/Users/gleb/IdeaProjects/Bot/prometheus/alerts.yml:76-78` — `BotManagerDown … for: 2m`;
  `:213-215` — `EnvironmentGroupDead: groups_dead_by_env > 0, for: 5m`.
- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:181-229` — the `viptalk-shim`
  precedent: stock `python:3.12-alpine`, `command: ["python","-u","/app/shim.py"]`,
  bind-mounted script, `restart: unless-stopped`, `logging: *default-logging`, a
  stdlib-only healthcheck, and deliberately **no `depends_on: bot-manager`**.
- `/Users/gleb/IdeaProjects/Bot/viptalk-shim/shim.py` — 485 lines, stdlib only,
  permissive about path/body, refuses `status: resolved`.
- **Guard tests exist and will fail on a careless edit**:
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/java/com/vingame/bot/domain/alert/AlertmanagerRoutingTest.java`
  parses `alertmanager.yml` and walks the route tree; `:167-173` asserts
  `EnvironmentSocketDown` reaches **exactly** `[viptalk]`, and `:105-127`
  (`matches`) **fails loudly** on any matcher operator other than `=`.
  `…/AlertRulesAudienceTest.java` enforces `job:`/`audience:` labels on rules.

### Build-level facts that constrain Phase 2

- `/Users/gleb/IdeaProjects/Bot/pom.xml:110-126` pins `annotationProcessorPaths` to
  mapstruct + lombok only. Specifying that element **disables classpath scanning for
  annotation processors**, so log4j2's plugin processor would *not* run and a
  `@Plugin`-annotated custom filter would get **no `Log4j2Plugins.dat` entry**.
- `/Users/gleb/IdeaProjects/Bot/bot-app/pom.xml:131-138` already carries a
  `requiresUnpack` for `log4j-core` — evidence that fat-jar plugin discovery is
  already a known sore point here.

---

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Async appender (Phase 0) | **ready** | `AsyncAppender` is stdlib log4j-core, no new dependency. Full async loggers would need `com.lmax:disruptor` — see AD-3. |
| 2 h rollover (Phase 0) | **ready** | One-line change; `%d{…-HH}` keeps filenames unique. |
| Loki per-stream retention (Phase 0) | **ready** | `level` is already a Loki label (`promtail-config.yml:30-34`). |
| Promtail DEBUG drop (Phase 0) | **partial — flagged** | Conflicts with Phase 2's scoped DEBUG. Shipped **off by default**; see AD-6. |
| Console/file double-write (Phase 0) | **ready — added** | Not in the agreed list; a ThresholdFilter halves DEBUG cost for free. See AD-5. |
| Externalized log config (Phase 0) | **ready — added** | `LOGGING_CONFIG` + bind mount, same posture as `prometheus.yml`. See AD-2. |
| Env-driven default level (Phase 1) | **partial** | `LOGGING_LEVEL_COM_VINGAME_BOT` *should* work (Spring Boot treats `log4j2.properties` as a standard config location) but is **unverified on this stack**. Fallback in AD-7. |
| Tier-1 INFO folding (Phase 1) | **ready** | 5 known call sites, all cited above. |
| Tier-2 fleet rollup (Phase 1) | **ready** | Pure read over existing `BotGroupBehaviorService` accessors. |
| Session-summary level (Phase 1) | **decided** | Demoted INFO→DEBUG; see AD-8. Reverses a `CLAUDE.md` guideline, which Phase 1 rewrites. |
| Scoped per-group DEBUG (Phase 2) | **partial** | Needs a filter installed **programmatically**, not via `@Plugin` — see AD-9 and the `annotationProcessorPaths` finding. |
| Auto-escalation (Phase 2) | **ready** | All three signals already computed at cited lines. |
| `below spin cost` spam fix (Phase 2) | **ready** | Single call site, `SlotMachineBot.java:300`. |
| Evidence shim (Phase 3) | **ready** | Exact `viptalk-shim` precedent to copy. |
| Alertmanager route for evidence (Phase 3) | **partial — trap** | Adding a `continue: true` child for a non-`BotManagerDown` alertname **silently kills its VipTalk delivery** unless a sibling is added. See AD-14. |
| Prod disk size | **BLOCKED — unknown** | Nobody has stated Bot-1/Prod-Bot disk size. Pre-ramp check in Verification step P0-6. |
| Ring buffer for pre-detection run-up | **out of scope** | Explicit non-goal; see Open Items. |

---

## Architecture Decisions

**AD-1 — Four phases, each independently shippable.** Phase 0 touches no Java and is
safe to land alone. Phase 1 does not depend on Phase 0. Phase 2 depends on Phase 1
only for "INFO is the default" being true in prod (it is correct, just less useful,
if Phase 1 has not shipped). Phase 3 depends on nothing but the existing
Alertmanager.

**AD-2 — Log config is externalized to a bind mount, with the in-jar copy retained as
the fallback.** `log4j2.properties` lives inside the jar, so today a log-level or
retention change is a **rebuild + full redeploy of the bot fleet's JVM**. That is
untenable at 10 environments. Ship a committed `logging/log4j2.properties`,
bind-mount it read-only at `/app/config/log4j2.properties`, and point
`LOGGING_CONFIG=${LOGGING_CONFIG:-/app/config/log4j2.properties}` at it — the same
posture as `prometheus.yml` / `alerts.yml` / `alertmanager.yml`. The in-jar file
**stays** and stays in sync, so tests, local runs and any host missing the mount
behave exactly as before (log4j2 with no config at all falls back to
`DefaultConfiguration` = ERROR-to-console, which would be a silent catastrophe).
*Flagged as an addition to the agreed design*: without it, "Phase 0 is config only"
still means a rebuild, and every later tuning pass does too.

**AD-3 — `AsyncAppender`, not `AsyncLoggerContextSelector`.** Full async loggers
require `com.lmax:disruptor`, which is verifiably absent from the classpath; adding
it is a pom change and therefore not config-only. `AsyncAppender` is in `log4j-core`,
is pure configuration, and its `ArrayBlockingQueue` at `bufferSize = 8192` is ample
for the target 7,200 events/s. If measurement later shows queue contention, the
disruptor upgrade is a clean follow-up that changes no other decision here.

**AD-4 — Under queue pressure, drop DEBUG/TRACE, never INFO+.** Set
`log4j2.asyncQueueFullPolicy = Discard` and `log4j2.discardThreshold = DEBUG` (via
`log4j2.component.properties` on the classpath, or `JAVA_TOOL_OPTIONS` in compose).
Logging must never add latency to a bot thread; if the choice is "block a bot" or
"lose a DEBUG line", lose the line. WARN/ERROR are never discarded and remain
coupled to alerting.

**AD-5 — The ConsoleAppender is capped at INFO+ via a `ThresholdFilter`.** Every line
is currently serialized and written twice. `docker logs` remains useful (INFO+ and
all WARN/ERROR still appear) while DEBUG/TRACE go to the JSON file only — halving
the cost of exactly the tier we are trying to make survivable, and removing DEBUG
pressure on the 50m×5 json-file cap. *Flagged as an addition to the agreed design.*

**AD-6 — Loki per-stream retention is the primary control; the promtail DEBUG `drop`
stage ships present but disabled.** These two agreed items are in tension: a
permanent `drop` on DEBUG makes **Phase 2's scoped per-group DEBUG invisible in
Grafana**, which is the whole point of Phase 2. Resolution: `retention_stream` gives
`{level=~"DEBUG|TRACE"}` **24 h** and `{level=~"WARN|ERROR"}` **30 d** with
`retention_period` (the default for everything else, i.e. INFO) raised to **30 d**;
the `drop` stage is committed, documented, and commented out, to be enabled only on
an instance that is provably drowning. *Flagged: this is the one place the agreed
design is not implemented as written, and the reason is Phase 2.*

**AD-7 — Env-driven level via Spring Boot's `logging.level.*`, with a log4j2 property
substitution as the fallback.** Try `LOGGING_LEVEL_COM_VINGAME_BOT=INFO` in compose
first — Spring Boot's `Log4J2LoggingSystem` lists `log4j2.properties` as a standard
config location and applies `logging.level.*` over it afterwards, and because the
`com.vingame.bot` LoggerConfig exists by exact name, `setLogLevel` mutates it in
place and **preserves `additivity=false` and both appenderRefs**. This must be
**verified on the box, not assumed** (Verification P1-1). If it does not take, fall
back to `logger.app.level = ${env:BOT_LOG_LEVEL:-info}` in the properties file.
Either way the shipped default becomes **INFO**, and staging sets the variable to
`DEBUG`.

**AD-8 — The per-round session summaries are demoted INFO→DEBUG.** They are the only
INFO class that scales with round rate (~0.7 GB/day at 300 groups) and would dwarf
tiers 1 and 2 combined. The information is not lost: it stays default-visible on
staging (where DEBUG is the default), it is fully covered in prod by
Prometheus/Grafana (`bot_bets_placed_total`, `bot_bet_amount_total`,
`bot_winnings_total`), and Phase 1's tier-2 rollup carries a downsampled
rounds-and-stake line per group. This **reverses** the current `CLAUDE.md` guideline,
which Phase 1 rewrites; it is one level constant away from being reverted.

**AD-9 — The scoped-DEBUG filter is installed programmatically, not as a
`@Plugin`.** `pom.xml:110-126` pins `annotationProcessorPaths`, so log4j2's plugin
annotation processor never runs and a `@Plugin` filter would have no
`Log4j2Plugins.dat` entry inside the fat jar. Instead a Spring `@Component` obtains
`(LoggerContext) LogManager.getContext(false)`, calls
`config.getLoggerConfig("com.vingame.bot").addFilter(filter)` and `ctx.updateLoggers()`
in `@PostConstruct`. No plugin registry, no `packages =` attribute, no fat-jar
scanning. Chosen over the built-in `DynamicThresholdFilter` because that filter's
value→level map comes from static configuration and would require a context
reconfiguration per runtime change.

**AD-10 — The filter returns `ACCEPT` for enabled groups and `NEUTRAL` for everything
else — never `DENY`.** A LoggerConfig's filter runs *before* the level check
(`Logger.PrivateConfig.filter` consults `config.getFilter()` first and short-circuits
on a non-`NEUTRAL` result), which is precisely what lets an `ACCEPT` surface a DEBUG
event through an INFO-level logger. `NEUTRAL` leaves normal level rules intact for
every other group. The filter short-circuits on an empty registry so the steady-state
cost is one volatile read.

**AD-11 — Scoped DEBUG over global DEBUG with fast deletion.** Global DEBUG is
~5.2 GB/hour and ~7,200 events/s at target scale; no retention policy makes that
survivable, and it is also the shape that filled the disk on 2026-06-30. Default INFO
plus per-`botGroupId` DEBUG with a mandatory TTL costs a single group's worth of
volume (tens of lines/minute) and is the only version of "turn on detail" that scales
linearly with *incidents* rather than with fleet size.

**AD-12 — Detail is auto-escalated on early-warning signals, not on death.** Enabling
DEBUG after a group is DEAD produces logs of a group that has stopped doing anything.
First watchdog expiry, a reconnect-rate threshold, and a rising `dead/total` that is
still under `bot.group.dead.threshold` all fire *before* the group dies and all are
already computed. Auto-escalation is what makes the deliberate absence of a ring
buffer acceptable.

**AD-13 — The evidence promoter is a separate container, `python:3.12-alpine`,
stdlib only, script bind-mounted, no `depends_on: bot-manager`.** bot-manager cannot
promote its own logs when bot-manager is the thing that died — the identical
reasoning that already justifies `viptalk-shim` for `BotManagerDown`. It runs as
`${HOST_UID}:${HOST_GID}` (no sudo exists on Bot-1 and none is needed; hardlinking
requires only write+execute on the destination directory).

**AD-14 — `ln`, never `cp`.** A copy doubles the bytes at exactly the moment disk is
the constraint. A hardlink costs zero additional blocks and still defeats log4j2's
`Delete`: unlinking the rolled file leaves the inode alive while our link holds a
reference. The **live `console.log` is hardlinked too** — it keeps growing until
rollover renames it, and our link follows the original inode, so we capture precisely
the post-incident tail and then stop.

**AD-15 — Files are chosen as "newest two at execution time", never by arithmetic on
the alert timestamp.** This re-centres the window automatically: an incident at
minute 118 of a 2 h period yields `prev` = 58 min of normal + 2 min post-error and
`current` = 3 min post-error, and the ancient period falls off by itself. Timestamp
arithmetic would need clock-skew handling and would still pick the wrong file at a
boundary.

**AD-16 — Two-phase promotion (immediate + deferred), plus a tail pass.** Pin
prev+current **immediately** on the webhook (insurance: `BotManagerDown` fires while
the box may still be going down, and waiting 5 minutes loses the worst incident
class), re-run at **+5 min** (matching Alertmanager's `group_interval`), and run a
final pass at **the next rollover boundary + 120 s** so the file that was live at
T+0 is promoted after it closes. All passes are the same idempotent
"newest-two-plus-live" operation; idempotent hardlinks make the repeats free.

**AD-17 — Coalesce on Alertmanager's `groupLabels`, one pending deadline per
incident key.** Alerts carry `product` / `environmentId` / `gameId`, **not
`botGroupId`** (`groups_dead_by_env` has no group-id label), so "one deadline per
group" is not expressible from the payload. The incident key is the webhook's
`groupLabels` map rendered canonically — which is exactly the tuple Alertmanager
already dedups on (`alertname, product, environmentId, audience`). A deteriorating
environment redelivering every 5 minutes must refresh one deadline, never stack
timers. *Flagged: a correction to the agreed wording, same intent.*

**AD-18 — Destination is `logs/evidence/`, a subdirectory on the same filesystem.**
Same filesystem is mandatory for hardlinks. A subdirectory is what makes it escape
both sweepers, for two independent verified reasons each: promtail's
`__path__: /logs/*.log` is non-recursive (else promoted files are re-ingested into
Loki, defeating the whole point), and log4j2's `Delete` uses `basePath /app/logs`
with `maxDepth = 1` **and** an `ifFileName.glob` of `console-*.log` matched against
the path relative to `basePath`.

**AD-19 — `evidence/` has its own sweep.** Age-based (default 14 d) plus a total-size
guard (default 5 GB, oldest-first), run on every pass and on a 1 h timer. Without it
the fix for unbounded growth is itself unbounded growth.

**AD-20 — 2 h rollover, `modulate = true` retained.** Fewer boundaries and, more
importantly, a longer window of pre-incident context for Phase 3's "newest two".
`%d{yyyy-MM-dd-HH}` still yields unique names because `modulate` puts boundaries on
even hours.

**AD-21 — The shim retro-promotes on an unclean start.** In a full-stack failure
Alertmanager may be dead too, so the webhook cannot be the only path. The shim writes
`logs/evidence/.clean-shutdown` on SIGTERM and removes it on start; a start that
finds no marker promotes the newest two immediately, tagged `boot`.

---

## Plan

### Phase 0 — Config only: async, level-aware retention, longer window

No Java changes. Touches `log4j2.properties`, `loki-config.yaml`,
`promtail-config.yml`, `docker-compose.yml`.

1. **Externalize the log config (AD-2).** Copy
   `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/log4j2.properties` to a
   new committed `/Users/gleb/IdeaProjects/Bot/logging/log4j2.properties`. Add to
   `docker-compose.yml` under `bot-manager`: a `./logging/log4j2.properties:/app/config/log4j2.properties:ro`
   volume and `- LOGGING_CONFIG=${LOGGING_CONFIG:-/app/config/log4j2.properties}`.
   Apply steps 2–5 to **both** copies and add a header comment to each naming the
   other as its twin.
2. **Async (AD-3, AD-4).** Insert an `Async` appender wrapping the rolling appender,
   and point `rootLogger`/`logger.app` at it instead of `RollingFileAppender`:
   ```properties
   appender.async.type = Async
   appender.async.name = AsyncRolling
   appender.async.appenderRef.type = AppenderRef
   appender.async.appenderRef.ref = RollingFileAppender
   appender.async.bufferSize = 8192
   appender.async.blocking = false
   ```
   **Gotcha:** in the properties format a nested AppenderRef *inside an appender*
   requires the explicit `.type = AppenderRef` line (unlike a logger's appenderRef).
   Omitting it produces a silently mis-built appender. Add
   `log4j2.asyncQueueFullPolicy=Discard` / `log4j2.discardThreshold=DEBUG` via
   `bot-app/src/main/resources/log4j2.component.properties`.
3. **Console at INFO+ (AD-5).** Add to the console appender:
   ```properties
   appender.console.filter.threshold.type = ThresholdFilter
   appender.console.filter.threshold.level = info
   appender.console.filter.threshold.onMatch = NEUTRAL
   appender.console.filter.threshold.onMismatch = DENY
   ```
4. **2 h rollover (AD-20).** `appender.rolling.policies.time.interval = 1` → `2`.
   Leave `modulate = true` and the filePattern untouched.
5. **Re-check the file caps.** With INFO as the future default the 10 GB /
   `ifAccumulatedFileSize` cap stops binding; leave it as the backstop but raise
   `age` from `7d` to `14d` only **after** step P0-6 establishes the disk size.
   Until then change nothing here — the current caps are the thing standing between
   us and a repeat of 2026-06-30.
6. **Loki retention (AD-6).** In
   `/Users/gleb/IdeaProjects/Bot/loki/loki-config.yaml`, raise
   `limits_config.retention_period` to `720h` and add:
   ```yaml
     retention_stream:
       - selector: '{level=~"WARN|ERROR"}'
         priority: 1
         period: 720h
       - selector: '{level=~"DEBUG|TRACE"}'
         priority: 1
         period: 24h
   ```
   Keep the existing file-header comment style explaining why the block exists.
7. **Promtail drop stage, disabled (AD-6).** Add the stage to
   `/Users/gleb/IdeaProjects/Bot/promtail-config.yml` **commented out**, with a
   comment stating that enabling it blinds Phase 2's scoped DEBUG in Grafana:
   ```yaml
   #      - match:
   #          selector: '{level=~"DEBUG|TRACE"}'
   #          action: drop
   ```
8. Update `docs/plans/LOG_VOLUME_TIERING.md`'s sibling docs? No — documentation
   changes land in Phase 1 with the tier model.

### Phase 1 — A cheap, correct default level

1. **Prove the env-driven level (AD-7).** Add
   `- LOGGING_LEVEL_COM_VINGAME_BOT=${BOT_LOG_LEVEL:-INFO}` to the `bot-manager`
   service in `docker-compose.yml`, set `logger.app.level = info` in **both**
   `log4j2.properties` copies, and verify per P1-1. Only if that fails, switch the
   properties files to `logger.app.level = ${env:BOT_LOG_LEVEL:-info}` and drop the
   compose variable. Staging sets `BOT_LOG_LEVEL=DEBUG` in `secrets.env`/`.env`.
2. **Fold the five per-bot INFO classes into group-level counts (tier 1).**
   - Add a small group-scoped aggregator (new class in `bot-engine`,
     `infrastructure/observability`, sibling of `SessionAggregationService`) keyed by
     `botGroupId`, accumulating `initialized` and `auto-deposited` counts + summed
     deposit amounts, flushed on a short delay (5 s) after the last increment so a
     group start emits **one** line: `group <id> (<name>): 47/47 bots initialized,
     game=<name>, strategy=<id>` and `group <id>: 47 bots auto-deposited, total 2.3B`.
   - Demote `…/BettingMiniGameBot.java:192`, `…/SlotMachineBot.java:155`,
     `…/BettingMiniGameBot.java:332`, `…/SlotMachineBot.java:163` to DEBUG and feed
     the aggregator at each site.
   - `…/Bot.java:297` (`restart requested`) → DEBUG; the periodic-logout *cycle*
     already logs at INFO at group level, which is the "why".
   - Keep group create/start/stop/restart and state transitions in
     `BotGroupBehaviorService` at INFO unchanged — those are already group-scoped.
3. **Tier 2 — periodic fleet rollup.** New `@Component` in
   `bot-app/…/infrastructure/observability/`, modelled on `InfoGaugeRefresher`
   (`:88-95` scheduler idiom), on a 5-minute virtual-thread cadence:
   - One INFO line per environment: `env <id> (<name>, product <code>): groups=<n>,
     bots=<n>, connected=<n>, dead=<n>, deadGroups=<n>` — sourced from
     `countManagedBotsByEnv()`, `countOpenWsByEnv()`, `countBotsByEnvAndStatus()`,
     `countDeadGroupsByEnv()`, `listRunningEnvironmentInfo()`.
   - A **second line per group only when that group is not clean** (any DEAD or
     RECONNECTING bot, or the group itself DEAD): `group <id> (<name>): playing=<n>,
     reconnecting=<n>, dead=<n>/<n>` — the same figures `monitorHealth` already
     computes at `BotGroupBehaviorService.java:1793-1800`. Volume scales with
     sickness, not fleet size.
   - Include per-group `rounds` and `staked` since the last rollup, drained from
     `SessionAggregationService` (new `drainRollup()` returning and resetting
     per-group counters), so AD-8's demotion loses no operational signal.
4. **Demote the session summaries (AD-8).** `SessionAggregationService.java:305` and
   `:418` → `log.debug`.
5. **Update the docs.** Rewrite the "Logging Guidelines" section of
   `/Users/gleb/IdeaProjects/Bot/CLAUDE.md` around the tier model: INFO = tier 1
   (group lifecycle) + tier 2 (5-minute fleet rollup, unclean groups only); DEBUG =
   per-bot / per-session aggregate detail **including the per-round session
   summaries**, reachable per-group via Phase 2; TRACE unchanged. State explicitly
   that INFO must not contain anything whose rate is a function of bot count or round
   rate. Correct the stale "Spring Boot 4.0.0" line while in there.

### Phase 2 — Scoped per-group DEBUG with TTL and auto-escalation

1. **Registry.** New `ScopedDebugRegistry` in **`bot-api`**, package
   `com.vingame.bot.common.logging` (alongside `BotMdc`, reachable from both
   `bot-engine` and `bot-app`): a `ConcurrentHashMap<String, Long>` of
   `botGroupId → expiryEpochMillis`, `enable(groupId, duration)`,
   `isEnabled(groupId)`, `sweep()`, and a `volatile boolean anyEnabled` fast path.
2. **Filter (AD-9, AD-10).** `ScopedDebugFilter extends AbstractFilter` in `bot-api`,
   returning `NEUTRAL` immediately when `!anyEnabled`, else `ACCEPT` when
   `ThreadContext.get("botGroupId")` is enabled, else `NEUTRAL`. Never `DENY`. Use
   `ThreadContext.get` directly — no MDC map copies on a hot path.
3. **Installer.** `@Component` in `bot-app` with `@PostConstruct`:
   `LoggerContext ctx = (LoggerContext) LogManager.getContext(false);`
   `ctx.getConfiguration().getLoggerConfig("com.vingame.bot").addFilter(filter);`
   `ctx.updateLoggers();`. Runs after Spring Boot has initialized the logging system,
   so it is not clobbered. Plus a virtual-thread `sweep()` every 30 s.
4. **REST surface.** New `LogLevelController` at `/api/v1/logging` in `bot-app`:
   `POST /debug/{botGroupId}?minutes=N` (default 15, cap 120) → 200 with
   `{groupId, expiresAt}`; `DELETE /debug/{botGroupId}`; `GET /debug` listing active
   scopes. Document alongside the existing controllers in `CLAUDE.md`.
5. **Auto-escalation (AD-12).** Call `registry.enable(groupId, 15m)` from:
   - first watchdog expiry for a group — `…/BettingMiniGameBot.java:371`;
   - reconnect-rate threshold — count reconnects per group over a rolling 5 min at
     `…/Bot.java:569`/`:585` (the sites that already increment
     `bot_reconnects_total`), escalate above a configurable threshold;
   - `dead/total` crossing half of `bot.group.dead.threshold` while still under it —
     `BotGroupBehaviorService.java:1793-1802`.
   Each escalation emits **one** INFO line naming the trigger and the expiry. Rate-limit
   escalation to one per group per 15 min so a flapping group cannot re-arm forever.
6. **Fix the unbounded spam.** `…/SlotMachineBot.java:300`: log only on the
   transition into the below-cost state (an `AtomicBoolean`), and log once on the
   transition back out. Today it fires every 3 s indefinitely.
7. **Config.** New properties with sane defaults: max TTL, escalation thresholds,
   an `bot.logging.scoped-debug.enabled` master switch.

### Phase 3 — Evidence shim (out-of-band retention promotion)

1. **`evidence-shim/shim.py`** — stdlib only, modelled directly on
   `/Users/gleb/IdeaProjects/Bot/viptalk-shim/shim.py`:
   - `POST <any path>` — accept the Alertmanager payload, ignore `status: resolved`,
     be permissive about path and body shape. Compute the incident key from
     `groupLabels` (AD-17). Run pass 1 immediately, schedule pass 2 at +5 min and
     pass 3 at the next even-hour boundary + 120 s (AD-16), coalescing per key.
   - `GET /health` — resolved config, pending incidents, last promotion outcome,
     evidence dir size. Never touches bot-manager.
   - **Promotion pass**: list `/logs/console*.log`, sort by mtime desc, take the
     newest two plus the live `/logs/console.log` (AD-15), and `os.link` each into
     `/logs/evidence/`, catching `FileExistsError` (idempotent). The live file is
     linked under a distinct name — `console-live-<key>-<utc-ts>.log` — since its
     source name is reused across incidents.
   - **Persistence**: pending deadlines in `/logs/evidence/.pending.json` so a shim
     restart does not lose scheduled passes.
   - **Unclean start (AD-21)**: on boot, if `/logs/evidence/.clean-shutdown` is
     absent, run a promotion pass tagged `boot`; remove the marker; write it on
     SIGTERM.
   - **Sweep (AD-19)**: on every pass and hourly — unlink evidence files older than
     `EVIDENCE_MAX_AGE_DAYS` (14), then oldest-first until under
     `EVIDENCE_MAX_BYTES` (5 GB).
   - Create `/logs/evidence/` on start with `os.makedirs(exist_ok=True)`.
2. **`evidence-shim/selftest.py`** — mirror `viptalk-shim/selftest.py`: no network,
   no containers; exercise newest-two selection, idempotent linking, coalescing, and
   the sweep against a tmpdir.
3. **`docker-compose.yml`** — new `evidence-shim` service copying the `viptalk-shim`
   block (`:181-229`): `python:3.12-alpine`, `command: ["python","-u","/app/shim.py"]`,
   `restart: unless-stopped`, `logging: *default-logging`, `PYTHONDONTWRITEBYTECODE=1`,
   stdlib healthcheck, **no `depends_on: bot-manager`**, plus
   `user: "${HOST_UID}:${HOST_GID}"` and volumes
   `./evidence-shim/shim.py:/app/shim.py:ro` and `./logs:/logs` (**read-write** —
   unlike promtail's `:ro`).
4. **`alertmanager/alertmanager.yml`** — new receiver `evidence` →
   `http://evidence-shim:8080/alertmanager`, `send_resolved: false`, `max_alerts: 0`.
   Routes (AD-14 — read the trap in Implementation Notes before writing these):
   ```yaml
     routes:
       - matchers: [alertname = "BotManagerDown"]
         receiver: evidence
         continue: true
       - matchers: [alertname = "EnvironmentGroupDead"]
         receiver: evidence
         continue: true
       - matchers: [alertname = "EnvironmentGroupDead"]
         receiver: viptalk          # MANDATORY sibling — see AD-14
       - …existing BotManagerDown pair, unchanged…
   ```
   Use `=` matchers only — `AlertmanagerRoutingTest.matches` (`:105-127`) asserts
   loudly on any other operator.
5. **Extend `AlertmanagerRoutingTest`** with a case asserting
   `EnvironmentGroupDead` reaches **both** `evidence` and `viptalk`, so the
   consumed-alert trap can never be reintroduced silently.

---

## Implementation Notes / Concerns

- **Phase 0 still requires a rebuild+redeploy the first time**, because the in-jar
  `log4j2.properties` and `log4j2.component.properties` change. AD-2's bind mount is
  what makes *subsequent* passes deploy-free. Do not skip the in-jar copy on the
  theory that the mount covers it — a host without the mount would then run at
  log4j2's `DefaultConfiguration` (ERROR to console only), which looks like "the app
  went quiet".
- **`appender.async.appenderRef.type = AppenderRef` is not optional** in the
  properties format. This is the single most likely silent failure in Phase 0.
- **Two copies of `log4j2.properties` will drift.** Consider a trivial test asserting
  the two files are byte-identical modulo their header comments; cheap insurance.
- **`retention_stream` selectors only work if the label exists.** `level` is promoted
  by `promtail-config.yml:30-34` today; if anyone removes that `labels` stage the
  retention split silently degrades to the global period.
- **Loki per-stream retention can be *longer* than the global `retention_period`**,
  but the compactor only applies it on its `compaction_interval` (10 m) with a
  `retention_delete_delay` of 2 h — do not expect same-minute deletion when verifying.
- **A filter cannot resurrect an event the *root* logger has already dropped.** The
  Phase 2 filter must be attached to the `com.vingame.bot` LoggerConfig, which has
  `additivity = false` and its own appenders (`log4j2.properties:9-11`), so it is
  self-contained. Attaching it to the root instead would not work the same way.
- **`/actuator/loggers` still works and still sets a *global* level.** It is not
  removed; it is the escape hatch. Operators must understand that a global
  `{"configuredLevel":"DEBUG"}` on a 10-env prod instance is now a 5 GB/hour action.
  Say so in `CLAUDE.md`.
- **Phase 2 escalation must be idempotent and rate-limited.** A flapping group firing
  watchdog expiries every 3 minutes must not hold DEBUG open indefinitely; the
  one-escalation-per-group-per-15-min rule in step 5 is what bounds it.
- **The Alertmanager routing trap is real and already bit this repo once** — see the
  comment at `alertmanager/alertmanager.yml:51-58`. A matching child route
  **consumes** the alert; `continue: true` continues to the next **sibling**, never
  back to the parent's receiver. Adding an `evidence` child for `EnvironmentGroupDead`
  without the sibling `viptalk` route **silently stops that alert reaching VipTalk**
  while everything appears to work.
- **`AlertmanagerRoutingTest:167-173` asserts `EnvironmentSocketDown` reaches exactly
  `[viptalk]`.** Keep evidence routes scoped to specific alertnames; a broad
  `severity = "critical"` route would break that test — and, more importantly, would
  mean it was catching a real regression.
- **Hardlinking the live `console.log` behaves correctly by construction**: log4j2
  rolls over by renaming `console.log` to `console-<date>.log` and creating a new
  `console.log`, so our link follows the *original* inode and stops growing at
  rollover. That is the desired post-incident tail, not a bug.
- **`os.link` needs write+execute on the destination directory only.** No permission
  on the source file, no privilege, no sudo. But the shim must run as the same
  `HOST_UID:HOST_GID` as bot-manager or it cannot write into the bind-mounted
  `logs/`.
- **`logs/evidence/` must be created by the shim, not by `deploy.sh`.** `deploy.sh` is
  a temporary uncommitted-in-spirit script (`mkdir -p logs prometheus …` at
  `/Users/gleb/IdeaProjects/Bot/deploy.sh:11`); do not add a dependency on editing
  it. `os.makedirs(exist_ok=True)` in the shim is self-sufficient.
- **Evidence files are hardlinks, so `du` double-counts them** against the live files
  until the originals are unlinked. The size guard is deliberately conservative on
  this — it will over-estimate, which is the safe direction.
- **Prod disk size is unknown.** Every retention number in this plan is a policy
  choice, not a fitted one. Step P0-6 turns it into a number before any ramp.

---

## Open Items

- **Prod/staging disk size is unstated.** Verification P0-6 measures it; if root is
  under ~100 GB, revisit AD-6's 30 d WARN/ERROR retention and the `14d` file age
  before Phase 1 ships.
- **Promtail DEBUG `drop` stage is shipped disabled (AD-6)**, deviating from the
  agreed design because it would blind Phase 2. If the user wants it on for prod
  specifically, that needs promtail `-config.expand-env=true` plus a per-instance
  variable — a small compose change, deliberately not in this plan.
- **`com.lmax:disruptor` / true async loggers** — deferred behind AD-3; revisit only
  if the `AsyncAppender` queue is measured saturating.
- **In-memory per-group ring buffer for the pre-detection run-up** — explicit
  non-goal. AD-12's auto-escalation recovers most of that window; a ring buffer is a
  possible follow-up if incidents prove otherwise.
- **`/api/v1/logging` is unauthenticated**, like `/api/v1/metrics/**` and
  `/api/v1/alerts/**`. It cannot leak data (it only raises verbosity) but it *can* be
  used to generate load. Folded into the existing Spring Security + Keycloak item, not
  solved here.
- **`CLAUDE.md` says Spring Boot 4.0.0; the parent pom says 3.4.0.** Corrected as part
  of Phase 1 step 5, noted here so it is not mistaken for scope creep.
- **Grafana dashboards are untouched.** If AD-8's demotion removes a panel's data
  source, that is a follow-up — no dashboard in
  `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/` currently reads
  logs.

---

## Verification

All commands run from the repo root on the target box (`/home/sgame/Bot` or
equivalent) unless stated. Host port for bot-manager is `8080`; Loki is `3100`;
Prometheus is `9090`. Alertmanager, node-exporter and the shims are **not** exposed on
the host — reach them with `docker compose exec`.

### Universal (every phase)

- **U-1** `docker compose ps` — expect **every** service `Up`, and `bot-manager`,
  `mongo`, `viptalk-shim` (and from Phase 3, `evidence-shim`) reporting `(healthy)`.
  Bot-1 runs the app *and* the observability stack in one Compose project, so a bot
  redeploy restarts Grafana/Prometheus/Loki too — they must be re-checked here.
- **U-2** `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health`
  — expect `200`.
- **U-3** `curl -s http://localhost:8080/api/v1/bot-group/ | head -c 200` — expect a
  JSON array, HTTP 200.

### Phase 0

- **P0-1 — the mounted config is the one in effect.**
  `docker compose exec bot-manager sh -c 'ls -l /app/config/log4j2.properties && grep -c "^appender.async" /app/config/log4j2.properties'`
  → expect the file to exist and a count `> 0`.
  `docker compose exec bot-manager sh -c 'grep -m1 LOGGING_CONFIG /proc/1/environ | tr "\0" "\n"'`
  → expect `LOGGING_CONFIG=/app/config/log4j2.properties`.
- **P0-2 — the async appender thread exists.**
  `docker compose exec bot-manager sh -c 'cat /proc/1/task/*/comm 2>/dev/null | grep -i async'`
  → expect at least one line beginning `AsyncAppender` (thread names are truncated to
  15 chars, so `AsyncAppender-R` is a pass). Empty output = the appender graph did not
  build; check the `.type = AppenderRef` line.
- **P0-3 — no events lost, file still growing.**
  `A=$(wc -l < logs/console.log); sleep 60; B=$(wc -l < logs/console.log); echo "$A -> $B"`
  → expect `B > A`.
- **P0-4 — console no longer carries DEBUG.**
  `docker compose logs --since 5m bot-manager | grep -c ' DEBUG '` → expect `0`.
  `grep -c '"level":"DEBUG"' logs/console.log` → expect `> 0` on staging (where the
  app level is DEBUG). Both must hold: that is the proof the double-write was halved
  rather than the DEBUG tier being lost.
- **P0-5 — rollover is on even hours.** Note the deploy time, then after the next
  boundary:
  `find logs -maxdepth 1 -name 'console-*.log' -newermt '<deploy timestamp>' -printf '%f\n'`
  → expect every filename's trailing `-HH` to be an **even** hour, and expect at least
  one such file.
- **P0-6 — disk headroom (PRE-RAMP GATE).**
  `curl -sG 'http://localhost:9090/api/v1/query' --data-urlencode 'query=node_filesystem_size_bytes{mountpoint="/",fstype!~"tmpfs|overlay|squashfs"}'`
  and the same for `node_filesystem_avail_bytes` → expect a **non-empty** result for
  each, and cross-check with `df -h /`. Record both numbers in the release report.
  An empty result means the host disk rules are blind and Phase 0's retention numbers
  are unvalidated.
- **P0-7 — Loki accepted the retention split.**
  `curl -s http://localhost:3100/ready` → expect `ready`.
  `curl -s http://localhost:3100/config | grep -A 12 retention_stream` → expect the
  two selectors with `24h` and `720h`.
- **P0-8 — the retention split actually bites (T+25 h, deferred).**
  `curl -sG 'http://localhost:3100/loki/api/v1/query_range' --data-urlencode 'query={job="bot-manager",level="DEBUG"}' --data-urlencode "start=$(date -d '30 hours ago' +%s)000000000" --data-urlencode "end=$(date -d '26 hours ago' +%s)000000000" --data-urlencode 'limit=1'`
  → expect `"result":[]`. The same query with `level=~"WARN|ERROR"` over the same
  window → expect a **non-empty** result. Allow for `retention_delete_delay: 2h`.

### Phase 1

- **P1-1 — the env-driven level took (AD-7 gate).**
  `curl -s http://localhost:8080/actuator/loggers/com.vingame.bot`
  → on prod-like config expect `{"configuredLevel":"INFO","effectiveLevel":"INFO"}`;
  on staging with `BOT_LOG_LEVEL=DEBUG` expect `DEBUG`/`DEBUG`. If prod shows `DEBUG`
  here, the `LOGGING_LEVEL_*` route did **not** work — fall back to the
  `${env:BOT_LOG_LEVEL:-info}` form before proceeding.
- **P1-2 — appenders survived the level override.**
  `curl -s http://localhost:8080/actuator/loggers/com.vingame.bot` returns 200 **and**
  `grep -c '"level":"INFO"' logs/console.log` grows over 60 s → proves the file
  appender is still attached (i.e. `setLogLevel` did not replace the LoggerConfig).
- **P1-3 — INFO line rate at the new floor.** With the app at INFO and the fleet
  running:
  `A=$(grep -c '"level":"INFO"' logs/console.log); sleep 300; B=$(grep -c '"level":"INFO"' logs/console.log); echo "delta=$((B-A)) over 300s"`
  → expect `delta < 100` for a 7-group staging fleet (tier-1 is idle-quiet, tier-2
  contributes ~1 line per environment per 5 min plus unclean groups).
- **P1-4 — the per-bot INFO classes are gone.**
  `grep '"level":"INFO"' logs/console.log | grep -cE 'initialized: game=|triggering deposit|restart requested'`
  measured over a group start → expect `0`.
- **P1-5 — the aggregated replacements are present.** Start a group, then:
  `grep -E 'bots initialized' logs/console.log | tail -1` → expect exactly **one**
  line for that group naming the full bot count. On an auto-deposit group:
  `grep -E 'bots auto-deposited' logs/console.log | tail -1` → expect one line with a
  count and a total.
- **P1-6 — tier-2 rollup is emitting.**
  `grep -E '^\{.*"message":"env ' logs/console.log | tail -3` (or
  `grep 'env .* groups=' logs/console.log | tail -3`) → expect at least one line per
  running environment within the last 6 minutes.
- **P1-7 — tier-2 is quiet about healthy groups.** With all groups healthy:
  `grep -cE 'group .* playing=' logs/console.log` over a 10-minute window → expect
  `0`. Stop one group's bots (or use a known-unhealthy group) and expect the count to
  become `> 0`.
- **P1-8 — session summaries demoted.**
  `grep '"level":"INFO"' logs/console.log | grep -c 'session' ` over 5 minutes on an
  active betting group → expect `0`; the same grep against `"level":"DEBUG"` on
  staging → expect `> 0`.

### Phase 2

- **P2-1 — enable a scope.** With a known `<GID>`:
  `curl -s -X POST 'http://localhost:8080/api/v1/logging/debug/<GID>?minutes=5' -w '\n%{http_code}\n'`
  → expect `200` and a body containing `expiresAt`.
  `curl -s http://localhost:8080/api/v1/logging/debug` → expect `<GID>` listed.
- **P2-2 — DEBUG flows for that group only.** After 60 s, with the app at INFO:
  `grep '"level":"DEBUG"' logs/console.log | grep -c '"botGroupId":"<GID>"'` → expect
  `> 0`.
  `grep '"level":"DEBUG"' logs/console.log | grep -vc '"botGroupId":"<GID>"'`
  measured over the same window (use `tail -n <N>` to bound it) → expect `0`.
- **P2-3 — the TTL expires.** Wait past `minutes=5` + 60 s, then:
  `tail -2000 logs/console.log | grep '"level":"DEBUG"' | grep -c '"botGroupId":"<GID>"'`
  → expect `0`. `curl -s http://localhost:8080/api/v1/logging/debug` → expect `<GID>`
  absent.
- **P2-4 — the spin-cost spam is bounded.** On a group with at least one bot below
  spin cost, at DEBUG, over 5 minutes:
  `grep -c 'below spin cost' logs/console.log` delta → expect `≤ 2 × (number of
  affected bots)` (one entry + one exit transition each), **not** 100 per bot
  (5 min ÷ 3 s).
- **P2-5 — auto-escalation fires.** After any natural watchdog expiry (or force one by
  blackholing a game host):
  `grep -E 'auto-enabled DEBUG|scoped debug escalat' logs/console.log | tail -3`
  → expect one INFO line naming the trigger, the `botGroupId` and the expiry; and
  `curl -s http://localhost:8080/api/v1/logging/debug` → expect that group present.
- **P2-6 — no escalation storm.** For a group that expired more than one watchdog in
  15 minutes: the escalation line count for that group over 15 min → expect `≤ 1`.

### Phase 3

- **P3-1 — the shim is up and healthy.**
  `docker compose ps evidence-shim` → expect `Up (healthy)`.
  `docker compose exec evidence-shim python -c "import urllib.request,json;print(json.dumps(json.loads(urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=3).read()),indent=1))"`
  → expect HTTP 200 and a JSON body reporting the evidence dir, its size, and
  `pending: {}`.
- **P3-2 — the evidence dir exists and is writable by the shim.**
  `ls -ld logs/evidence` → expect a directory owned by the same uid:gid as
  `logs/console.log` (`stat -c '%u:%g' logs/console.log logs/evidence`).
- **P3-3 — a synthetic alert promotes files.**
  ```
  docker compose exec evidence-shim python - <<'PY'
  import json,urllib.request
  b=json.dumps({"status":"firing","groupLabels":{"alertname":"EnvironmentGroupDead","product":"116","environmentId":"test-env"},"alerts":[{"status":"firing"}]}).encode()
  r=urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8080/alertmanager",data=b,headers={"Content-Type":"application/json"}),timeout=5)
  print(r.status)
  PY
  ```
  → expect `200`. Then `ls -la logs/evidence/` → expect **≥ 2** files (the newest
  closed `console-*.log` plus a `console-live-*.log`).
- **P3-4 — they are HARDLINKS, not copies (AD-14).**
  `stat -c '%h %i %n' logs/console.log logs/evidence/console-live-*.log`
  → expect the live file's **link count ≥ 2** and the **same inode number** on both
  lines. A differing inode means someone wrote `cp` and the disk-doubling failure mode
  is live.
- **P3-5 — promoted files are not re-ingested by promtail.**
  `curl -sG 'http://localhost:3100/loki/api/v1/query_range' --data-urlencode 'query={job="bot-manager",filename=~"/logs/evidence/.*"}' --data-urlencode 'limit=1'`
  → expect `"result":[]`.
- **P3-6 — promotion is idempotent and coalesced.** Repeat the P3-3 POST three times
  in quick succession, then `ls logs/evidence | wc -l` → expect the **same** count as
  after P3-3, and `…/health` → expect exactly **one** pending entry for that incident
  key (not three).
- **P3-7 — the deferred pass runs.** Note the file count after P3-3; at T+6 min,
  `ls logs/evidence | wc -l` → expect a count `≥` the P3-3 count with **no duplicate
  names**, and `…/health` showing the +5 min pass recorded as completed.
- **P3-8 — promoted files survive log4j2's `Delete`.** After the next rollover
  boundary (or force one), confirm the promoted `console-*.log` names are still
  present in `logs/evidence/` even if absent from `logs/`:
  `ls logs/evidence/ | head; ls logs/ | head` → expect at least one name present in
  `evidence/` and absent from `logs/`.
- **P3-9 — unclean start retro-promotes (AD-21).**
  `docker compose kill evidence-shim && docker compose up -d evidence-shim`, then
  `docker compose logs --since 2m evidence-shim | grep -i 'clean-shutdown\|boot'`
  → expect a line reporting no clean-shutdown marker and a boot-tagged promotion, and
  `ls logs/evidence | wc -l` → expect a count `>` the pre-kill count (or equal, if the
  same two files were already pinned — in which case the log line alone is the pass).
- **P3-10 — the sweep bounds the directory.** Set `EVIDENCE_MAX_AGE_DAYS=0` on the
  shim temporarily, restart it, wait for one sweep, then `ls logs/evidence | wc -l`
  → expect `0` (excluding dotfiles). Restore the real value and redeploy.
- **P3-11 — Alertmanager routing is intact (AD-14).**
  `docker compose exec alertmanager amtool --alertmanager.url=http://localhost:9093 config routes test alertname=EnvironmentGroupDead`
  → expect **both** `evidence` and `viptalk` in the output. Repeat with
  `alertname=BotManagerDown` → expect `evidence`, `viptalk-static-down` and `viptalk`.
  Repeat with `alertname=EnvironmentSocketDown` → expect **`viptalk` only**.
- **P3-12 — the guard test still passes in the build.**
  `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q -pl bot-app test -Dtest=AlertmanagerRoutingTest`
  → expect `BUILD SUCCESS` (run on the build machine, not the box).
