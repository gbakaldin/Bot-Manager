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

---

# Re-check — fix round `7e118bf`..`d9be331`

**Verdict:** PASS
**Build:** `mvn test` → **2,282 tests, 0 failures, 0 errors** (five runs at the final state, identical every time)
**Branch tip:** `d9be331`, verified in a detached `git worktree`, never in the working tree (58 dirty unrelated RIK/Aviator entries)

| Run | Tree | tests | failures | errors |
|---|---|---|---|---|
| 1 | `d9be331` exactly as committed | **2,276** | 0 | 0 |
| 2, 3 | + QA's 4 new blast-radius / delete-window tests | 2,280 | 0 | 0 |
| 4, 5 | + QA's 2 builder-guard tests | 2,282 | 0 | 0 |
| 6, 7 | + QA's Q2 pin (assertion only) | 2,282 | 0 | 0 |

Dev's 2,276 reproduces exactly. **The blocker is closed and every non-blocking finding was either
fixed or deferred with a reason I agree with.** One of Dev's adjudications against me is correct
and I concede it below; three things I would still change are recorded as non-blocking, and two of
them I closed myself with tests.

---

## Q1 — closed, at the boundary, and closed on **both** paths

Verified line by line at the tip:

- **`BotGroupMapper:92`, the Lombok builder** — `.targetStatus(dto.getTargetStatus())` is gone from
  `toEntity`, replaced by a comment naming R1/Q1. The type is no longer referenced by the mapper at
  all: an attempt to reinstate the line by hand does not even compile until you re-import
  `BotGroupStatus`, which is a small extra tripwire and not one anybody designed.
- **`BotGroupMapper:181`, the variable argument** —
  `setTargetStatus(Optional.ofNullable(dto.getTargetStatus()).orElse(existing))` is gone from
  `updateEntityFromDTO`. Note the removal is *strictly* safe for PATCH semantics: the old
  expression already fell back to the entity's own value, so a body that omitted the field was
  inert before and is inert now.
- **`@JsonProperty(access = READ_ONLY)` on `BotGroupDTO.targetStatus`**, asserted through a real
  `ObjectMapper` rather than by reading the annotation (`theDtoFieldIsReadOnlyInbound`), including
  that the field is **still rendered outbound** — which is what A3 needs.
- **Every remaining writer of the field is lifecycle code with a self-authored constant**: six
  `setTargetStatus(` sites in `BotGroupBehaviorService` (`DEAD`×2, `ACTIVE`, `STOPPED`×2, and
  `statusBeforeRestart` read back from Mongo). No other production code reaches it.

`BotGroupStatusRollbackSafetyTest.WritePaths.requestBodiesCannotPoisonTargetStatus` is **green by
the production fix, and the file is untouched** — `git log cade5c9..d9be331 --` on that path is
empty. I corrected one thing in it myself: its `as(...)` description still named
`ConversionFailedException` and the boot-query claim, both of which are wrong (below), and a
never-rendered message is exactly where a wrong model survives.

**On rejecting the validator arm: Dev is right, and for a stronger reason than the commit gives.**
The commit argues from the read-modify-write client. Two things make that decisive rather than
merely likely:

1. Under A3, `POST /` **renders** `REGISTRATION_PENDING` from Phase 4 on. So the client that hands
   the value back is not a careless client, it is the *normal* one — the trap CLAUDE.md records for
   strategy-key validation, but with the bad value supplied by our own response body.
2. A validator in `BotGroupConfigValidationService` runs **post-merge over the whole entity** on
   PATCH (Amendment A5 says so explicitly, and CLAUDE.md records the consequence for strategy
   keys). So the 400 would not be scoped to the offending field — a group that had once been
   poisoned would fail *every* PATCH until someone could change a field they are no longer allowed
   to send. The validator arm cannot even be used to clean up after itself.

Ignoring inbound also keeps the contract in A3 intact, which a 400 would not: `targetStatus` has to
keep round-tripping through a UI that never intends to write it.

