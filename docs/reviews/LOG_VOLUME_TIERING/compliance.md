# Compliance — LOG_VOLUME_TIERING

Branch: `feature/log-volume-tiering` (HEAD `3b38ecf`)
Plan reviewed: `docs/plans/LOG_VOLUME_TIERING.md` (added on this branch at `f7e8196`, unmodified since)
Diff reviewed: `git diff 03837a2..feature/log-volume-tiering` (20 commits; `main` is far behind
`staging`, so `main..HEAD` also carries the already-reviewed VIPTALK_ALERTING_V2 and
MODULE_DECOUPLING work — that is not this feature and is not assessed here)

Build: `mvn -o test` on JDK 21 — **BUILD SUCCESS**, 1808 tests, 0 failures, 0 errors.
`deploy.sh` is untouched by every commit on the branch (verified).

## Verdict

SEND_BACK_TO_DEV

Four of the six flagged deviations are correct and well-evidenced — three of them expose
genuine defects in the plan, which I have documented below as required amendments. The
send-back is for **one** thing: Phase 1 step 2's objective is not met. Four per-bot INFO
call sites whose rate scales with bot count are still at INFO, and one of them
(`ClientFactory.java:85`) fires on the *exact same code path*, one frame later, as the
`Bot.restart` line the plan told Dev to demote — so that demotion buys nothing. In the
same commit range, `CLAUDE.md` was rewritten to state the invariant those four lines
break, as "the rule that decides every level question".

I have **not** amended `docs/plans/LOG_VOLUME_TIERING.md`: the constraint on this role is
that the plan may only be edited under a `PLAN_AMENDED` verdict. The three amendments are
specified verbatim below so they can land alongside the fix in the next pass.

## Phase-by-phase

### Phase 0 — Config only: async, level-aware retention, longer window
Status: implemented (drifted on one property; plan was wrong — see Drift 1)

- Step 1 (AD-2) — `logging/log4j2.properties` committed, bind-mounted `:ro` at
  `/app/config/log4j2.properties`, `LOGGING_CONFIG=${LOGGING_CONFIG:-/app/config/log4j2.properties}`
  in compose. Both copies carry a header naming the other as its twin. ✔
- Step 2 (AD-3, AD-4) — `Async` appender wrapping `RollingFileAppender`, `bufferSize = 8192`,
  and the load-bearing `appender.async.appenderRef.type = AppenderRef` line is present.
  `log4j2.component.properties` added with `Discard` / `discardThreshold = DEBUG`.
  `blocking = true`, not the plan's `false` — see Drift 1. ✔
- Step 3 (AD-5) — console `ThresholdFilter` at `info`, `onMatch = NEUTRAL`,
  `onMismatch = DENY`. ✔
- Step 4 (AD-20) — `interval = 2`, `modulate = true`, filePattern untouched. ✔
- Step 5 — `Delete` block deliberately unchanged (`7d` / `10GB`), with the comment the plan
  asked for. ✔
- Step 6 (AD-6) — `retention_period: 720h` plus the two `retention_stream` selectors,
  verbatim. ✔
- Step 7 (AD-6) — promtail `drop` stage committed **commented out**, with the "this blinds
  Phase 2" comment. ✔

**Verified independently, not from Dev's summary:**

- The two `log4j2.properties` twins are byte-identical below their headers
  (`diff <(tail -n +14 bot-app/…) <(tail -n +16 logging/…)` → empty). Guarded in the build
  by `Log4j2TwinConfigTest`.
- The queue-full policy actually resolves. I compiled a probe against
  `log4j-core-2.24.1` with the shipped `log4j2.component.properties` on the classpath:

  ```
  policy = org.apache.logging.log4j.core.async.DiscardingAsyncQueueFullPolicy
    TRACE -> DISCARD   DEBUG -> DISCARD
    INFO  -> ENQUEUE   WARN  -> ENQUEUE   ERROR -> ENQUEUE
  ```

  The lowercase `log4j2.asyncQueueFullPolicy` / `log4j2.discardThreshold` keys the plan
  specified do normalize onto log4j2's `log4j2.AsyncQueueFullPolicy` /
  `log4j2.DiscardThreshold`, so AD-4 is genuinely in force. This was worth checking: a
  key that failed to normalize would have silently left `DefaultAsyncQueueFullPolicy`,
  which never discards, and combined with `blocking = true` would have made *every* DEBUG
  line block a bot thread — the opposite of AD-4.

