# QA — LOG_VOLUME_TIERING

> **Phases 0–3** were passed in the first section below and are not re-litigated.
> **Phase 4** (commits `a268929`, `e97dcb2`, `118a10b`, `68cc9dd`) is reviewed in
> **[`## Phase 4 — two tracks`](#phase-4--two-tracks-a268929--68cc9dd)** at the bottom of
> this file, which carries the current verdict and build figures.

## Phases 0–3

**Verdict:** PASS
**Build:** `mvn test` → 1822 tests, 0 failures, 0 errors, 0 skipped
(bot-api 123 · bot-strategies 111 · bot-messages 136 · bot-engine 412 · bot-app 1040)

Dev's reported "1023 tests" is the **bot-app** module total; the reactor total on the
branch before my additions was 1800. I added 22 tests across two modules.

Nothing was skipped, which matters here: `EvidenceShimSelfTestRunnerTest` and
`VipTalkShimSelfTestRunnerTest` both `Assumptions.assumeTrue(python3Available())`, so a
"green" build on a box without `python3` would silently not have run the shim suites at
all. On this machine they ran.

---

## Tests added / updated

| File | Covers |
|---|---|
| `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/Log4j2TestConfigShapeTest.java` | The **third** log4j2 copy (`bot-app/src/test/resources/log4j2-test.properties`) keeps production's logger/appender graph — the shape `LoggingLevelOverrideTest` and `ScopedDebugFilterInstallationTest` both assert against |
| `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/AsyncQueuePolicyTest.java` | AD-3/AD-4/AD-5: both loggers route *through* `AsyncRolling`; `blocking = true` is coupled to `log4j2.component.properties`' `Discard`/`DEBUG`; the console `ThresholdFilter`; the JSON layout Loki's pipeline parses |
| `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/LogRetentionPipelineTest.java` | AD-6 parsed structurally: `retention_period: 720h`, both `retention_stream` selectors, `compactor.retention_enabled`, promtail promoting `level`/`botGroupId` to **labels**, the DEBUG `drop` stage present-but-inactive, and exactly one `__path__` |
| `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/LoggingComposeWiringTest.java` | AD-2/AD-7 delivery: `LOGGING_CONFIG` resolves to the path the `./logging/log4j2.properties` mount lands on (`:ro`), and `LOGGING_LEVEL_COM_VINGAME_BOT=${BOT_LOG_LEVEL:-INFO}` is actually passed |
| `bot-app/src/test/java/com/vingame/bot/domain/alert/AlertmanagerRoutingTest.java` (updated, +1 test) | **Every** alert rule enumerated from `prometheus/alerts.yml` still reaches the `viptalk` receiver — the general form of the AD-14 trap, for alertnames nobody has written yet |
| `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/PerBotInitLogLevelTest.java` | P1-4: both `initialized` lines are DEBUG (not INFO); the group aggregator is fed exactly once per bot; the feed runs with `botGroupId` MDC already applied, without which `recordInitialized` silently discards it |

---

## Coverage of the diff

- `bot-api/.../ScopedDebugFilter.java` ← `ScopedDebugFilterTest` (all 14 overloads, event-vs-thread context, TRACE exclusion) + `ScopedDebugFilterInstallationTest` (end-to-end)
- `bot-api/.../ScopedDebugRegistry.java` ← `ScopedDebugRegistryTest` (TTL, cap, clamp, sweep, extend-never-shorten)
- `bot-app/.../ScopedDebugInstaller.java` ← `ScopedDebugFilterInstallationTest` (attach point, idempotence, survives the level-override path)
- `bot-app/.../LogLevelController.java` ← `LogLevelControllerTest` (200/400/404 surface, cap refusal, not-armed refusal)
- `bot-app/.../FleetRollupLogger.java` ← `FleetRollupLoggerTest` (one line per env, group line only when unclean, MDC, drain)
- `bot-engine/.../GroupLifecycleAggregator.java` ← `GroupLifecycleAggregatorTest` + new `PerBotInitLogLevelTest`
- `bot-engine/.../ScopedDebugEscalator.java` ← `ScopedDebugEscalatorTest` (three triggers, cooldown, master switch, registry cap)
- `bot-engine/.../SlotMachineBot.java` (spin-cost) ← `SlotMachineBotSpinCostLogTest` (100 evaluations → 1 line, both transitions, funded bot silent)
- `bot-engine/.../SessionAggregationService.java` ← `SessionAggregationServiceTest` (AD-8 level asserted as DEBUG, not merely ignored)
- `log4j2.properties` ×2 ← `Log4j2TwinConfigTest` + new `AsyncQueuePolicyTest`; ×3 with the new `Log4j2TestConfigShapeTest`
- `loki-config.yaml`, `promtail-config.yml` ← `EvidenceRetentionEscapeTest` + new `LogRetentionPipelineTest`
- `evidence-shim/shim.py` ← `evidence-shim/selftest.py` (20 cases) via `EvidenceShimSelfTestRunnerTest`
- `alertmanager/alertmanager.yml` ← `AlertmanagerRoutingTest` (4 evidence cases + the new all-rules guard), `AlertPipelineWiringTest` (3 new evidence-shim cases)
- `docker-compose.yml` ← `AlertPipelineWiringTest` + new `LoggingComposeWiringTest`

---

## The five silent-failure areas, checked by mutation

I did not take "a test exists" as evidence. Each of these was verified by breaking the
production artefact, watching the build go red, and restoring it. The working tree is
clean of all of it (`git status` shows only the pre-existing `deploy.sh` and
`VIPTALK_ALERTING_V2/release.md`).

**1. The log4j2 filter attach point — GENUINE.**
Reverted `ScopedDebugInstaller` to AD-9's `configuration.getLoggerConfig(APP_LOGGER).addFilter(filter)`:
`ScopedDebugFilterInstallationTest` went **6 of 9 red**, including
`scopedGroupGetsDebugThroughAnInfoLogger` on the assertion *"ACCEPT must beat the level
check — that is the whole mechanism"*. So the test proves promotion end-to-end (a DEBUG
event, from a scoped group, reaching a real `AbstractAppender` through a LoggerConfig
asserted to be at `Level.INFO`), not merely that a filter object is attached — the
attached-object case is exactly what the AD-9 spelling produces, and it fails.