**One residual, which I closed rather than reported.** The source guard still scans for
`setTargetStatus(` only, so a *new* production site writing the field through
`BotGroup.builder()` — the exact shape that let Q1 through — would still be invisible to it. My
Q1 write-up asked for that extension and the fix round did not land it; the new value-level mapper
test pins the two known mapper paths but not a third site somewhere else. Added:

- `noAppendedConstantReachesTheEntityThroughABuilder` — the builder form of the same scan, scoped
  to the **entity** builder by walking back to the nearest `X.builder()`. That scoping is
  load-bearing: `BotGroupDTO.builder().targetStatus(REGISTRATION_PENDING)` is Phase 4's *design*
  (A3), so an unscoped scan would fail the build on a correct line and get itself deleted.
- `theBuilderScanRecognisesAnEntityBuilder` — feeds the walker the deleted `toEntity` chain and its
  DTO twin, so the guard above cannot pass by recognising nothing.

Non-vacuity demonstrated: reinstating `.targetStatus(BotGroupStatus.STARTING)` in `toEntity` fails
both the new builder guard (naming `BotGroupMapper.java:92`) and Dev's mapper value test.

---

## 1. The blast radius — **Dev is right and my original verdict was wrong**

Stated plainly: **`findByTargetStatus(ACTIVE)` does not fail on a poisoned document.** I repeated
A1's claim without measuring it, and the reviewer (R1, sub-point 1) and Dev both corrected it. I
have now measured the mechanism rather than asserting it, in a new `BlastRadius` nested class:

- `theBootQueryFiltersOnTheStringAndNeverConvertsAPoisonedGroup` — maps
  `Criteria.where("targetStatus").is(ACTIVE)` through a **real `QueryMapper`** over the real
  `MongoMappingContext` and asserts the rendered BSON is exactly `{"targetStatus": "ACTIVE"}`. The
  server does the matching, conversion happens only on what comes back, so a document holding
  `"STARTING"` is never converted by that query and cannot make it throw.
- `onePoisonedDocumentTakesThePageWithIt` — the `POST /{envId}/filter` shape: the converter has no
  per-document tolerance, so a healthy group *after* the poisoned one is never produced. The page
  is lost, not filtered.

**And the reach is wider than either account.** A query that filters on some *other* field still
converts every document it returns, so a poisoned group that is also
`activationMode == SCHEDULED` is returned by `ActivationScheduler`'s
`findByActivationMode(SCHEDULED)` and fails that tick — **every minute, for every scheduled group
in the fleet**, not just the poisoned one. `findAll()` and `findByGameId` have the same shape.
Pinned by `aQueryOnAnotherFieldStillConvertsThePoisonedGroup`.

Two corrections that make the severity honest in the other direction:

- **No poisoned document can exist today.** The three constants are new on this branch and the
  branch has never been deployed (A7 gates the release to after Phase 5), so there is no cleanup
  question — only a "must stay closed" question.
- **The reviewer's sub-point 2 is now safe by construction.**
  `BotGroupBehaviorService:1854`'s `setTargetStatus(statusBeforeRestart)` launders whatever got
  in; with the boundary closed, the only values the document can hold are the original three or
  null, so the site no longer depends on luck.

Also confirmed: with `persistedTarget == STARTING` and no runtime, `RecoveryEligibility` condition
(2) returns false, so the group is silently unmanaged by *both* reconcilers — Dev's description of
the current-jar cost is accurate.

---

## 2. `lastError` sanitisation — holds, with one thing to carry into Phase 4

Traced end to end rather than read:

- **One assembly point.** `BotGroupController.statusDTO` builds the body for `/status` **and** both
  acks, and its only exception-derived field is `lastError` ←
  `behaviorService.getLastStartError(id)` ← `startAttempts.lastError(id)`. Nothing else on
  `BotGroupStatusDTO` can carry a throwable's words.
