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

---

## Re-check — 2026-09-29 (fix round, and A1's own factual errors)

Diff re-reviewed: `git diff 0558a51..HEAD` — **7** commits, `7e118bf`..`72bbb24`, 20 files,
+1,520 / −144 (excluding `review-phase2.md`). Six are Dev's fix round
(`7e118bf`..`d9be331`); the seventh, `72bbb24`, is the **main session's** and is treated as
out-of-scope-but-recorded below.
Plan re-read: `docs/plans/GATEWAY_REQUEST_BUDGET.md`, now **committed** (`8e4a1bc`), including
A1-A13 and the new `## Amendment — 2026-09-29 (A14-A20)`.
Inputs: `qa-phase2.md` (FAIL on Q1; Q2-Q6), `review-phase2.md` (CHANGES_REQUESTED; R1-R13).
Build at `72bbb24`, in a **detached worktree** rather than the working tree (which carries ~58
dirty RIK/Aviator entries): **2,276 tests, 0 failures, 0 errors, 0 skipped** — bot-api 148,
bot-messages 167, bot-strategies 126, bot-engine 494, bot-app 1,341. QA's deliberately-red
`BotGroupStatusRollbackSafetyTest$WritePaths` is green, unchanged by Dev.

### Verdict

**PLAN_AMENDED** — the diff is accepted; the plan was wrong and is corrected.

The plan error this time is **mine**: A1 (written in the 2026-09-23 amendment and relied on ever
since) asserts three things about how this codebase behaves that are false, and QA and the
reviewer each falsified part of it. A1 is now the text a future reader trusts, so it is corrected
in `A14` rather than left to a review file. Nothing goes back to Dev: the fix round closes QA's
blocker and twelve of the reviewer's thirteen findings, and the thirteenth is deferred with a
reason that survives checking.

**Each corrected claim was verified here independently, not accepted on report** — the first is a
claim about Spring Data query translation, so it was measured rather than reasoned about:

| A1 said | Actually | How it was established |
|---|---|---|
| a poisoned document "would fail the whole startup query" | **it does not** — `findByTargetStatus(ACTIVE)` resolves to `ExecutableFind.as(BotGroup).matching(…).all()` and `QueryMapper` renders the criterion as the BSON filter `{"targetStatus": "ACTIVE"}`, a `String`, so the match is server-side and a non-matching document is never read | issued the derived method against a recording `MongoOperations` proxy with a real `MappingMongoConverter`; no server, no mocking of the query path |
| `ConversionFailedException` | `IllegalArgumentException: No enum constant …` from `Enum.valueOf` via `MappingMongoConverter.getPotentiallyConvertedSimpleRead:1420` | read a document holding an undeclared constant name through a real converter |
| (implicitly) the damage is at boot | **`GET /{id}` and `POST /{envId}/filter`** — the latter is `mongoTemplate.find(query, BotGroup.class)` over the whole environment (`BotGroupService:96-105`), so one bad document breaks the **list view for every group beside it**; plus, on the current jar, the group leaves both `findByTargetStatus(ACTIVE)` and `RecoveryEligibility` and is never started or recovered again | read the two call paths |
| `BotGroupDTO.targetStatus` "can still only carry the original three" | true of what it **renders**, false of what it **accepted** — the builder path in `toEntity:92` and the variable path in `updateEntityFromDTO:181` both wrote it, and the source guard could see neither | QA's Q1 / review's R1; re-read both mapper paths |

**And one correction to the correction, which is new here.** Dev, the reviewer and the task
description all describe the rollback symptom as a **500**. It is a **400**:
`IllegalArgumentException` reaching a controller is answered by
`RestExceptionHandler.handleIllegalArgument` (`:94-100`) as `{"type":"Bad request","msg":"No enum
constant …"}`, that arm has existed since `92469f5`, and Spring Data does not wrap the exception
on the way up. The invariant's severity is unchanged; the symptom is *more* misleading than
advertised, because a 400 on the environment list view reads as "the UI sent something wrong" and
points the investigation away from "a document written by a newer jar is unreadable". Three
javadoc strings still say "500s the list" and no code does; folded into Phase 3's doc pass rather
than spending a commit, with A14 as the authority.

### Phase-by-phase

#### Phase 2 — async start/restart, `STARTING`, startup daisy-chain
Status: **implemented**

Every A6 Phase 2 change-list item and every test-list item verified in the first pass still
holds; the fix round changed *how* three of them behave, in the direction the plan asked for.