**The prefix gate — GENUINE.** Replaced `isScopedLogger(...)` with `return true`:
`thirdPartyLoggersAreNotPromoted` failed on the **Mongo driver** assertion
(`org.mongodb.driver.protocol`). So the third-party block is proved, not assumed.

**2. The three log4j2 copies.** `Log4j2TwinConfigTest` covers the two shipped ones and is
sound (header-fenced diff, plus a non-vacuity check). The **test-scoped** copy was
unguarded — this is the gap the brief anticipated. Its logger shape is currently correct
(same logger name, `info`, `additivity=false`, both appenderRefs named
`ConsoleAppender`/`AsyncRolling`, the `.type = AppenderRef` line), and the new
`Log4j2TestConfigShapeTest` now pins the whole graph key-by-key against the shipped file,
with the file path enumerated as the one intended difference. Without it,
`LoggingLevelOverrideTest` and `ScopedDebugFilterInstallationTest` could both drift into
proving things about a configuration the app never runs.

**3. `EvidenceRetentionEscapeTest` — GENUINE.**
Set `delete.maxDepth = 2` and `__path__: /logs/**/*.log`: **2 of 4 red**, with the
intended messages. It would fail the build on either change.

**4. The shim selftest runner — GENUINE.**
Replaced `os.link(` with `shutil.copyfile(` in a scratch copy of `shim.py`:
`selftest.py` printed **8 FAILED** and exited **1**. All three of the runner's assertions
(exit code zero, output contains `all checks passed`, output does not contain `  FAIL `)
fire on that. It cannot swallow a failure. The hardlink assertions are inode-level, not
existence-level — `equal(after_link.st_ino, before.st_ino, ...)` and
`equal(after_source.st_nlink, 2, ...)`, plus the same for the rolled file and a check that
the *new* live inode after a rollover is among the pinned set.

**5. Alertmanager routing — GENUINE, and now generalised.**
Deleted the `EnvironmentGroupDead → viptalk` sibling: **2 red**, including my new
`everyAlertRuleStillReachesVipTalk`. VipTalk delivery is proved intact for all three
named alerts — `EnvironmentGroupDead` (evidence **and** viptalk), `BotManagerDown`
(`containsExactly(evidence, viptalk-static-down, viptalk)`) and `EnvironmentSocketDown`
(`containsExactly(viptalk)`). The new test extends that to all 16 rules in
`prometheus/alerts.yml`, so the next `continue: true` route added for any alertname
cannot silently eat its delivery.

---

## Which of the plan's 34 phase verification steps are provable in the build

"Build" = fails `mvn test` if broken. "Box" = genuinely needs the running stack; the
releaser is on the hook for these.

### Phase 0 — 1 build / 7 box (5 with strong build backing)

