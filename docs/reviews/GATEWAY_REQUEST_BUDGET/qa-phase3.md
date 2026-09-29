# QA — GATEWAY_REQUEST_BUDGET Phase 3 (enforcement behind `mode=enforce`)

**Verdict:** PASS
**Build:** `mvn test` → **2,356 tests, 0 failures, 0 errors, 0 skipped** (five full runs, identical)
**Branch:** `feature/gateway-request-budget`, diff `b818e04..4bad37a` (12 commits: `6a8d69e`..`4bad37a`)
**Verified in:** a detached `git worktree`, never in the working tree (59 dirty unrelated RIK/Aviator
entries — `08c52a3` swept 134 lines of exactly that back out, and the same check caught a tip that did
not compile in an earlier phase). Runs 1 and A-D are at Dev's tip `4bad37a`; the final run is at QA's
own commit `391bdff`, whose parent `6762698` (the docs-only compliance pass) landed while I was
measuring — so the final run covers the current branch tip, docs commit included.

| Run | Tree | tests | failures | errors |
|---|---|---|---|---|
| 1 | `4bad37a` exactly as committed | **2,340** | 0 | 0 |
| A, B, C | + QA's 16 tests | 2,357* | 0 | 0 |
| D | + QA's 16 tests, surefire reports wiped first | **2,356** | 0 | 0 |
| E | the committed tip `391bdff`, fresh worktree | **2,356** | 0 | 0 |

\* 2,357 in runs A-C is an artefact of my own aggregation, not of the tree: surefire does not delete
stale report files, so a `-Dtest=GatewayBudgetEscalationIT` report from a targeted run was still on
disk and being counted. Run D wiped `*/target/surefire-reports` first. **2,340 + 16 = 2,356 exactly**,
and run D also confirms the escalation IT leaves no report in a default run — i.e. it really is
excluded by name.

`GatewayBudgetEscalationIT` on demand: **4 tests, 0 failures, 4 runs** (3 at `4bad37a`, 1 at the
committed tip), ~37 s each.

```
mvn -o -pl bot-engine -am test -Dtest=GatewayBudgetEscalationIT \
    -Dsurefire.failIfNoSpecifiedTests=false -Dgroups=stub-gateway
```

**Why PASS.** Every one of the six behavioural invariants holds under test, and I could not construct
a wedge. Four findings below are non-blocking; **one of them is a claim in Dev's own report that does
not hold** (Q1) and one is a coverage gap I closed myself rather than report (the open-circuit refusal
path shipped with no test reaching it at all). Nothing here changes a shipped behaviour or a number.

---

## Tests added (16 test methods, 4 files, all green, all mutation-checked)

