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

> **Phases 0–3 shipped to Bot-1 staging on 2026-08-19 and the release measured the
> goal UNMET**: 98.7% of INFO volume turned out to be `com.vingame.websocketparser.*`
> logging under the *root* logger, outside everything this feature governs
> (`docs/reviews/LOG_VOLUME_TIERING/release.md` Finding 1). **Phase 4, appended at the
> bottom of this file, revises the appender topology Phase 0 established.** It
> supersedes parts of AD-5 and AD-6 and rewrites ten verification steps. Read
> `## Phase 4 — Two tracks: aggregate to Loki, detail to disk` before acting on
> anything in Phases 0–3.

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
Logging must never add latency to a bot thread **for the discardable tiers**; INFO+
blocks rather than being lost. If the choice is "block a bot" or "lose a DEBUG line",
lose the line. WARN/ERROR are never discarded and remain coupled to alerting.

**AD-5 — The ConsoleAppender is capped at INFO+ via a `ThresholdFilter`.** Every line
is currently serialized and written twice. `docker logs` remains useful (INFO+ and
all WARN/ERROR still appear) while DEBUG/TRACE go to the JSON file only — halving
the cost of exactly the tier we are trying to make survivable, and removing DEBUG
pressure on the 50m×5 json-file cap. *Flagged as an addition to the agreed design.*

> **Extended by Phase 4 / AD-27.** The console keeps its INFO+ `ThresholdFilter`, but
> it no longer receives `com.vingame.websocketparser.*` at all — that logger is pulled
> out of the root logger with `additivity = false`. See AD-23 and AD-27.

**AD-6 — Loki per-stream retention is the primary control; the promtail DEBUG `drop`
stage ships present but disabled.** These two agreed items are in tension: a
permanent `drop` on DEBUG makes **Phase 2's scoped per-group DEBUG invisible in
Grafana**, which is the whole point of Phase 2. Resolution: `retention_stream` gives
`{level=~"DEBUG|TRACE"}` **24 h** and `{level=~"WARN|ERROR"}` **30 d** with
`retention_period` (the default for everything else, i.e. INFO) raised to **30 d**;
the `drop` stage is committed, documented, and commented out, to be enabled only on
an instance that is provably drowning. *Flagged: this is the one place the agreed
design is not implemented as written, and the reason is Phase 2.*

> **REVERSED by Phase 4 / AD-24.** Phase 4 caps the Loki-shipped file at INFO+ with a
> `ThresholdFilter`, so **no DEBUG or TRACE line reaches Loki by any path**. The reason
> AD-6 kept the `drop` stage disabled — that it would blind Phase 2's scoped per-group
> DEBUG in Grafana — no longer applies, because Phase 4 blinds it in Grafana anyway and
> moves the drill-in to a file on the box. The `drop` stage stays committed-and-disabled
> (it is now redundant, not merely optional) and the `{level=~"DEBUG|TRACE"}`
> `retention_stream` selector stays as an inert **tripwire**. See AD-24 and AD-29.

**The `720h` is provisional until P0-9 measures it.** Raising `retention_period`
168 h → 720 h is a **4.3× increase in the retained horizon**, landing in the same
change as an *unmeasured* volume reduction — the reduction is a projection, and the
only ingest figure anywhere in this plan is P1-3's 300-second INFO line count on a
7-group staging fleet, which does not translate to prod Loki bytes. Two properties
make that combination worse than it looks: Loki's store is the named volume
`loki-data` (`docker-compose.yml:98`), i.e. the **same root filesystem** whose size
P0-6 admits is unknown and which ENOSPC'd on 2026-06-30 taking Mongo with it; and a
720 h horizon does not reach steady state for **30 days**, so if the projection is
wrong the disk fills a month after the release report closed, with nothing in the
plan able to fail first. Under the old 168 h it announced itself in a week. So the
number ships as written — it is a policy choice, not a fitted one — but it is
explicitly **gated on P0-9**, and the rollback is one line in the bind-mounted
`loki/loki-config.yaml` (`720h` → `168h`, then `docker compose restart loki`): no
rebuild, no redeploy, no application change. Do not treat the 720 h as settled
until P0-9 has run at T+7 d.

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
`bot_winnings_total`), and Phase 1's tier-2 rollup carries downsampled
rounds-and-stake figures — aggregated onto each environment's line, and additionally
per group on the detail line an unclean group already gets. It must **not** emit a
line per group per cycle: at 300 groups that is ~1 line/s of INFO whose rate is a
function of fleet size, which step 5 forbids and which would re-create at INFO a
downsampled copy of what this AD demotes. This **reverses** the current `CLAUDE.md` guideline,
which Phase 1 rewrites; it is one level constant away from being reverted.

**AD-9 — The scoped-DEBUG filter is installed programmatically, not as a
`@Plugin`.** `pom.xml:110-126` pins `annotationProcessorPaths`, so log4j2's plugin
annotation processor never runs and a `@Plugin` filter would have no
`Log4j2Plugins.dat` entry inside the fat jar. Instead a Spring `@Component` obtains
`(LoggerContext) LogManager.getContext(false)`, calls
`ctx.getConfiguration().addFilter(filter)` and `ctx.updateLoggers()`
in `@PostConstruct`. No plugin registry, no `packages =` attribute, no fat-jar
scanning. Chosen over the built-in `DynamicThresholdFilter` because that filter's
value→level map comes from static configuration and would require a context
reconfiguration per runtime change.

**AD-10 — The filter returns `ACCEPT` for enabled groups and `NEUTRAL` for everything
else — never `DENY`.** The **Configuration's** filter runs before the level check —
`Logger.PrivateConfig.filter` reads `config.getFilter()` where `config` is the
`Configuration`, not the LoggerConfig (Logger.java:542, :586 in 2.24.1). A
LoggerConfig's own filter runs only in `LoggerConfig.log(LogEvent)`, after the level
gate, where only a `DENY` can still matter — attached there this filter is inert. The
filter therefore hangs off the `Configuration`, and the blast radius the single
LoggerConfig used to provide is restored by a `com.vingame.bot` logger-name prefix
gate in the filter itself. An `ACCEPT` ahead of the level check is precisely what lets
a DEBUG event surface through an INFO-level logger. `NEUTRAL` leaves normal level
rules intact for every other group. The filter short-circuits on an empty registry so
the steady-state cost is one volatile read.

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
   appender.async.blocking = true
   ```
   **Gotcha:** in the properties format a nested AppenderRef *inside an appender*
   requires the explicit `.type = AppenderRef` line (unlike a logger's appenderRef).
   Omitting it produces a silently mis-built appender. `blocking = true` is
   **not optional**. `AsyncAppender.append()` consults `asyncQueueFullPolicy` only on
   the blocking branch; with `blocking = false` a full queue drops the event at every
   level, ERROR included, and AD-4 has no effect. Add
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
   `ThreadContext.get` directly — no MDC map copies on a hot path. **It must also gate
   on the logger name** (`com.vingame.bot` prefix): the filter hangs off the
   `Configuration`, which is consulted for every logger in the JVM, so without the
   prefix a scoped group's thread would surface the Mongo driver's and Netty's DEBUG
   too.
3. **Installer.** `@Component` in `bot-app` with `@PostConstruct`:
   `LoggerContext ctx = (LoggerContext) LogManager.getContext(false);`
   `ctx.getConfiguration().addFilter(filter);`
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
   Each escalation emits **one** INFO line naming the trigger and the expiry. Bound
   re-arming with a **quiet period measured from the moment the scope expires**, not from
   the escalation that opened it: a group may not re-arm until
   `lastEscalation + ttl + cooldown` — 15 min TTL + 45 min cooldown at the shipped
   defaults. *"One escalation per group per 15 min"* — i.e. one per TTL — **bounds
   nothing**: when the cooldown equals the TTL the two lapse at the same instant, so a
   group parked mid-band (`dead/total` inside the escalation band but still under
   `bot.group.dead.threshold`, so never declared DEAD) re-escalates on the very next 30 s
   health tick and holds DEBUG open ~96% of the time, unattended, for as long as it stays
   half-broken. The bound worth stating is a **duty cycle**: an unattended group cannot
   hold scoped DEBUG for more than `ttl / (ttl + cooldown)` = **25%** of any window,
   however hard it flaps. See the Amendment at the bottom of this file.
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
- **The Phase 2 filter must be attached to the `Configuration`, not to a
  LoggerConfig.** `Logger.PrivateConfig.filter` reads `config.getFilter()` where
  `config` is the `Configuration` (Logger.java:542, :586 in 2.24.1), and that is the
  only filter consulted *before* the level check. A LoggerConfig's own filter runs
  later, in `LoggerConfig.log(LogEvent)`, after the level gate has already discarded
  the DEBUG event — attached there the filter installs cleanly, reports healthy and
  promotes nothing. Because a `Configuration` filter is consulted for every logger in
  the JVM, the blast radius that naming one LoggerConfig used to provide is restored
  by a `com.vingame.bot` logger-name prefix gate inside the filter. Promoted events
  still route through the `com.vingame.bot` LoggerConfig, which has
  `additivity = false` and its own appenders (`log4j2.properties:9-11`), so the
  appender set is the one this plan assumes.
- **`/actuator/loggers` still works and still sets a *global* level.** It is not
  removed; it is the escape hatch. Operators must understand that a global
  `{"configuredLevel":"DEBUG"}` on a 10-env prod instance is now a 5 GB/hour action.
  Say so in `CLAUDE.md`.
- **Phase 2 escalation must be idempotent and rate-limited, and the cooldown must run
  from scope *expiry*.** A flapping group firing watchdog expiries every 3 minutes must
  not hold DEBUG open indefinitely. "One escalation per group per 15 min" does not
  achieve that — measured from the escalation, a cooldown equal to the TTL lapses at the
  same instant the scope does and the group re-arms on the next 30 s health tick. The
  gate is `lastEscalation + ttl + cooldown` (15 + 45 by default), which makes the bound a
  **duty cycle** of `ttl / (ttl + cooldown)` = 25%. Assert the duty cycle, not the fact
  that a re-arm eventually succeeds — "eventually" is true of the broken version too.
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
  before Phase 1 ships. P0-6 measures *size*, not *growth* — on its own it cannot tell
  you whether 720 h fits, because the volume reduction it is being weighed against is
  a projection. **P0-9 supplies the growth rate and is the actual gate on the 720 h**
  (see AD-6). Both are pre-ramp and both are still unmeasured; neither can be closed
  from the diff.
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

> **READ FIRST — Phase 4 rewrites ten of these steps.** Once Phase 4 has shipped, the
> steps below that grep `logs/console.log` for DEBUG are grepping the wrong file: DEBUG
> and TRACE move to `logs/detail/detail.log`, which is a **PatternLayout** file, not
> JSON. The amended text for each lives in
> **`## Phase 4 → Verification → Corrections to the Phase 0–3 steps`** at the bottom of
> this file. Affected: **U-3, P0-2, P0-4, P0-5, P0-8, P1-3, P1-8, P2-2, P2-3, P2-4,
> P3-3, P3-10**. Everything else stands unchanged.

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
- **P0-9 — Loki growth is measured before the 720 h horizon can bind (T+24 h and
  T+7 d, deferred — PRE-RAMP GATE, pairs with P0-6).** Nothing else in this plan can
  fail on an over-optimistic volume projection before day 30; this is that step.
  ```
  docker system df -v | grep loki-data          # or: du -sb /var/lib/docker/volumes/*loki-data*/_data
  ```
  Record the figure at T+24 h and again at T+7 d, and take the **T+7 d minus T+24 h**
  delta as the daily rate (T+0 to T+24 h is skewed by pre-change data still inside the
  old 168 h window). Then:
  - **Project 30 days**: `rate/day × 30`, plus the current size. Compare against
    `node_filesystem_avail_bytes` from P0-6.
  - **Gate**: if the 30-day projection exceeds **50%** of available disk, drop
    `retention_period` back to `168h` (leave the WARN/ERROR `retention_stream` at
    `720h` — that tier is cheap and is what an incident is reconstructed from) before
    any ramp. This is a bind-mount edit plus `docker compose restart loki`.
  - Also confirm the INFO tier is the one dominating: DEBUG/TRACE expire at 24 h, so by
    T+7 d the store should be overwhelmingly INFO+WARN+ERROR. If DEBUG dominates,
    scoped DEBUG (Phase 2) or `BOT_LOG_LEVEL` is wrong on that instance, and the
    retention numbers are not the problem to fix first.
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
  `grep '"level":"INFO"' logs/console.log | grep -cE 'initialized: game=|triggering deposit|restart requested|Successfully created bot|Bot starting in virtual thread|assigned strategy|assigned slot strategy|Setting shared EventLoopGroup'`
  measured over a group start → expect `0`.
  The narrow form of this step (the first three patterns only) returned `0` while four
  more per-bot INFO sites were emitting freely, and would have passed on a broken
  state — `ClientFactory`'s `Setting shared EventLoopGroup` in particular fires on
  every restart and every re-auth, exactly cancelling the `restart requested`
  demotion this step checks for. **Grep for the message set, not for the five sites
  the Findings section happened to enumerate.** `PerBotInfoLogGuardTest` enforces the
  same rule in the build, so P1-4 is now a confirmation rather than the only net.
