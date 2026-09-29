# Compliance — GATEWAY_REQUEST_BUDGET, Phase 3 (enforcement behind `mode=enforce`)

Branch: `feature/gateway-request-budget`
Plan reviewed: `docs/plans/GATEWAY_REQUEST_BUDGET.md` at `4bad37a`, top to bottom, with
**A20** (this phase's ordered absorb list), A4, A5, A6 Phase 3, A15, A16 and A19 as the
authority.
Diff reviewed: `git diff b818e04..HEAD` — 12 commits `6a8d69e`..`4bad37a`, 51 files,
+5,673 / −458.
Earlier verdicts: `compliance.md` (Phase 1, PASS), `compliance-phase2.md` (Phase 2,
PLAN_AMENDED, plus `## Re-check`).

## Verdict

**PLAN_AMENDED** — the diff is accepted; nothing goes back to Dev.

All twelve of A20's items are implemented. Five of the deviations Dev reported are **genuine plan
errors** and are corrected in `## Amendment — 2026-09-29 (Phase 3 compliance; A21-A29)` at the
bottom of the plan; nothing above A21 was rewritten. The other three are faithful readings.

### Build evidence — taken in a clean detached worktree at the branch tip

This is the check `08c52a3` exists because of, so it was run rather than trusted:

| Check | Result |
|---|---|
| `mvn -o -DskipTests test-compile` over all five modules at `4bad37a` | **SUCCESS** — the tip no longer depends on anyone's uncommitted work |
| `GatewayBudgetEscalationIT` (V3g), loopback stub, real clock | **4 tests, 0 failures, 0 errors, 37 s** |
| The plan's *written* V3g command (`-Dgroups=stub-gateway -Dtest=…`) | **BUILD FAILURE at `bot-api`** — plan error, corrected as A24.2 |

I did not run the full suite; that is QA's step.

### Invariants — all four hold

| Invariant | Evidence |
|---|---|
| `observe` is byte-for-byte Phase 1 | `admit()` returns on the first branch for any non-`ENFORCE` mode; `refuseIfCircuitOpen` / `refuseIfCancelled` are both gated on `ENFORCE`; `SlidingWindowGatewayBudgetObserveModeTest` drives every entry point 600 past the cap on a frozen clock |
| Nothing is stamped that did not leave the JVM | timeout, cancel, circuit-refusal and `tryExecute(ZERO)` all increment an outcome counter and never `stampLocked`; asserted in the admission, wait, cancellation and both tripwire tests |
| The clock is read **inside** the lock | `admit()` and `stampAdmitted()` both re-read `nanos` under `lock`; `startedWaiting` survives only for the wait timer. This is the defect the IT found (84 arrivals in a 60-request window) |
| Phase boundary | no registration worker, no Cloudflare detector, no circuit **trigger** (`circuitOpen` has no setter anywhere, production or test), and the compiled default is `observe` in all three of the places A5.4 names |

---

## A20's twelve items, one by one

| # | Item | Status |
|---|---|---|
| 1 | Bound every wait whose subject cannot make progress (A16.2) | **implemented** |
| 2 | A4's `authenticate` / `getBalance` rewrap | **implemented** |
| 3 | `cancelScope` must wake waiters, not mark them | **implemented** |
| 4 | Resolve the environment for a cancel, or place `reserve` after the runtime is published | **implemented** |
| 5 | A5.1 / A5.2 / A5.3 with `count-ws-upgrades=true` as fact | **implemented** |
| 6 | Per-gateway-host keying pre-flight (A16.6a) | **implemented** (one javadoc cost estimate overstated — A26) |
| 7 | A5.5's two tripwire rewrites, plus the group-level cancellation half | **implemented** |
| 8 | Budget outcomes reach `lastError` correctly — keep them safe | **implemented** |
| 9 | The MDC idiom | **implemented as specified; one claim in the code is wrong** (A27.1) |
| 10 | Alerting for enforce mode | **implemented** (annotation overclaims — A27.2) |
| 11 | `StubGateway` lands here so V3g proves the cap | **implemented** |
| 12 | A5.4's three-file flip warning | **implemented** |

**1 — bounded waits (A16.2).** Three layers, in the right order. `BoundedLogin` (`6a8d69e`) is
first and is the one that mattered: A20.1 says bound what cannot make progress **before**
layering an unbounded wait on it, and the library's `AuthClient` builds its request with no
timeout on an `HttpClient` with no connect timeout, so a stalled TCP login parked a bot-creation
thread — a semaphore permit and, transitively, the group lock — indefinitely, **today**, with no
exotic cause. The fix runs the library call on its own virtual thread, waits 10 s, and calls
`HttpClient.shutdownNow()` on the very client the parked `send()` is using, reached through a
subclass because the getter is `protected`; without that abort the bound would move the leak from
the caller to a thread nobody can see. Second, an open circuit **refuses every tier** including
ESSENTIAL (`admit`, `refuseIfCircuitOpen`, `admitWaitersLocked`'s `break`). Third,
`GatewayBudgetSettings` still permits `max-wait=0` for ESSENTIAL alone, with A16.2's rule —
progress is guaranteed only by the window — written into the validator, the properties file and
both javadocs. `FOLLOWUPS.md` P13 was re-read as its own entry demanded and narrowed from both
ends, with `semaphore.acquire()` named as what remains.

**2 — A4.** `catch (GatewayBudgetException e) { throw e; }` sits **ahead of** the
`RuntimeException` arm in `authenticate`, with **no `metrics.incLogin(false)`**, and the same
treatment in `readBalance` ahead of the arm that would have moved
`bot_verify_token_total{outcome="failure"}`. `deposit` needed nothing (it catches only
`IOException`/`InterruptedException`) and `ApiGatewayClientBudgetPassthroughTest` asserts that
explicitly rather than leaving it implied. The distinction the diff gets right and that the plan
only gestures at: a **timeout** keeps `incLogin(false)` — the request left the JVM and the
gateway did not answer — while a budget refusal moves no series at all.

**3 — `cancelScope` wakes.** Matched on `GatewayRequestScope.botGroupId`, removes the waiter,
decrements the depth gauge, counts `cancelled`, completes the future **exceptionally outside the
lock**, and runs an admission pass afterwards. The side-effects-outside-the-lock discipline is
consistent across `admitWaitersLocked`, `reserve`, `release` and `cancelScope` (`runAfterUnlock`),
and the reason is written down: completing a future runs its continuation, which would let a bot
thread re-enter the budget from inside the admission pass.

**4 — reservation placement.** `budget.reserve(ESSENTIAL, botCount × 3, scope(groupId))` is taken
at `startLocked:1032`, **after** `runningGroups.put(id, runtime)` at `:963` and before
`createBotsInParallel`; released first in the `finally`, on every path. `cancelStartInFlight`
resolves the environment from the runtime, so the ordering is what makes a `/stop` in that window
able to release it — and `BotGroupBehaviorServiceReservationTest` pins it with a named test
(`theReservationIsTakenOnlyAfterTheRuntimeIsPublished`) rather than leaving it to the comment.
The `2 × window` TTL is the backstop, not the plan, and says so.

**5 — A5.1/5.2/5.3.** `run` and `runWsUpgrade` both go through `admitOrThrowUnchecked`; with
`count-ws-upgrades=false` the upgrade still checks the circuit and cancellation and neither waits
on nor consumes the window; `countWsUpgrade` consults the same flag and
`EnvironmentProbeScheduler` uses it. Demand is `botCount × 3` **unconditionally**, per A15.1,
with `REQUESTS_PER_BOT_AT_START` as a named constant. An interrupt reaching a `Runnable` caller
becomes `GatewayRequestCancelledException` with the flag restored — the honest translation, and
the opposite of the `connect()`-eats-interrupts defect AD-8 exists to avoid.

**6 — host keying.** Keyed per `Environment`, as the user decided. `budgetKey(environmentId,
apiGatewayUrl)` accepts the URL and ignores it; one WARN per host per JVM names every colliding
environment and the cap each thinks it owns; host only, lower-cased; an unparseable URL skips the
check rather than failing startup. Two tests cover the collision and the three non-collisions
(distinct hosts, the same environment twice, a junk URL). The seam's **cost** is understated in
its own javadoc — `find`'s only caller has no gateway URL to give it — corrected as A26.

**7 — tripwires.** Both now assert enforce behaviour explicitly and would therefore have failed
had enforcement arrived without them being looked at, which is the whole point of a tripwire:
`SlidingWindowGatewayBudgetWindowTest.aCancelledScopeIsOnlyStampedWhenItIsReallySent` names both
modes in one test, and `…ObserveModeTest.aCancelledScopeIsRefusedAndNotStampedUnderEnforce`
asserts the group-level half A20.7 specifically asked for — a scope whose **bot is healthy** and
whose group's start was cancelled — not just `Bot.isStopped()`. The `mode=enforce` startup WARN
is deleted and `GatewayBudgetRegistryTest.enforceModeSaysNothingBeyondThePosture` asserts its
**absence**, with the reasoning (a tier-1, Loki-visible, actively false statement about a
production instance's posture) in the test.

**8 — `lastError` safety.** `ClientSafeMessage`'s policy is restated as "our own hierarchy, whose
messages we wrote", with the two members the handler treats differently enumerated
(`ResourceNotFoundException`'s bodyless 404; `GatewayBudgetException`, which had no arm until
`b106a45`). The new 429/503 arms carry `Retry-After`, floored at one second and **omitted
entirely** when unknown, with A16.3's "when we will next ask, not when it will work" in the
javadoc and the caveat in the body rather than the header. The terminal `GatewayBudgetException`
arm turns `GatewayRequestCancelledException`'s documented "never reaches REST" into a 429 naming
the cancellation instead of a sanitised 500.

**9 — MDC.** No nested MDC scope was created anywhere in the diff, so A20.9 is satisfied by
construction (`git diff | grep -E '^\+.*(BotMdc|MDC\.)'` is empty) and no `clear()` was
introduced. But `recordAdmitted`'s javadoc claims every path runs under the bot's MDC, and on the
waiter path it does not — see A27.1. DEBUG-track only, identity present in the message text,
cheap known fix; recorded, not blocking.

**10 — alerting.** `GatewayBudgetSustainedQueue` is the new rule and it is the right *shape*
(`min_over_time(queue_depth{tier="ESSENTIAL"}[15m]) > 0` — the queue never emptied once —
ESSENTIAL only, because a DEFAULT queue is the design working). `GatewayBudgetNearCap` becomes a
ratio against the exported `gateway_budget_hard_cap`, so lowering the cap tightens rather than
disarms it, and its annotation now states both meanings. Dashboard reference lines come from
`gateway_budget_hard_cap` / `gateway_budget_ceiling{tier}` instead of three literals, the dead
`ESSENTIAL ceiling` field override is gone, and the two panels A6 Phase 3 asks for (queue depth
by tier, wait p95 by tier) are added. One overclaim in the new rule's annotation — A27.2.

**11 — `StubGateway` + V3g.** Test scope, no block mode, no Netty WS endpoint (both Phase 5's),
loopback-only on an ephemeral port, **no hostname in either file**. It keeps its own sliding count
from its own arrival stamps with no code shared with the budget, which is the entire value: every
other test in this feature asks the budget what it thinks it admitted. It earned its keep
immediately by finding the stale-clock defect. Ran green here in 37 s.

**12 — the three-file flip warning.** In `application.properties`, naming all three files and
`ApplicationContextLoadsTest`'s equality assertion as the thing that fails the build, applied to
both the `essential.ceiling=850` escape hatch and the Phase 6 mode flip.

---

## Phase-by-phase

Phases 1 and 2 are shipped and unmodified by this diff.

### Phase 3 — enforcement behind `mode=enforce`
Status: **implemented** (with the five plan corrections in A21-A26)

A6 Phase 3's items, and what landed:

| A6 Phase 3 | Status |
|---|---|
| Waiter queues, `admitWaiters()`, ceilings, max-waits, wake-up at earliest expiry, `reserve`, `cancelScope`, `tryExecute`, `Retry-After`; observe unchanged | implemented |
| `createBotsInParallel` reservation + release; `stop()` → `cancelScope` before the lock; the start INFO line | implemented |
| `checkBalance` → `tryExecute(ZERO)` + `balanceReadDeferred`; pre-deposit PRIORITIZED refresh in both bots; non-terminal `performReauth` / `deposit` | implemented — **drifted in shape**, see A21 and A22 |
| Registration wait override; **`save` → 429 on complete budget failure** | override implemented; **429 deliberately deferred**, see A25 |
| Throttle WARN/INFO; dashboard panels; compose passes the mode | implemented (compose already did, Phase 1) |
| Staging `GATEWAY_BUDGET_MODE=enforce` in `secrets.env` | the user's / Releaser's step; `secrets.env` is uncommitted by policy |
| A4, A5.1, A5.2, A5.3, A5.5, A5.6 | implemented |
| `StubGateway` moves into this phase | implemented |

Every test A6 Phase 3 lists exists, plus four the plan did not ask for and that cover the two
defects found in-phase: `GatewayBudgetEscalationIT`, `ApiGatewayClientBudgetPassthroughTest`,
`BotGroupBehaviorServiceReservationTest.theReservationIsTakenOnlyAfterTheRuntimeIsPublished`, and
`StartupChainTest`'s locked-intent test.

### Verification section — achievable?

- **V3a** (grep `mode=enforce`) — yes, and `GatewayBudgetRegistryTest` pins the rendering.
- **V3b, V3c, V3e, V3f** — yes. Note V3b's window-max assertion is the budget's own gauge, which
  is exact; the receiver-side caveat (A24.1) applies only to V3g's stub.
- **V3d** — **obsolete**, corrected in A25. On the single deployment registration is
  asynchronous, so the step's premise no longer exists; and inside the branch, before Phase 4, a
  complete budget failure during registration is a **502**, not the 429 the step demands.
- **V3g** — the substance is achievable and was run; the **wording, the command and the
  "six lines to paste" are all wrong as written**. Corrected in A24.

---

## Drift — the deviations Dev reported, judged

| # | Deviation | Judgement |
|---|---|---|
| 1 | A16.1's rename pulled into Phase 3 (`block-probe-interval`, 60m); no detector | **faithful reading** — the refusal path needs a number for `Retry-After` now. Recorded as A23 |
| 2 | `performReauth` became tri-state | **genuine plan error — amended (A21)** |
| 3 | `getBalanceIfAdmitted` → `OptionalLong`, funnel refactored | **genuine plan error — amended (A22)** |
| 4 | `GatewayCallSiteGuardTest`'s `new AuthClient( == 1` rewritten | **faithful reading** |
| 5 | V3g's headline assertion is not literally achievable | **genuine plan error — amended (A24)** |
| 6 | The clock was read before the lock; found by the IT, fixed in-phase | **in-phase defect, correctly fixed** |
| 7 | AD-19's registration 429 deferred | **correct call — amended (A25)**, with what is left exposed stated precisely |
| 8 | Provenance: `9a4ef87` swept 134 lines of RIK_114, reverted by `08c52a3` | **correctly remedied**, see below |

**1 — the rename.** Pulling A16.1 forward is right, not scope creep: `GatewayCircuitOpenException`
is thrown from this phase (A16.2) and has to carry a `retryAfter`, and reading that number out of
a property called `block-cooldown` would bake the assumption the user falsified into the one field
an HTTP client obeys. `GatewayBudgetSettings` additionally rejects zero for it, which the plan did
not specify and which is right for the same reason. No detector, no trigger, no state machine —
the phase boundary holds, and the cost of that (the refusal branches are unreachable and
untested in the shipped artifact) is now written into A23 and handed to Phase 5 as A29.1 rather
than left to be discovered as coverage.

**2 — the tri-state.** A real plan error, and the failure mode had it been implemented as written
is worse than the one AD-9 forbids: `false` already meant "marked DEAD, stop the loop" at both
call sites, so a budget refusal returning `false` would have ended the reconnect loop on a bot
that was never marked DEAD — parked `RECONNECTING`, no loop running, invisible to auto-recovery
and to every dead-group signal. **Both call sites verified independently** (A21): `cycle++` and
the `MAX_RECONNECT_CYCLES` check precede `performReauth()` in `runWsReconnectLoop`, so RETRYABLE
is charged a cycle and the cap holds; `runAuthThenWsLoop` skips its immediate WS upgrade and
enters at `cycle=1`, so the cap is not bypassable through that branch either.

**3 — `OptionalLong`.** A real plan error of the same kind: there is no `long` that can mean "not
sent" (`0` is the common balance on a fresh account, `-1` is legal). The funnel was refactored so
the three admission semantics share one `httpCall(request)`, which keeps
`GatewayCallSiteGuardTest`'s single-`httpClient.send(` rule true — the property that makes an
uncounted escape from the budget unbuildable. Verified in the guard test's own diff.

**4 — the guard rewrite.** Correct, and stronger than a re-number would have been: it asserts
`new AuthClient(` is **zero** in `ApiGatewayClient`, `BoundedLogin.login(` is exactly one, and
`BoundedLogin` contains `extends AuthClient`, `LOGIN_TIMEOUT` and `shutdownNow()` — i.e. it pins
the abort mechanism, not just the arity. A re-numbered `== 2` would have passed for a
`BoundedLogin` that had quietly stopped being able to give up.

**5 — V3g.** Amended, and the right call: Dev asserted the in-flight allowance explicitly
(`observedMax <= HARD_CAP + threads`) with the measurement that produced it in the comment,
instead of a fudge factor that hid it. That the receiver's window can read `cap + in-flight` is
also a standing argument against ever raising `hard-cap`, and it is now in the class javadoc.

**6 — the stale-clock defect.** This is the item that justifies A20.11 on its own. Every other
test in the feature asks the budget what it thinks it admitted; the one that asked the receiver
found a real overshoot (84 arrivals in a 60-request window under a 24-thread fan-out) whose cause
was a stamp dated from before the admission that produced it. Fixed on all three paths (`admit`,
`stampAdmitted`, `admitWaiters`) with `startedWaiting` retained only for the wait timer, where
measuring from before the lock is correct. `4bad37a` then fixed the FIFO test that was recording
the scheduler's resume order rather than the budget's admission order — a flaky assertion, not a
flaky budget, and the rewrite additionally fails if more than one waiter wakes per freed slot,
which is the failure that would actually breach the cap.

**7 — the deferred 429.** Correct: A2/AD-19a supersede AD-19 in full and A6 Phase 4 deletes the
method the 429 would have lived in, so building it adds a path the next phase removes. The half
worth keeping (the 15-minute wait override, so an admitted registration finishes rather than
half-finishes) **is** implemented. What is left unprotected is stated in A25 and is real but not
reachable in any deployed artifact, because A7's single cut is after Phase 5: inside the branch,
a complete budget failure during registration is a 502 about a healthy gateway, and a 200-bot
create can park a Tomcat worker for hours. **If Phase 4 slips or is descoped, that becomes a
release blocker**, and the minimal fix is on record.

**8 — provenance.** Recorded in full, because it is the hazard MEMORY already attributes one
unvalidated prod build to:

- `9a4ef87` staged `BettingMiniGameBot.java` whole and carried **134 lines of the user's
  in-flight RIK_114 work** (`GameRequestFactory`'s `buildRequest` branch, `COMMIT_CODE`,
  `pendingCommit`, `commitFailureWarned`, `afterBetSent`, two imports, the `beforeReconnect`
  clear). `08c52a3` restored the file to `b818e04` plus **only** the Phase 3 hunk (the AD-10
  pre-deposit gate in `onNewSession`) and put the RIK hunks back in the working tree untouched —
  the same remedy `831e311`/`f3ddb28` applied to their siblings. Verified: `BettingMiniGameBot.java`
  is dirty again in the working tree, and the branch's own hunk is the four-line
  `depositIsWarranted` change and its comment. No history was rewritten, so `9a4ef87`'s message
  still describes more than its final diff contains; the two must be read together.
- **The same check caught a branch tip that did not compile.** `GameRequestFactory` exists in
  **no commit** — it is part of the uncommitted `bot-messages` work — so `ad2ae5b` built fine in
  the working tree and failed to compile `bot-engine` in a clean worktree. I re-ran that check at
  `4bad37a`: `test-compile` **SUCCESS** across all five modules. A branch that only builds against
  someone else's uncommitted work is not releasable, and this is now the second time the detached
  worktree is the only thing that would have caught it. **It belongs in the Dev checklist, not in
  a compliance pass.**

---

## Out-of-scope changes

Two, both small and both accounted for:

1. **`36aec4e`'s documentation pass** — the "500s the list" → 400 correction in three javadocs,
   `BotGroupStatusRollbackSafetyTest` rewritten from a defect report into regression guards,
   `RecoveryEligibility`'s stale `PATCH {"targetStatus":"STOPPED"}` clause, `FOLLOWUPS.md` P13.
   **Assigned to this phase by A14** ("fold the correction into Phase 3's documentation pass
   rather than spending a commit on it"), so in scope by amendment.
2. **`BotGroupMapper.toEntity` no longer copies `lastStartedAt` / `lastStoppedAt` /
   `lastFailureReason` from the DTO on create.** A QA finding from the Phase 2 re-check, not in
   A6 Phase 3. Behaviour change, but the right direction and tiny: `lastFailureReason` is rendered
   to operators, `lastStoppedAt` gates the recovery settle window, and neither has any legitimate
   client-supplied value. It also makes the "system-managed set" the comment already claimed
   actually closed, which is what Phase 4's `registeredCount`/`namedCount` need to join (A28.2).
   Accepted.

Nothing else. The ~58 dirty working-tree entries (`Aviator.js`, the RIK dispatch tests,
`deploy.sh`, the RIK plans) are untouched by `b818e04..HEAD` after `08c52a3` and were not staged
or stashed by this pass. `CLAUDE.md` and `.claude/agents/*` are untouched by the diff and by me.

---

## Amendments to the plan

Appended as `## Amendment — 2026-09-29 (Phase 3 compliance; A21-A29)`. Nothing above A21 was
rewritten.

- **A21** — AD-9's `performReauth` could not stay a boolean; the tri-state, with both call sites
  verified and the cap shown to hold through the RETRYABLE branch.
- **A22** — AD-10 needed a new method, not a tier switch; `OptionalLong`, the shared `httpCall`
  factory, and the two consequences the plan did not state (`depositIsWarranted` subsumes the
  minimum test; the deposit aggregate reads the refreshed figure).
- **A23** — why A16.1's rename is in this phase, the added zero-rejection, and the fact that the
  refusal path is unreachable and untested until Phase 5 supplies a trigger.
- **A24** — V3g corrected in three ways: the headline assertion (budget exact, receiver
  `cap + in-flight`), the command (the written one **fails**; the working one given), and the
  six-line ladder that does not exist. Plus Implementation Note 18: excluded by the `*IT` name,
  not by `excludedGroups`.
- **A25** — V3d is obsolete; the AD-19 429 is deliberately not built; exactly what that leaves
  exposed inside the branch and why it is not reachable in a deployed artifact.
- **A26** — the `budgetKey` seam is real but "one line" understates it: `find`'s only caller has
  no gateway URL, so a host key needs the URL on `BotGroupRuntime` or a Mongo read on `/stop`.
- **A27** — three claims in shipped code and config that are now stale or overstated
  (`recordAdmitted`'s MDC claim; `GatewayBudgetSustainedQueue`'s annotation versus AD-18's
  3,000-bot case; `classifyCreationFailure`'s "inert until Phase 3").
- **A28** — **what Phase 4 inherits, ordered** (seven items): `registrationMaxWait()` is
  necessary but not sufficient and the `UNLIMITED`/zero trap that goes with it; the render-only
  constraint on `registeredCount`/`namedCount`; the 3-request resume path and the captured
  envelope; the `Semaphore(0)` trap disappearing with the bulk method; the `startLocked` guard
  placement; the worker's scope needing a group id; registration's own pre-registered counters.
- **A29** — **what Phase 5 inherits** (five items): it writes the first test of code Phase 3
  shipped; `EnvironmentWsProbe` still reads a block page as healthy; AD-12's in-repo login is
  what lets `BoundedLogin` delete itself, and what must move with it; `httpCall` is the
  classifier's insertion point; A27's corrections.

---

## For the Reviewer and QA (not compliance findings)

1. **`recordAdmitted`'s DEBUG line can carry another group's MDC** on the waiter path, because
   the deferred action runs on whichever thread ran the admission pass. Track 2 only, identity is
   in the message text, fix is a per-`Waiter` MDC snapshot. A27.1.
2. **`GatewayBudgetSustainedQueue` will fire during a legitimate 3,000-bot start** (≈50 minutes
   of continuously non-empty ESSENTIAL queue against a ~30-minute trigger), while its own
   annotation says it cannot. A27.2.
3. **`admit()` runs no admission pass on the arrival path** (deliberate, with the reasoning in
   the code: every event that can create room runs one, and a pass whose admitted waiters were
   completed after this block threw would lose those completions). I checked the obvious
   consequence — can a DEFAULT arrival overtake a queued ESSENTIAL waiter? — and **it cannot**,
   but only because of the monotonic ceiling validation: an ESSENTIAL waiter is queued only when
   `window >= min(essential.ceiling, hard-cap)`, and `default.ceiling <= essential.ceiling` is
   enforced at startup, so `hasRoomLocked(DEFAULT)` is false whenever it is. Worth knowing that
   strict priority on the arrival path rests on `GatewayBudgetSettings`' validator rather than on
   the admission code, because that is not written down anywhere.
4. `getBalance`'s pre-existing unconditional `Thread.sleep(500)` is now load-bearing enough to be
   worked around in the IT (which uses `deposit` for its volume rather than balance reads). Still
   only a follow-up, but it now distorts any timing measurement of the balance path.

## Release concerns

1. **Nothing deploys after Phase 3 alone** — A7's single cut is after Phase 5. A25's two
   synchronous-registration defects are the concrete reason that gating matters rather than a
   preference.
2. **V3d must not be run as written** and V3g's command must be the corrected one (A24.2), or the
   Releaser will report a BUILD FAILURE for a green test.
3. Staging sets `GATEWAY_BUDGET_MODE=enforce` in the uncommitted `secrets.env`/`.env` merge,
   never in `docker-compose.yml`; prod stays `observe` until Phase 6.
4. Behaviour to release-note beyond Phase 2's list: a budget outcome is now a **429** (or 503 for
   a circuit that cannot yet open) rather than a 502 or a sanitised 500; a periodic logout the
   budget refuses is a DEBUG line rather than an ERROR; a login now times out after 10 s instead
   of never.