- `bot-engine/src/test/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudgetCircuitRefusalTest.java`
  (new, 5 tests) — **A16.2, which shipped with no test able to reach it.** `circuitOpen` is a private
  `AtomicBoolean` that nothing writes until Phase 5, so all four branches guarding it were covered by
  reading the source only. Flipped through reflection, which is the only seam:
  - every tier is refused with `GatewayCircuitOpenException` and **ESSENTIAL does not park** — the
    assertion is as much "it returned" as "it threw", since ESSENTIAL's configured wait is `0`;
  - `outcome="circuit_open"` per tier, `outcome="timeout"` untouched, **window stays 0**, rollup
    fragment reads `circuit=open`;
  - `run()` and `runWsUpgrade()` are refused too, **including with `count-ws-upgrades=false`** (A5.2's
    "uncounted is not sendable-into-a-block" — the one branch in that else-arm);
  - a soft caller (`tryExecute(ZERO)`, i.e. the drift read on a library thread) gets `empty()`, not a
    throw into the message pipeline;
  - **a waiter already queued when the circuit opens is not released into the block by a pass** — the
    admission pass has its own check, and it is the one that matters during an outage;
  - `observe` refuses nothing even with the flag set.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudgetClockDisciplineTest.java`
  (new, 3 tests) — the regression test for the stale-clock defect. See Q1: **reinstating that defect
  left the entire suite green, the escalation IT included.** Discriminates deterministically with a
  clock that jumps a whole window between the pre-lock and in-lock reads.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/client/BoundedLoginTest.java`
  (new, 4 tests) — `BoundedLogin`'s only existing coverage is a **source grep** (`GatewayCallSiteGuardTest`
  looks for the strings `extends AuthClient`, `LOGIN_TIMEOUT`, `shutdownNow()`), which proves the
  mechanism is *mentioned*. Behaviourally, against a `ServerSocket` on the **loopback address, ephemeral
  port**, that accepts and answers nothing: the bound really elapses and returns; **the abandoned
  exchange is really aborted** (the server observes the hang-up — without `abort()` the leak just moves
  from the caller to a thread nobody can see, still holding the socket); a successful login is unchanged;
  and a library failure keeps its own type rather than becoming `IllegalStateException`, which is what
  `ApiGatewayClient.authenticate`'s `RuntimeException` arm is built on.
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BotReconnectTest.java`
  (+2 tests, new nested `BudgetRefusedReauthTests`) — the **tri-state at the loop level**, which is
  where the wedge is. `PerformReauthTests.aBudgetRefusalIsNotTerminal` pins the return value; nothing
  pinned the consequence. Collapsing RETRYABLE into TERMINAL makes `runAuthThenWsLoop` **return with
  `reconnecting` still set and no worker running** — a bot that never reconnects, never goes DEAD and
  never counts toward its group's dead ratio, because `triggerFullReconnect` short-circuits for ever
  on the flag it left behind. Both call sites (`runAuthThenWsLoop`'s immediate re-auth and
  `runWsReconnectLoop`'s in-loop one) are covered, and both assert the loop still **terminates bounded**
  (DEAD after the same 9 authenticate attempts the TERMINAL route gets).
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/PeriodicLogoutBudgetOutcomeTest.java`
  (new, 2 tests) — `ad2ae5b`'s arm, which shipped untested. A refused reconnect is DEBUG with nothing
  at ERROR or WARN, and `logout()`/`restart()` were both really attempted (which is what makes the
  outcome self-healing); plus the counterweight — a genuine failure is **still** an ERROR, so the arm
  cannot have been widened.

### Mutation testing: every assertion above, and Dev's, was made to fail

I reverted eight production behaviours one at a time and re-ran. What each one broke:

| Mutation | Result |
|---|---|
| `admit()` stops refusing a cancelled scope | **3 failures** — *both* rewritten tripwires fire (`SlidingWindowGatewayBudgetWindowTest.aCancelledScopeIsOnlyStampedWhenItIsReallySent`, `ObserveModeTest.aCancelledScopeIsRefusedAndNotStampedUnderEnforce`) plus `CancellationTest.anAlreadyCancelledScopeNeverQueues` |
| ceiling comparison `<` → `<=` | **9 failures** across admission, wait and reservation tests |
| `cancelScope` marks but does not complete the futures | **3 failures**, incl. one whose message is literally the A20.3 requirement |
| circuit check removed from `admit()` | **3 failures** in my new file |
| circuit check removed from the admission **pass** | **1 failure** in my new file (the queued-waiter case) |
| A4's two passthrough arms removed | **4 failures** in `ApiGatewayClientBudgetPassthroughTest` |
| `BoundedLogin`'s `client.abort()` removed | **1 failure** — the socket stays open |
| `ReauthOutcome` collapsed back to a boolean | **2 failures**, both mine; **every pre-existing reconnect test stayed green** |
| one alert metric name typo'd in `alerts.yml` | **1 failure** (`AlertRuleMetricsTest.everyReferencedApplicationMetricExists`) — so both new rules' metric names are verified against a real Prometheus exposition |
| hard-cap comparison `>=` → `>` | **0 failures — see Q4** (it is redundant with the ESSENTIAL ceiling by construction, not a test gap) |
| **the stale-clock fix reverted** | **0 failures, whole suite + the IT — see Q1** |

---

## The six invariants named in the task

### 1. Nothing can be starved into a wedge — **holds**

Traced every unbounded wait. `essential.max-wait=0` is the only one, `GatewayBudgetSettings` permits
zero for ESSENTIAL alone, and the window it waits on drains by construction:

- An ESSENTIAL waiter can only be enqueued while `window >= essential.ceiling` (= hard cap) or while its
  own queue is non-empty. Both imply `stamps` is non-empty, so `scheduleWakeUpLocked` always sets a
  wake-up, and every pass re-arms it while anyone is waiting. `reservedAbove(ESSENTIAL)` is
  structurally `0`, so **no reservation can block the top tier** — the one shape that could park it
  with an empty window and therefore with nothing to wake it.
- `refuseIfCircuitOpen` / the `circuitOpen` checks in `admit()` **and** in the pass mean an open circuit
  refuses rather than parks, at every tier (A16.2). Now under test (above).
- `BoundedLogin` really releases: 10 s, then `HttpClient.shutdownNow()` on the client the parked
  `send()` is using. Proven behaviourally, including that removing the abort leaves the socket open.
  Verified against the `websocket-parser-core-3.0.5` sources that the library request genuinely has
  **no `.timeout(...)` and no connect timeout** — A19's premise is fact.
- The scheduler cannot deadlock the pass: `admitWaitersLocked` returns its side effects as a
  `List<Runnable>` run **after** the unlock, so completing a waiter's future cannot re-enter the budget
  under its own lock. `reportThrottleState()` likewise logs outside the lock.
- `RejectedExecutionException` on the wake-up degrades to "no early wake-up" — reachable only after
  `shutdown()`, which is tests only.

One latency wrinkle, not a wedge: a **lower**-tier waiter enqueued while `stamps` is empty (blocked
purely by a reservation) gets no wake-up scheduled. It cannot matter — `reserved + window` is invariant
under an admission that consumes a permit, so consumption never creates room for a lower tier; only
`release()` does, and `release()` runs a pass. `releasingAReservationAdmitsQueuedLowerTiers` covers it.

### 2. Strict priority and FIFO under real concurrency, and the ceilings bind — **holds**

- Ceilings bind: the `<` → `<=` mutation produces 9 failures. Strict priority is structural as well as
  tested — ceilings are validated monotonic and `reservedAbove` only subtracts *more* for lower tiers,
  so a lower tier can never have room when a higher one does not, and an arriving ESSENTIAL is admitted
  synchronously past 100 parked DEFAULTs (`strictPriorityAcrossTiers`).
- **`4bad37a`'s FIFO fix is correct and strictly stronger than what it replaced.** Freeing one slot per
  clock step with a barrier after each makes the recorded order the admission order by construction,
  and the added `hasSize(expected)` per step also catches waking more than one waiter per freed slot —
  which is the failure that would actually breach the cap.
- **I went looking for more of that class and found none.** Every remaining cross-thread assertion in
  the new files waits on a barrier the budget itself controls (`CountDownLatch`, or polling
  `snapshot().queued*()`, which is synchronous budget state) rather than on a continuation's ordering.
  `oneFreedSlotWakesOneWaiter` does use a 50 ms settle before `hasSize(1)`, but it is backed by two
  synchronous assertions (`windowRequests()==900`, `queuedEssential()==4`) that fail deterministically
  if more than one waiter is released, so the sleep is decoration rather than the assertion.
- `strictPriorityAcrossTiers` and the FIFO test both carry comments naming the race they had to avoid,
  which is the right artefact to leave behind.

### 3. The cap holds, and the caveat is *nearly* honest — **holds, with Q1**

`GatewayBudgetEscalationIT` passes on demand, three runs, and it does measure the **receiver**:
`StubGateway` stamps at **handler entry, before any branching** (verified in the source), keeps its own
deque, shares no code with the budget, and runs on a thread-per-task virtual executor so the stub is
not the bottleneck being measured. It binds `InetAddress.getLoopbackAddress()` on port 0 and contains
no hostname. The register envelope it answers matches `gwms-register-envelope.md` exactly, `EXISTED`
at HTTP 200 included.

The caveat's *conclusion* is right — the overshoot is real and the 100-request gap absorbs it — but see
**Q1** for the part that does not hold, and **Q2** for the bound.

### 4. Cancellation as a stop-latency requirement — **holds**