- **P1-5 — the aggregated replacements are present.** Start a group, then:
  `grep -E 'bots initialized' logs/console.log | tail -1` → expect exactly **one**
  line for that group naming the full bot count. On an auto-deposit group:
  `grep -E 'bots auto-deposited' logs/console.log | tail -1` → expect one line with a
  count and a total. And for the per-bot strategy lines folded in the same pass:
  `grep -E 'strategy mix' logs/console.log | tail -1` → expect exactly **one** line per
  group start, carrying the whole `{RANDOM=n, ...}` histogram.
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
  15 minutes: the escalation line count for that group over 15 min → expect `≤ 1`. That
  still holds, but **on its own it is not discriminating** — it also passes on the
  defective one-per-TTL cooldown this plan used to describe, which re-armed every ~15 min
  and would still show `≤ 1` in any 15-minute window. Add the check that actually bounds
  the duty cycle: over **60 minutes** on a persistently sick group (one parked mid-band,
  or one flapping watchdogs throughout), the escalation line count for that group →
  expect `≤ 1`, the re-arm interval being
  `escalation.minutes + escalation.cooldown-minutes` = 15 + 45. Cross-check by polling
  `curl -s http://localhost:8080/api/v1/logging/debug` every minute across that hour →
  expect the group **absent** for at least 45 of the 60 samples (the 25% duty cycle),
  not present throughout.

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

---

## Amendment — 2026-08-19 (Compliance Architect)

Two corrections, both to text that described a defect as the design. Neither changes
what the branch shipped; both stop the plan asserting something false about it. This
is in addition to the three amendments landed at `b98ab61` (Drift 1 `blocking`,
Drift 2 the filter attach point, Drift 3 AD-8's "line per group"), which were
authorised by the previous compliance pass and are confirmed landed verbatim.

**A1 — Phase 2 step 5's escalation rate limit (also the matching Implementation Note,
and verification P2-6).**

*What the plan said:* "Rate-limit escalation to one per group per 15 min so a flapping
group cannot re-arm forever."

*Why it is wrong:* the stated rule does not produce the stated property. One
escalation per 15 min **is** one per TTL, and the TTL is what the escalation grants.
With `escalation.minutes` and `escalation.cooldown-minutes` both 15 — which is what
"one per 15 min" specifies, and what shipped first — the cooldown lapses at the same
instant the scope does. A group parked mid-band (`dead/total` inside the escalation
band but under `bot.group.dead.threshold`, so never declared DEAD and never
self-clearing) therefore re-escalates on the very next 30 s health tick, indefinitely
and unattended: a ~96% duty cycle. Combined with `max-scopes=50` that is scoped DEBUG
quietly reassembling the fleet-wide DEBUG this feature exists to prevent, on the box
that ENOSPC'd on 2026-06-30. The rule as written is not merely weak, it is
self-defeating: the tighter you set the cooldown to match the TTL, the closer the duty
cycle gets to 100%.

*What it now says:* the quiet period is measured from **scope expiry**, so the re-arm
gate is `lastEscalation + ttl + cooldown` and the property to state is a **duty
cycle**, `ttl / (ttl + cooldown)` = 25% at the shipped 15 + 45 defaults. P2-6 is
extended because its `≤ 1 per 15 min` assertion, while still true, **also passed on
the defective version** and so proved nothing; a 60-minute window and a duty-cycle
poll are what discriminate. This matches what the branch implements
(`ScopedDebugEscalator.reArmIntervalMillis()`, `cooldown-minutes` default 45) — the
code was already correct and Dev correctly declined to amend the plan unilaterally.

**A2 — AD-6's `720h`: staged behind a new measurement gate (P0-9), not settled.**

*What was missing:* the plan raises `limits_config.retention_period` 168 h → 720 h in
the same change as a volume reduction that is **projected, not measured**, and no
verification step measures Loki ingest or growth. P0-6 records disk *size*; P0-8
proves only that the DEBUG/WARN split bites at T+25 h. Neither can fail on an
over-optimistic projection.

*Why that matters here specifically:* Loki's store is the named volume `loki-data`
(`docker-compose.yml:98`) on the same root filesystem P0-6 admits is of unknown size,
and which filled on 2026-06-30 and took Mongo down with it. A 4.3× longer horizon does
not reach steady state for **30 days**, so the failure would land a month after the
release report closed — whereas under 168 h it announced itself within a week. An
unmeasured retention increase whose failure mode is deferred past the point anyone is
still watching is exactly the shape of the incident this plan is a response to.

*What is added:* verification **P0-9** (T+24 h and T+7 d, pre-ramp, paired with P0-6):
measure the `loki-data` volume, take the T+7 d − T+24 h delta as the daily rate,
project 30 days against `node_filesystem_avail_bytes`, and if the projection exceeds
50% of available disk drop `retention_period` back to `168h` while leaving the
WARN/ERROR `retention_stream` at `720h`. AD-6 and the Open Item now say the 720 h is
provisional pending P0-9 and name the rollback, which is one line in the bind-mounted
`loki/loki-config.yaml` plus `docker compose restart loki` — no rebuild, no
application change. **The shipped config is unchanged**; this closes a hole in the
Verification section, it does not ask for different code.

---
---

# Phase 4 — Two tracks: aggregate to Loki, detail to disk

*Added 2026-08-20, after the Phase 0–3 release measured the goal unmet.*

## Goal (Phase 4)

Phases 0–3 made `com.vingame.bot`'s logging cheap and correct, and the release proved
it: **59 INFO lines in 300 s on a 155-bot fleet**. Over the same window
`com.vingame.websocketparser.*` emitted **4,402** — logging per bot, at INFO, under the
*root* logger, where none of this feature's level model, `logger.app.level`,
`LOGGING_LEVEL_COM_VINGAME_BOT` or tier folding can reach it. **98.7% of INFO volume is
the library.** Projected forward that is 6.5 GB/day at 2,000 bots, 65 GB/day at 20,000,
98 GB/day at 30,000 — the original problem, one logger prefix outside the fix.

Phase 4 splits the output into **two tracks on the same 2-hour rollover**. **Track 1
(main)** is `logs/console.log`: our own API at INFO+, JSON, capped at INFO+ by an
appender-level filter, and the **only** thing promtail ships to Loki. **Track 2
(detail)** is `logs/detail/detail.log`: the ws-parser library in full plus all our own
DEBUG/TRACE, plain text, **never shipped to Loki**, short local retention, retained
past that only when the Phase 3 evidence shim promotes it. Phase 4 is **config only —
no Java anywhere**; the one code artefact it touches is the Python evidence shim.

---

## Findings — Current State (Phase 4)

### Measured on Bot-1 staging, 2026-08-19, 155 bots

From `/Users/gleb/IdeaProjects/Bot/docs/reviews/LOG_VOLUME_TIERING/release.md:417-443`,
a 300-second window with `BOT_LOG_LEVEL=DEBUG`:

| Logger | lines / 300 s | lines/s | per bot/s |
|---|---|---|---|
| `com.vingame.websocketparser.VingameWebSocketClient` | 3,385 | 11.28 | 0.0728 |
| `com.vingame.websocketparser.auth.AuthClient` | 1,000 | 3.33 | 0.0215 |
| **ws-parser total, INFO** | **4,402** | **14.67** | **0.0947** |
| `com.vingame.bot.*` total, INFO | 59 | 0.20 | — |
| `com.vingame.bot.*` DEBUG (staging default) | 4,775 | 15.92 | — |

The eight ws-parser message classes are enumerated at `release.md:794-806`
(`AUTH [...]`, `handshake completed`, `Connected to server`, `Resolving authentication
token`, `Agency token: <n>-<id>`, `Closed connection`, `Authenticating user …`,
`Authenticated with token`). Every one is per-bot and per-connection, i.e. rate ∝ bot
count × reconnect rate — exactly the shape Phase 1 folded for our own code.

Two secondary properties of those lines (`release.md:814-818`):
- `User <bot>: Agency token: <n>-<id>` logs **agency-token material at INFO**.
- The `AUTH` lines carry raw **ANSI colour escapes** (`ESC[36m`) into the JSON
  `message` field, so terminal formatting is leaking into structured logs.

### The library's package root is a single prefix

`vingame:websocket-parser-core` (`/Users/gleb/IdeaProjects/Bot/pom.xml:47,54-55`, version
`3.0.5`) ships exactly one root package — verified by listing the jar:
`com/vingame/websocketparser` and twelve subpackages
(`auth`, `backpressure`, `encryption`, `exception`, `message/**`, `scenario/**`).
**`com.vingame.websocketparser` is a complete and exact prefix.** No other third-party
logger shares it.

### The current appender graph

`/Users/gleb/IdeaProjects/Bot/logging/log4j2.properties` (and its byte-identical in-jar
twin `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/log4j2.properties`):

- `:17-20` — `rootLogger.level = info`, writing to `ConsoleAppender` **and**
  `AsyncRolling`. This is the line that puts every ws-parser INFO event into
  `console.log` and therefore into Loki.
- `:38-42` — `logger.app` (`com.vingame.bot`), `additivity = false`, same two appenders.
- `:44-57` — console `PatternLayout` + `ThresholdFilter` at `info` (AD-5).
- `:59-65` — `RollingFileAppender` → `/app/logs/console.log`, `JsonTemplateLayout`.
  **No filter of any kind**, so it takes whatever its loggers send it, at every level.
- `:78-90` — `AsyncRolling` wrapping it, `bufferSize = 8192`, `blocking = true`.
- `:95-98` — 2 h rollover, `modulate = true` (AD-20).
- `:104-115` — `Delete`, `basePath = /app/logs`, `maxDepth = 1`, glob `console-*.log`,
  `age = 7d` OR `ifAccumulatedFileSize = 10GB`.

`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/log4j2.component.properties:20-21`
— `log4j2.asyncQueueFullPolicy = Discard`, `log4j2.discardThreshold = DEBUG`. **JVM-wide
via `PropertiesUtil`, not per-appender**, and in-jar only (no bind-mounted twin).

### Appender-level filters beat the scoped-DEBUG `ACCEPT` — already proved on the box

AD-10's `ScopedDebugFilter` is a **Configuration** filter returning `ACCEPT`, which
bypasses the *level* check (`Logger.PrivateConfig.filter`). It does **not** bypass an
appender's own filter: `AppenderControl.callAppender0` calls `appender.isFiltered(event)`
before `append()`. The release is the empirical proof — at `release.md:250-273`, with
`BOT_LOG_LEVEL=DEBUG` **and two scoped-DEBUG escalations live**, the console carried
**0** DEBUG lines while the JSON file carried 14,310. **The whole of Phase 4's track-1
containment rests on this, and it is measured, not theorised.**

### The escape route from promtail is already proved twice

- `/Users/gleb/IdeaProjects/Bot/promtail-config.yml:18` — `__path__: /logs/*.log`,
  **non-recursive**. `logs/evidence/` escapes on exactly this basis and the release
  confirmed it live (`release.md:688-701`, P3-5 returned `"result":[]` with a
  non-vacuous control).
- `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/java/com/vingame/bot/infrastructure/logging/EvidenceRetentionEscapeTest.java:66-98`
  fails the build on `/logs/**`, `/logs/*/`, or a `Delete` `maxDepth` above 1 —
  mutation-verified by QA (`qa.md:79-82`).

### Promtail's positions file has no volume

`/Users/gleb/IdeaProjects/Bot/promtail-config.yml:5-6` — `positions.filename:
/tmp/positions.yaml`. `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:101-110` mounts
only `./logs:/logs:ro` and the config file — **no volume covers `/tmp`**. Every
`docker compose down` discards the offsets, so promtail re-reads all ~12 retained
`console-*.log` files from byte 0. Measured: `loki-data` **1.02 GB → 3.24 GB in 17
minutes** after the deploy (`release.md:825-852`), with Loki logging both
`timestamp too old` rejections and accepted duplicates.

### The evidence shim's file selection is hardcoded to one track

`/Users/gleb/IdeaProjects/Bot/evidence-shim/shim.py:109-110` — module constants
`LIVE_NAME = "console.log"` and `LOG_GLOB = "console*.log"`. `candidates()` (`:344-368`)
lists `config.logs_dir` **non-recursively**; `selection()` (`:370-377`) takes the newest
`newest_count` plus the live file; `promote()` (`:381-416`) hardlinks each into
`evidence/`; `sweep()` (`:737-789`) applies one age (`EVIDENCE_MAX_AGE_DAYS`, measured
from `.promoted.json`) then evicts oldest-promotion-first under one byte cap
(`EVIDENCE_MAX_BYTES`, default 5 GB). **A track-2 file in a subdirectory is invisible to
all of it** — the shim would keep working and silently promote only aggregates.

### The three log4j2 copies and their guards

| File | Guard |
|---|---|
| `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/log4j2.properties` | `Log4j2TwinConfigTest` — byte-identical to the mounted twin below the `# ====` header fence |
| `/Users/gleb/IdeaProjects/Bot/logging/log4j2.properties` | same test |
| `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/resources/log4j2-test.properties` | `Log4j2TestConfigShapeTest` — 24 enumerated `SHAPE_KEYS` must match the shipped file exactly; only `appender.rolling.fileName`/`filePattern` may differ |

The test copy exists because **`AsyncAppender.start()` throws** when its referenced
appender is unavailable, and `/app/logs` is not creatable off-box — see its header
comment. Any new async appender must therefore be added to the test copy too, pointed at
`target/test-logs/`, or every Spring-context test in `bot-app` dies with
`ExceptionInInitializerError`.

Other tests that pin the current graph and will need extending:
`AsyncQueuePolicyTest` (`:69-94` asserts *both* loggers route through `AsyncRolling`;
`:95-115` couples `blocking = true` to the discard policy; `:116-135` the console
`ThresholdFilter`), `LoggingComposeWiringTest`, `LogRetentionPipelineTest`.

---

## Per-aspect readiness / mapping (Phase 4)

| Aspect | State | Notes |
|---|---|---|
| Pull ws-parser out of root (`additivity = false`) | **ready** | One 4-line logger block in each of 3 properties files. AD-23. |
| Second rolling appender in a subdirectory | **ready** | log4j2's `FileManager` mkdirs the parent; `logs/evidence/` is the precedent for "the app creates it, not `deploy.sh`". AD-25. |
| Track-1 `ThresholdFilter` at INFO+ | **ready** | Same construct as AD-5's console filter, on `AsyncRolling` instead. Mechanism already proved live. AD-24. |
| Track-2 `PatternLayout` | **ready** | Nothing parses track 2; kills the ANSI-in-JSON problem for free. AD-25. |
| Track-2 non-blocking async | **ready** | One property. Deliberately opposite to track 1's `blocking = true`. AD-25. |
| Track-2 retention sizing | **partial — measured on staging only** | Shipped numbers are derived from a 155-bot measurement. P4-6 is the pre-ramp gate. AD-26. |
| Scoped DEBUG leaves Grafana | **decided — accepted loss** | AD-24. The *pointer* (the escalation INFO line) stays in Loki; the payload is on the box. |
| Loki retention re-examined | **ready** | No value changes; comments + a re-baselined P0-9. AD-29. |
| promtail positions volume | **ready** | One named volume + one path change. AD-31. |
| Evidence shim promotes both tracks | **partial — needs Python** | `LOG_GLOB`/`LIVE_NAME` become a two-entry track list; the sweep gains a second age and detail-first eviction. AD-28. |
| Agency-token material | **decided — bounded, not fixed** | Confined to track 2; real fix is in ws-parser. AD-30, Open Items. |
| ws-parser level itself | **decided — stays INFO** | Lowering it to WARN is the escape hatch, not the design. AD-32. |
| P0-5's acceptance criterion | **ready** | Plan text only; corrected below. |
| Prod disk on `Prod-Bot` | **BLOCKED — still unknown** | P0-6 measured **Bot-1** (100 GiB, 66.96 GiB free). `Prod-Bot` has never been measured. |

---

## Architecture Decisions (Phase 4)

**AD-22 — Two tracks, one rollover interval, and track 1 keeps the name
`console.log`.** Both appenders roll every **2 hours with `modulate = true`** — AD-20 is
reaffirmed, not amended, and a *single* boundary for both tracks is what lets the
evidence shim's one tail pass (AD-16) serve both. Track 1 keeps the file name
`/app/logs/console.log`, the appender names `RollingFileAppender` / `AsyncRolling` and
the `JsonTemplateLayout`. That is deliberate cheapness: promtail's glob, the shim's
`LIVE_NAME`, `EvidenceRetentionEscapeTest`, `AsyncQueuePolicyTest`, every Grafana query
and every verification grep in Phases 0–3 keep working. Track 2 is the new thing:
`/app/logs/detail/detail.log`, pattern `/app/logs/detail/detail-%d{yyyy-MM-dd-HH}.log`,
appenders `DetailFileAppender` / `AsyncDetail`, property keys `appender.detail.*` /
`appender.asyncdetail.*`.