### Phase 1 — A cheap, correct default level
Status: **partial**

- Step 1 (AD-7) — `logger.app.level = info` in both twins,
  `LOGGING_LEVEL_COM_VINGAME_BOT=${BOT_LOG_LEVEL:-INFO}` in compose, `BOT_LOG_LEVEL`
  documented in `secrets.env.example`. `LoggingLevelOverrideTest` pins that the override
  mutates the existing LoggerConfig and preserves `additivity=false` + both appenderRefs —
  i.e. the AD-7 gate is now answered in the build, not only at P1-1 on the box. ✔
- Step 2 — **partial. This is the send-back.** The five enumerated sites are demoted and
  fed into `GroupLifecycleAggregator`, and a sixth (`BotFactory` `Successfully created bot`)
  was correctly added. Four more remain at INFO — see Drift 4.
- Step 3 — `FleetRollupLogger` added in `bot-app/…/infrastructure/observability/`, 5-minute
  virtual-thread cadence, one env line, group lines only when unclean, `rounds`/`staked`
  drained from `SessionAggregationService.drainRollup()`. Emits nothing at all when no group
  is running. Drifted from AD-8's literal wording — see Drift 3. ✔ (as amended)
- Step 4 (AD-8) — both session summaries `log.info` → `log.debug`;
  `SessionAggregationServiceTest` asserts the level so it cannot be reverted by accident. ✔
- Step 5 — `CLAUDE.md` "Logging Guidelines" rewritten around the tier model; Spring Boot
  4.0.0 → 3.4.0 corrected; `/api/v1/logging` documented. ✔ *but* the invariant it now
  states as normative is false of the shipped code (Drift 4).

### Phase 2 — Scoped per-group DEBUG with TTL and auto-escalation
Status: implemented (drifted on the attach point; plan was wrong — see Drift 2)

- Step 1 — `ScopedDebugRegistry` in `bot-api`, `ConcurrentHashMap<String, Long>`,
  `volatile anyEnabled` fast path, `enable`/`isEnabled`/`sweep`. ✔
- Step 2 (AD-9/AD-10) — `ScopedDebugFilter extends AbstractFilter`, `NEUTRAL` on empty
  registry, `ACCEPT` for a scoped `botGroupId`, **never `DENY`**, `ThreadContext.get`
  direct. TRACE deliberately not promoted (AD-11's spirit; the plan is silent, this is the
  conservative reading). All filter overloads are implemented, which matters — `AbstractFilter`
  defaults each shape to `NEUTRAL` independently, so a partial override is a silently inert
  filter. ✔
- Step 3 — `ScopedDebugInstaller` `@Component` with `@PostConstruct`, 30 s virtual-thread
  sweep, re-install on `LoggerContext` config rebuild. Attached to the `Configuration`,
  not the `com.vingame.bot` LoggerConfig — see Drift 2. ✔ (as amended)
- Step 4 — `LogLevelController` at `/api/v1/logging`: `POST /debug/{id}?minutes=N`
  (default 15, cap 120), `DELETE /debug/{id}`, `GET /debug`. ✔
- Step 5 (AD-12) — all three triggers wired: watchdog expiry
  (`BettingMiniGameBot.onWatchdogExpiry`), reconnect rate (both sites that increment
  `bot_reconnects_total`, rolling window), and `dead/total` crossing half of
  `bot.group.dead.threshold` while still under it (`monitorHealth`). One INFO line per
  escalation, cooldown-limited to one per group per 15 min. ✔
- Step 6 — `SlotMachineBot` below-spin-cost logged on entry and exit transitions only,
  via `AtomicBoolean`. Message still contains `below spin cost`, so verification P2-4's
  grep still matches. ✔
- Step 7 — nine `bot.logging.scoped-debug.*` properties with defaults and rationale. ✔

**Mandatory TTL verified:** `ScopedDebugRegistry.enable` refuses null/blank ids and
non-positive TTLs, clamps anything above `MAX_TTL = 2h`, and there is no overload that
enables without an expiry. `DEFAULT_MAX_SCOPES = 50` stops scoped DEBUG being reassembled
into global DEBUG one call at a time.

