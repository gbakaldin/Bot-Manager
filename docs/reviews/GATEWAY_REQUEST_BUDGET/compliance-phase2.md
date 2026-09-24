# Compliance — GATEWAY_REQUEST_BUDGET, Phase 2 (async start/restart, daisy-chain, progress)

Branch: `feature/gateway-request-budget`
Plan reviewed: `docs/plans/GATEWAY_REQUEST_BUDGET.md` — **working tree, uncommitted**; the
governing text (Amendments A1-A9, 574 lines) is not in any commit. Read top to bottom, with
A1 / A3 / A6 / A9 as the authority for this phase.
Diff reviewed: `git diff d947c09..HEAD` — 6 commits `b2a6799`..`750fc91`, 22 files,
+2,559 / −144.
Phase 1 verdict: `compliance.md` in this directory (PASS).

## Verdict

**PLAN_AMENDED**

The diff faithfully implements A6 Phase 2. Three of the plan's own instructions cannot be
carried out as written — one would break `/restart`, one is an unmatchable grep, one asks for
test infrastructure that does not exist in this repo — and they are corrected in
`## Amendment — 2026-09-24 (A10-A13)` at the bottom of the plan. Nothing goes back to Dev.

Two documentation edits the plan assigns to Dev are **outstanding and release-blocking**; one of
them (`.claude/agents/releaser.md:67`) will abort a good deploy at its first smoke step. Both
are recorded as A13 and are owned by the main session.

**Invariants — all four hold:**

| Invariant | Evidence |
|---|---|
| `STARTING` is `actualStatus`-only, never reaches Mongo | `BotGroupRuntime:169` born `STARTING`, flipped at `startLocked:910`; `startingIsNeverPersisted` captures every `save(group)` as `ACTIVE` while the runtime reads `STARTING`; `BotGroupStatusPersistenceGuardTest` scans all five modules' `src/main/java` for `setTargetStatus(` + the three constants, with a non-vacuity test |
| Enum appended, never inserted | `BotGroupStatusAppendOnlyTest`: ordinals 0/1/2 pinned, exactly six constants in A1's order, plus an explicit `BotSortKey.STATUS` ordering assertion |
| `/start` and `/restart` = 200 + DTO meaning accepted | `BotGroupController:156,244`; `BotGroupControllerTest` asserts `200` + `actualStatus: "STARTING"` + `botCount`/`botsUp`/`lastError` on both, and `never()).start(...)` / `never()).restart(...)` |
| No Phase 3/4/5 work smuggled in | Grepped the whole diff for `registrationState=`/`registerUsers`/`circuit`/`waiter`/`ceiling`/`tryExecute`/`reserve(`/`Retry-After`/`ENFORCE`: five hits, **all in javadoc**. `SlidingWindowGatewayBudget`, `GatewayBudget`, `ApiGatewayClient`, `application.properties`, `prometheus/`, `docker-compose.yml` are untouched by this diff |

Build evidence: the 11 test classes this phase touches run green — 219 tests, 0 failures,
0 errors, 0 skipped (Java 21).

## Phase-by-phase

Only Phase 2 was in scope. Phase 1 is `SHIPPED` in the plan and unmodified by this diff.

### Phase 2 — async start/restart, `STARTING`, startup daisy-chain
Status: **implemented** (one change-list item outstanding by authority boundary, see A13)

Change list, item by item:

