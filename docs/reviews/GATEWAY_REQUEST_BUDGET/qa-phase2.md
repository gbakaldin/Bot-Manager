# QA — GATEWAY_REQUEST_BUDGET Phase 2 (async start/restart, startup daisy-chain, progress)

**Verdict:** FAIL
**Build:** `mvn test` → **2,261 tests, 1 failure, 0 errors** (branch tip + QA's tests, run twice, identical both times)
**Branch:** `feature/gateway-request-budget`, diff `d947c09..750fc91` (6 commits) + QA commit `cade5c9`
**Verified in:** detached `git worktree` at `750fc91`, not the working tree (which carries ~59 dirty entries of unrelated RIK/Aviator work)

The single failure is **QA's own deliberately-red test** for finding **Q1** below. Everything Dev
shipped is green, three times over, with no flakes.

| Run | Tree | tests | failures | errors |
|---|---|---|---|---|
| 1, 2, 3 | `750fc91` as committed | 2,245 | 0 | 0 |
| 4, 5 | `750fc91` + QA's 16 tests | 2,261 | **1** (Q1, intentional) | 0 |

Dev's own tip number (2,245) reproduces exactly. Per module at the tip: bot-api 148,
bot-messages 167, bot-strategies 126, bot-engine 494, bot-app 1,310.

**Why FAIL rather than PASS-with-a-note.** Q1 breaks the one invariant the plan states as an
absolute and the one this phase's whole persistence split exists to protect: rolling back to
`vingame-bot:rollback-*` must stay safe at all times. It is reachable from a single public-API
request body, it is new in this phase, and the fix is small and local. Nothing else here is
blocking; the async lifecycle work itself is in good shape and better tested than the plan asked
for.

---

## Tests added / updated

- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupStartCancellationRaceTest.java`
  (new, 9 tests) — every cancellation window *around* the one Dev covered.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/model/BotGroupStatusRollbackSafetyTest.java`
  (new, 6 tests, **1 red**) — A1's persistence claims measured against a real
  `MappingMongoConverter`, plus the two request-body write paths.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupControllerTest.java`
  (+1 test) — the nullable-progress contract on `/status`: `botsUp`, `registeredCount` and
  `lastError` are **absent**, not `0`/`""`, for a group not started in this JVM.

All 16 use mocked `BotFactory`; **no test in this diff or in QA's additions opens a socket or
makes a gateway request.** Verified by inspection of every new test class: no
`java.net.http.HttpClient`, no `VingameWebSocketClient`, no `ApiGatewayClient`, no
`EnvironmentWsProbe`, no hostname anywhere. `ApplicationContextLoadsTest` now fires
`ApplicationReadyEvent` (so `onStartup` runs under `@SpringBootTest`), but
`BotGroupRepository` is `@MockitoBean`, so `findByTargetStatus(ACTIVE)` returns empty and the
chain queues nothing.

---

## The five invariants the task named

### 1. `STARTING` must never reach Mongo — **substitute is NOT strong enough. See Q1.**

The enum itself is correct and well guarded:

- `BotGroupStatusAppendOnlyTest` pins ordinals 0/1/2, the exact six-constant order, **and**
  drives a status through the real `BotSortKey.STATUS` extractor + the real `Comparable`
  contract, so an insertion fails even if someone "fixes" the ordinal assertions. The
  `BotSortKey.STATUS`-sorts-on-`ordinal()` hazard is genuinely closed. Append-only: confirmed.
- `startingIsNeverPersisted` (Dev) asserts a `STARTING` runtime while building and only
  `ACTIVE` in the captured saves. Sound as far as it goes.
- I added the round-trip Dev could not: **a real `MappingMongoConverter`** over a real
  `MongoMappingContext` needs no Mongo server, and `PersistedStrategyKeyCompatTest` had already
  established that seam in this repo for exactly this question. It confirms `targetStatus` is
  stored as the bare constant-name `String` with nothing in the way, that the original three
  still read back verbatim, that an absent value stays `null`, and that an unknown constant
  name is a hard read failure.

  One correction: the failure is **`IllegalArgumentException: No enum constant …`** out of
  `MappingMongoConverter.getPotentiallyConvertedSimpleRead` → `Enum.valueOf`, **not**
  `ConversionFailedException`. A1 and `BotGroupStatus`' own javadoc both name the wrong type.
  The consequence A1 reasons from is unaffected — one poisoned document fails the whole
  `findByTargetStatus(ACTIVE)` boot query — but the docs should say the right thing (**Q5**).

What the substitute misses is the **write side**, which is Q1.

### 2. `/stop` must unwind a paced start — **holds, including the window `750fc91` fixed, and its siblings**

Dev's fix is correct and the ordering (cancel attempt → `cancelScope` → lock) is right. I probed
the accepted-but-not-yet-locked window directly and every sibling I could find:

| Window | Result | Test |
|---|---|---|
| `/stop` while the build is inside `createBotsInParallel` | unwinds, no runtime, `STOPPED` persisted | Dev's `stopDuringABuildCancelsIt` |
| **`/stop` accepted-but-not-yet-locked** (the `750fc91` regression) | build stays cancelled; **0 bots built**; only `STOPPED` ever persisted | `stopBeforeTheBuildTakesTheLockIsNotUndone` |
| `/delete` mid-start (`stopAndLogout`, its own `clearRetained`) | build stays cancelled; 0 bots; nothing persisted | `deleteMidStartCancelsTheBuild` |
| `/stop` during `restartAsync` | stop wins, does not wait out the rebuild, 0 bots, ends `STOPPED`, never `ACTIVE` | `stopDuringARestartWins` |
| two `/start`s at the same instant | exactly one build (`findById(env)` once, `botCount` bots once) | `twoConcurrentStartsProduceOneBuild` |

The `750fc91` test reproduces the window **deterministically, not by timing**: it pre-seeds
`groupLocks` with its own `ReentrantLock` and holds it on the test thread, so the build parks on
the lock while `stop()` — re-entrant on the same thread — runs to completion. Without the fix
the group comes up (`botFactory.createBot` × 2, runtime `ACTIVE`, `ACTIVE` persisted) behind a
`/stop` that already answered `200`; with it, none of that happens. That is a real regression
test, not a restatement.

### 3. Cancellation is not interrupt-based, and the under-permit check does stop gateway spend — **holds**

Nothing in the diff interrupts a build thread; cancellation is a `volatile boolean` on the
attempt plus a `BooleanSupplier` on `BotConfiguration`. `cancelUnderThePermitBoundsGatewaySpend`
measures the placement rather than reading it: parallelism 2, `botCount` 20, the cancel lands
while the first two tasks hold permits — `botFactory.createBot` is called **at most 2 times**,
and `bot_creation_failures_total` does not move (a cancelled bot is neither up nor failed). The
other 18 acquire a permit, re-check, and return `null`. A pre-acquire-only check would have let
all 20 through, which is the 2,990-of-3,000 argument in Dev's comment, and it is correct.

`botConfigurationCarriesTheCancellationPredicate` pins the app half of AD-8: the supplier handed
to every bot is non-null and reads the **live** registry, not a snapshot taken at build time.
The engine half (`Bot.requestCancelled` = `isStopped() || startCancelled`) is Dev's
`BotGatewayTierTest`.

Note for Phase 3: in observe mode `GatewayBudget.execute` never consults the scope at all, so
today the **only** thing that stops spend is the under-permit check. That is fine now and is
exactly why the placement matters; the scope predicate becomes load-bearing when queueing lands.

### 4. `restart()`'s internal stop must NOT cancel the attempt — **holds, and is now pinned**

Dev is right that AD-16 is wrong here, and the reasoning is right: by the time the internal stop
runs, the restart's *own* attempt is the one in flight, so cancelling "the start in flight"
would cancel the restart. `restartInternalStopDoesNotCancelItsOwnAttempt` pins it end to end —
a `restartAsync` of a running group rebuilds all its bots, ends `ACTIVE`, records no
`lastError`, and persists exactly `[STOPPED, ACTIVE]`. Flip `parkRuntimeless` to `true` in
`restart()` and the test fails on all four assertions.

(The `persistedStatuses()` helper records the status **at save time** via an `Answer`, not via
an `ArgumentCaptor`: the service saves the same `BotGroup` instance twice, so a captor reports
the final value twice and a `[STOPPED, ACTIVE]` sequence silently reads as `[ACTIVE, ACTIVE]`.
Worth knowing before writing more assertions of this shape.)

### 5. The startup daisy-chain — **holds**

Dev's `StartupChainTest` covers all five properties properly: `onStartup()` returns while a
build is still in flight (the regression test for the whole move off `@PostConstruct`), groups
start one at a time in order with no overlap, a failing group does not stop the chain, the
`queued for daisy-chained start` line precedes the first start, and SCHEDULED groups are still
skipped. `startupChainThread()` is a clean seam for tests that would otherwise race a thread
they did not create, and `BotGroupBehaviorServiceTest` was correctly updated to join it. Nothing
to add. One non-blocking gap: **Q4**.

---

## Coverage of the diff

| Production file | Test file(s) | What is covered |
|---|---|---|
| `model/BotGroupStatus.java` | `BotGroupStatusAppendOnlyTest`, `BotGroupStatusPersistenceGuardTest`, **`BotGroupStatusRollbackSafetyTest`** | append-only ordinals via the real sort extractor; no literal `setTargetStatus(STARTING)`; real-converter BSON shape, unknown-name read failure, **and the two unguarded write paths (red)** |
| `model/StartOrigin.java` | `ActivationSchedulerTest`, `BotGroupControllerTest`, `BotGroupStartCancellationRaceTest` | every entry point passes its own origin (`REST`, `SCHEDULE`, `STARTUP`, `RECOVERY`, `SCHEDULED_RESTART`) |
| `service/StartAttemptRegistry.java` | `StartAttemptRegistryTest` (16 tests) | `begin` idempotence, per-group isolation, counters, `finish` retention + truncation, `cancel` leaves the attempt open, **`clearRetained` never drops an open attempt**, `describe` never throws |
| `service/BotGroupBehaviorService.java` — `startAsync`/`restartAsync`/`submitLifecycle` | `BotGroupBehaviorServiceAsyncStartTest`, `BotGroupStartCancellationRaceTest` | ack is `STARTING`; validation stays synchronous and opens no attempt; single-flight under a true race; `onFailure` runs once on the build thread; failure lands in `lastError`; zero-bot restart no longer reaches a caller |
| …`stop`/`stopAndLogout` | `BotGroupStartCancellationRaceTest` | cancel-before-lock ordering in all four windows above |
| …`startLocked` cancel check + reclaim guard | `BotGroupStartCancellationRaceTest`, `stopDuringABuildCancelsIt` | cancelled build unwinds as INFO not ERROR, tears the runtime down, persists nothing; `STARTING` treated like `ACTIVE` by the reclaim guard is covered only by *reading* (see Gaps) |
| …`createBotsInParallel` cancel checks | `cancelUnderThePermitBoundsGatewaySpend` | spend bounded by parallelism; skipped bots counted as neither up nor failed |
| …`onStartup`/`runStartupChain` | `StartupChainTest`, `BotGroupBehaviorServiceTest` | returns immediately, serial, in order, isolated, SCHEDULED skipped, queued-line ordering |
| …`isGroupRunning`/`getActualStatus` | `BotGroupBehaviorServiceAsyncStartTest`, `StartupChainTest` | both widenings (STARTING runtime, and an open attempt with no runtime) |
| …`startForRecovery` | `recoveryInheritsValidationAndLeaksNoAttempt` | shared `validateStartable` reached; no attempt leaked on the throw path |
| …`scheduleRestart` | `scheduledRestartIsTracked` | goes through the tracked async entry, visible on `/status` |
| `service/ActivationScheduler.java` | `ActivationSchedulerTest` | START → `startAsync(SCHEDULE)`, never `start`; every NONE/STOP case asserts `never().startAsync(...)` too |
| `controller/BotGroupController.java` | `BotGroupControllerTest` (7 start + 3 restart + 4 status) | `200` + `STARTING` DTO on both; `botCount`/`botsUp`/`lastError` in the body; 404 and both 400s still synchronous; the async-rollback `Runnable` restores `activationMode`; a synchronous-half failure still rolls back **and** still answers 500; restart never parks MANUAL_ON |
| `dto/BotGroupStatusDTO.java` | `BotGroupControllerTest` | the four new fields, including the absent-not-zero contract for `registeredCount` (Phase 4 boundary) |
| `runtime/BotGroupRuntime.java` | `BotGroupRuntimeTest`, `BotGroupBehaviorServiceTest` | born `STARTING`; the two fixtures that needed an explicit `ACTIVE` were updated rather than weakened |
| `config/bot/BotConfiguration.java`, `bot/core/Bot.java` | `BotGatewayTierTest`, `botConfigurationCarriesTheCancellationPredicate` | `startCancelled` wired before `initialize()`; scope cancelled by stop **or** group-start cancel; null supplier falls back to `isStopped` alone |

### Not a per-bot INFO regression

The lines this phase adds at INFO are all per **group** or per **operator action**: `start
admitted` (one per start), `stop requested while a start was in flight`, `cancelled the build at
n/m bots`, `N/M bots were not built`, `Start of bot group X was cancelled by a stop`, `queued for
daisy-chained start`, the per-action `ignored — a start is already in flight`. Nothing new fires
per bot or per request. `PerBotInfoLogGuardTest` stays green.

---

## Findings

### Q1 — BLOCKING. `STARTING` reaches Mongo through a request body; the source guard cannot see either path

`BotGroupStatusRollbackSafetyTest.WritePaths.requestBodiesCannotPoisonTargetStatus` is **red**,
and this is what it reports:

```
Expecting empty but was: ["POST /api/v1/bot-group/ → BotGroupMapper.toEntity → "STARTING"",
    "PATCH /api/v1/bot-group/{id} → updateEntityFromDTO → "STARTING"",
    "POST /api/v1/bot-group/ → BotGroupMapper.toEntity → "REGISTRATION_PENDING"",
    "PATCH /api/v1/bot-group/{id} → updateEntityFromDTO → "REGISTRATION_PENDING"",
    "POST /api/v1/bot-group/ → BotGroupMapper.toEntity → "REGISTRATION_FAILED"",
    "PATCH /api/v1/bot-group/{id} → updateEntityFromDTO → "REGISTRATION_FAILED""]
```

`targetStatus` is a **writable** field on `BotGroupDTO` (`:142`), and both request-body paths
copy it into the entity that is handed to `repository.save`:

- `BotGroupMapper:92` — `toEntity`, via the Lombok **builder**: `.targetStatus(dto.getTargetStatus())`.
  Reached by `POST /api/v1/bot-group/` (`BotGroupController:116`).
- `BotGroupMapper:181` — `updateEntityFromDTO`, via
  `setTargetStatus(Optional.ofNullable(dto.getTargetStatus()).orElse(existing))`.
  Reached by `PATCH /api/v1/bot-group/{id}` (`BotGroupService.update:316`).

Nothing downstream sanitises it: `BotGroupService.save` never inspects `targetStatus`, and
`BotGroupConfigValidationService.validate` does not either.

`BotGroupStatusPersistenceGuardTest` misses both **structurally**, not by accident: the first is
not a `setTargetStatus(` call at all, and the second's argument is a variable. Its own javadoc
anticipates the variable case ("a reviewer has to keep the variable ones honest") but the
builder case is invisible even to a reviewer following that instruction, and neither is a
variable a reviewer can reason about — it is whatever a client sent.

**Why this is not "the API was always lax".** Before this phase every constant the DTO could
carry (`ACTIVE`/`STOPPED`/`DEAD`) was one an older jar could read, so a weird `targetStatus` in
a request body was a data-consistency annoyance. After it, three of the six are **unreadable by
any jar built before this feature**, and `findByTargetStatus(ACTIVE)` is on the boot path, so
one such document does not degrade one group — it fails the whole startup query. A1 names that
as the reason for the whole in-memory-only design: "Rolling back to `vingame-bot:rollback-*`
must stay a safe action at all times."

It also gets worse in Phase 4 by design, not by neglect: A3 has `POST /` render
`targetStatus: "REGISTRATION_PENDING"`, so any UI that does read-modify-write PATCH with the
DTO it just received will hand the value straight back.

A1's consumer audit already claims the property that is false — "`BotGroupDTO.targetStatus` can
still only carry the original three". True of what the API *renders*; false of what it
*accepts*.

**Fix belongs in production code** (Dev's, not mine). Either is small:
- reject the three appended constants at the DTO boundary (a `BadRequestException` in
  `BotGroupConfigValidationService.validate`, which already runs on both create and post-merge
  PATCH, so one arm covers both paths and inherits the existing 400); or
- stop accepting `targetStatus` on the write side at all (`@JsonProperty(access = READ_ONLY)`
  on `BotGroupDTO.targetStatus`) — lifecycle is what `/start` and `/stop` are for, and this is
  arguably the right shape regardless.

Whichever is chosen, **extend `BotGroupStatusPersistenceGuardTest` to the builder form**
(`.targetStatus(`) as well, or the next field that gets a new constant repeats this exactly.
Do not resolve the red test by deleting or `@Disabled`-ing it.

### Q2 — non-blocking. A successful `/stop` during a `/restart` reports itself as an ERROR and poisons `lastError`

Observed in the suite log, from `stopDuringARestartWins`:

```
[group-restart-g-1] ERROR BotGroupBehaviorService [//] - Asynchronous restart of bot group g-1
  (origin REST) failed: java.lang.IllegalStateException: Restart of group g-1 produced 0/3
  bots; check logs and bot_creation_failures_total metric for cause
```

The operator's `/stop` worked perfectly — the group ends `STOPPED`, no bot was built, nothing
leaked. But `restart()`'s zero-bot check (`:1604-1611`) sits **after** the `try/catch` around
`start(id)` and has no cancellation arm, so a cancelled rebuild is indistinguishable from a
rebuild that failed to authenticate anything. Consequences:

- one ERROR per cancelled restart, for an outcome that is an operator decision that completed.
  `startLocked` deliberately logs the equivalent `/start` case as **INFO** ("An ERROR here
  would page someone for a successful `/stop`") — the restart path is missing the symmetric
  check;
- `/status` then reports `lastError: "…produced 0/3 bots; check logs and
  bot_creation_failures_total…"` on a group the operator just stopped, pointing them at a
  metric that did not move.

Rate is one per rare human action, so it is not a volume problem; it is a "the one ERROR you
get is a lie" problem. Suggested fix: skip the zero-bot check when
`startAttempts.isCancelled(id)` (or when the attempt was cancelled at any point during the
restart), mirroring `startLocked`'s `cancelled` flag. I deliberately did **not** encode the
current behaviour as an assertion, so fixing it will not break a test.

### Q3 — non-blocking. The new async MDC scope is silently dropped part-way through the build

`submitLifecycle` sets `BotMdc.setGroupContext(id, environmentId)` on the build thread precisely
so async lifecycle lines are attributable. It works, and then stops working:

```
[group-start-g-1] INFO  BotGroupBehaviorService [g-1//] - group g-1 (Group): start admitted …
[group-start-g-1] INFO  BotGroupBehaviorService [g-1//] - Creating 5 bots for group Group …
[group-start-g-1] INFO  BotGroupBehaviorService [//]    - Bot group Group started successfully …
```

`createBotsInParallel`'s result-collection `finally` calls `BotMdc.clear()` (as does
`teardownRuntimeMemory`), which clears the **whole** MDC rather than restoring what it found. So
the lines emitted after it — `started successfully`, `Start of bot group X was cancelled by a
stop`, and the async-failure **ERROR**, which is now "the only place this failure is visible" —
carry no `botGroupId`/`environmentId`, i.e. no JSON fields in track 1 and nothing to filter on
in Loki.

Not a regression (before this phase those lines ran on an HTTP thread with no group MDC at all),
but the new intent is half-delivered, and the affected line is the one an operator most needs to
find. Fix is a save/restore instead of a `clear()` in those two `finally` blocks.

### Q4 — non-blocking. The daisy-chain is not drained at shutdown

`@PreDestroy shutdown()` shuts `scheduler` and `botCreationExecutor` but neither interrupts nor
awaits `startupChain`. On a `docker compose restart` during a long paced chain, the chain's
virtual thread dies with the JVM mid-build, leaving the bots it had already authenticated
connected upstream with no teardown, and `botCreationExecutor.shutdownNow()` interrupts
in-flight creations whose `join()` is uninterruptible anyway. Bounded and self-healing (the
sockets drop when the process exits), and it was not reachable before because `onStartup` ran to
completion inside context refresh. Worth a line in `FOLLOWUPS.md` rather than a change now.

### Q5 — documentation. A1 and `BotGroupStatus`' javadoc name the wrong exception

The rollback failure is `IllegalArgumentException: No enum constant
com.vingame.bot.domain.botgroup.model.BotGroupStatus.…` from
`MappingMongoConverter.getPotentiallyConvertedSimpleRead`, not `ConversionFailedException`.
Measured, not inferred — `BotGroupStatusRollbackSafetyTest.anUnknownConstantNameFailsTheRead`.
The argument A1 builds on it is unchanged; only the type name is wrong, in the plan, in
`BotGroupStatus`' class javadoc and in `BotGroupStatusPersistenceGuardTest`'s javadoc.

### Q6 — informational. `STARTING` quietly changes recovery's live-sibling short-circuit

`countOpenWsByEnvForActiveRuntimes` (`:2329`) skips any runtime whose `actualStatus != ACTIVE`,
so a group whose build is in flight no longer counts as DEAD_GROUP_AUTO_RECOVERY AD-10's "ACTIVE
sibling with an open socket". The effect is that recovery runs its anonymous WS probe instead of
short-circuiting — the safe direction, costing one probe — but it is an unflagged behaviour
change to a shipped feature, and it is the kind of thing worth naming before someone debugs
`outcome="live_sibling"` disappearing during a large start. Not covered by a test; the window is
minutes today and tens of minutes under Phase 3.

---

## Gaps

Things in the diff that are not covered by an automated test, and why:

- **G1 — a real Mongo round-trip.** Still absent, and still unavailable: the repo has no
  embedded-Mongo or Testcontainers infrastructure and adding it is out of scope for a QA pass.
  What I added closes most of the gap — the **real `MappingMongoConverter`** is the class
  `MongoTemplate` delegates document mapping to, so the BSON shape and the unknown-name read
  failure are measured rather than assumed. What remains unproven is only the driver/server
  layer below it, which does not participate in enum conversion.
- **G2 — `startLocked`'s reclaim guard treating `STARTING` like `ACTIVE`.** Covered by reading
  only. A1 calls it unreachable in practice (the per-group lock serialises starts and the
  attempt registry refuses a second `/start`), and I agree — which also means a test would have
  to reach in and plant a `STARTING` runtime under the lock to exercise it, proving little about
  production. Left as a one-line source fact with a comment that says why it is written down.
- **G3 — the budget scope cancel in the pre-runtime window.** `cancelStartInFlight` resolves the
  environment id from `runningGroups`, so in the accepted-but-not-yet-locked window it cancels
  the *attempt* but not the *scope*. Harmless today (no request can be queued before the runtime
  exists, and observe mode never queues) and untestable as a behaviour until Phase 3 makes
  `cancelScope` do something. Phase 3 should either read the id from the group document or
  assert the ordering makes it unreachable; noting it so it is not discovered by a stuck
  `/stop`.
- **G4 — `runWithManualOverrideAsync`'s `restoreMode` re-reading the group.** The re-read is
  right (minutes may have passed), and `theModeRollbackIsHandedToTheAsyncStart` covers that the
  callback restores the mode. Not covered: that it restores from a *freshly read* document
  rather than a stale one. Would need a two-writer fixture for a one-line correctness claim.
- **G5 — the `"budget"` arm of `classifyCreationFailure`** stays inert until Phase 3, as
  designed. Phase 1's QA said the same.
- **G6 — none of this exercises pacing.** Phase 2 is deliberately independent of the budget;
  every duration claim in the plan (33-50 minutes for 3,000 bots) is Phase 3's to demonstrate,
  via `GatewayBudgetEscalationIT` (V3g) on the laptop + stub.
- **G7 — the UI contract.** `200` now means *accepted*, and three new `status` strings can
  appear. The frontend is not in this repo; A9's items 2 and 3 still need the user's
  confirmation and no test here can substitute for it.

## Failures

```
[ERROR] BotGroupStatusRollbackSafetyTest$WritePaths.requestBodiesCannotPoisonTargetStatus:177
[A1: nothing new is ever persisted into targetStatus, because an older jar throws
ConversionFailedException on it and findByTargetStatus(ACTIVE) is on the boot path. These
request-body paths reach Mongo with no guard in between, and the source guard cannot see either
of them (one is a builder, one passes a variable).]
Expecting empty but was: ["POST /api/v1/bot-group/ → BotGroupMapper.toEntity → "STARTING"",
    "PATCH /api/v1/bot-group/{id} → updateEntityFromDTO → "STARTING"",
    "POST /api/v1/bot-group/ → BotGroupMapper.toEntity → "REGISTRATION_PENDING"",
    "PATCH /api/v1/bot-group/{id} → updateEntityFromDTO → "REGISTRATION_PENDING"",
    "POST /api/v1/bot-group/ → BotGroupMapper.toEntity → "REGISTRATION_FAILED"",
    "PATCH /api/v1/bot-group/{id} → updateEntityFromDTO → "REGISTRATION_FAILED""]
```

Intentional, reproducible in both runs, and the only failure in the suite. It goes green when
Q1 is fixed in production code.