### Phase 3 — Evidence shim
Status: implemented (one compatible refinement — see Drift 5)

- Step 1 — `evidence-shim/shim.py`, 784 lines, stdlib only. Newest-two-plus-live selection
  at execution time (AD-15), `os.link` only with an explicit comment refusing a `cp`
  fallback on `EXDEV` (AD-14), three passes coalesced per `groupLabels` key (AD-16/AD-17),
  `.pending.json` persistence, `.clean-shutdown` marker + boot-tagged retro-promotion
  (AD-21), age-then-size sweep on every pass and hourly (AD-19), `os.makedirs(exist_ok=True)`
  (AD-18). Live file linked as `console-live-<key>-<utc-ts>.log`, exactly the plan's name. ✔
- Step 2 — `evidence-shim/selftest.py` (639 lines), run inside the Maven build by
  `EvidenceShimSelfTestRunnerTest`. ✔
- Step 3 — `evidence-shim` service copies the `viptalk-shim` block: `python:3.12-alpine`,
  bind-mounted script `:ro`, `./logs:/logs` **read-write**, `user: ${HOST_UID}:${HOST_GID}`,
  `restart: unless-stopped`, `logging: *default-logging`, stdlib healthcheck, **no
  `depends_on: bot-manager`**. ✔
- Step 4 — `evidence` receiver + routes, `=` matchers only. ✔
- Step 5 — `AlertmanagerRoutingTest` extended. ✔

**`logs/evidence/` escapes both sweepers — verified:** promtail is still
`__path__: /logs/*.log` (non-recursive); log4j2's `Delete` is still `basePath /app/logs`,
`maxDepth = 1`, `ifFileName.glob = console-*.log`. `EvidenceRetentionEscapeTest` now fails
the build if either changes, and additionally pins `EVIDENCE_ROLLOVER_HOURS` to
`appender.rolling.policies.time.interval` — a coupling the plan named in AD-16 but did not
ask anyone to guard.

**Alertmanager delivery did not regress — verified by reading the route tree and by the
green guard test:**

| Alert | Receivers, in order |
|---|---|
| `BotManagerDown` | `evidence` (continue) → `viptalk-static-down` (continue) → `viptalk` |
| `EnvironmentGroupDead` | `evidence` (continue) → `viptalk` (terminal sibling) |
| `EnvironmentSocketDown` | `viptalk` only (matches no child, falls to the parent receiver) |

The AD-14 trap is avoided: every `evidence` route carries `continue: true` and every
alertname routed to it has a terminal `viptalk` sibling. The pre-existing
`AlertmanagerRoutingTest:167-173` assertion that `EnvironmentSocketDown` reaches **exactly**
`[viptalk]` still passes, and two new cases pin the `EnvironmentGroupDead` and
`BotManagerDown` fan-outs so the trap cannot be reintroduced silently.

## Drift

### Drift 1 — AD-3/AD-4 `blocking`: **Dev is right, the plan is wrong**

The plan's Phase 0 step 2 snippet says `appender.async.blocking = false`. Dev shipped
`true`. I verified against `log4j-core-2.24.1` sources, `AsyncAppender.append()`:

```java
if (!transfer(memento)) {
    if (blocking) {
        ...
        final EventRoute route = asyncQueueFullPolicy.getRoute(dispatcher.getId(), memento.getLevel());
        route.logMessage(this, memento);
    } else {
        error("Appender " + getName() + " is unable to write primary appenders. queue is full");
        logToErrorAppenderIfNecessary(false, memento);
    }
}
```

`asyncQueueFullPolicy` is consulted **only** on the `blocking` branch. With
`blocking = false` and no `errorRef` configured (none is), a full queue drops the event at
every level — ERROR included — leaving nothing but a status-logger line. AD-4's operative
sentence, "WARN/ERROR are never discarded and remain coupled to alerting", would have been
unimplementable as written.