`cancelScope` completes every matching waiter's future exceptionally, keyed on
`GatewayRequestScope.botGroupId`, and the mutation that marks-without-waking fails three tests
including one whose message is the requirement. The two paths that depend on it (`/stop` and `DELETE`
via `stopAndLogout`, both on the group lock) unwind because the parked bot throws rather than waiting
out its tier's max-wait.

Cancelled-bot arithmetic is clean. A bot cancelled **before** taking a semaphore permit returns `null`
and is `skipped` — neither up nor failed. A bot cancelled **inside** the budget throws, so it is counted
failed (correct: it did not come up) and reaches `classifyCreationFailure` → `"budget"` — see **Q3** for
the one consequence of that. Neither path can corrupt the reservation: `consumeReservationLocked`
decrements only when a permit was actually available, `release()` subtracts only the remainder and is
idempotent, and the `2 × window` TTL is a backstop rather than the plan. `reserved` cannot go negative.

`Bot.requestCancelled()` reads `startAttempts.isCancelled`, which requires an **open** attempt — so a
healthy long-lived bot cannot inherit a permanently-cancelled scope after a finished attempt. Worth
saying because the alternative would have silently disabled every gateway call for that group.

### 5. A budget outcome never looks like an upstream failure — **holds**

A4's two arms are pinned four ways over and the mutation that removes them fails all four.
`classifyCreationFailure` reaches `"budget"` (typed arm first, ahead of the message heuristic).
`bot_login_total` and `bot_verify_token_total` stay at **zero** on a refused request — asserted as a
sum over all series, so a new tag combination cannot hide an increment. `deposit` needs no arm and that
is asserted rather than assumed. `performReauth`'s tri-state is pinned at both the return value and,
now, the loop consequence.

### 6. `getBalanceIfAdmitted` never waits — **holds**

`OptionalLong.empty()` → the bot returns its local estimate, leaves the server anchor alone (so
`bot_money_drained_total` cannot accrue against an invented figure) and sets `balanceReadDeferred`; the
pre-deposit PRIORITIZED refresh then suppresses a deposit the stale figure would have triggered, and a
refusal of *that* read skips the round. The funnel is still a single `httpClient.send(` —
`sendIfAdmitted` shares `httpCall(request)` — so `GatewayCallSiteGuardTest`'s `isEqualTo(1)` keeps its
teeth, and that scanner's own teeth are still checked against a synthetic offending file.

### Also checked

- **Shared-gateway-host WARN**: fires once per host, only on a real collision, names the sorted
  environment ids, host-only (`8443` deliberately absent), silent for distinct hosts / a repeated
  environment / an unparseable URL / null. `budgetKey` still returns the environment id, so the key
  change is one line if it is ever wanted. One gap: a **third** environment joining an
  already-warned host produces no new line (`warnedGatewayHosts` is per host), so the WARN names the
  environments known at the time it fired.
- **`GatewayBudgetSustainedQueue`** is the right shape (ESSENTIAL only, `min_over_time > 0` so the
  queue never emptied once) and **`GatewayBudgetNearCap` is genuinely a ratio** against the exported
  `gateway_budget_hard_cap` with the same label set from the same target, so lowering the cap tightens
  it. Both metric names are verified against a real exposition — proven by mutating one to a typo.
  See **Q5** on the annotation's wording.
- **429/503 + `Retry-After`**: floored at 1 (never `Retry-After: 0`, which is a tight loop for an
  obedient client), omitted entirely when unknown, 429 is WARN and 503 is ERROR, and
  `GatewayRequestCancelledException` has its own documented-as-unreachable arm. **No message in the
  hierarchy names a host, a port or an upstream body** — I checked all three constructors and
  `GatewayRequestScope.describe()`, which is `groupId/botId`. `ClientSafeMessage` forwards them
  verbatim onto the unauthenticated `/status`, which is why that mattered.
- **The two rewritten tripwires can fail** — both do, under the mutation above. The rewrite also
  closed A20.7's requirement properly: it asserts a cancelled scope whose **bot is healthy** and whose
  *group start* was cancelled, which is the population Phase 2 created and the half the old assertion
  could never have reached.