**AD-23 — `com.vingame.websocketparser` gets its own LoggerConfig with
`additivity = false`, routed to track 2 only.** This is the entire mechanical fix for
Finding 1: the library stops inheriting the root logger's appenders, so its 14.67
lines/s never reach the console, never reach `console.log`, and therefore never reach
Loki. `additivity = false` is what makes it exclusive rather than additive — without it
the events would go to track 2 *and* still propagate to root. The logger name must stay
spelled exactly `com.vingame.websocketparser` for the same reason `com.vingame.bot`
must (AD-7): Spring Boot's `setLogLevel` mutates a LoggerConfig that exists **by exact
name** in place, preserving `additivity` and the appenderRef; against a name that does
not exist it creates a fresh LoggerConfig with **no appenders**, which would silently
delete the detail track.

**AD-24 — Track 1 is capped at INFO+ by a `ThresholdFilter` on `AsyncRolling`, so no
DEBUG or TRACE line can reach Loki by any path. This deliberately reverses AD-6's
reason for shipping the promtail `drop` stage disabled.** AD-6 kept that stage off
because dropping DEBUG at ingest would blind Phase 2's scoped per-group DEBUG in
Grafana. Under the two-track split our DEBUG lands in track 2, which Loki never sees, so
**scoped DEBUG is blind in Grafana regardless** — the `drop` stage is now redundant
rather than harmful. The user has accepted this. What replaces it:

*How an operator reads a scoped-DEBUG session now.* The arming and auto-escalation lines
(`ScopedDebugEscalator` — `scoped debug escalated for group <id> — trigger: …, expires
…`) are `com.vingame.bot` **INFO**, so they stay in track 1, in Loki, in Grafana. Grafana
tells you *that* a drill-in exists, for which group, and when it opened and closed. The
payload is then read on the box:

```bash
grep "\[<GROUP_ID>/" logs/detail/detail.log | tail -400          # live file
grep -h "\[<GROUP_ID>/" logs/detail/detail-*.log | tail -400     # rolled siblings
grep -h "\[<GROUP_ID>/" logs/evidence/detail-*.log               # if an alert pinned it
```
(the MDC renders as `[botGroupId/botId/gameType]` under track 2's `PatternLayout`, not as
JSON fields). If the group is implicated in an alert, the evidence shim has already
pinned those files (AD-28) and they survive the local sweep for 3 days. **The filter is
on `AsyncRolling`, not on the wrapped `RollingFileAppender`**, so a DEBUG event is
rejected *before* it consumes a slot in the 8,192-entry queue.

*The narrow exception, costed and not taken.* `com.vingame.bot` DEBUG **could** be
shipped to Loki at 24 h while ws-parser stays file-only: change
`appender.async.filter.threshold.level` from `info` to `debug` and set
`BOT_LOG_LEVEL=INFO` on staging (otherwise staging's global DEBUG floods it). Cost, at
the shipped Phase 2 bounds: a scoped group at ~4.4 DEBUG lines/s × up to
`max-scopes = 50` × the 25% duty cycle ≈ 1.9 GB/day worst case, ~100 MB/day at the 1–3
concurrent scopes actually observed, retained 24 h — genuinely cheap, and it would put
the drill-in back in Grafana. **It is not the default** because it re-couples staging's
Loki bill to `BOT_LOG_LEVEL` and because the user chose the clean two-track split. It is
one line in the bind-mounted file plus `docker compose restart bot-manager`; the
`retention_stream` selector that would govern it is already present (AD-29), so nothing
else has to change to take it later.

**AD-25 — Track 2 is a subdirectory, `PatternLayout`, `immediateFlush = false`, and
`blocking = false`.** Four decisions, each load-bearing:

1. **Subdirectory `/app/logs/detail/`.** This is the proven escape from promtail's
   non-recursive `__path__: /logs/*.log` (used twice already, verified live at
   `release.md:688-701`). It also puts track 2 outside track 1's `Delete`
   (`basePath /app/logs`, `maxDepth = 1`, glob `console-*.log`) — three independent
   reasons it cannot be swept by the wrong sweeper. Track 2 gets its **own** `Delete`
   anchored at `basePath = /app/logs/detail`, `maxDepth = 1`, glob `detail-*.log`, which
   for the same reasons cannot reach `logs/` or `logs/evidence/`.
2. **`PatternLayout`, not `JsonTemplateLayout`.** Nothing parses track 2 — not promtail,
   not Loki, not Grafana. JSON costs ~400 B/line against ~250 B for a pattern line, i.e.
   the layout choice alone removes ~38% of track 2's bytes. It also **sidesteps the
   ANSI-escape defect entirely**: `ESC[36m` inside a plain-text line is harmless (it
   renders as colour under `less -R`), whereas inside a JSON `message` field it is
   corruption. The pattern keeps the full logger name (`%c`) so a misroute is greppable,
   and the same MDC triple the console uses.
3. **`immediateFlush = false`.** The standard async recipe: log4j2 flushes at
   `endOfBatch`, i.e. whenever the async queue drains, so at steady state the file is
   current within milliseconds and the per-event flush syscall is gone. The exposure is
   a partial trailing buffer lost on `SIGKILL` — acceptable for a best-effort forensic
   tier, unacceptable for track 1, which keeps the default `true`.
4. **`blocking = false` on `AsyncDetail`, against `blocking = true` on `AsyncRolling`.**
   This is the one place Phase 4 deliberately inverts a Phase 0 decision, and the reason
   is that AD-4's discard policy cannot express it. `log4j2.discardThreshold = DEBUG` is
   a **JVM-wide** property, so it cannot discard track 2's INFO without also discarding
   track 1's. But track 2 now carries the highest-volume tier in the system (1,893
   lines/s at 20k bots) at **INFO**, which under `blocking = true` would put a bot thread
   on a queue wait — precisely the latency AD-4 exists to prevent, reintroduced through
   the back door. `blocking = false` makes a full detail queue drop the event at every
   level, which for a best-effort forensic tier is exactly right; track 1 keeps
   `blocking = true` so its INFO+ signal is never lost. Buffer 16,384 (≈8.6 s of backlog
   at 20k bots) to absorb GC pauses without dropping. `includeLocation` stays at its
   default `false` — location capture on 1,900 events/s would be ruinous.

**AD-26 — Track 2's retention is 12 hours OR 10 GB accumulated, whichever binds first;
the byte cap is the one that binds at scale.** The numbers, from the measured
0.0947 ws-parser INFO lines/s/bot at ~250 B/line under `PatternLayout`:

| Bots | lines/s | write rate | per day | per 2 h file | what binds | coverage |
|---|---|---|---|---|---|---|
| 155 (Bot-1 today) | 14.7 | 3.7 KB/s | 0.32 GB | 26 MB | age | **12 h** |
| 2,000 | 189 | 47 KB/s | 4.1 GB | 341 MB | age | **12 h** |
| 20,000 | 1,893 | **473 KB/s** | 41 GB | 3.4 GB | 10 GB | **5.9 h** |
| 30,000 | 2,840 | 710 KB/s | 61 GB | 5.1 GB | 10 GB | **3.9 h** |

(The brief's 6.5 / 65 / 98 GB/day figures assume the 400 B JSON line; the ~250 B pattern
line is where the difference comes from. **P4-6 measures the real byte rate on the box
and the design does not depend on this estimate** — it depends on the caps, which are
absolute.) On staging DEBUG adds ~16 lines/s on top; on prod `com.vingame.bot` is at
INFO so track 2 is ws-parser plus scoped-DEBUG bursts.

**The property being bought is: at least 3.9 hours of full detail at any scale up to
30,000 bots — 5.9 h at 20,000, and the full 12 h below ~2,000 — for at most 10 GB of
disk.** Below 20,000 bots that comfortably covers the evidence shim's "newest two plus
live" window (AD-15, up to 6 h). At 30,000 it does not: 3.9 h holds only ~1.95 archives,
so the shim pins essentially the entire retained set and nothing recoverable is lost —
but the *window* is shorter than the design intends, which is one more reason P4-6 is a
pre-ramp gate rather than a report line.

Budget against P0-6's measurement (Bot-1: 100 GiB total, **66.96 GiB free**):
track 1 `10GB` (unchanged; now INFO-only, so it will never bind — realistically < 250 MB)
+ track 2 `10GB` + evidence `12GB` = **32 GB nominal ceiling, ~48% of free disk**, against
~1 GB actual at today's 155 bots. Track 1's `Delete` `age` goes **7d → 14d**: Phase 0
step 5 deferred that "only after P0-6 establishes the disk size", P0-6 has now run, and
14 d aligns track 1 with the evidence directory's aggregate age. **Pre-ramp gate: above
~5,000 bots on one box, or on any box with under 100 GB of disk, re-run P4-6 and
re-derive.** Both caps are one line each in bind-mounted files.

**AD-27 — The console carries track 1 and nothing else.** It keeps AD-5's INFO+
`ThresholdFilter` and keeps its `rootLogger` and `logger.app` refs, but ws-parser no
longer reaches it (AD-23), so `docker logs bot-manager` becomes: our own group-level
INFO+, plus non-ws-parser root INFO+ (Spring's `Started Starter in 9.267 seconds`, Mongo,
Netty), plus all WARN/ERROR from those. **Root deliberately stays wired to the console
and to track 1**, a narrow widening of "track 1 is our own API only": losing Spring
startup lines would break the releaser's smoke test and losing third-party ERROR would
blind operators to 5xx and driver failures, for a volume that is tens of lines per JVM
lifetime. The risk that some *other* third-party logger turns chatty at INFO is real and
is what P4-4's per-logger histogram exists to catch. Root is **also** wired to track 2,
so the detail file is self-contained and readable without cross-referencing.

**AD-28 — The evidence shim promotes BOTH tracks, with two ages and detail-first
eviction. AD-14/AD-15/AD-16 are extended, not replaced.**

- *Both, not track 2 only.* Track 2 alone is what forensics needs line-by-line, but
  without track 1 you lose the incident timeline — the tier-2 fleet rollup, the group
  lifecycle lines, the escalation record. Track 1's files are tiny. Promoting both costs
  nothing at promotion time and is what makes the pinned set self-explanatory.
