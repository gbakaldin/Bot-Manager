# QA — LOG_VOLUME_TIERING

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