- **The enforce WARN is gone and its absence is asserted** (`enforceModeSaysNothingBeyondThePosture`),
  which is the right inversion — that line would now be a tier-1, Loki-visible, false statement about a
  production instance's posture.
- **No test opens a non-loopback socket.** Grepped every added and changed test file for `http://`,
  `wss://`, `ws://`, `Socket`, `HttpClient` and hostnames. The only sockets in the whole diff are
  `StubGateway` and my `BoundedLoginTest`, both on `InetAddress.getLoopbackAddress()` with port 0. The
  pre-existing `EnvironmentProbeSchedulerTest` staging-host **string** (handed to a mock, never dialled)
  is unchanged — same note as Phase 1's G5.

---

## Findings

### Q1 — non-blocking, but a claim in Dev's report does not hold: the 84-in-60 measurement is not evidence of the stale-clock defect, and nothing in the tree catches that defect

The fix is right: `admit()` reads the clock a second time **inside** the lock and the stamp uses that
value. A stamp dated before its own admission expires early, and the window then admits again while the
request it belonged to is still in flight. Real, worth fixing.

But two things the commit message and the IT's comment assert are wrong:

1. **"measured as an 84-in-60 overshoot by `GatewayBudgetEscalationIT`, against the stub's own arrival
   count"** — I reverted the fix (one line) and measured the same figure. With the defect: `observedMax=84`.
   With the fix, three runs on the same machine: **79, 84, 84**. The IT cannot discriminate the two, so
   the number it is credited with finding is not attributable to the defect. The overshoot it measures is
   the arrival-vs-admission effect the javadoc *also* describes, and that effect is present either way.
2. **The defect is not pinned.** With the fix reverted the **whole suite is green, the escalation IT
   included** — because the IT's receiver-side bound is `hard-cap + threads` = `60 + 24` = 84, i.e.
   exactly the value the defect produces. The assertion is right for what it is there for and cannot
   also serve as this regression's guard.

`SlidingWindowGatewayBudgetClockDisciplineTest` now pins it deterministically (a clock that jumps a
window between the two reads: with the fix the window holds the admission, with the defect it reads 0 —
"a request we admitted is missing from the window it was admitted against"). I looked for siblings and
found **none**: `stampAdmitted` (the observe path) reads only under the lock, `stamp()` and
`admitWaitersLocked` take `now` from inside the lock, `windowRequests()` and `retryAfter()` read outside
the lock but only to *prune for a read*, which cannot mis-date anything. The one read that is
deliberately pre-lock is `startedWaiting`, and it is used only for the wait timer, where measuring from
before the lock is correct.

### Q2 — non-blocking. The caveat's bound ("`bot.creation.parallelism` and `user.registration.parallelism`, 10 each") is not the real bound, though its conclusion survives

The plan's own Findings say the opposite of what the caveat claims: the first-read burst is "the one
truly unbounded burst in the start path" (**no semaphore**), and neither drift reads (ws-parser message
threads, one per bot) nor reconnect upgrades (a thread per reconnecting bot) are semaphore-bounded
either. So "in-flight at the roll ≤ 10" is not true of three of the paths the budget paces.

The correct bound is **admission rate × latency jitter**: arrivals in an observer's window are
admissions over an interval of length `window + (max latency − min latency)`, so the overshoot is the
number of admissions inside one jitter span. That is *smaller* than the stated bound at production
admission rates (~3/s at the cap), which is why the conclusion — an order of magnitude inside the
100-request margin — still holds. It is also why the IT's ratio is so much worse: 12 admissions/s
against ~2 s of connection-pool jitter is ~24, which is exactly what it measures. Worth correcting in
the javadoc because the wrong reason invites the wrong fix (raising the semaphores looks free under the
stated bound, and is not).

Related flake risk: `observedMax <= HARD_CAP + threads` is fitted to the observation rather than
derived, and the true quantity is machine-dependent. It passed 3/3 full IT runs and 4/4 single-method
runs here (79-84 against a bound of 84 — **no headroom at all**). A slower or busier laptop is likely
to exceed it. Recommend the Releaser re-run it once if it fails rather than treating a single failure as
a cap breach, and recommend the bound be re-expressed against measured jitter.