- *AD-15 extended:* "newest two at execution time" becomes **newest two per track, plus
  each track's live file** — 6 names, ≤ 3 inodes per track. The selection rule (mtime
  desc, name desc as tiebreak) is unchanged and runs per track.
- *AD-16 unchanged:* both tracks roll on the same boundary (AD-22), so the single tail
  pass at `next rollover + 120 s` closes both live files. No new timers.
- *AD-14 extended:* `ln`, never `cp`, still holds — but the bytes a hardlink pins are
  free only at promotion time. A pinned 2 h detail file is **3.4–5.1 GB at 20–30k bots**
  and cannot be reclaimed until the last name is unlinked. Worst-case pin per incident is
  therefore ~10.2 GB at 20k bots (3 detail inodes + 3 negligible aggregate inodes),
  ~15.3 GB at 30k. **At 30k that exceeds the 12 GB evidence cap and one incident would
  partially evict its own oldest detail file** — deliberate: the guard must bind
  somewhere, detail-first eviction makes it bind on the cheapest bytes, and P4-6 is the
  gate that says "raise the cap or lower `WSPARSER_LOG_LEVEL` before ramping there".
- *AD-19 extended:* the sweep gains **`EVIDENCE_DETAIL_MAX_AGE_DAYS` (default 3)**
  alongside `EVIDENCE_MAX_AGE_DAYS` (14, unchanged, aggregates only), and
  `EVIDENCE_MAX_BYTES` rises **5 GB → 12 GB**. Under the byte guard, **detail files are
  evicted before aggregate files** regardless of promotion time, then oldest-promotion
  first within each class — shedding the large, cheap-to-lose bytes rather than
  discarding an old incident's 7 MB timeline to make room for a new incident's 3 GB
  payload. Track membership is decided by the `detail` filename prefix.

**AD-29 — Loki keeps `retention_period: 720h`; the `{level=~"DEBUG|TRACE"}`
`retention_stream` stays as an inert tripwire; P0-9 is re-baselined and repurposed.** No
value in `loki/loki-config.yaml` changes.

- *720 h stays, and is now cheap.* Loki now ingests only track 1. The measured 1.016 GB
  at a 168 h horizon was ~99% ws-parser and DEBUG; what remains projects to roughly
  **7 MB/day at staging scale and ~2 GB at a 30-day steady state** even allowing 10× for
  a busier fleet, against 66.96 GiB free. The 4.4 GB projection the release shipped on is
  now an over-estimate by more than an order of magnitude, and P0-9's "abort if the
  30-day projection exceeds 50% of available disk" gate is correspondingly further from
  binding. It stays gated anyway — the projection is still a projection.
- *The DEBUG/TRACE selector now matches nothing, and that is why it stays.* With AD-24's
  threshold in place the only ways a DEBUG line can reach Loki are: someone removes the
  `ThresholdFilter`, or someone repoints promtail at `logs/detail/`. Both are silent
  failures whose blast radius is a 720 h horizon over the highest-volume tier in the
  system. Leaving the selector in place degrades that to 24 h. It costs nothing, it keeps
  `LogRetentionPipelineTest` green unmodified, and it is the switch AD-24's costed
  exception would need. **Documented in `loki-config.yaml` as a tripwire, not as an
  active policy.**
- *P0-9 becomes a misroute detector as well as a disk gate.* Its old method (T+7 d minus
  T+24 h) was corrupted by the redeploy re-ingest; AD-31 fixes that, so the delta is now
  meaningful. Its new expected magnitude is **single-digit MB/day**. A figure an order of
  magnitude above that is not a disk problem — it means a track is misrouted, and P4-5 is
  the step that says which.

**AD-30 — Agency-token material is accepted in track 2, bounded there, and fixed
upstream later.** Today `User <bot>: Agency token: <n>-<id>` is written at INFO into
`console.log`, shipped to Loki and retained 720 h, and echoed to `docker logs`. After
Phase 4 it exists in exactly one place: a plain-text file on the box, swept at 12 h or
10 GB, readable only by whoever can already read `logs/` (which has always contained
everything). It is **never** in Loki, Grafana or `docker logs`. Evidence promotion is
the one path that extends it, and **`EVIDENCE_DETAIL_MAX_AGE_DAYS = 3` (AD-28) is
deliberately chosen against the aggregate's 14 d partly for this reason** — 3 days of
token material behind an incident is a defensible trade for the forensics. This is a
strict and large improvement over the shipped state; it is **not a fix**. The fix is in
the library — see Open Items.

**AD-31 — promtail's positions file moves to a named volume.** `positions.filename`
becomes `/promtail-positions/positions.yaml`, backed by a new named volume
`promtail-positions`. `/tmp` is deliberately **not** what gets mounted: shadowing a
container's `/tmp` is a wider blast radius than the problem warrants. Without this,
every `docker compose down` makes promtail re-read ~12 retained archives from byte 0 —
measured at +2.2 GB into `loki-data` in 17 minutes — which under the 720 h horizon now
persists 4.3× longer and which invalidates P0-9's method outright. **Expect one final
re-ingest on the deploy that lands this** (there are no positions yet); take P0-9's
baseline after it settles, and never again after that.

**AD-32 — ws-parser's own level stays at INFO; lowering it is the escape hatch, not the
design.** "Track 2 carries ws-parser at all levels" is a **routing** statement, not a
threshold change: the logger keeps the `info` threshold it effectively has today, which
is exactly what the 14.67 lines/s measurement and every projection in AD-26 are built
on. Raising it to `debug` would be an unmeasured volume increase in the tier this
feature exists to bound; lowering it to `warn` would delete the per-bot
connection/auth narrative that root-caused the PING-before-AUTH regression. Both
directions are available as **one environment variable** —
`LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER`, wired in compose as
`${WSPARSER_LOG_LEVEL:-INFO}`, taking the same Spring Boot relaxed-binding path AD-7
proved for `com.vingame.bot` (P1-1 PASS). On a box where disk becomes the binding
constraint before a ramp, `WSPARSER_LOG_LEVEL=WARN` in `secrets.env` plus a restart
removes ~99% of track 2's volume with no rebuild.

---

## Plan (Phase 4)

Three phases. **4a and 4b are order-independent** and neither depends on the other.
**4c is inert without 4a** (the shim would find no detail files and behave exactly as it
does today), so it is safe in any order but only useful after 4a.

### Phase 4a — The two-track appender split (config only, no Java)

Touches three properties files, `docker-compose.yml`, four tests and `CLAUDE.md`.

1. **Both shipped twins** — `/Users/gleb/IdeaProjects/Bot/logging/log4j2.properties` and
   `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/log4j2.properties`, applied
   identically below the header fence (`Log4j2TwinConfigTest`):

   a. Add the detail appenderRef to both existing loggers:
   ```properties
   rootLogger.appenderRef.detail.ref = AsyncDetail
   logger.app.appenderRef.detail.ref = AsyncDetail
   ```

   b. Add the ws-parser logger (AD-23):
   ```properties
   logger.wsparser.name = com.vingame.websocketparser
   logger.wsparser.level = info
   logger.wsparser.additivity = false
   logger.wsparser.appenderRef.detail.ref = AsyncDetail
   ```

   c. Cap track 1 at INFO+ (AD-24) — **on the Async wrapper, not on the rolling file**:
   ```properties
   appender.async.filter.threshold.type = ThresholdFilter
   appender.async.filter.threshold.level = info
   appender.async.filter.threshold.onMatch = NEUTRAL
   appender.async.filter.threshold.onMismatch = DENY
   ```

   d. Add the track-2 appender pair (AD-25). **The `.type = AppenderRef` line is the same
   trap Phase 0 flagged — omitting it throws `ConfigurationException` at context start:**
   ```properties
   appender.detail.type = RollingFile
   appender.detail.name = DetailFileAppender
   appender.detail.fileName = /app/logs/detail/detail.log
   appender.detail.filePattern = /app/logs/detail/detail-%d{yyyy-MM-dd-HH}.log
   appender.detail.immediateFlush = false
   appender.detail.layout.type = PatternLayout
   appender.detail.layout.pattern = %d{yyyy-MM-dd'T'HH:mm:ss.SSS}{UTC} %-5level %c %t [%X{botGroupId}/%X{botId}/%X{gameType}] - %msg%n
   appender.detail.policies.type = Policies
   appender.detail.policies.time.type = TimeBasedTriggeringPolicy
   appender.detail.policies.time.interval = 2
   appender.detail.policies.time.modulate = true
   appender.detail.strategy.type = DefaultRolloverStrategy
   appender.detail.strategy.max = 200
   appender.detail.strategy.delete.type = Delete
   appender.detail.strategy.delete.basePath = /app/logs/detail
   appender.detail.strategy.delete.maxDepth = 1
   appender.detail.strategy.delete.ifFileName.type = IfFileName
   appender.detail.strategy.delete.ifFileName.glob = detail-*.log
   appender.detail.strategy.delete.ifAny.type = IfAny
   appender.detail.strategy.delete.ifAny.ifLastModified.type = IfLastModified
   appender.detail.strategy.delete.ifAny.ifLastModified.age = 12h
   appender.detail.strategy.delete.ifAny.ifAccumulatedFileSize.type = IfAccumulatedFileSize
   appender.detail.strategy.delete.ifAny.ifAccumulatedFileSize.exceeds = 10GB

   appender.asyncdetail.type = Async
   appender.asyncdetail.name = AsyncDetail
   appender.asyncdetail.appenderRef.type = AppenderRef
   appender.asyncdetail.appenderRef.ref = DetailFileAppender
   appender.asyncdetail.bufferSize = 16384
   appender.asyncdetail.blocking = false
   ```

   e. Track 1's `Delete` `age`: `7d` → `14d` (AD-26; P0-6's gate is now closed).

   f. Update both header comments to describe the two tracks, and add an inline comment
   at `appender.asyncdetail.blocking = false` explaining why it is the *opposite* of
   `appender.async.blocking = true` — a future reader who has memorised Phase 0's Drift 1
   will otherwise "fix" it.