AD-4 does contain a competing sentence — "Logging must never add latency to a bot thread" —
and `blocking = true` can block on `queue.put()` for INFO+. But AD-4 resolves its own
tension explicitly: *"if the choice is 'block a bot' or 'lose a DEBUG line', lose the
line"*, i.e. the no-latency guarantee is scoped to the discardable tiers. Dev's reading is
the correct one. (Incidentally the blocking is benign here: bot threads are virtual, and
`ArrayBlockingQueue` blocks on a `ReentrantLock`, which unmounts rather than pinning a
carrier thread.)

**Amendment required to the plan** (Phase 0 step 2 snippet and AD-4):

- In the Phase 0 step 2 code block, change `appender.async.blocking = false` to
  `appender.async.blocking = true`, and add to the "Gotcha" note: *"`blocking = true` is
  not optional. `AsyncAppender.append()` consults `asyncQueueFullPolicy` only on the
  blocking branch; with `blocking = false` a full queue drops the event at every level,
  ERROR included, and AD-4 has no effect."*
- In AD-4, qualify the second sentence: *"Logging must never add latency to a bot thread
  **for the discardable tiers**; INFO+ blocks rather than being lost."*

### Drift 2 — AD-9/AD-10 attach point: **Dev is right, and the plan had a silent-no-op defect**

This is the important one, and Dev's claim holds. From `log4j-core-2.24.1`,
`Logger.PrivateConfig` (Logger.java:585-594, and identically in all thirteen overloads):

```java
boolean filter(final Level level, final Marker marker, final String msg) {
    final Filter filter = config.getFilter();          // config is the CONFIGURATION
    if (filter != null) {
        final Filter.Result r = filter.filter(logger, level, marker, msg);
        if (r != Filter.Result.NEUTRAL) {
            return r == Filter.Result.ACCEPT;
        }
    }
    return level != null && intLevel >= level.intLevel();
}
```

`config` is the `Configuration` field declared at Logger.java:542, not `loggerConfig`
(declared at :540). The pre-level filter is therefore the **Configuration's**. A
LoggerConfig's own filter is reached later, in `LoggerConfig.log(LogEvent)` →
`isFiltered(event)` (LoggerConfig.java:646-650), which runs only *after* the level gate
has already discarded the DEBUG event and where `AbstractFilterable.isFiltered` can act on
`DENY` alone. So the plan's

```java
config.getLoggerConfig("com.vingame.bot").addFilter(filter)
```

would have compiled, installed, reported healthy through `GET /api/v1/logging/debug`,
and promoted exactly nothing. The whole of Phase 2 would have been a no-op that looked
like a success — the worst available failure mode.

Dev attached to the `Configuration` and restored the blast-radius limit with
`ScopedDebugFilter.SCOPED_LOGGER_PREFIX = "com.vingame.bot"`, gating on
`logger.getName()` / `event.getLoggerName()`. That is the right substitute: a Configuration
filter is consulted for every logger in the JVM, so without the prefix a scoped group's
thread would also have surfaced the Mongo driver's and Netty's DEBUG. Blast radius is
therefore **equivalent to the plan's intent, not wider** — promotion is still confined to
`com.vingame.bot.*`, and because that LoggerConfig exists by exact name with
`additivity = false`, promoted events land on the same appender set the plan assumed.
`ScopedDebugInstaller` additionally WARNs at startup if that LoggerConfig ever stops
existing, which is the one edit that would quietly change the meaning.

`ScopedDebugFilterInstallationTest` pins the whole chain end-to-end, including "a scoped
group does NOT promote third-party loggers", "every other group stays at INFO", "TRACE is
not promoted", and "the filter survives a `logging.level.*` override" — i.e. it would fail
if someone reverted to the plan's attach point.

**Amendments required to the plan:**

- AD-9, last-but-one sentence: replace `config.getLoggerConfig("com.vingame.bot").addFilter(filter)`
  with `ctx.getConfiguration().addFilter(filter)`.
- AD-10, first sentence: replace *"A LoggerConfig's filter runs before the level check
  (`Logger.PrivateConfig.filter` consults `config.getFilter()` first…)"* with *"The
  **Configuration's** filter runs before the level check — `Logger.PrivateConfig.filter`
  reads `config.getFilter()` where `config` is the `Configuration`, not the LoggerConfig
  (Logger.java:542, :586 in 2.24.1). A LoggerConfig's own filter runs only in
  `LoggerConfig.log(LogEvent)`, after the level gate, where only a `DENY` can still
  matter — attached there this filter is inert. The filter therefore hangs off the
  `Configuration`, and the blast radius the single LoggerConfig used to provide is
  restored by a `com.vingame.bot` logger-name prefix gate in the filter itself."*