| # | A6 Phase 2 item | State |
|---|---|---|
| 1 | `StartAttemptRegistry` in `bot-app/domain/botgroup/service`: `begin` (putIfAbsent), `progress`, `cancel`, `finish`, `lastError`; `StartAttempt` with `AtomicInteger botsUp/botsFailed`, `phase`, `startedAt`, cancel flag | **implemented**, superset (`isOpen`, `isCancelled`, `botUp`, `botFailed`, `origin`, `describe`, `clearRetained`). `progress(id, Phase)` is explicit rather than auto-advancing — see Judgement 7 |
| 2a | `startAsync` / `restartAsync` on a virtual thread, attempt opened synchronously, activation-mode restore in the task's failure path | **implemented** via `submitLifecycle(id, origin, onFailure, action, lifecycle)`; the restore travels as a `Runnable` because a `catch` cannot see a failure that lands after the response |
| 2b | `startLocked` opens/advances/finishes the attempt, increments `botsUp`/`botsFailed` in the creation lambda | **implemented with a required relocation**: the open/finish sit at the accept boundary (`submitLifecycle`, `startTracked`, `startForRecovery`), not in `startLocked`. Necessarily so — `restart()` calls `start()`, so opening in `startLocked` would open a *second* attempt inside a restart and collide with its own `putIfAbsent`. `startLocked` advances the phase and the counters, which is the part that belongs there |
| 2c | `isGroupRunning` consults the registry (AD-16) | **implemented**: `ACTIVE ∥ STARTING` on the runtime, **or** an open attempt when there is no runtime yet. Both windows, as A1's consumer audit requires; `ActivationScheduler:130` and `RecoveryCandidateSelector:82` inherit it unchanged |
| 2d | `stop(id, …)` cancels the attempt before locking; in this phase cancellation only prevents further bots being built | **implemented**, gated on `parkRuntimeless` → **plan amendment A10** |
| 2e | `onStartup` → `ApplicationReadyEvent` + `startup-chain` thread (AD-14) | **implemented**: `@EventListener(ApplicationReadyEvent.class)`, the `queued for daisy-chained start` line before the chain starts, `startup complete` moved to the end of `runStartupChain`, per-group `try/catch` kept, SCHEDULED still skipped |
| 2f | `scheduleRestart` → `restartAsync` | **implemented** (`SCHEDULED_RESTART` origin) |
| 3 | `ActivationScheduler` START → `startAsync(id, SCHEDULE)`; STOP unchanged | **implemented** |
| 4 | `/start` `/restart` → 200 + `BotGroupStatusDTO`; `getStatus`/`getHealth` carry the progress; `StartAttemptDTO` dropped | **implemented**. `BotGroupStatusDTO` gains exactly A1's four named fields (`botCount`, `botsUp`, `registeredCount` — declared, always null until Phase 4 — `lastError`); assembled in one private `statusDTO(group, actualStatus)` so the ack and `/status` cannot drift. `getHealth` picks up the new value for free from `runtime.getActualStatus()`. No `StartAttemptDTO` and no `startAttempt` block anywhere |
| 5 | `AGENTIC_WORKFLOW.md` universal smoke; **CLAUDE.md REST rows** | AGENTIC_WORKFLOW **implemented** (`3ee11ed`, with an explicit "do not grep `startup complete`" note). CLAUDE.md rows **outstanding** → A13 |
| A1 | consumer-audit edits: `startLocked` reclaim guard, `isGroupRunning`, `BotGroupRuntime` initial status | **all three implemented**; `countOpenWsByEnvForActiveRuntimes` and `performPeriodicLogout` correctly **left alone**, as A1 directs |

Test list:

| A6 Phase 2 test | State |
|---|---|
| `StartAttemptRegistryTest` (begin idempotent while open; finish retains `lastError`; cancel flips the flag) | **implemented**, 16 tests in 5 nested classes, including `leavesAnOpenAttemptAlone` (the `750fc91` race) |
| `BotGroupBehaviorServiceAsyncStartTest` (attempt visible before the runtime; second `/start` submits no second task; stop during a build cancels it and leaves no runtime; `isGroupRunning` true while an attempt is open) | **implemented**, all four, plus `startingIsNeverPersisted`, the `onFailure` callback, synchronous validation, and `/restart`'s zero-bot error landing in `lastError` |
| `StartupChainTest` (one at a time in order; a failing group does not stop the chain; the "queued" line precedes the first start; SCHEDULED still skipped) | **implemented**, all four, plus `onStartup()` returning while the chain still runs — the assertion that Tomcat is never held |
| `ActivationSchedulerAsyncStartTest` | **implemented as a substitution** — folded into `ActivationSchedulerTest`. See Judgement 7 |
| `BotGroupControllerTest` (`/start` 200 + body, `/restart` 200, 404 and the two 400s synchronous, mode restored on async failure) | **implemented**, both rollback halves distinguished (`asyncFailureRollsBackModeThroughTheCallback` runs the captured `Runnable`; `synchronousFailureRollsBackMode` keeps the `catch` path) |
| `BotGroupStatusAppendOnlyTest`, `BotGroupStatusPersistenceGuardTest` | **implemented** |
| Mongo round-trip of a mid-start group's `targetStatus` | **absent, substituted** → plan amendment A12 |