2. **The third copy** —
   `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/resources/log4j2-test.properties`:
   apply 1a–1d verbatim **except** `appender.detail.fileName = target/test-logs/detail/detail.log`
   and `filePattern = target/test-logs/detail/detail-%d{yyyy-MM-dd-HH}.log`. **This is not
   optional**: `AsyncAppender.start()` throws when its target is unavailable, so a test
   config declaring `AsyncDetail` against `/app/logs/detail` kills every Spring-context
   test in the module. Extend the file's header to name the second intended difference.

3. **`docker-compose.yml`**, `bot-manager` service — add next to
   `LOGGING_LEVEL_COM_VINGAME_BOT` (`:69`):
   ```yaml
   - LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER=${WSPARSER_LOG_LEVEL:-INFO}
   ```
   with a comment carrying AD-32: this is the disk escape hatch (`WARN` removes ~99% of
   track 2), and the logger name must not be renamed in `log4j2.properties` or the
   override silently creates an appender-less LoggerConfig. Add `WSPARSER_LOG_LEVEL` to
   `secrets.env.example`.

4. **Extend the guard tests.** Each must fail on the pre-4a tree — Dev should verify that
   by reverting the properties change and watching them go red:
   - `AsyncQueuePolicyTest` — assert **three** loggers and their refs (`rootLogger` and
     `logger.app` → `ConsoleAppender` + `AsyncRolling` + `AsyncDetail`; `logger.wsparser`
     → `AsyncDetail` **only**, `additivity = false`, name exactly
     `com.vingame.websocketparser`); assert `appender.async.blocking = true` **and**
     `appender.asyncdetail.blocking = false` as a deliberate pair, with the AD-25(4)
     reasoning in the assertion message; assert the `AsyncRolling` `ThresholdFilter` at
     `info` with `onMismatch = DENY` (this is the single assertion that keeps DEBUG out
     of Loki); assert `appender.detail.layout.type = PatternLayout` and that track 1
     keeps `JsonTemplateLayout`.
   - `Log4j2TestConfigShapeTest` — add every new graph key to `SHAPE_KEYS`
     (`rootLogger.appenderRef.detail.ref`, `logger.app.appenderRef.detail.ref`, the four
     `logger.wsparser.*`, `appender.detail.type/name/layout.type`,
     `appender.detail.policies.time.interval/modulate`, the four
     `appender.asyncdetail.*`, and the four `appender.async.filter.threshold.*`), and
     extend `theOnlyDifferenceIsTheOutputPath` to enumerate `appender.detail.fileName` /
     `filePattern` as the **second** intended difference. The
     `everyShapeKeyIsActuallyDeclared` anti-vacuity case already covers typos in the new
     keys.
   - `EvidenceRetentionEscapeTest` — add a case asserting track 2's own `Delete` is
     anchored at `basePath = /app/logs/detail` with `maxDepth = 1` and glob
     `detail-*.log`, in **both** twins, with the message explaining that raising either
     lets a sweeper reach `logs/` or `logs/evidence/`. Add an assertion that
     `appender.detail.fileName` is under a **subdirectory** of the promtail mount, since
     that is the only thing keeping track 2 out of Loki. Keep the existing
     `appender\.rolling\.policies\.time\.interval` regex — it will not match
     `appender.detail.*`, but add a sibling assertion that the two intervals are equal
     (AD-22's one-boundary requirement, which AD-28's single tail pass depends on).
   - `LoggingComposeWiringTest` — assert `LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER` is
     passed and defaults to `INFO`.

5. **`CLAUDE.md`** — rewrite the "Logging Guidelines" preamble around the two tracks:
   what each carries, that **only track 1 reaches Loki/Grafana**, how a scoped-DEBUG
   session is read now (AD-24's three greps), and that the DEBUG-tier signals
   `CLAUDE.md` currently calls "default-visible" — the 5 s `UpdateBet` aggregate with
   the strategy-decision histogram, and the per-round session summaries (AD-8) — are now
   **file-visible on the box, not Grafana-visible**. The tier definitions themselves
   (INFO/DEBUG/WARN/ERROR/TRACE) are unchanged; only where each lands changes. While in
   there, fix the stale `GET /api/v1/bot-group/` row in the REST table
   (`release.md:884-886` — the endpoint is `POST /{envId}/filter` and returns 405).

**Deploy note for 4a:** `mkdir -p logs/detail` on the box before `docker compose up`.
log4j2's `FileManager` creates the parent directory itself, and the bind-mounted `logs/`
is owned by the same uid bot-manager runs as — but if it ever failed, `AsyncDetail`
would throw at context start and the JVM would come up with no logger at all. One
`mkdir` removes the whole class of risk, the same way the release pre-created `logging/`
and `evidence-shim/`.

### Phase 4b — promtail positions volume + Loki comments (config only, no Java)

1. `/Users/gleb/IdeaProjects/Bot/promtail-config.yml:5-6` —
   `positions.filename: /promtail-positions/positions.yaml`, with a comment naming the
   bug (Finding 2: +2.2 GB into `loki-data` in 17 minutes per redeploy) and stating that
   the path **must** be inside a volume.
2. `/Users/gleb/IdeaProjects/Bot/docker-compose.yml` — add
   `- promtail-positions:/promtail-positions` to the promtail service's volumes and
   `promtail-positions:` to the top-level `volumes:` block (`:350-355`).
3. `/Users/gleb/IdeaProjects/Bot/loki/loki-config.yaml` — **comments only, no value
   changes** (AD-29): record that Loki now ingests track 1 only, that the
   `{level=~"DEBUG|TRACE"}` selector is an inert tripwire against a misroute rather than
   an active policy, and that `retention_period: 720h` is now expected to cost
   single-digit MB/day.
4. Extend `LogRetentionPipelineTest` with one case: promtail's `positions.filename`
   directory must appear as a mount target on the promtail service in
   `docker-compose.yml`. Assert it fails on the current `/tmp/positions.yaml`.

### Phase 4c — The evidence shim promotes both tracks (Python, no Java)

`/Users/gleb/IdeaProjects/Bot/evidence-shim/shim.py`:

1. Replace the `LIVE_NAME` / `LOG_GLOB` module constants (`:109-110`) with a **track
   list**: `("aggregate", <logs_dir>, "console*.log", "console.log", "console-live-%s-%s.log")`
   and `("detail", <detail_dir>, "detail*.log", "detail.log", "detail-live-%s-%s.log")`.
   `Config` gains `EVIDENCE_DETAIL_DIR` (default `<logs_dir>/detail`).
2. `candidates()` (`:344-368`) and `selection()` (`:370-377`) take a track and are called
   per track; each keeps the existing mtime-desc / name-desc rule and `newest_count`. A
   missing detail directory returns `[]` — **this is what makes 4c safe to ship before
   4a.**
3. `promote()` (`:381-416`) iterates both tracks, using each track's live-name template.
   The per-incident live name is still stamped from `firstSeenAt` (`:517-521`) so repeat
   passes stay idempotent — but there are now **two** stored names per incident, so
   `record()`'s pending entry carries `liveNames: {track: name}` rather than a single
   `liveName`. **`load_pending` must tolerate the old single-`liveName` shape**, or a
   shim restart mid-incident loses its scheduled passes.
4. `sweep()` (`:737-789`) — classify each evidence file by the `detail` filename prefix;
   apply `EVIDENCE_DETAIL_MAX_AGE_DAYS` to detail files and `EVIDENCE_MAX_AGE_DAYS` to
   the rest; then, under `EVIDENCE_MAX_BYTES`, evict **all detail candidates
   oldest-promotion-first before touching any aggregate** (AD-28). Keep `st_size`
   over-estimation and the `.promoted.json` promotion-time basis exactly as they are.
5. `summary()` — report per-track file counts and bytes, and both ages, so `/health`
   shows at a glance whether the detail track is being seen at all.
6. `/Users/gleb/IdeaProjects/Bot/docker-compose.yml`, `evidence-shim` service:
   `EVIDENCE_MAX_BYTES` default `5368709120` → `12884901888` (12 GB), new
   `EVIDENCE_DETAIL_MAX_AGE_DAYS=${EVIDENCE_DETAIL_MAX_AGE_DAYS:-3}`, comments carrying
   AD-28 and AD-30. Mirror in `secrets.env.example`.
7. `/Users/gleb/IdeaProjects/Bot/evidence-shim/selftest.py` — extend for: newest-two
   selection **per track**; a missing detail directory being a no-op rather than an
   error; detail-first eviction under the byte guard (construct one old aggregate and one
   new detail, set a byte cap below both, assert the **detail** goes); the two ages being
   independent; and `load_pending` accepting the legacy single-`liveName` entry.
   `EvidenceShimSelfTestRunnerTest` runs it in the build and cannot swallow a failure
   (mutation-verified, `qa.md:83-90`).
8. `EvidenceRetentionEscapeTest` — extend `theShimHardlinksRatherThanCopying`'s sibling
   coverage with an assertion that the shim's detail directory default matches
   `appender.detail.fileName`'s directory in `logging/log4j2.properties`, the same way
   `EVIDENCE_ROLLOVER_HOURS` is already pinned to the rollover interval.

---

## Implementation Notes / Concerns (Phase 4)

- **The `ThresholdFilter` goes on `AsyncRolling`, not on `RollingFileAppender`.** Both
  work, but on the wrapper the DEBUG event is rejected before it consumes a queue slot;
  on the inner appender it is queued, dispatched to the async thread, and only then
  discarded. On staging (`BOT_LOG_LEVEL=DEBUG`, ~16 DEBUG lines/s) that is the
  difference between a filter and a treadmill.
- **`appender.asyncdetail.appenderRef.type = AppenderRef` is not optional.** Phase 0
  called this "the single most likely silent failure"; on 2.24.1 it is worse than silent
  — it throws `ConfigurationException: No type attribute provided for component
  appenderRef` at context start and the JVM never gets a logger. Same trap, second
  appender.
- **`blocking = false` on `AsyncDetail` looks like Phase 0's Drift 1 defect and is
  not.** Drift 1 corrected `blocking = false` → `true` on track 1 because a
  non-blocking queue drops ERROR. Track 2 is deliberately the other way. Put the reason
  in the file, in the test's assertion message, and in `CLAUDE.md`, or someone will
  "fix" it back.
- **Do not rename `com.vingame.websocketparser`.** Same failure mode as AD-7's warning
  about `com.vingame.bot`: Spring Boot's `setLogLevel` against a non-existent
  LoggerConfig name creates a fresh one with **no appenders**, which would delete track 2
  without an error anywhere.
- **Three log4j2 copies, and the third one bites hardest.** `Log4j2TwinConfigTest`
  catches drift between the two shipped files. Nothing catches "I forgot the test copy"
  except every Spring-context test in `bot-app` failing with
  `ExceptionInInitializerError` before its first assertion — a failure that looks like a
  Spring problem and is not.
- **Track 2's `Delete` runs on track 2's rollover only.** Both roll on the same 2 h
  boundary, so both sweeps fire together. A future change to either interval breaks
  AD-22's single-boundary assumption *and* AD-28's single tail pass; the equality
  assertion added to `EvidenceRetentionEscapeTest` is the guard.
- **`ifAccumulatedFileSize` counts the archives, not the live file.** log4j2's `Delete`
  glob is `detail-*.log`, which excludes `detail.log`. At 30k bots the live file adds up
  to another 5.1 GB on top of the 10 GB cap before it rolls. Budget 15 GB, not 10, for
  track 2 at that scale.
- **PatternLayout renders MDC as `[groupId/botId/gameType]`, not as JSON fields.** Every
  Phase 0–3 verification grep that reaches for `"botGroupId":"<GID>"` must become
  `[<GID>/` when pointed at track 2. This is the single most likely way the Releaser
  gets a false negative.
- **`%-5level` pads to five characters**, so `grep ' DEBUG '` and `grep ' INFO  '` behave
  differently. Prefer `grep -E ' (DEBUG|TRACE) '` and anchor on the timestamp when
  counting.
- **`immediateFlush = false` means `wc -l logs/detail/detail.log` lags the true count by
  up to one batch.** Harmless for the verification steps as written (they measure deltas
  over 300 s), misleading if anyone tries to assert an exact count.
- **The first deploy of 4b still re-ingests once.** There are no saved positions yet, so
  promtail reads every retained archive from byte 0 one last time. Expect ~2 GB into
  `loki-data`, then never again. **Take P0-9's baseline after that settles**, not
  before.
- **Deleting `logs/detail/` by hand while the app runs will not free space** — the JVM
  holds the open file descriptor for `detail.log`, and evidence hardlinks hold the
  archives. Use the caps, or restart bot-manager after unlinking.
- **`du -sb logs` now spans both tracks and evidence**, and `du` de-duplicates hardlinks
  within a single invocation. Any P0-9/P4-6 command that measures the two separately
  must not be summed naively.
- **`docker logs` loses the `AUTH [...]` flood, which some operators use as a liveness
  signal.** The replacement is the ws-parser narrative in `logs/detail/detail.log` and
  `bot_messages_total` in Prometheus. Say so in `CLAUDE.md` so it is not reported as a
  regression.

---

## Open Items (Phase 4)

- **`Prod-Bot`'s disk size has never been measured.** P0-6 measured Bot-1 (100 GiB,
  66.96 GiB free) and every number in AD-26 is calibrated against that. Run P0-6 on
  `Prod-Bot` before Phase 4 lands there; if it is materially smaller, cut track 2's
  `10GB` and evidence's `12GB` proportionally.