| Finding | Commit | Verified state |
|---|---|---|
| **R1 / Q1** (blocking) — `targetStatus` client-writable | `7e118bf` | Closed at the boundary: `@JsonProperty(access = READ_ONLY)` + removed from **both** mapper write directions. The guard test gains a **value-level** assertion over `BotGroupStatus.values()` in both directions (survives the next appended constant) and a real-`ObjectMapper` inbound/outbound pair. Chose the boundary over a validator for a reason I agree with and have folded into A14: A3 renders `REGISTRATION_PENDING` from Phase 4 on, so a validator would 400 every PATCH from a read-modify-write client — CLAUDE.md's strategy-key trap, one field over |
| **R2** (security) — raw `Throwable.toString()` on an unauthenticated endpoint | `bebad84` | `ClientSafeMessage` extracts the policy `RestExceptionHandler` already had, and the handler's `INTERNAL_ERROR_MSG` now *comes from it* — one string, one rule, which is materially better than the "classify in two places" the finding asked for. Our own `BotManagerException` hierarchy (+ `IllegalArgumentException`) verbatim, everything else `Internal server error — see server logs (<ClassName>)`. The class name is a deliberate addition over the handler, with the reason stated (a `lastError` holder has no request URI to correlate a log against). `IllegalStateException` is correctly **not** on the safe list, which is why the one message the field genuinely needs is recorded explicitly instead |
| **R10** — zero-bot `/start` left `lastError` null | `bebad84` | `recordFailure(id, …)` on the branch that returns normally; `/status` now answers `DEAD, botsUp: 0, lastError: "Started 0/N bots …"` |
| **Q2** — a successful `/stop` during `/restart` logged an ERROR and poisoned `lastError` | `bebad84` | `restart`'s zero-bot check now returns early when the attempt was cancelled, mirroring `startLocked`'s INFO. The symmetry the finding asked for |
| **R3** — `/health` said `STOPPED` while `/status` said `STARTING` | `9ef553a` | `getActualStatus(id)`, so all three surfaces (`/status`, `/health`, `filterSorted`) agree. **This closes inheritance #5 from the first pass** |
| **R4** — chain logged N ERRORs per restart | `9ef553a` | Cooperative `volatile shuttingDown`, set **before** the executors are torn down, checked at the top of the loop, one INFO naming what was abandoned. Per-group `catch` widened to `Throwable` with the stderr/Loki reason. Not an interrupt, for the documented `connect()`-swallows-interrupts reason |
| **R5** — chain overwrote an operator `/stop` | `9ef553a` | `stillWantsToStart` re-reads each group and skips unless still `ACTIVE` and still not `SCHEDULED`. Same discipline `startForRecovery` applies to the identical race, and the lost write was `STOPPED`, DEAD_GROUP_AUTO_RECOVERY's only opt-out |
| **R12** — scheduled restart swallowed its new synchronous rejection | `9ef553a` | try/catch + ERROR on the scheduler thread |
| reviewer's open question on `onStartup` | `9ef553a` | Settled **deliberately**, which is what the reviewer asked for: log ERROR and **rethrow**, so the container exits and the restart policy retries once Mongo is back, rather than leaving a fleet nothing will ever start. The ERROR is what stops it reading as healthy-then-gone |
| **R6** — DELETE mid-build leaked bots / resurrected the document | `766f245` | `stopAndLogout` now takes the per-group lock (cancel still happens **before** it, so a delete does not wait out the start it is cancelling). QA's `deleteMidStartCancelsTheBuild` correctly moved onto its own thread — with the lock, calling it on the thread that still owes the build its latch is a deadlock; the property under test (cancel lands before the lock) is unchanged and is now asserted *while* the teardown parks |
| **R7** — `startForRecovery` ignored `begin()` | `766f245` | Returns `false` like every other caller, with the group-keyed-registry reason in the comment. Dev's AD-16 argument ("`putIfAbsent` means a restart is never submitted during a start") is now true of *every* path |
| **R11** — attempt leaked between `begin()` and the thread starting | `766f245` | `submit(...)` extracted and wrapped in `catch (Throwable) { finish(id, t); throw t; }` |
| **R8** — `botsUp` counted in the index-ordered join loop | `95d551e` | Moved into the per-bot task, where AD-17 put it, with `botFailed` alongside (including the interrupted path). The join loop keeps the logging and the metric, where deterministic order is wanted. **This makes V2a's "rising toward `botCount`" honest**, which it was not |
| **Q3** — `BotMdc.clear()` in a nested `finally` dropped the caller's scope | `95d551e` | `BotMdc.snapshot()`/`restore()` added and used at **four** nested scopes, not one — the two `startLocked` blocks matter as much as the collection loop, since the zero-bot ERROR and the failed-start ERROR are emitted after them. Leaving the context set was correctly rejected (the chain and the recovery tick run several groups on one thread) |
| **Q6** — `STARTING` silently changed recovery's live-sibling short-circuit | `95d551e` | Decided and documented on `countOpenWsByEnvForActiveRuntimes` itself, including the reverse hazard (a reclaim rebuild of the group being recovered passes through `STARTING`) and the operator-facing consequence (`outcome="live_sibling"` disappears during a large start). A1's audit said "leave", and leaving it *with the reason written down* is the right reading |
| **Q5** — wrong exception type in the docs | `7e118bf` | Corrected in `BotGroupStatus` and the guard test; corrected in the plan here (A14) |
| **R13** — unused surface on `StartAttempt` | `d9be331` | Class is `private`, all accessors and the write-only `finishedAt` gone |
| **R9** → **P13**, **Q4**'s residue → **P14** | `d9be331` | Deferred with reasons — judged below |