| Step | Where | Notes |
|---|---|---|
| P0-1 mounted config in effect | **Box** | Build now proves the twin, the mount path and `LOGGING_CONFIG` agree (`LoggingComposeWiringTest`). Only "the container picked it up" is left. |
| P0-2 async appender thread exists | **Box** | Strongly backed: the `.type = AppenderRef` spelling and both appenderRefs are pinned (`AsyncQueuePolicyTest`), and every Spring-context test starts a real log4j2 context with the Async wrap — a mis-built graph throws in the build. |
| P0-3 file still growing | **Box** | Runtime only. |
| P0-4 console carries no DEBUG | **Box** | The `ThresholdFilter` block is pinned; the observed behaviour is not. |
| P0-5 rollover on even hours | **Box** | `interval = 2` / `modulate = true` pinned in both twins and cross-checked against the shim's `EVIDENCE_ROLLOVER_HOURS`. Needs a real boundary. |
| P0-6 disk headroom (**PRE-RAMP GATE**) | **Box only** | Nothing in the build can know the disk size. This is still the plan's own stated blocker. |
| P0-7 Loki accepted the split | **Box** | Config content pinned (`LogRetentionPipelineTest`); Loki's acceptance of it is not. |
| P0-8 the split actually bites (T+25 h) | **Box only** | Deferred, wall-clock. |

### Phase 1 — 4 build / 4 box

| Step | Where | Notes |
|---|---|---|
| P1-1 env-driven level took | **Build (mostly)** | `LoggingLevelOverrideTest` proves the relaxed binding *and* the in-place LoggerConfig mutation, in both directions; `LoggingComposeWiringTest` proves compose passes the name. Still worth the one `curl` on the box. |
| P1-2 appenders survived the override | **Build** | Asserted directly, and now against a config shape pinned to production's. |
| P1-3 INFO line rate at the new floor | **Box only** | Needs a running fleet. |
| P1-4 per-bot INFO classes gone | **Build (partial)** | New `PerBotInitLogLevelTest` pins both `initialized` lines at DEBUG. `Bot.restart()`'s *"restart requested"* and the two *"triggering deposit"* lines are **not** level-pinned in the build — see Gaps. |
| P1-5 aggregated replacements present | **Build** | `GroupLifecycleAggregatorTest` pins one line per group, the count, the shortfall case and the MDC. |
| P1-6 tier-2 rollup emitting | **Build** | `FleetRollupLoggerTest`; the 5-minute cadence itself is box. |
| P1-7 tier-2 quiet about healthy groups | **Build** | Both directions covered (healthy → 1 line; unclean → +1). |
| P1-8 session summaries demoted | **Build** | Levels asserted as `DEBUG` in `SessionAggregationServiceTest`. |

### Phase 2 — 5 build / 1 box

| Step | Where | Notes |
|---|---|---|
| P2-1 enable a scope over REST | **Build** | `LogLevelControllerTest`. |
| P2-2 DEBUG flows for that group only | **Build** | End-to-end through a real appender; mutation-verified above. Box adds the JSON file and the Loki `botGroupId` label. |
| P2-3 the TTL expires | **Build** | Registry expiry + the filter going NEUTRAL again. The 30 s sweeper cadence is box. |
| P2-4 spin-cost spam bounded | **Build** | 100 gate evaluations → 1 line, both transitions, funded bot silent. |
| P2-5 auto-escalation fires | **Box** | The trigger logic is fully unit-covered; the *wiring* from a real watchdog expiry / reconnect into it is only structurally covered. Worth one real observation. |
| P2-6 no escalation storm | **Build** | Cooldown proved in both directions. |

### Phase 3 — 7 build / 5 box