## Drift

### Judged item by item, as asked

**1. AD-16's "restart()'s internal stop does the same" is wrong — Dev is right.**
*Verified independently, not taken on report.* `restartAsync` opens the attempt in
`submitLifecycle` **before** the virtual thread runs; `restart(id)` then calls `stop(id, false)`.
An unconditional `cancelStartInFlight` there sets `cancelled` on the restart's *own* attempt →
every `createBotsInParallel` task returns `null` → the post-build `isCancelled(id)` check unwinds
the runtime → `restart`'s zero-bot guard throws. Every `/restart` becomes a `/stop` plus a
spurious `lastError`. The `parkRuntimeless` gate is also the *right* discriminator, not merely a
workaround: it is already the flag DEAD_GROUP_AUTO_RECOVERY AD-5 uses to separate a statement of
intent from a teardown step, and there are exactly two call sites (`stop(id)` → `true`,
`restart` → `false`), so the gate is exhaustive. Dev's second argument also holds —
`restartAsync`'s `putIfAbsent` means a restart is never submitted while another start is open, so
the internal stop can never be the thing that must cancel someone else's start.
→ **genuine plan error; amended as A10.**

**2. The cancellation check is re-taken under the semaphore permit.**
Correct and load-bearing. `createBotsInParallel` submits all N `supplyAsync` tasks in one loop,
so all N clear any pre-`acquire()` check within milliseconds and then park on the semaphore; at
`parallelism=10` a 3,000-bot group has ~2,990 tasks already past it before a `/stop` can plausibly
arrive. Only a task that has just taken a permit is about to spend gateway requests, so the
under-permit check is the one that prevents them. The plan's own wording ("via the flag the
creation lambda checks") is satisfied either way and is not wrong — it simply does not say
*where*, and only one placement works.
→ **faithful reading of intent; no plan change.** Keeping the cheap pre-`acquire()` check as well
is right: it stops a cancelled build queueing for permits it will not use.

**3. The `750fc91` race — `clearRetained` drops only the retained record.**
A real bug, correctly diagnosed and correctly fixed. Traced: `/stop` in the accepted-but-not-yet-
locked window → `cancelStartInFlight` sets the flag → the lock is free → `runtime == null` →
`parkRuntimeless` persists `STOPPED` → the old `finally` dropped the **open** attempt → the build
then woke, saw no cancellation, and brought the group up over an operator's `STOPPED`. Dropping
only `last` keeps the flag where the build polls it, and the build's own `finally` remains the
only thing that closes an open attempt. `StartAttemptRegistryTest.leavesAnOpenAttemptAlone` pins
it, and AD-17's "retained until the next start or stop" is still honoured (the stop half now
clears the *record*, not the *attempt*).
→ **faithful; a defect found and fixed inside the phase. No plan change.**

**4. V2b's grep is unmatchable.**
Confirmed empirically — the shipped line, observed in the test run, is
`group g-1 (Group): start admitted — origin RECOVERY, 3 bots`. `grep -c "start admitted.*<GID>"`
can never return 1. Keeping AD-18's shape is right: it is the format the estimate, the rollup and
Phase 3's declared demand all extend. The verification step is what is broken.
→ **genuine plan error; amended as A11**, with a second grep added so a `1` is distinguishable
from a group that was never started. (Checked the neighbours too: **V2c**'s
`grep -E "start of group <GID> cancelled"` *does* match `A stop during the start of group <GID>
cancelled the build at n/m bots`. **V2a/V2d** are already corrected by A8. **V0b** matches the
shipped `queued for daisy-chained start` line.)