- **The agency-token log line is not fixed, only contained (AD-30).** The real fix is in
  `websocket-parser-core` — stop logging token material, or log a fingerprint. Note the
  library publishes jars from uncommitted trees, so this needs the source located first.
  Not in this plan.
- **The ANSI escapes are not fixed either**, only rendered harmless by track 2's
  `PatternLayout`. If track 2 ever needs to be JSON, this comes back.
- **AD-24's narrow exception (`com.vingame.bot` DEBUG to Loki at 24 h) is costed and
  deliberately not taken.** Revisit if operators report the file-level drill-in is not
  usable in practice; it is one line plus `BOT_LOG_LEVEL=INFO` on staging.
- **Ramping past ~5,000 bots on one box is gated on re-running P4-6.** AD-26's coverage
  table is extrapolated from a 155-bot measurement.
- **Nothing alerts on track 2 filling the disk.** `node-exporter` reports
  `node_filesystem_avail_bytes` and `prometheus/alerts.yml` has disk rules; whether they
  fire early enough at 473 KB/s sustained is unverified. Out of scope here, worth a
  follow-up.
- **Grafana dashboards are still untouched**, and none reads logs
  (`/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/`). If anyone adds a
  Loki panel, it can only ever show track 1.
- **Round-completion-dependent checks remain unexercised on staging** (Finding 3: zero
  `endGame` fleet-wide). Phase 4 does not change that and does not depend on it.

---

## Verification (Phase 4)

Run from `/home/sgame/bot-java` on Bot-1. **Prerequisite before `docker compose up`:
`mkdir -p logs/detail`.**

The universal steps **U-1** and **U-2** are unchanged and still apply.

### New steps

- **P4-1 — both tracks exist and both appender graphs built.**
  ```bash
  ls -l logs/console.log logs/detail/detail.log
  docker compose exec bot-manager sh -c 'cat /proc/1/task/*/comm 2>/dev/null | grep -ci async'
  docker compose exec bot-manager sh -c 'grep -c "^appender.asyncdetail" /app/config/log4j2.properties'
  ```
  → expect both files present and non-empty; the thread count **`2`** (log4j2 names both
  `Log4j2-AsyncApp…`, truncated at 15 chars, so they are indistinguishable by name —
  the count is the assertion); and the config grep `> 0`. A count of `1` means
  `AsyncDetail` did not build — check the `.type = AppenderRef` line. If bot-manager is
  not up at all, read `docker compose logs bot-manager | head -40` for
  `ConfigurationException`.

- **P4-2 — ws-parser is in track 2 and nowhere else.** Mark both files, wait, classify
  only the new lines:
  ```bash
  A=$(wc -l < logs/console.log); D=$(wc -l < logs/detail/detail.log); sleep 300
  echo "track1 new: $(tail -n +$((A+1)) logs/console.log | grep -c 'com.vingame.websocketparser')"
  echo "track2 new: $(tail -n +$((D+1)) logs/detail/detail.log | grep -c 'com.vingame.websocketparser')"
  docker compose logs --since 5m bot-manager | grep -c websocketparser
  ```
  → expect track1 **`0`**, track2 **`> 1000`** on a 155-bot fleet (14.67 lines/s × 300 s
  ≈ 4,400), console **`0`**. Track2 at `0` means `logger.wsparser` did not take; track1
  above `0` means `additivity = false` is missing.

- **P4-3 — track 1 carries no DEBUG even with `BOT_LOG_LEVEL=DEBUG`.** Using the same
  `A` / `D` marks:
  ```bash
  tail -n +$((A+1)) logs/console.log       | grep -c '"level":"DEBUG"'
  tail -n +$((D+1)) logs/detail/detail.log | grep -cE ' (DEBUG|TRACE) '
  ```
  → expect **`0`** and **`> 0`**. Both must hold: the first alone passes if the app went
  quiet. This is the assertion that keeps Loki clean, and it must be run on **staging,
  where the logger is at DEBUG** — on a prod-like INFO instance it passes vacuously.

- **P4-4 — INFO reaching Loki, by logger. REPLACES P1-3.**
  ```bash
  A=$(grep -c '"level":"INFO"' logs/console.log); sleep 300
  B=$(grep -c '"level":"INFO"' logs/console.log); echo "delta=$((B-A)) over 300s"
  tail -n "$((B-A))" logs/console.log | grep '"level":"INFO"' \
    | sed -n 's/.*"logger":"\([^"]*\)".*/\1/p' | sort | uniq -c | sort -rn
  tail -n "$((B-A))" logs/console.log | grep -c '"logger":"com.vingame.websocketparser'
  ```
  → expect `delta < 100` for a 7-group staging fleet; expect the histogram to be
  dominated by `com.vingame.bot.*`; expect the last count to be exactly **`0`**. P1-3
  failed at `delta=2022` purely because of the last line — that is now a hard assertion
  rather than a footnote. **Misroute in the other direction:**
  ```bash
  grep -c 'FleetRollupLogger' logs/detail/detail.log
  ```
  → expect `> 0`; track 2 must be self-contained (AD-27), so our own INFO appears in
  both files. `0` here means `logger.app.appenderRef.detail.ref` is missing.

- **P4-5 — Loki holds track 1 and only track 1.**
  ```bash
  S=$(date -d '1 hour ago' +%s)000000000; E=$(date +%s)000000000
  q() { curl -sG 'http://localhost:3100/loki/api/v1/query_range' \
          --data-urlencode "start=$S" --data-urlencode "end=$E" \
          --data-urlencode 'limit=1' --data-urlencode "query=$1"; }
  q '{job="bot-manager"} |= "websocketparser"'
  q '{job="bot-manager", level="DEBUG"}'
  q '{job="bot-manager", filename=~"/logs/detail/.*"}'
  q '{job="bot-manager", level="INFO"}'
  ```
  → expect `"result":[]` for the **first three** and a **non-empty** stream for the
  fourth. The fourth is the non-vacuity control — without it the first three pass on a
  dead Loki.

- **P4-6 — track 2's real write rate (PRE-RAMP GATE, pairs with P0-6).**
  ```bash
  X=$(du -sb logs/detail | cut -f1); sleep 600; Y=$(du -sb logs/detail | cut -f1)
  R=$(( (Y-X)/600 ))
  echo "bytes/s=$R  per-2h-file=$(( R*7200 ))  per-day=$(( R*86400 ))"
  curl -s http://localhost:8080/api/v1/environment/ >/dev/null && echo ok
  ```
  → expect `R` in the low **thousands** of bytes/s at 155 bots (AD-26 predicts ~3,700).
  Record `R` and the fleet bot count `N` in the release report. Then project:
  `R × TARGET / N`. **Gate:** if the projected per-2 h file exceeds **5 GB**, or the
  projected per-day exceeds **60 GB**, do not ramp until either the `10GB` cap is
  lowered or `WSPARSER_LOG_LEVEL=WARN` is set (AD-32) — both are bind-mount edits plus
  `docker compose restart bot-manager`, no rebuild.