**Two reviewer *notes* remain unaddressed, correctly.** `parkRuntimeless` still carries two
meanings (persist `STOPPED`; cancel a start in flight) with no rename — they coincide for all
three of today's callers, the gate is exhaustive, and A10 documents the discriminator, so a rename
is cosmetic churn in a 2,700-line file. And `/start` still reads the group two or three times per
accept; the reviewer labelled it harmless and it is.

### Drift

None new. The fix round introduces no deviation from A6 Phase 2, A1 or A3, and the three
substitutions it makes were all named as required by a finding.

#### Are the two deferrals legitimately out of phase scope?

**P13 (a wedged build parks its group for the JVM's life) — yes, and the entry is right about
why.** A6 Phase 2 asks for `begin`/`progress`/`cancel`/`finish`/`lastError` and AD-17 for
"retained until the next start"; nothing in the plan asks for attempt liveness. More important,
the obvious fix is *wrong*: a TTL that drops a stuck attempt re-opens the race `750fc91`
closed, because dropping an open attempt uncancels its build — so a `/stop` that already answered
`200` could be followed by the group coming up. A real fix needs a generation number on the
cancellation predicate or a bounded build, and the unbounded wait that makes this expected rather
than conceivable is Phase 3's own `essential.max-wait=0`. The entry says all of that and tells
Phase 3 to re-read it. Diagnosable today (a second `/start` prints the attempt's age — confirmed
in the test log: `elapsed 3600s` shape).

**One correction to P13, which I found while checking it and which the plan now carries (A19):**
it is described as though a build that never returns needed an exotic cause. It does not. The four
in-repo gateway calls carry `.timeout(Duration.ofSeconds(10))`; the **login does not go through
them** — it goes through the library's `AuthClient`, which builds its request with no timeout on
an `HttpClient.newHttpClient()` with no connect timeout (`websocket-parser-core-3.0.5` sources,
`AuthClient.java:25,127-137`). A stalled TCP connection on a login parks a build thread
indefinitely **today**. That does not make it Phase 2's to fix — but it does make AD-12's
in-repo login (Phase 5) obliged to set a timeout, and since A7 ships Phases 2-5 as one
deployment, forgetting it ships the exposure. Recorded as A19.

**P14 (an unclean shutdown leaves an in-flight group's bots connected) — yes.** The half that
was a *regression of this phase* (N ERRORs per restart) is fixed; what remains is bounded,
self-healing (the sockets drop when the process exits) and pre-existing in kind. The entry names
the two real options and points at the drain `PLUGIN_HOT_RELOAD` needs anyway. Nothing in A6
Phase 2 asks for a drain.

### Phase boundary

**Still clean.** Grepped the whole fix-round diff for
`registrationState`/`registerUsers`/`registeredCount`/`circuit`/`waiter`/`ceiling`/`tryExecute`/
`reserve(`/`Retry-After`/`ENFORCE`/`cancelScope`/`runWsUpgrade`/`Cloudflare`/`countWsUpgrade`:
**every hit is javadoc, a FOLLOWUPS entry or `review-phase2.md`.** `SlidingWindowGatewayBudget`,
`GatewayBudget`, `ApiGatewayClient`, `application.properties`, `prometheus/`, `docker-compose.yml`
and `logging/` are untouched by `0558a51..HEAD`.

**`STARTING` is still `actualStatus`-only** — and the perimeter is stronger than it was:
`grep setTargetStatus( | grep -E "STARTING|REGISTRATION"` over all five modules' `src/main` is
empty, the mapper carries the field in neither direction (asserted by value, for every constant),
Jackson refuses it inbound (asserted through a real `ObjectMapper`), and
`startingIsNeverPersisted`'s captor still shows only `ACTIVE`.
**The enum is still append-only** — `BotGroupStatusAppendOnlyTest` (3 tests) green, ordinals
0/1/2 pinned through the real `BotSortKey.STATUS` extractor, exactly six constants.

### Out-of-scope changes

- **`72bbb24` is the main session's, not Dev's**, and sits inside the reviewed range: the
  releaser smoke-test fix (A13.2, user-approved, keyed on chain completion) and
  `docs/reviews/GATEWAY_REQUEST_BUDGET/gwms-register-envelope.md`, a live capture of the gwms
  `EXISTED` envelope. Both are legitimate and both **close open items** rather than adding scope —
  A13.2 and Open Item 13. The envelope capture carries one fact A2 did not anticipate (a
  re-register returns **no tokens**, so a resumed index costs three DEFAULT requests and
  `registeredCount` alone cannot express "registered but not named"); it amends A6 Phase 4 items 1
  and 3, recorded as A17.
- Nothing else. Provenance re-checked: the ~58 dirty working-tree entries (`Aviator.js`, the RIK
  dispatch tests, `deploy.sh`, …) are untouched by `0558a51..HEAD` and were not staged, stashed or
  reverted. Only `docs/plans/GATEWAY_REQUEST_BUDGET.md` and this file are staged by this pass.

### Amendments to the plan

Appended as `## Amendment — 2026-09-29 (A14-A20)`; nothing above A14 was rewritten.

- **A14 — A1's three factual errors**, each with the probe that falsified it, plus the 400-not-500
  correction, plus how the write hole was closed and why a validator would have broken A3. Carries
  one new constraint into Phase 4: `registeredCount` must be render-only for the same reason, or
  A2.1's high-water-mark invariant is client-writable.
- **A15 — Open Item 1 closed** (WS hosts share the Cloudflare rule). Includes the two things the
  answer changes that were not in the plan: reconnects are cap consumption at ~180 per 5-minute
  window in the observed staging hot loop, and the recovery probe reads a Cloudflare block page as
  *healthy*, which makes AD-13's circuit gate on recovery load-bearing rather than an optimisation.
- **A16 — Open Item 7 closed**: no tolerable cooldown. Renames `block-cooldown` →
  `block-probe-interval` (60m), makes an open circuit **refuse every tier instead of parking
  ESSENTIAL** (with the rule behind it: an unbounded wait is only admissible where progress is
  guaranteed), restates `Retry-After`'s meaning, fixes the alerting and its annotation, rules out
  a manual-close endpoint, and records the two prevention gaps (per-environment keying vs a
  per-IP×zone rule; `bulk-create-accounts.py` as a rule not a caveat).
- **A17 — A13.1, A13.2 and Open Item 13 closed**, with the three things the envelope capture
  obliges Phase 4 to change.
- **A18 — Phase 2 verification**: V2a/V2b/V2c/V2f stand; four steps added (V2g `/health` agrees,
  V2h the chain honours a `/stop`, V2i one INFO not N ERRORs, V2j `lastError` is sanitised, V2k
  one curl for the Phase-2 blocker).
- **A19 — `FOLLOWUPS.md` P13/P14 are taken**, Open Item 10's reference to "P13" is stale (it is
  P15), and P13's reachability is corrected with the `AuthClient` timeout finding and the
  obligation it places on AD-12.
- **A20 — what Phase 3 absorbs, ordered** (twelve items), superseding the first pass's inheritance
  list.

### Release concerns for the Releaser (updated)

1. ~~The plan is uncommitted~~ — **closed**: `8e4a1bc`. The branch a Releaser checks out now
   carries the plan it implements. Note the pre-A8 `202` / `startAttempt` text still stands in the
   original `## Verification` section; **A8 and A18 are the authoritative Phase 2 steps.**
2. ~~`.claude/agents/releaser.md:67` will abort the deploy~~ — **closed**: `72bbb24`.
3. ~~CLAUDE.md's REST table is stale~~ — **closed**: `8e4a1bc`.
4. **Two externally visible behaviour changes to release-note**, both improvements:
   `/restart` of a group with no `gameId` is now a 400 that leaves the group running (was
   stop-then-500); and `PATCH`/`POST` bodies no longer accept `targetStatus` at all — it is
   rendered and ignored. Any client that was setting a group's lifecycle through PATCH (nothing
   in this repo does) must use `/start` and `/stop`.
5. `lastError` on `GET /{id}/status` is now sanitised; a failure that used to appear as a raw
   `Throwable.toString()` appears as `Internal server error — see server logs (<ClassName>)`, with
   the detail in `console.log`'s ERROR line.
6. Nothing deploys after Phase 2 alone — A7's single cut is after Phase 5.