**5. A1's Mongo round-trip test is not achievable, and the substitute is adequate.**
Verified: no Testcontainers, no flapdoodle/embedded Mongo, no `@DataMongoTest` anywhere in the
repo. The step implies adding a container or embedded server to the build for one assertion, and
it would prove **less** than what shipped — the invariant is a negative over all call sites, and
no round trip of one scenario can observe the absence of a write. `startingIsNeverPersisted`
covers the dynamic half one layer above the driver a round trip would have stubbed anyway
(`save(group)` captor: `containsOnly(ACTIVE)` while the runtime reads `STARTING`), and the source
guard covers the "never" — the half a round trip structurally cannot reach. Adequate for a
rollback-safety invariant.
→ **genuine plan oversight; test expectation amended as A12.**

**6. Phase 4 deferrals are correctly scoped.**
A6 Phase 4 item 4 explicitly owns "the `startLocked` and `ActivationScheduler` guards"; A6 Phase 4
item 1 owns the `registrationState` / `registeredCount` document fields. Neither guard is
expressible without them, and `registeredCount` on `BotGroupHealthDTO`/`BotGroupDTO` has nothing
to read. A1's prose reads as though the DTO fields land now; A6 is the authoritative phase list
and A6 defers them. `BotGroupStatusDTO.registeredCount` *is* declared now (always null) so the
response shape does not change again — which is what A1 actually needs from this phase. The
`validateStartable` seam is in place and its javadoc names Phase 4's guards as its next tenant.
→ **faithful; correctly deferred, no amendment.**

**7. The smaller items — all faithful.**
- *`StartAttemptRegistry` an owned `final` field, not a `@Component`.* The plan never said
  `@Component`, only the package. The stated reason is real and visible in the diff: the two
  existing `@InjectMocks` fixtures of this 2,600-line service needed one added mock
  (`GatewayBudgetRegistry`) and **no** null guards anywhere in the registry's call sites.
- *`progress(id, Phase)` explicit.* Superset of the plan's `progress(id)`; the two advance sites
  (`BUILDING` before the build, `STARTING_BOTS` after it) are distinct, and an auto-advancing
  counter would have to know the shape of `startLocked`.
- *Activation assertions folded into `ActivationSchedulerTest`.* Acceptable — it asserts
  `startAsync(eq(id), eq(SCHEDULE), any())` **and** `never()).start(anyString())`, which is the
  mechanism by which the tick does not block, and it adds `never()).startAsync(...)` to all five
  no-decision cases so a future regression cannot silently start a group. One sub-assertion has
  no home there: *"the next tick sees the group as running"* is undecidable with a mocked
  `behaviorService`, and it is covered where it is real —
  `BotGroupBehaviorServiceAsyncStartTest.startingIsVisibleBeforeTheRuntimeExists`. Coverage is
  split, not missing.
- *`BotGroupBehaviorService` gains a `GatewayBudgetRegistry` parameter.* Required by AD-8/AD-16:
  `stop()` has to call off the queued requests, and the queue belongs to the environment's
  budget. `find()` (not `forEnvironment`) is right — conjuring a budget and a fresh set of
  `gateway_budget_*` series as a side effect of a stop would be a lie about the fleet.
- *`/restart` validates before its internal stop.* A consequence of AD-15's "the two
  `BadRequest` checks stay synchronous", and a strict improvement: a `/restart` of a group with
  no `gameId` used to stop it and then 500; it is now a 400 that changes nothing. **Name it in
  the release notes** — it is externally visible.
- *`countOpenWsByEnvForActiveRuntimes` unchanged.* Exactly what A1's consumer audit instructs
  ("leave"; at most one extra anonymous probe per tick).

**8. The two documentation edits Dev did not make.**
Dev's reasoning is correct and I share the constraint: no agent message authorises editing
`CLAUDE.md` or anything under `.claude/`. Recorded as **outstanding, release-blocking**, and the
plan now says who owns them (A13). Both verified as still un-edited:
- `CLAUDE.md:672,674` — still `Start all bots in group` / `Restart all bots in group`, with no
  mention of 200-means-accepted, the DTO, `STARTING`, or `lastError` replacing the zero-bot 500.
- `.claude/agents/releaser.md:67` — still
  `grep -E "Started Starter|startup complete"`, with `Expect: at least one line matching each
  pattern` and *"If either smoke check fails, **stop**"*. Under the daisy-chain `startup complete`
  arrives at the end of the chain — minutes today, up to an hour once paced — so **the Releaser
  will abort a healthy deploy at its first smoke step.** This is the single most likely way this
  branch fails on release day, and it fails in the direction of a false alarm.