- Implementation Notes, the bullet beginning *"A filter cannot resurrect an event the
  root logger has already dropped… The Phase 2 filter must be attached to the
  `com.vingame.bot` LoggerConfig"* — same correction; it repeats the same wrong premise
  and must not survive the AD-10 edit.
- Phase 2 step 3 — change the quoted `ctx.getConfiguration().getLoggerConfig("com.vingame.bot").addFilter(filter);`
  to `ctx.getConfiguration().addFilter(filter);` and add the prefix-gate requirement to
  step 2.

### Drift 3 — AD-8 vs Phase 1 steps 3/5: **a real internal contradiction; Dev's reading governs**

The plan contradicts itself. AD-8 says the tier-2 rollup "carries a downsampled
rounds-and-stake **line per group**". Phase 1 step 3 bullet 2 says a group line is emitted
"**only when that group is not clean**" because "volume scales with sickness, not fleet
size", and step 5 makes it normative that "INFO must not contain anything whose rate is a
function of bot count or round rate". A rounds-and-stake line for every group every 5
minutes is ~300 lines per cycle = ~1 line/s at the plan's own 300-group figure — a
fleet-size-scaled INFO class, which is precisely what Phase 1 exists to remove, and it
would have re-added at INFO a downsampled version of the very thing AD-8 demoted.

Dev folded the figures into the env line (aggregated over that environment's groups,
clean ones included) and onto the unclean-group detail lines. That satisfies AD-8's stated
purpose — "so AD-8's demotion loses no operational signal" — at a line rate that scales
with environments and sickness rather than with group count. Steps 3 and 5 govern; AD-8's
"per group" phrasing is the defect.

**Amendment required to the plan** — AD-8, final clause: replace *"and Phase 1's tier-2
rollup carries a downsampled rounds-and-stake line per group"* with *"and Phase 1's tier-2
rollup carries downsampled rounds-and-stake figures — aggregated onto each environment's
line, and additionally per group on the detail line an unclean group already gets. It must
**not** emit a line per group per cycle: at 300 groups that is ~1 line/s of INFO whose rate
is a function of fleet size, which step 5 forbids and which would re-create at INFO a
downsampled copy of what this AD demotes."*

### Drift 4 — Five vs six per-bot INFO sites: **Dev's sixth is right, but the enumeration is still incomplete. This is the send-back.**

Dev's demotion of `BotFactory` `Successfully created bot` is correct and well-argued: same
class, same rate, same path, one line below the two `initialized` twins.

But four more INFO call sites fire at a rate that is a function of bot count and are
untouched:

| Site | Line | Rate |
|---|---|---|
| `bot-engine/…/infrastructure/client/ClientFactory.java` | `:85` `Setting shared EventLoopGroup on client: {}` | **1/bot at start + 1/bot per restart + 1/bot per re-auth** |
| `bot-app/…/infrastructure/runtime/BotGroupRuntime.java` | `:220` `Bot starting in virtual thread {}` | 1/bot at group start |
| `bot-app/…/botgroup/service/BotGroupBehaviorService.java` | `:769` `Bot {}: assigned strategy {}` | 1/bot at group start |
| `bot-app/…/botgroup/service/BotGroupBehaviorService.java` | `:782` `Bot {}: assigned slot strategy {}` | 1/slot bot at group start |

`ClientFactory.java:85` is the serious one. `clientFactory.newClient(...)` is called from
`Bot.java:294` (initialize), `Bot.java:363` (`restart()`) and `Bot.java:824` (re-auth), and
the INFO fires inside the builder lambda on every call. So:

- It **cancels the demotion the plan explicitly asked for.** `Bot.restart()` now logs
  `restart requested` at DEBUG (`Bot.java:357`) — and three lines later calls
  `newClient`, which logs at INFO. Periodic logout restarts one bot per group per cycle;
  the plan costed that at "a standing ~8 lines/s at 30k bots". That ~8 lines/s is still
  there, just under a different message.