### Q3 — non-blocking. `reason="budget"` cannot distinguish "the window refused it" from "we cancelled it"

`GatewayRequestCancelledException` is a `GatewayBudgetException`, so a bot woken by `cancelScope` takes
`createBotsInParallel`'s `catch (RuntimeException)` arm: `startAttempts.botFailed`, one
`log.error("Failed to create bot i/N …")`, and `bot_creation_failures_total{reason="budget"}`. On a
deliberate `/stop` of a paced start that is ERROR-level noise and a metric that reads as throttling.

**The magnitude is bounded by `bot.creation.parallelism`, not by `botCount`**, and that is what keeps
this out of the blocking column: the pre-permit and under-permit `isCancelled` checks mean only the ≤10
bots actually holding a permit can be inside the budget, and everyone else returns `null` and is
`skipped`. So a cancelled 3,000-bot start costs ~10 ERROR lines, not 3,000.

Still worth fixing, because A20.2 makes that tag one of only three places a budget outcome during a
build is visible at all, and `/stop` now writes into it. `classifyCreationFailure`'s label set is
already bounded and documented; a `"cancelled"` value ahead of the `GatewayBudgetException` arm is a
two-line change. Filing it rather than fixing it, per the QA/Dev split.

### Q4 — informational. The hard-cap check in `hasRoomLocked` is unreachable, by construction

`>=` → `>` on the hard cap fails **nothing**, and that is not a test gap: validation enforces
`essential.ceiling <= hard-cap` and `reservedAbove(ESSENTIAL) == 0`, so ESSENTIAL's ceiling check is
always at least as strict. The cap is enforced *by* `essential.ceiling`. Two consequences worth
recording: the belt-and-braces line is genuinely belt-and-braces (fine, keep it), and the documented
`essential.ceiling=850` escape hatch **lowers the effective cap to 850** rather than merely reserving
50 for the tiers below — which is what it says on the tin, but the `hard-cap` property stops being the
number that binds.

### Q5 — non-blocking, documentation. Two annotations claim more than the code delivers

1. **`GatewayCircuitOpenException`'s body does not carry A16.3's caveat.** The handler javadoc says
   "the truth is in the body's `msg`, because the block may need operator action and may outlive a
   day", and `RestExceptionHandlerTest`'s DisplayName says "the body carries the caveat" — but the
   message is `Gateway edge block on environment <id> (cf-ray …); circuit open for another 3600s`,
   which is the *probe interval* presented as a duration, i.e. precisely the "lie the UI will repeat"
   that A16.3 objects to. The test only asserts the body contains `cf-ray`. The exception class is
   Phase 1's and untouched here, and the circuit cannot open until Phase 5, so this is Phase 5's to
   close — but the javadoc and the test name should not claim it is already done.
2. **`GatewayBudgetSustainedQueue`'s description says "this is not a large start passing through — it
   is a start that cannot finish".** With `min_over_time[15m] > 0` and `for: 15m` the effective window
   is ~30 minutes, and AD-18 puts a 3,000-bot start at 33-50 minutes of continuous ESSENTIAL queueing.
   So the largest legitimate start **will** fire it, for ~20 minutes. The rule's own comment is honest
   about the arithmetic ("well inside the second"); the annotation an operator reads is not. `warning`
   + `audience: internal` keeps the cost low.

### Q6 — informational. `tryExecute(tier, …, Duration.ZERO)` means "unbounded" for any tier whose *configured* wait is zero

`admit()` gates the now-or-never branch on `maxWait.isZero() && !settings.isUnboundedWait(tier)`, and
`isUnboundedWait` reads the **configured** wait, not the argument. So `tryExecute(ESSENTIAL, …, ZERO)`
parks unboundedly — the opposite of what the call site asked for. No caller does this today (only
`sendIfAdmitted`, at DEFAULT), and for DEFAULT/PRIORITIZED the behaviour is correct because their
configured waits are non-zero. Latent trap: an explicit `ZERO` is the caller's intent and should
outrank configuration.

---

## Coverage of the diff