## Out-of-scope changes

None in production code. Everything in the diff traces to A6 Phase 2, A1, A3 or AD-14/15/16.

Two items are *additions* rather than out-of-scope:

- **`BotConfiguration.startCancelled` + `Bot.requestCancelled()`** (`d9c4e6a`) — the second half of
  AD-8's cancel predicate, which Phase 1's `Bot.scope()` javadoc already named as Phase 2's
  ("Phase 2's start-attempt registry adds the second half"). It is **inert today**:
  `SlidingWindowGatewayBudget` does not consult `scope.isCancelled()` in either mode, and
  `cancelScope` is a documented `log.debug` stub. Plumbing with zero behaviour change, not
  premature enforcement. Covered by two new `BotGatewayTierTest` cases, including the null-supplier
  fallback.
- **`docs/process/AGENTIC_WORKFLOW.md`** (`3ee11ed`) — asked for by change-list item 5.

Provenance: the diff is clean of the branch's unrelated RIK/Aviator work. The ~57 dirty
working-tree entries (`Aviator.js`, `BettingMiniGameBot`, `GameMessageTypes`, the RIK dispatch
tests, `deploy.sh`, …) are untouched by `d947c09..HEAD` and were not staged, stashed or reverted.

## Amendments to the plan

Appended to `docs/plans/GATEWAY_REQUEST_BUDGET.md` as
`## Amendment — 2026-09-24 (compliance pass on Phase 2; A10-A13)`, in the existing convention.
Nothing above A10 was rewritten.

- **A10 — AD-16 corrected.** `restart()`'s internal stop must *not* cancel the attempt;
  cancellation is gated on `parkRuntimeless`. Justification: implementing AD-16 literally turns
  every `/restart` into a `/stop`, traced through `submitLifecycle` → `restart` → `stop(id, false)`
  → the creation lambda's `isCancelled` → the zero-bot guard.
- **A11 — V2b corrected.** `grep -c "<GID>.*start admitted"`, plus a second grep for the
  refusal line. Justification: the shipped log line puts the group id first, so the planned
  pattern matches nothing — confirmed against the line emitted in the test run.
- **A12 — A1's test expectation corrected.** The captor test plus the source guard replace the
  Mongo round trip. Justification: the repo has no Mongo test infrastructure of any kind, and a
  round trip cannot see the absence of a write, which is what the invariant is.
- **A13 — ownership recorded.** The `CLAUDE.md` REST rows and `.claude/agents/releaser.md:67`
  are the main session's, with the releaser grep flagged as release-blocking.

## Release concerns for the Releaser

1. **The plan itself is uncommitted.** All 574 lines of Amendments A1-A9 — the text that governs
   this phase, including the persistence split, the `200` decision and the phase renumbering —
   exist only in the working tree. A13's A10-A13 block is appended to the same uncommitted file.
   The branch a Releaser checks out therefore contains Phase 2's code and **not** the plan it
   implements, and `## Verification` on the branch still carries the pre-A8 `202` /
   `startAttempt` text. Commit the plan before the single deployment (A7).
2. **`.claude/agents/releaser.md:67` will abort the deploy** (A13). Fix before running.
3. **`CLAUDE.md`'s REST table is stale** for `/start` and `/restart` (A13).
4. `/restart` of a group with no `gameId` is now a 400 that leaves the group running, instead of
   a stop-then-500. Release-note it.
5. Nothing deploys after Phase 2 alone — A7's single cut is after Phase 5.

## What Phase 3 and Phase 4 inherit that the plan does not yet say

The seams are real. Each was checked against the shipped code, not the javadoc.

**Seams present and sufficient for Phase 3:**