- It also fires on every full reconnect. The project's own field data (TIP prod, ~230
  reconnects/bot/day at 5 bots) makes this the largest single INFO class remaining at
  fleet scale — larger than the ~0.7 GB/day session-summary class that AD-8 went to the
  trouble of demoting.

The other three are start-time only, so they do not move the steady-state floor, but a
30k-bot fleet start still emits ~90k INFO lines from them. `BotGroupBehaviorService:769`
carries a comment that argues *for* INFO on exactly the grounds this plan overturns
("N bots = N lines at start, mirrors the 'Bot starting in virtual thread' line emitted from
BotGroupRuntime at the same scale") — that is BETTING_STRATEGIES AD-14, which
LOG_VOLUME_TIERING supersedes and should say so, the same way it supersedes the old
`CLAUDE.md` session-summary guideline.

This is not a plan defect. Phase 1 step 5 states the invariant correctly and Dev applied it
correctly to a sixth site; the plan's *finding* was simply not exhaustive, and a correct
implementation was available. What makes it a compliance failure rather than a note is
that the same commit range rewrote `CLAUDE.md` to present that invariant as **"the rule
that decides every level question"** while shipping four counterexamples — the guideline is
now false of the code it documents.

**What should happen:**

1. Demote all four to DEBUG, keeping the MDC tag (all four already run inside a
   `BotMdc`-populated scope). `ClientFactory.java:85` is arguably better as a one-shot
   per-environment line at client-registry creation, since the identity hash it prints is
   constant for the life of the `EventLoopGroup` — that would preserve the diagnostic
   entirely.
2. Note in `CLAUDE.md` that this supersedes BETTING_STRATEGIES AD-14 for the two
   `assigned strategy` lines, and update the stale comment at
   `BotGroupBehaviorService.java:2002`, which still refers to *"the subsequent
   `Bot {userName}: restart requested` **INFO** line at Bot.java:176"* — that line is now
   DEBUG at `Bot.java:357`.
3. Consider a cheap guard in the spirit of `Log4j2TwinConfigTest`: a test that scans
   `bot-app`/`bot-engine` sources for `log.info` inside known per-bot classes, so the next
   per-bot INFO line fails the build instead of the guideline. Optional, but this is the
   second enumeration of these sites to come up short.

### Drift 5 — Phase 3 live-file naming: **compatible refinement, no plan defect**

The plan (Phase 3 step 1) says to link the live file under
`console-live-<key>-<utc-ts>.log` and catch `FileExistsError` for idempotence. Dev keeps
exactly that name — timestamped from first sight of the incident, so it is stable per
incident — and adds one case: when the destination name already exists but holds a
**different inode** and the source is the live file, link under a numbered sibling
(`…-2.log` … `…-9.log`) instead of skipping. `same_inode` → skip is unchanged, so repeats
of the same pass stay free.

Checked against AD-14/AD-15/AD-16's intent, this is right and is not a deviation worth
amending:

- AD-14's stated purpose for linking the live file is "we capture precisely the
  post-incident tail". For an incident still pending across a rollover — a group dead for
  hours redelivering every 5 min, or a crash loop retro-promoting on each boot — log4j2
  has renamed `console.log` and created a new inode. A bare `FileExistsError` skip pins
  the pre-boundary inode only and loses the post-boundary tail, which is the exact data
  AD-14 exists to capture.
- It does not weaken AD-16's idempotence claim: same inode still skips, so the +5 min and
  rollover+120 s passes remain no-ops in the ordinary case.
- It does not touch AD-15's selection rule, and it costs zero blocks (still `os.link`), so
  AD-14's "never `cp`" is intact. Growth is bounded at 8 siblings per incident and the
  AD-19 sweep covers them.

Dev's framing ("a stable name refuses the new inode") is accurate; the plan simply did not
consider an incident outliving a rollover. The plan's text is not *wrong*, only silent, so
no amendment — but it is worth a line in the release notes.

### Drift 6 — Architect-authored deviations: still coherent

- **AD-6 (promtail `drop` shipped disabled).** Shipped commented out with the "enabling
  this blinds Phase 2" comment, and the primary control (`retention_stream`, 24 h
  DEBUG/TRACE, 720 h WARN/ERROR, 720 h default) is in place. Post-implementation this holds
  up better than it did on paper: Phase 2 landed, so a `drop` on `{level=~"DEBUG|TRACE"}`
  really would make `/api/v1/logging` produce lines nobody can query in Grafana. The
  promtail `labels` stage that `retention_stream` depends on is now called out in a comment
  in both files. Coherent. ✔