- **P4-7 — track 2's retention is anchored where it cannot reach anything else.**
  ```bash
  docker compose exec bot-manager sh -c 'grep -E "^appender\.detail\.strategy\.delete" /app/config/log4j2.properties'
  ```
  → expect `basePath = /app/logs/detail`, `maxDepth = 1`, `glob = detail-*.log`,
  `age = 12h`, `exceeds = 10GB`. Then after the next even-hour boundary:
  ```bash
  ls -l logs/detail/ ; find logs/detail -maxdepth 1 -name 'detail-*.log' | wc -l
  ```
  → expect **exactly one new archive per 2 h** and a steady-state count of **≤ 7**
  (12 h ÷ 2 h, plus the one mid-rollover). A count that keeps climbing past 7 means the
  `Delete` never fires — check `basePath`.

- **P4-8 — promtail no longer re-ingests on restart (AD-31).**
  ```bash
  docker compose exec promtail sh -c 'head -20 /promtail-positions/positions.yaml'
  B1=$(docker run --rm -v bot-java_loki-data:/d alpine du -sb /d | cut -f1)
  docker compose restart promtail; sleep 180
  B2=$(docker run --rm -v bot-java_loki-data:/d alpine du -sb /d | cut -f1)
  echo "loki grew $((B2-B1)) bytes across a promtail restart"
  docker compose logs --since 4m loki | grep -c 'timestamp too old'
  ```
  → expect the positions file to list `/logs/console.log` with a **non-zero** offset;
  expect the growth to be **under 50 MB** (organic ingest only, against the 2.2 GB of
  Finding 2); expect the `timestamp too old` count to be **`0`**. Repeat once after a
  full `docker compose down && ./deploy.sh` — that is the case that actually bit.

- **P4-9 — evidence promotes both tracks, still by hardlink.** Reuse P3-3's synthetic
  POST, then:
  ```bash
  ls logs/evidence/ | grep -c '^console'
  ls logs/evidence/ | grep -c '^detail'
  stat -c '%h %i %n' logs/detail/detail.log logs/evidence/detail-live-*.log
  ```
  → expect **≥ 2** of each; expect the live detail file and its promoted link to report
  the **same inode** and a link count **≥ 2**. A differing inode means someone wrote a
  copy and the disk-doubling failure mode is live at 3 GB a file.
  ```bash
  docker compose exec evidence-shim python -c "import urllib.request,json;print(json.dumps(json.loads(urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=3).read()),indent=1))" | grep -iE 'detail|evidenceFiles|evidenceBytes|maxBytes'
  ```
  → expect a per-track breakdown with a **non-zero** detail count. Zero means the shim
  is not seeing `logs/detail/` — check `EVIDENCE_DETAIL_DIR`.

- **P4-10 — the two evidence ages are independent (AD-28).** Temporarily set
  `EVIDENCE_DETAIL_MAX_AGE_DAYS=0` in `secrets.env`, `./deploy.sh`, wait one sweep
  (`EVIDENCE_SWEEP_INTERVAL_SECONDS`, or trigger a promotion), then:
  ```bash
  ls logs/evidence | grep -c '^detail'
  ls logs/evidence | grep -c '^console'
  ```
  → expect **`0`** detail files and the aggregate count **unchanged and `> 0`**. Restore
  the real value and redeploy. This is the step that proves the sweep discriminates
  rather than sharing one age; P3-10's single-age form cannot.

- **P4-11 — the new scoped-DEBUG operator path (AD-24).** With a known `<GID>`:
  ```bash
  curl -s -X POST "http://localhost:8080/api/v1/logging/debug/<GID>?minutes=5" -w '\n%{http_code}\n'
  sleep 60
  grep -c "\[<GID>/" logs/detail/detail.log
  grep -c 'scoped debug\|Scoped per-group DEBUG' logs/console.log
  ```
  → expect `200`; expect the detail grep **`> 0`** (the drill-in payload is on the box);
  expect the console/track-1 grep **`> 0`** (the *pointer* is still in Loki). Confirm the
  pointer really reached Loki:
  ```bash
  curl -sG 'http://localhost:3100/loki/api/v1/query_range' \
    --data-urlencode "start=$(date -d '1 hour ago' +%s)000000000" \
    --data-urlencode "end=$(date +%s)000000000" --data-urlencode 'limit=1' \
    --data-urlencode 'query={job="bot-manager"} |= "scoped debug"'
  ```
  → expect a **non-empty** result. This pair is the whole contract of AD-24: Grafana
  tells you a drill-in exists and when; the box holds it.

- **P4-12 — `docker logs` is clean.**
  ```bash
  docker compose logs --since 5m bot-manager | grep -cE 'websocketparser|Agency token|\x1b\[36m'
  docker compose logs --since 30m bot-manager | grep -cE 'Started Starter|bot groups running'
  ```
  → expect **`0`** for the first (no library flood, no token material, no ANSI on
  stdout) and **`> 0`** for the second measured across a restart (Spring startup and the
  auto-start summary are still visible — AD-27's reason for keeping root wired to the
  console).

### Corrections to the Phase 0–3 steps

Once Phase 4 has shipped, replace these. Everything not listed stands unchanged.

- **U-3** — the command is stale independently of Phase 4 (`release.md:189-195`):
  `GET /api/v1/bot-group/` returns **405**, pre-existing. Use
  `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/environment/`
  → expect `200`, and
  `curl -s -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8080/api/v1/bot-group/<ENV_ID>/filter -o /dev/null -w '%{http_code}\n'`
  → expect `200`.
- **P0-2** — the thread name is `Log4j2-AsyncApp…` on log4j2 2.24.1, not
  `AsyncAppender-R`. **Superseded by P4-1**, which counts **two** such threads.
- **P0-4** — `grep -c '"level":"DEBUG"' logs/console.log` now expects **`0`**, not
  `> 0`; the `> 0` half moves to `logs/detail/detail.log` with the pattern
  `grep -cE ' (DEBUG|TRACE) '`. **Superseded by P4-3.**
- **P0-5** — the acceptance criterion is **wrong as written and fails forever on a
  working system**. log4j2 names an archive for `rollover time − 1 hour`, so a 2-hour
  modulated interval yields **odd** suffixes (`-13`, `-15`, `-17`, …); the release
  observed `console-2026-08-19-13.log` from a correct 14:00Z rollover
  (`release.md:275-328`). Replace with: after the next even-hour boundary,
  ```bash
  find logs -maxdepth 1 -name 'console-*.log' -newermt '<deploy timestamp>' -printf '%T@ %f\n' | sort -n
  find logs/detail -maxdepth 1 -name 'detail-*.log' -newermt '<deploy timestamp>' -printf '%T@ %f\n' | sort -n
  ```
  → expect **exactly one new archive per track per 2 h**, with **mtimes on even-hour
  boundaries** and **no two archives sharing a name**. Do not assert the parity of the
  filename suffix. AD-20's substantive requirement is uniqueness, and it holds.
- **P0-8** — the DEBUG half is now vacuous: DEBUG never reaches Loki at all, so the query
  returns empty whether or not retention works. Keep the **WARN/ERROR** half (non-empty
  over a 26–30 h old window, allowing `retention_delete_delay: 2h`) as the proof the
  compactor runs, and take the DEBUG assertion from **P4-5** instead.
- **P1-3** — **replaced by P4-4.** Its `< 100` threshold was unachievable while
  ws-parser sat under the root logger; the per-logger histogram is what makes it both
  achievable and discriminating.
- **P1-8** — the DEBUG control moves to track 2:
  `grep -c 'session' logs/detail/detail.log` over 5 minutes on an active betting group →
  expect `> 0`. The INFO half (`grep '"level":"INFO"' logs/console.log | grep -c
  'session'` → `0`) is unchanged. Note the EndGame half is still unexercisable on this
  fleet (Finding 3).
- **P2-2 / P2-3** — retarget to track 2 and to PatternLayout MDC. P2-2 becomes
  `grep -c "\[<GID>/" logs/detail/detail.log` → `> 0`, and
  `tail -n 2000 logs/detail/detail.log | grep -E ' (DEBUG|TRACE) ' | grep -vc "\[<GID>/"`
  → `0` **on a prod-like instance only** (on staging, `BOT_LOG_LEVEL=DEBUG` means every
  group emits DEBUG legitimately and the exclusion assertion is meaningless — the
  release's own P2-2 had the same limitation against `console.log`). P2-3's expiry check
  becomes the same grep against `tail -2000 logs/detail/detail.log` → `0`.
- **P2-4** — `grep -c 'below spin cost' logs/detail/detail.log`, not `console.log`.
  Threshold unchanged (`≤ 2 ×` affected bots over 5 minutes). Still unexercised until a
  bot falls below spin cost.
- **P3-3** — the expected file count after a synthetic alert rises from **≥ 2** to
  **≥ 4** (two tracks × at least one closed plus one live). **Superseded by P4-9.**
- **P3-10** — must set **both** `EVIDENCE_MAX_AGE_DAYS=0` and
  `EVIDENCE_DETAIL_MAX_AGE_DAYS=0` to empty the directory. **Superseded by P4-10**,
  which additionally proves the two ages are independent.
- **P0-9** — re-baseline **after** Phase 4b has shipped and its one final re-ingest has
  settled; the old baseline is invalid because it spans a redeploy (AD-31). Expected
  magnitude is now **single-digit MB/day**; the 50%-of-available-disk abort gate is
  unchanged, and a figure an order of magnitude above the expectation should be
  investigated as a misroute (run P4-5) before it is treated as a disk problem.

---

**Phase 4 is not a "universal smoke test only" feature.** P4-1 through P4-12 are all
executable on the box and all have explicit pass conditions. **P4-6 is a pre-ramp gate
that cannot be closed from the build**, and **P4-3 and P4-5 must be run on staging with
`BOT_LOG_LEVEL=DEBUG`** — on a prod-like INFO instance they pass vacuously and prove
nothing.