| Production change | Covered by | What is asserted |
|---|---|---|
| `SlidingWindowGatewayBudget` queues, ceilings, hard cap, priority, FIFO | `SlidingWindowGatewayBudgetAdmissionTest` | each tier's ceiling; nothing past the cap for any tier mix; counter/gauge reconcile; strict priority; FIFO one slot at a time; one freed slot wakes exactly one; `run`/`runWsUpgrade` paced; `count-ws-upgrades=false` semantics; `countWsUpgrade` follows the flag |
| …waits, timeouts, `Retry-After`, the registration override | `SlidingWindowGatewayBudgetWaitTest` | DEFAULT times out with the right type/tier/env and a `retryAfter` equal to the next expiry; nothing stamped on a timeout; ESSENTIAL never times out and goes when the window drains; `tryExecute(ZERO)` stamps nothing; the registration override outlasts DEFAULT's 30 s |
| …`reserve` / consumption / release / TTL | `SlidingWindowGatewayBudgetReservationTest` | demand shrinks the tiers below; consumption by `(tier, botGroupId)`; release returns the remainder **and walks the queues**; oversized floors at zero; a leaked reservation is retired |
| …`cancelScope` | `SlidingWindowGatewayBudgetCancellationTest` | waiters are **woken**, only the matching group, nothing stamped, other tiers' order preserved, an interrupt restores the flag, an already-cancelled scope never queues |
| …the circuit refusal path (A16.2) | **`SlidingWindowGatewayBudgetCircuitRefusalTest` (QA)** | every tier refused, ESSENTIAL not parked, the pass refuses too, soft callers get `empty()`, observe unaffected |
| …stamp dating | **`SlidingWindowGatewayBudgetClockDisciplineTest` (QA)** | the stamp is dated from inside the lock; observe takes exactly one read; the window is exact under a monotonic clock |
| `observe` is byte-for-byte Phase 1 | `SlidingWindowGatewayBudgetObserveModeTest` (rewritten) | every entry point past the cap; a cancelled scope *is* sent and counted; no queue; concurrency-exact; oversized reservation inert |
| The cap against a real receiver | `GatewayBudgetEscalationIT` + `StubGateway` | 910 mixed-tier attempts, 24 threads, sampled continuously: budget window ≤ cap **exactly**; stub count ≤ cap + threads; every admitted request really was sent and no refused one was; DEFAULT stops at its ceiling while ESSENTIAL keeps going; the overflow waits for expiry; a real login through the library is counted and parsed |
| `GatewayBudgetSettings` rename + `with*` helpers | `GatewayBudgetSettingsTest` | `block-probe-interval` rejects zero and negatives; unbounded wait for ESSENTIAL only; monotonic ceilings |
| `GatewayBudgetRegistry` shared-host WARN, no enforce WARN | `GatewayBudgetRegistryTest` | one WARN per host naming both envs, host-only; silent without a collision; `gatewayHost` parsing; 44 meters/env; posture line only |
| `ApiGatewayClient` A4 arms, registration override, `getBalanceIfAdmitted` | `ApiGatewayClientBudgetPassthroughTest`, `ApiGatewayClientTierTest`, `GatewayCallSiteGuardTest` | the type survives on all three methods; **no** `bot_login_total` / `bot_verify_token_total` movement; one `httpClient.send(`; one `BoundedLogin.login(`; zero `new AuthClient(` |
| `BoundedLogin` | **`BoundedLoginTest` (QA)** + `GatewayCallSiteGuardTest` | the bound elapses, the exchange is aborted, success unchanged, library exception type preserved |
| `Bot` AD-9 / AD-10 | `BotBudgetOutcomeTest`, `BotReconnectTest` (+QA) | deferred drift read returns the estimate and moves no anchor; pre-deposit refresh suppresses or confirms; refused deposit is not a failed deposit; refused confirming read marks the figure stale; `performReauth` tri-state **and its loop consequence** |
| `BotGroupBehaviorService` reservation + start INFO | `BotGroupBehaviorServiceReservationTest` | demand = `botCount × 3` at ESSENTIAL under the group scope; released on success, outright failure and an unusable document; taken **after** the runtime is published; the estimate is windows |
| …RR1 (intent re-read inside the lock) | `StartupChainTest` | a `/stop` landing while the chain queues for the lock is honoured |
| …RR2 (`/health` == `/status`) | `BotGroupBehaviorServiceAsyncStartTest` | both answer `STARTING` in the reclaim window and both answer `DEAD` once the attempt closes |
| …periodic-logout budget arm | **`PeriodicLogoutBudgetOutcomeTest` (QA)** | DEBUG not ERROR; logout+restart really attempted; a genuine failure is still ERROR |
| REST 429/503 | `RestExceptionHandlerTest` | statuses, `Retry-After` incl. the omit-when-unknown and never-zero cases, `type` strings, the cancelled arm |
| `alerts.yml` ratio + sustained-queue rules | `AlertRuleMetricsTest`, `AlertRulesAudienceTest` | every referenced metric exists in a real exposition (mutation-verified); `audience` labels present |
| `ClientSafeMessage` widening rationale | existing tests + reading | forwards our own hierarchy; the two members the handler treats differently are called out |
| Dashboard `gateway_budget_ceiling` / `hard_cap` panels | `MetricKeyDashboardParityTest` | JSON parses and the keys exist |