- **AD-2 / AD-5 (additions to the agreed Phase 0 scope).** Both landed as described and
  both earn their place: AD-2's mount is what makes Phase 1's level change and every later
  retention tune a `docker compose restart`; AD-5's `ThresholdFilter` is what keeps scoped
  DEBUG (Phase 2) off stdout and out of the 50m×5 json-file cap. Note for the release
  report: the compose mount means a host where `logging/log4j2.properties` is absent gets a
  *directory* created at that path and the app fails to start. That is the intended loud
  failure and is documented in both the compose comment and the mounted file's header — but
  it is a new first-deploy requirement on every box.

## Out-of-scope changes

None material. Everything in the diff traces to a plan step or an AD. Specifically checked:

- `bot-api/pom.xml` — adds `log4j-core` at `provided` scope. Not named in the plan, but
  unavoidable: Phase 2 step 2 puts `ScopedDebugFilter extends AbstractFilter` in `bot-api`.
  `provided` is the right scope (keeps the contract module from imposing a logging backend
  on consumers) and does not disturb `pom.xml:110-126`'s pinned `annotationProcessorPaths`,
  which is what AD-9 turns on.
- `bot-strategies/src/test/resources/log4j2-test.xml`, `bot-app/src/test/resources/log4j2-test.properties`
  — test logging contexts needed by the new filter/installer tests.
- Test-constructor churn in `BotFactory*Test` / `BotGroupBehaviorService*Test` — mechanical,
  follows the two new `BotFactory` constructor parameters.
- `deploy.sh` is **not** in the diff. Confirmed, per the standing rule and the plan's own
  Implementation Note that `logs/evidence/` must be created by the shim rather than by
  `deploy.sh` — which is what `ensure_dir()` does.

## Verification section — achievable?

Yes, with two corrections to note for the Releaser:

- Every step's grep target exists in the shipped code. P2-4's `below spin cost` survived
  the message rewrite (`… below spin cost {} ({} x {}) — pausing spins`); P2-5's
  `scoped debug escalat` matches `scoped debug escalated for group …`; P1-5's
  `bots initialized` / `bots auto-deposited` match `GroupLifecycleAggregator`'s two lines;
  P1-6/P1-7's `env … groups=` / `group … playing=` match `FleetRollupLogger`.
- **P1-4 is now too narrow.** It greps for
  `initialized: game=|triggering deposit|restart requested` and will report `0` — while the
  four sites in Drift 4 are still emitting. It should also count
  `Bot starting in virtual thread|assigned strategy|Setting shared EventLoopGroup`. As
  written, P1-4 would have passed on this diff and hidden the defect.
- **P0-2** expects a thread named `AsyncAppender…`; with `appender.async.name = AsyncRolling`
  the thread is `AsyncAppender-AsyncRolling`, truncated to `AsyncAppender-` in
  `/proc/*/comm`. The step already anticipates the 15-char truncation. Fine.
- P0-6 (disk size) and P0-8 (T+25 h retention bite) remain genuinely deferred to the box;
  nothing in the diff can pre-empt them.

## Amendments to the plan

None made. Three are **required** and are specified verbatim under Drift 1, Drift 2 and
Drift 3 above — the `blocking` snippet, the AD-9/AD-10/Implementation-Note attach point
(including the duplicate wrong premise in the Implementation Notes), and AD-8's "line per
group". All three are falsifiable technical errors, two of them verified directly against
`log4j-core-2.24.1` sources and one against the plan's own internal consistency, and Dev's
code is right in all three cases.

They are not applied here because this role may only edit the plan under a `PLAN_AMENDED`
verdict, and the verdict is `SEND_BACK_TO_DEV`. They should land together with the Drift 4
fix, so the plan and the code agree in one pass rather than two. If Architect-1 disagrees
with any of the three, the code — not the plan — is what is currently correct, and the
burden is on the plan to show otherwise.