- `GatewayRequestScope.isCancelled()` now composes both halves (`Bot.isStopped()` **or** the
  group's `startCancelled` supplier), so the moment Phase 3 consults the scope at admission, a
  cancelled group start calls off *every* one of its bots' queued requests — including bots that
  are individually healthy and unstopped. That is the hook AD-8 needs and it did not exist before.
- `stop()` / `stopAndLogout()` call `cancelScope(groupId)` **before** taking the group lock. The
  ordering — the load-bearing part — is introduced now, one phase ahead of the queues it protects.
- `startAttempts.isCancelled` is polled twice in the creation lambda; the under-permit check is
  the effective one.
- `validateStartable(group)` is the shared pre-flight for **every** entry point (`/start`,
  `/restart`, the chain, the reconciler, recovery) — where Phase 4's registration guards go.

**Five things the plan does not yet say:**

1. **`stop()`'s promptness now depends on the budget, exactly as asked.** Today a cancelled
   build returns within one in-flight HTTP call per permit (≤ `parallelism` of them), so V2c's
   "stop 200 within 5 s" is comfortable. Once Phase 3 has waiter queues, a bot that has already
   passed the under-permit check and parked **inside** the budget cannot return until
   `cancelScope` wakes it — so V2c becomes a direct test of `cancelScope`, and A5.1's
   "enforcement must reach `run` and `runWsUpgrade`" becomes a *stop-latency* requirement, not
   only a pacing one. A5.2's decision (an uncounted WS upgrade is still cancellable) is what keeps
   that true when `count-ws-upgrades=false`. **Phase 3's `cancelScope` must wake waiters, not
   merely mark them**, and it must match on `GatewayRequestScope.botGroupId` — which is the key
   `Bot.scope()` already supplies and the key `cancelStartInFlight` already passes.
2. **`cancelStartInFlight` resolves the environment from the *runtime*, so it is blind before
   the runtime exists.** `startLocked` creates and publishes the runtime (`:759`, `:763`) before
   `createBotsInParallel` (`:824`), so today every bot that can have queued anything implies a
   runtime — the lookup cannot miss. **AD-7's `reserve(tier, n, scope)` breaks that** if the
   declared-demand reservation is taken at the top of `startLocked`, before `:763`: a `/stop` in
   that window would leave the reservation shrinking lower ceilings with nothing to release it.
   Phase 3 must either place `reserve` after the runtime is published, or resolve the environment
   id from the group document in `cancelStartInFlight`.
3. **A4's `authenticate` rewrap is still correctly scoped to Phase 3, and this phase raises its
   stakes.** `ApiGatewayClient` is untouched here and nothing throws `GatewayBudgetException` yet,
   so the defect is still latent. But `/start` no longer has an HTTP response, so a budget outcome
   during a build is now visible *only* through `classifyCreationFailure`'s tag,
   `bot_creation_failures_total` and the attempt's `lastError`. If `authenticate` keeps rewrapping
   to `UpstreamLoginException`, the `"budget"` arm Phase 1 shipped stays dead **and there is no
   longer a 502 at the caller to hint otherwise** — a paced start and a brand-wide auth outage
   become indistinguishable to the operator. One correction to A4's framing: cancelled bots return
   `null` and are counted as `skipped`, deliberately outside both the failure list and
   `bot_creation_failures_total`, so A4's concern is about *paced* requests only, not cancelled ones.
4. **A5.5's two tripwire rewrites are still correctly scoped, with one addition.** Both named
   tests (`aCancelledScopeIsStillAdmittedInPhaseOne`, `aCancelledScopeIsAdmittedInEitherMode`) are
   untouched by this diff and still assert Phase 1 semantics, so the analysis in A5.5 holds
   verbatim. What changed is the *population*: before this phase the only cancelled scope in the
   fleet came from `Bot.isStopped()`; now every bot of a cancelled group start has one. Phase 3's
   rewrite must therefore assert the **group-level** half too — a cancelled scope whose bot is
   perfectly healthy — or it will pin only the case that was already true. `BotGatewayTierTest`'s
   new `theScopeCarriesBothHalvesOfCancellation` is the fixture to lift from.
5. **A residual cosmetic asymmetry, for whoever writes Phase 4's DTO work.** In the window
   between "accepted" and "the runtime exists", `GET /{id}/status` reads `STARTING` (from the
   attempt) while `GET /{id}/health` reads `STOPPED` (it consults `runningGroups` only). Short
   today and harmless; A1 only requires health to carry the new *value*, which it does via
   `runtime.getActualStatus()`. Worth closing when `registeredCount` is added to
   `BotGroupHealthDTO` in Phase 4, since that is the next edit to the same method.