---

## Gaps

- **No Mongo round trip.** Unchanged and still unavailable; nothing in this phase persists anything new.
- **The circuit's *trigger* is Phase 5's.** I test the consequences of `circuitOpen == true` through
  reflection; nothing writes it yet, and `CloudflareBlockDetector` does not exist. Phase 5 should keep
  my file and add a real flip.
- **`GatewayBudgetSustainedQueue` and the ratio rule are not evaluated.** Their metric names and labels
  are verified; the thresholds are not (no Prometheus in the build). Q5.2 is a reading, not a test.
- **Production-scale pacing is still V3b's job.** Everything here is a fake clock or a 5-second window.
  The 33-50-minute claim for 3,000 bots is arithmetic (`estimatedStartMinutes`), not a measurement.
- **The `count-ws-upgrades=false` demand case.** A15.1 asked that
  `BotGroupBehaviorServiceReservationTest` keep a `× 2` case as a hypothetical; the code made demand
  unconditionally `× 3`, so the case is no longer expressible. Consistent with A15.1 itself, stale in
  A6 Phase 3's test list.
- **A third environment joining an already-warned gateway host** produces no new WARN (see above).
- **`docker-compose.yml`, `secrets.env`, `GATEWAY_BUDGET_MODE`** — not unit-testable here; the bound
  default is (`ApplicationContextLoadsTest`).
- **A cancelled scope still throws into the ws-parser message pipeline** on a drift read
  (`admit()` throws cancellation even for soft callers, deliberately and documented). Only reachable
  for a stopped bot or a cancelled build, i.e. while the bot is being torn down anyway. Noted rather
  than covered, because the alternative — a cancelled caller mistaking "called off" for "no room" and
  carrying on — is worse.

## Flake hunt

- **Five full-suite runs, identical** (2,340 at Dev's tip; 2,356 with QA's tests, including one at the
  committed tip in a fresh worktree). **Four IT runs, identical**, ~37 s each.
- Every cross-thread assertion I added waits on a `CountDownLatch`, a `CompletableFuture`
  (`succeedsWithin`) or synchronous budget state — never a sleep-then-assert. Class-level `@Timeout` on
  all four new files, because the failure mode under test is *parking*, and a parked test hangs rather
  than failing.
- `BoundedLoginTest`'s timings are generous in both directions (a 400 ms bound asserted `>= 400 ms` and
  `< 15 s`; the hang-up asserted within 10 s).
- The one real flake risk in the diff is **Q2**: `observedMax <= 84` against a measured 79-84. Also
  worth knowing for the Releaser: `defaultStopsAtItsCeilingAndEssentialDoesNot` spends ~2.5 s of a 5 s
  window inside `getBalanceIfAdmitted`'s pre-existing `Thread.sleep(500)`, so a badly loaded machine
  could let early stamps expire and make its `isEqualTo(DEFAULT_CEILING)` read low. It did not here.

## Failures

None. Five full-suite runs and four IT runs, no failures, no errors, no flakes.