- **Two writers of `attempt.error`, both accounted for.** `recordFailure` has exactly two
  production call sites and both pass a **self-authored** sentence (`startLocked`'s zero-bot reason,
  `checkRestartProducedBots`' reason). `finish` is the only other writer and routes through
  `ClientSafeMessage.of`.
- **The policy is what it says it is.** `BotManagerException` and `IllegalArgumentException`
  verbatim; everything else `INTERNAL_ERROR + " (" + getSimpleName() + ")"`. Pinned by
  `aForeignThrowableIsSanitised` / `ourOwnExceptionsAreVerbatim` / `ourOwnExceptionsAreForwardedVerbatim`,
  and the async-path test uses a deliberately hostile message
  (`"environment exploded at mongo-7.internal:27017"`) and asserts the hostname does **not** appear.
- **The widening is bounded.** A bare `getSimpleName()` carries none of the three things the
  handler's policy names (no hostname, no wiring detail, no infra internals), and the rationale —
  the holder of a `lastError` has no request URI or timestamp to correlate a log against — is
  correct and is a real operational difference from an HTTP response.
- **The restart's zero-bot message arrives intact.** `recordFailure` sets it on the still-open
  attempt *before* the throw, and `finish` keeps a recorded reason (`attempt.error == null` guard)
  rather than deriving `"Internal server error (IllegalStateException)"` from the throwable. Pinned
  by `restartZeroBotFailureLandsInLastError`, which would go red if either half were removed.
- **No second leak path.** `setLastFailureReason` — the *persisted* twin, which **is** rendered on
  `GET /{id}` — has only two production call sites and both pass self-authored strings.
- `RestExceptionHandler.INTERNAL_ERROR_MSG` is byte-identical to the string it moved to, so no
  response body changed.

**Q7 (non-blocking, for Phase 4).** The policy forwards *any* `BotManagerException` verbatim, and
`UpstreamGatewayException`'s message is built as `"Login failed for user 'X': " + e.getMessage()`
where the tail is the ws-parser / upstream text — **the very material the fix's own commit message
cites as the reason for the split** ("ws-parser text that embeds a fragment of the upstream
response body — on a library separately known to log agency-token material"). It is not reachable
in `lastError` today: per-bot authentication failures are caught inside `createBotsInParallel`'s
per-bot task and converted into the self-authored zero-bot reason, so they never reach
`finish(id, error)`. Phase 4 changes that by design — `RegistrationWorker`'s failures are
`UpstreamRegistrationException` built from an upstream envelope, on a path whose whole purpose is
to land in `registrationError` / `lastError` on an unauthenticated endpoint. The right time to
decide whether `UpstreamGatewayException` is "operator-safe by construction" is before that lands,
not after.

---

## 3. My four non-blocking findings

| | Status | Judgement |
|---|---|---|
| **Q2** — `/restart` zero-bot ERROR + poisoned `lastError` | Fixed (`isCancelled` arm + `recordFailure`) | Correct, and I added the missing pin |
| **Q3** — async MDC scope dropped mid-build | Fixed (`BotMdc.snapshot()`/`restore(Map)`) | Correct and complete |
| **Q4** — daisy-chain not drained at `@PreDestroy` | ERROR-storm half fixed; residue deferred as P14 | Right split |
| **Q6** — `STARTING` leaves the live-sibling short-circuit | Accepted as deliberate, reasoned in the javadoc | Agree — it is the *only* safe answer |

- **Q2.** The fix is right (skip the zero-bot check when the attempt was cancelled, mirroring
  `startLocked`'s INFO), but **nothing pinned it**: `stopDuringARestartWins` never asserted
  `lastError`, so deleting the arm again would have been silent. I added one assertion to that test
  — `getLastStartError("g-1")` is `null` after a `/stop` wins the race — and demonstrated
  non-vacuity by deleting the arm: the test fails with
  `but was: "Restart of group g-1 produced 0/3 bots; check logs and bot_creation_failures_total…"`,
  which is the exact string the finding was about. That run also re-confirms Q3 from the side: the
  ERROR line now carries `[g-1//]`.
- **Q3.** `clear()` is now `ALL_KEYS.forEach(MDC::remove)` with a javadoc pointing at
  `snapshot()`/`restore()`, and the four nested scopes use save/restore. I enumerated the five
  `BotMdc.clear()` calls that remain in `BotGroupBehaviorService` and every one is the **outermost**
  scope on a thread that owns its whole MDC (the async lifecycle thread, the per-bot task on the
  virtual-thread-per-task creation executor, `stopAndLogoutLocked` on an HTTP thread, and the two
  scheduler tasks). Nothing nested clears any more.
- **Q6.** The javadoc's decisive argument is the one I had not made: widening the filter to include
  `STARTING` would let the **group being recovered** satisfy its own gate, because a reclaim
  rebuild of that group passes through `STARTING` and `RecoveryEligibility` only vetoes on
  `ACTIVE`. That reopens from the other end precisely the self-attestation hazard the same javadoc
  explains two paragraphs earlier. Accepting the behaviour change and writing down "expect
  `outcome="live_sibling"` to vanish during a large fleet start" is the correct call, and the
  javadoc is the right place for it.

---

## 4. `deleteMidStartCancelsTheBuild` — the reshaping is correct, and it lost one property

**Every assertion I wrote is unchanged**: `createBot` never called, no runtime left behind, nothing
persisted. The cancel check moved from a direct assert to `awaitTrue` — same property, polled
because it now happens on another thread — and one assertion was **added** (the deleter completes
rather than waiting out the start). `awaitTrue` is bounded at 20 s and throws `AssertionError`, so
there is no hang. The reshaping is necessary and right: with R6's lock, calling `stopAndLogout` on
the thread that still owes the build its latch is a genuine deadlock.

**But the reshaped test no longer exercises what the original one did.** With the lock, the delete
parks until the build releases it, so the build's own `finally` has already closed the attempt by
the time `clearRetained` runs — the reshaped test can never see `clearRetained` against an **open**
attempt, which is the hazard its own javadoc says it exists for ("its own `clearRetained` call and
therefore its own copy of the hazard"). Demonstrated, not inferred: mutating `clearRetained` to
also `open.remove(botGroupId)` leaves `deleteMidStartCancelsTheBuild` **green**.

The window is still reachable in production — a DELETE that lands after `/start` was accepted but
before its build takes the lock finds no runtime, runs `clearRetained` on an open attempt, and
returns. So I restored the coverage with a new test rather than by reverting the reshape:

- `deleteBeforeTheBuildTakesTheLockIsNotUndone` — the delete twin of
  `stopBeforeTheBuildTakesTheLockIsNotUndone`, same pre-seeded-lock idiom, and it goes **red** under
  the same mutation that leaves the reshaped test green.

---

## 5. The two deferrals — correctly deferred, one line missing from P13

**P13 (R9) — correctly deferred, and the reasoning is the important part.** "A TTL that drops a
stuck attempt re-opens the race `750fc91` closed" is exactly right, and it is the same invariant
`stopBeforeTheBuildTakesTheLockIsNotUndone` and my new delete twin pin: dropping an open attempt
*uncancels* its build. Naming a generation number on the cancellation predicate (or a bounded
build) as the shape of a real fix is correct, and so is the hook — I verified
`bot.gateway.budget.tier.essential.max-wait=0` and that `GatewayBudgetSettings` permits zero for
`ESSENTIAL` **only**, i.e. `0` really is "unbounded, cancellable". Phase 3 giving `ESSENTIAL` that
wait is what turns P13 from conceivable into expected, and the entry says so.

**One line P13 is missing.** Since R6, a wedged build parks more than `/status`: `/stop` has always
blocked on the group lock, and **`DELETE` now does too**, each holding a servlet thread for the
life of the JVM. That is a consistent extension of an existing property rather than a new defect —
and it is also the *point* of R6 — but P13's "Impact" says only "cannot be retried without a JVM
restart", which understates it: repeated operator attempts to stop or delete a wedged group
accumulate parked request threads.

**P14 — correctly deferred.** The split is honest (the ERROR-storm half is what mattered and is
fixed), the residue really is bounded and self-healing, and folding it into the drain
`PLUGIN_HOT_RELOAD` needs is better than bolting a 10 s join onto `@PreDestroy` now.

---

## Tests added / updated in this re-check

- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/model/BotGroupStatusRollbackSafetyTest.java`
  — new nested `BlastRadius` (3 tests): the boot query's criteria rendered through a real
  `QueryMapper`; a query on another field still converting a poisoned group (the activation-tick
  exposure); one poisoned document losing the healthy groups beside it. Plus the stale
  `ConversionFailedException` / boot-query wording corrected in
  `requestBodiesCannotPoisonTargetStatus`' description.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/model/BotGroupStatusPersistenceGuardTest.java`
  — `noAppendedConstantReachesTheEntityThroughABuilder` + `theBuilderScanRecognisesAnEntityBuilder`
  (2 tests): the builder form of the source guard, scoped to the entity builder so Phase 4's DTO
  rendering is not a false positive.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupStartCancellationRaceTest.java`
  — `deleteBeforeTheBuildTakesTheLockIsNotUndone` (1 test) restoring the `clearRetained`-on-an-open-
  attempt coverage the reshaping lost, and one assertion added to `stopDuringARestartWins` pinning
  Q2's fix.

All six new tests are unit tests over mocked collaborators. **No test in this diff, in the fix
round, or in my additions opens a socket or makes a gateway request** — verified by grepping every
changed test for `HttpClient`, `Socket`, `MongoClients`, `http://`, `wss://` and for any hostname:
nothing. The blast-radius tests use a real `MappingMongoConverter` / `QueryMapper`, which are pure
document and criteria mapping with no driver and no server.

## Gaps (unchanged from the original verdict unless noted)

- **G1 — a real Mongo round trip.** Still absent, still unavailable, and A12 explicitly replaces it
  with the captor test plus the source guard. What I added closes more of it: the criteria rendering
  is now measured, so the only unproven layer is the driver/server, which does not participate in
  enum conversion.
- **New — the server-side filtering claim is Mongo's behaviour, not ours.** I measured that we send
  `{"targetStatus": "ACTIVE"}`; that the server then returns only matching documents is Mongo's
  contract, not something a test in this repo can assert. The list-view test likewise *models* the
  repository's document-by-document conversion rather than proving it.
- **New — P13 and P14 are not covered by tests, deliberately.** A test for P13 would have to wedge
  a build for the life of the JVM; P14's residue is observable only across a real process exit.
- G2-G7 unchanged: the `STARTING` reclaim guard is still read-only coverage, `cancelScope` in the
  pre-runtime window is still inert until Phase 3, the `"budget"` classification arm is still
  Phase 3's, pacing is still Phase 3's to demonstrate, and the UI contract for "200 means accepted"
  still needs the user, not a test.

## Documentation drift noticed while re-checking (not blocking, not Dev's to fix alone)

- **A1 still carries both wrong claims** (`ConversionFailedException`, and the boot query failing).
  Dev corrected `BotGroupStatus`' javadoc and the guard test's and flagged the plan as
  Architect-2's; that is the right division, and the plan edit is still outstanding.
- **`RecoveryEligibility`'s javadoc now cites a route that no longer exists.** It justifies the
  explicit `STOPPED` veto partly with "two known routes produce the pair: a
  `PATCH {"targetStatus":"STOPPED"}`, and a lost Mongo write". The R1/Q1 fix **removed** the PATCH
  route. The veto is still correct and the second route still stands, so this is stale evidence
  rather than a wrong conclusion — but one clause of it is now unreachable.
- **`BotGroupMapper.toEntity` still copies `lastFailureReason`, `lastStartedAt` and `lastStoppedAt`
  from the DTO on create**, immediately beside the line the fix removed, while the method's own
  trailing comment calls all three "system-managed". No rollback hazard (a `String` and two dates
  are readable by any jar), so this is cosmetic — but a reader who sees `targetStatus` closed may
  reasonably assume the whole set is, and it is not. Only `targetStatus` is.

## Failures

None. Seven full-suite runs, no failures, no errors, no flakes.