| Step | Where | Notes |
|---|---|---|
| P3-1 shim up and healthy | **Box only** | Container lifecycle. |
| P3-2 evidence dir exists and is writable | **Box only** | uid/gid on the host. `AlertPipelineWiringTest` pins `user: ${HOST_UID}:${HOST_GID}` and the read-write `./logs:/logs`. |
| P3-3 a synthetic alert promotes files | **Build** | `selftest.py` posts over a loopback socket and asserts the promotion. |
| P3-4 they are HARDLINKS | **Build** | Inode + link-count assertions, mutation-verified. |
| P3-5 not re-ingested by promtail | **Build** | Config-level (both the glob's spelling and the parsed scrape set). The Loki query is box. |
| P3-6 idempotent and coalesced | **Build** | `selftest.py`. |
| P3-7 the deferred pass runs | **Build** | Deadline logic; wall-clock behaviour is box. |
| P3-8 survive log4j2's `Delete` | **Build** | `maxDepth = 1` / `basePath` pinned (mutation-verified) + the shim leaves nothing in the swept top level. A real rollover is box. |
| P3-9 unclean start retro-promotes | **Build** | `selftest.py` (marker present and absent). |
| P3-10 the sweep bounds the directory | **Build** | Age and size guards. |
| P3-11 `amtool config routes test` | **Build (equivalent)** | Our route walker reproduces Alertmanager's consume/continue semantics and is mutation-verified. `amtool` on the box is still worth running once, because it is the *real* implementation and our walker is a model of it. |
| P3-12 guard test passes in the build | **Done** | Green in this run. |

**Totals: 17 of 34 provable in the build, 17 needing the box** (of which 12 have config-
level backing in the build and 5 — P0-3, P0-6, P0-8, P3-1, P3-2 — are purely operational).
P0-6 remains the plan's own pre-ramp gate and is not a QA-satisfiable item.

---

## Gaps

- **`Bot.restart()`'s *"restart requested"* and the two *"triggering deposit"* lines are
  not level-pinned.** They are demoted correctly in the diff (verified by reading it), but
  no test would fail if they went back to INFO. `restart()` immediately closes the client,
  builds a new one and calls `start()`, and the deposit-trigger sites need a subscribed
  bot with a seeded low balance — both are more fixture than the assertion is worth, and
  the two `initialized` lines (the 30k-lines-at-fleet-start class) *are* pinned. Verify on
  the box with P1-4's grep.
- **The AsyncAppender's discard behaviour under a genuinely full queue is not exercised.**
  The build pins the configuration coupling (`blocking = true` ↔ `Discard`/`DEBUG`); it
  does not fill an 8192-entry queue and observe that DEBUG is dropped and ERROR is not.
  That is a load test, not a unit test.
- **`retention_stream` is proved as configuration, never as behaviour.** P0-8's 25-hour
  observation is the only real proof and cannot be built.
- **The scoped-debug sweeper's 30 s cadence and the escalator's real trigger wiring**
  (an actual watchdog expiry arming a scope) are structurally covered but never observed
  end-to-end. P2-5.
- **`ScopedDebugFilterInstallationTest` mutates the JVM-global `LoggerContext`.** It calls
  `installer.install()` in `@BeforeEach` precisely because other cached Spring contexts in
  the module install their own filter onto the same context. That is handled and the suite
  is green, but it is the one place in this feature where test isolation depends on an
  explicit re-assert rather than on the framework. Worth remembering if a future test in
  `bot-app` starts touching log4j2 configuration.
- **`promtail-config.yml`'s `drop` stage guard is structural.** A future edit that adds a
  *second* scrape job under `/logs/` would be caught by the new one-`__path__` assertion,
  but an edit that changes the drop stage's selector while leaving it commented out would
  not be — it is inert either way.
- **Everything about the shim's interaction with the real `docker compose` network**
  (P3-1, P3-2) is container-level. The Python suite runs on loopback with a tmpdir.

## Failures

None.

---

# Phase 4 — two tracks (`a268929` … `68cc9dd`)

**Verdict:** PASS
**Build:** `mvn test` → **1856 tests, 0 failures, 0 errors, 0 skipped**
(bot-api 125 · bot-strategies 111 · bot-messages 136 · bot-engine 416 · bot-app 1068)

Dev's tree was 1849/0/0/0 and reproduced here before I touched anything. I added **7
tests** (one class) and fixed **two flakes** — one Dev reported, one Dev did not know
about and which fails the build for ~2.5% of all wall-clock times of day.

Skips remain **zero**, which still matters: `EvidenceShimSelfTestRunnerTest` and
`VipTalkShimSelfTestRunnerTest` are the only tests that could vanish silently, and both
ran.

---

## The main gap Dev identified: nothing started the shipped file. Closed.

`Log4j2TestConfigShapeTest` compares **keys**; `AsyncQueuePolicyTest` and
`EvidenceRetentionEscapeTest` compare **strings**; `Log4j2TwinConfigTest` compares the two
shipped copies **to each other**. Nothing parsed the shipped graph, so a value-level
defect — a misspelled `Delete` element type, an unparseable layout pattern, a missing
`.type = AppenderRef`, an `appenderRef` naming an appender that does not exist — passed
the whole build and first appeared as bot-manager not coming up.

**New:** `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/ShippedLog4j2ConfigRoutingTest.java`
rewrites `/app/logs` → `target/log4j2-routing/<twin>/logs` in **each shipped twin**, starts
a **private** `LoggerContext` against the rewritten copy (never `LogManager`'s, so no other
test's logging is disturbed), emits one uniquely-marked event per interesting logger, stops
the context to flush, and reads both files back. 7 tests:

| Test | Asserts |
|---|---|
| `theShippedConfigurationStarts` | No `ConfigurationException`; **no log4j2 status-logger ERROR** on the calling thread; all five named appenders exist **and started** (recorded while the context is up — after `stop()` everything reports `!isStarted()`) |
| `theLibraryLandsInTrackTwoOnly` | ws-parser markers in `detail.log`; **absent** from `console.log`, which also contains no `websocketparser` at all; and the resolved `LoggerConfig` is named exactly `com.vingame.websocketparser`, `additive == false`, appenders `containsExactly("AsyncDetail")` — that last is how "the console carries no ws-parser line" is proved (see Gaps for why not by capturing stdout) |
| `debugNeverReachesTheTrackLokiIngests` | With the app logger mutated to DEBUG **in place, the way Spring Boot's `setLogLevel` does it**, the DEBUG marker is in `detail.log` and **not** in `console.log`; and the MDC renders as `[qa-group-42/`, i.e. AD-24's drill-in grep works. Both halves, for P4-3's stated reason |
| `applicationInfoIsWrittenToBothTracks` | App INFO and a third-party root INFO in **both** files; `com.vingame.bot` and root each resolve to exactly `{ConsoleAppender, AsyncRolling, AsyncDetail}`; `additive == false` on the app logger |
| `eachTrackIsWrittenInItsOwnLayout` | Track 1's first line is JSON with `"level":"INFO"`; track 2 has **no** JSON line, carries the full logger name, and pads `%-5level` so `grep -E ' (DEBUG\|TRACE) '` works |
| `theDetailDirectoryIsCreatedByTheAppender` | Nothing pre-creates `logs/detail`; log4j2's `FileManager` does — so the deploy note's `mkdir -p logs/detail` is belt-and-braces, not a load-bearing manual step |
| `bothDeleteBlocksResolveToTheAnchorsTheClaim` | Each track's `DefaultRolloverStrategy` carries **exactly one** custom action, it **is** a `DeleteAction`, and its resolved `getBasePath()` / `getMaxDepth()` / `IfFileName` glob / `IfLastModified` age / `IfAccumulatedFileSize` threshold are `logs`+`console-*.log`+14 d and `logs/detail`+`detail-*.log`+12 h+10 GB |

Both twins are run — the mounted copy is what `LOGGING_CONFIG` selects, the in-jar copy is
the fallback that runs when the mount is missing, and a broken fallback is exactly AD-2's
"the app went quiet".

**Mutation-verified — 8 for 8**, each applied to `logging/log4j2.properties`, run, reverted:

| Mutation | Result |
|---|---|
| Drop `appender.asyncdetail.appenderRef.type` | **ERROR** — `ConfigurationException: No type attribute provided for component appenderRef`. The build now reproduces the exact container-start failure the plan calls "the same trap, second appender" |
| `appender.async.filter.threshold.level` `info` → `debug` | `debugNeverReachesTheTrackLokiIngests` red |
| Drop `logger.wsparser.additivity` | `theLibraryLandsInTrackTwoOnly` red on "98.7% of INFO volume walks back in" |
| `…delete.ifFileName.type = IfFileNam` | **2 red** — the status-error listener *and* the resolved-`DeleteAction` check. This is the exact defect class the brief named as passing today |
| Unclosed `%X{botGroupId` in the detail pattern | red on the MDC assertion |
| Drop `logger.app.appenderRef.detail.ref` | 3 red |
| `appender.detail.fileName` → `/app/logs/detail.log` (out of the subdirectory) | 5 red |
| `logger.wsparser.appenderRef.detail.ref` → `AsyncRolling` | 2 red |

---

## Verifications the brief asked for

**1. The new 4a/4b assertions are discriminating — confirmed independently, not taken on
report.** I restored `logging/log4j2.properties`, `bot-app/src/main/resources/log4j2.properties`,
`docker-compose.yml` and `promtail-config.yml` to their pre-4a state (`5350b12`), left the
test copy at Phase 4, and ran the five guard classes plus my new one: **20 failures**, of
which **13** are Dev's:

- `AsyncQueuePolicyTest` **4** — three loggers/refs, the blocking pair, the track-1
  `ThresholdFilter`, the two layouts;
- `EvidenceRetentionEscapeTest` **4** — track 2's `Delete` anchor, track 2 living in a
  subdirectory, the shared rollover boundary, the shim's detail directory;
- `Log4j2TestConfigShapeTest` **3** — the graph diff, the anti-vacuity case, the two
  intended differences;
- `LoggingComposeWiringTest` **1** — `LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER`;
- `LogRetentionPipelineTest` **1** (4b) — the positions path + mount pair, which fails
  precisely on the old `/tmp/positions.yaml`, as the plan asked.

Tree restored afterwards; `git status` clean of it.

**2. `AsyncQueuePolicyTest` pins both `blocking` values together — confirmed, and the
failure message says so.** `theTwoTracksBlockDifferentlyOnPurpose` asserts
`appender.async.blocking = true` and `appender.asyncdetail.blocking = false` in **one**
method. Mutation both ways:

- `asyncdetail.blocking` → `true`: **1 red**, message opens
  *"THIS IS NOT THE AD-3/AD-4 DEFECT AND MUST NOT BE \"FIXED\" TO true"* and carries the
  JVM-wide-`discardThreshold` reasoning;
- `async.blocking` → `false`: **2 red** — the pair test *and* the older
  `theDiscardPolicyIsActuallyReachable`.

So "fixing" either one reads as breaking the other, which is what was asked. The same
inversion is additionally pinned across files by `Log4j2TestConfigShapeTest`'s
`appender.asyncdetail.blocking` shape key, and stated in the properties file, the test copy
and `CLAUDE.md`.

**3. The shim's two-track selftest additions — checked by mutating `shim.py` in a scratch
copy and re-running `selftest.py`:**

| Mutation | Result |
|---|---|
| Sweep merges the two classes into one pool ordered by promotion time | `test_the_byte_guard_evicts_detail_before_aggregates` **red**, twice |
| Detail files swept at `max_age_days` instead of `detail_max_age_days` | `test_the_two_sweep_ages_are_independent` **red** |
| `_live_names` rejects the legacy single-`liveName` string | `test_load_pending_accepts_a_pre_phase_four_entry` **red**, plus a second case |
| `candidates()` loses its `isdir` guard for a missing track directory | **PASSED — a real gap. Closed, see below.** |

**Detail-first eviction is *not* vacuous.** The case constructs the aggregate as the
**older** file (`promoted_at = now − 10 000`) and the detail as the **newer** (`now`), with
`EVIDENCE_MAX_BYTES=150` against 100 + 100 bytes, then asserts the surviving file is the
**aggregate**. A promotion-time-only eviction takes the aggregate and fails — which is
exactly what my first mutation showed.

Per-track selection is inode-checked (`st_ino` of the promoted detail link equals the live
`detail.log`'s), the two closed files and two live links are each named, and each track's
ancient period is asserted **absent**.

**4. `EvidenceRetentionEscapeTest` covers `logs/detail/` the same way it covers
`logs/evidence/`.** The non-recursive-glob half is the shared, pre-existing
`promtailDoesNotScrapeSubdirectoriesOfTheLogsMount` (`__path__: /logs/*.log`, and neither
`/logs/**` nor `/logs/*/`), which protects both subdirectories at once; the new
`theDetailTrackLivesBelowTheScrapedDirectory` supplies the other half — that
`appender.detail.fileName` really is under `/app/logs/` **and** contains a further `/`. My
routing test then closes the loop behaviourally: with `fileName` moved up one level, the
detail file stops existing where the shim and promtail expect it and 5 assertions fail.
(Cosmetic: the class-level `@DisplayName` still says "logs/evidence/ escapes both
sweepers"; it now covers three things.)

**5. Dev's deliberate deviation from plan 4a step 2 — RULED SUFFICIENT.** The plan said
apply 1a–1d "verbatim" to the test copy, which would have written `/app/logs/detail` into a
file whose entire purpose is to name no container path; Dev used `appender.detail.strategy.max = 2`
with no `Delete` and added `theTestCopyNeverNamesTheContainerPath`, which scans **every
value** in the test copy for `/app/`. Sufficient, on four grounds:

- it matches the file's **pre-existing precedent** for track 1, which has carried
  `strategy.max = 2` and no `Delete` since Phase 0 for the same reason;
- the guard is **wholesale, not key-by-key**, so it also catches a third `/app` path
  nobody has thought of yet;
- what "verbatim" would have bought — proof the `Delete` block parses — is **now bought
  properly** by `ShippedLog4j2ConfigRoutingTest.bothDeleteBlocksResolveToTheAnchorsTheClaim`,
  against the **shipped** file rather than a copy of it. Verbatim-in-the-test-copy would
  have parsed a Delete the box never runs;
- the retention keys are named in the test copy's header as its documented second reason
  to differ, so the deviation is discoverable rather than silent.

Had the routing test not landed, I would have called this a gap rather than a deviation —
the `Delete` blocks were the one region of the shipped file with no execution anywhere.

---

## Two flakes fixed

**A. `LoggingLevelOverrideTest.overrideMutatesTheExistingLoggerConfig` — Dev's report
CONFIRMED, and fixed.** It does **not** reproduce from `-Dtest=LoggingLevelOverrideTest`,
`…#overrideMutatesTheExistingLoggerConfig`, the whole `…logging.*Test` package, or four
other subsets I tried — all green. It reproduces deterministically when the nested DEBUG
context is selected **first**:

```
mvn -o -pl bot-app test '-Dtest=LoggingLevelOverrideTest$StagingLevelContext,LoggingLevelOverrideTest#overrideMutatesTheExistingLoggerConfig'
→ expected: INFO
   but was: DEBUG
```

Cause is as Dev suspected but slightly worse than context-cache reuse: Spring Boot applies
`logging.level.*` to the **process-wide** Log4j2 `LoggerContext` and never unwinds it, so
once `StagingLevelContext` has run, `com.vingame.bot` stays at DEBUG for the rest of the
JVM. That also silently weakens `ScopedDebugFilterInstallationTest`, whose whole proof
("an `ACCEPT` beats the level gate") is only meaningful with the app logger at INFO.
**Fix:** an `@AfterEach` in `StagingLevelContext` restoring `Configurator.setLevel("com.vingame.bot", INFO)`,
with the reasoning and the reproducing command in the javadoc. The reproducing invocation
is now green, and so is the class and the full suite.

**B. `evidence-shim/selftest.py::test_deferred_and_tail_passes_fire_at_their_deadlines` —
found here, not previously reported, and it fails the Maven build.** It is **wall-clock
dependent**: the deferred pass is armed at +300 s and the tail pass at *next 2 h boundary
+ 120 s*, so whenever the suite runs within **181 s before an even hour**, `tick(now + 301)`
straddles the boundary, both passes come due, and `["deferred"]` reads
`["deferred", "tail"]` — 4 assertions red, `selftest.py` exits 1,
`EvidenceShimSelfTestRunnerTest` fails, `mvn test` fails. That is **~2.5% of all wall-clock
time**, on every build, and it is unrelated to whatever change is being tested. I hit it
live at 13:59:46 and then reproduced it deterministically by freezing the clock 14 s before
a boundary. Pre-existing (introduced with the shim in `3963884`), not Phase 4's doing.
**Fix:** anchor the case at `shim.next_rollover(time.time(), 2.0) + 1.0` — every deadline in
the case is relative to that `now`, so nothing else changes. Verified: red under the frozen
pre-boundary clock before, green after; the other two `tick()` call sites in the file both
have `tailAt: None` and cannot straddle anything.

## One gap closed in the shim suite

`test_a_missing_detail_directory_is_a_no_op` claimed "promotion works **and says
nothing**", but only the first half was asserted: `candidates()` reports an unlistable
directory through `shim.log()` → **stderr**, which never reaches `result["errors"]`.
Removing the `isdir` guard left the suite fully green while the shim logged
`ERROR cannot list /logs/detail` **on every pass, forever** — on exactly the hosts 4c was
made safe for (shim shipped before 4a). The case now captures `sys.stderr` around the
promotion and asserts no `ERROR` was logged; with the guard removed it is red.

---

## Which Phase 4 steps are provable in the build

"Build" = fails `mvn test` if broken. "Box" = genuinely needs the running stack.

| Step | Where | Notes |
|---|---|---|
| **P4-1** both tracks exist, both graphs built | **Build (equivalent)** | The routing test starts both shipped twins and asserts both async appenders exist, started, and wrote a file. The container's *thread count* is box, but the defect it detects — `AsyncDetail` not built — is now a build error with the exact `ConfigurationException` |
| **P4-2** ws-parser in track 2 and nowhere else | **Build** | Marker in `detail.log`, absent from `console.log`, no `websocketparser` string anywhere in track 1, and the resolved LoggerConfig's appenders are exactly `[AsyncDetail]` — which is also the `docker compose logs` half |
| **P4-3** track 1 carries no DEBUG at `BOT_LOG_LEVEL=DEBUG` | **Build** | Both halves, with the level mutated in place the way Spring Boot does it. Still worth one run on staging, because the build cannot prove the *container* got the variable |
| **P4-4** INFO reaching Loki, by logger | **Box** | Needs a running fleet for the histogram and the `delta < 100`. The build backs both misroute directions (0 ws-parser lines in track 1; app INFO present in track 2) |
| **P4-5** Loki holds track 1 only | **Box** | Loki query. Build backs the mechanism (non-recursive `__path__` + track 2 one level down + the INFO threshold) |
| **P4-6** track 2's real write rate | **BOX ONLY — PRE-RAMP GATE, and it cannot be closed from the build.** | Nothing in a build can know the byte rate of a 155-bot fleet, let alone project it to 20k. AD-26's whole coverage table is extrapolated from one measurement. This is the plan's own gate and it is not a QA-satisfiable item — same status as P0-6, which measured Bot-1 but has **never been run on `Prod-Bot`** |
| **P4-7** track 2's retention anchored | **Build (config) + box (behaviour)** | `basePath` / `maxDepth` / glob / 12 h / 10 GB now asserted as the **resolved `DeleteAction`**, not as text. "One archive per 2 h, ≤ 7 steady state" needs real boundaries |
| **P4-8** promtail no longer re-ingests | **Box** | `LogRetentionPipelineTest` pins the path and the mount **together** (either alone looks fine). The +2.2 GB / `timestamp too old` observation is box, and expect one final re-ingest on this deploy |
| **P4-9** evidence promotes both tracks by hardlink | **Build** | `selftest.py` per-track selection with inode + link-count checks, plus the `/health` per-track breakdown. The container and its uid/gid are box |
| **P4-10** the two evidence ages are independent | **Build** | `test_the_two_sweep_ages_are_independent`, mutation-verified. The `secrets.env` + redeploy round trip is box |
| **P4-11** the scoped-DEBUG operator path | **Box** | REST call + Loki query. The build now proves the payload half's *format*: track 2 renders `[<GID>/` under PatternLayout |
| **P4-12** `docker logs` is clean | **Box** | Actual stdout is box. The build proves ws-parser **cannot** reach `ConsoleAppender` (resolved graph), which is the mechanism |

**Totals: 6 of 12 provable in the build** (P4-1, P4-2, P4-3, P4-9, P4-10, and P4-7's
configuration half), 6 needing the box, of which **P4-6 is a pre-ramp gate that cannot be
closed from the build at all**. P4-3 and P4-5 must be run on **staging with
`BOT_LOG_LEVEL=DEBUG`** — on a prod-like INFO instance they pass vacuously.

---

## Gaps

- **P4-6 and `Prod-Bot`'s disk.** Repeated because it is the only thing here that gates a
  ramp: AD-26's numbers are extrapolated from 155 bots, and `Prod-Bot` has never been
  measured at all. Neither is closable from a diff.
- **The console is proved from the resolved graph, not by capturing stdout.** log4j2 caches
  one `OutputStreamManager` per console target for the whole JVM, so a private context's
  `ConsoleAppender` may write through the manager the module's main `LoggerContext` already
  created — a stdout capture would be order-dependent and flaky. The graph assertion
  (`appenders == [AsyncDetail]`, `additive == false`) is the stronger statement anyway: it
  holds for every event rather than for a sample. (Checked that this cannot close the JVM's
  `System.out`: `OutputStreamManager.closeOutputStream()` skips `System.out`/`System.err`,
  and non-direct console streams are `CloseShieldOutputStream`-wrapped.)
- **`AsyncDetail`'s `blocking = false` behaviour is not exercised.** The build pins the
  setting and its asymmetry with track 1; it does not fill a 16,384-entry queue and observe
  that an INFO event is dropped rather than parking the caller. That is a load test.
- **No rollover, no `Delete` execution.** The routing test proves the `DeleteAction` was
  **built** with the right anchors; it never fires one. "Exactly one archive per 2 h" and
  "the sweep actually unlinks" remain P4-7 / P0-5 on the box.
- **`immediateFlush = false` is asserted as configuration only.** The routing test stops the
  context before reading, so it never observes the lag the plan warns about for
  `wc -l`.
- **`EVIDENCE_DETAIL_MAX_AGE_DAYS` in `docker-compose.yml` is unguarded.** No test asserts
  compose passes it. Low risk — the shim's own default is the same 3 — so the only loss
  would be the operator override, which is what P4-10 exercises by hand.
- **The `docker logs` per-line assertions of P4-12** (no ANSI escapes, no `Agency token`
  on stdout) are box: the build proves the routing that makes them true, not the output.
- **`LoggingLevelOverrideTest`'s global-state hygiene now depends on an `@AfterEach`.** The
  underlying property — Spring Boot mutates the process-wide Log4j2 context and never
  unwinds it — is unchanged, so a *future* test class that sets
  `logging.level.com.vingame.bot=DEBUG` and does not restore it re-opens the same trap for
  everything that runs after it.

## Failures

None. `mvn test` → 1856 / 0 / 0 / 0.
