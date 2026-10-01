# Code Review — GATEWAY_REQUEST_BUDGET, Phase 4 fix round (scoped)

Branch: feature/gateway-request-budget
Reviewed: `git show d760f6d` (S1, S2, S4, T1: admission pass) and `git show 39405bb` (B2, QA F-1, S3, S5-S7: registration worker). I read the surrounding code at `f1cbc32`. The rest of the branch and the uncommitted working tree are out of scope.

I ran the tests in a detached worktree at `f1cbc32`, which has since been removed. `RegistrationWorkerTest` and `RegistrationConcurrentPatchTest` pass: 21/21. I also added one throwaway test to that worktree only, and it reproduces finding 1 (details below).

## Verdict

CHANGES_REQUESTED

There are two `bug` findings, both in `39405bb`:

- **B2 is only partly fixed.** It works for the single case the test covers, one starved group. It does not hold when the starved environment has two or more pending groups.
- **S3 puts responses that were not JSON into the transport bucket.** Edge and HTML refusals are among them.

`d760f6d` (the admission pass) is correct as far as I can tell. Its findings are advisory, and the main one is that none of S1/S2/S4 has a test.

## Findings

### [bug] B2: a starved environment with two or more pending groups still starves every younger group on every other environment
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/RegistrationWorker.java:367-381` (selection) and `:668-673` (`recordDeferral`)

The deferral backoff is 60 s (`deferral-backoff-seconds`). A pass that ends in a budget deferral first holds the single worker thread for `registration.max-wait` (15 m), because `registerOne` waits that long in the DEFAULT queue before `GatewayBudgetExhaustedException` is thrown. So a group's backoff always runs out long before the next starved group's pass finishes. Selection is still "oldest due first", so a deferred group comes straight back to the front as soon as its 60 s are up.

Scenario: env X is starved. A large start holds an ESSENTIAL reservation of botCount x 3, which pushes DEFAULT's effective ceiling to 0. Starved groups A and B are on X; healthy group H is on Y and is younger than both.
- t=0: tick picks A. It blocks 15 m and defers until t=16m.
- t=15m10s: A is not due, B is the oldest due group. B blocks 15 m and defers until t=31m10s.
- t=30m20s: A is due again (since t=16m) and older than H, so A runs again. Then B, then A, and so on.
- H never registers an account for as long as X stays starved.

With an instant refusal (the Phase 5 open circuit, which "may last a day or more") passes take milliseconds instead of 15 m. Ticks are 10 s apart, so the cycle closes once there are six starved groups older than H (6 x 10 s = 60 s). Several groups per environment is the normal operating shape: the prod Tai Xiu ramp is 10 x 50 groups on one environment.

I confirmed this in the scratch worktree with a test in the style of `aBudgetDeferralDoesNotStarveTheOtherGroups`. It has two starved groups on `env-1` and one younger healthy group on `env-2`. After each tick it expires the deferral of every group except the one just deferred, which is what a 15 m pass does in production. Over 8 ticks `otherClient.registerOne` is never called (`atLeastOnce` fails).

`RegistrationNotProgressing` cannot detect this either. A and B each initialise their series and fail nothing, and H never runs, so it would fire. But it fires for exactly the case the commit message says no longer exists.

Fix: make a deferral cost the group its **position in the order**, not just a fixed delay. For example:
- Keep a `lastDeferredAt` per group in `Attempt`.
- Order due groups by `(lastDeferredAt nullsFirst, createdAt, id)`. A group that has never been deferred always goes before one that has. Among deferred groups, the least recently deferred goes first, which gives round-robin.

This keeps "oldest create first" for healthy groups and lets any number of starved groups rotate behind them. Keying the deferral by `environmentId` alone is not enough: two starved environments with one group each recreate the cycle at 15 m per pass. Add a test with at least two starved groups, because the current test passes with or without this defect.

### [bug] S3: a response that was not JSON counts as a "transport failure", including the edge's own refusals
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/RegistrationWorker.java:682-692`, together with `bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:479` and `:569`

`isTransportFailure` treats any `IOException` in the cause chain as "we could not ask". But Jackson's `JsonProcessingException` / `JsonParseException` extend `IOException`. `registerOne` calls `mapper.readValue(responseBody, ...)` and `setDisplayName` calls `mapper.readTree(responseBody)`, so any body that is not JSON is classified as transport. That covers:
- Cloudflare's HTML block or challenge page (the 1k/5 min limit, or a WAF 403)
- An nginx 502 page
- An IP-allowlist 401 that returns HTML

In each case the gateway, or the edge in front of it, **did** answer, and in the Cloudflare case the answer is a refusal.

Effects:
1. The group retries 10 times at 30 s per index (the transport budget) instead of 3. Those are extra requests against an edge that is refusing this host, which is the behaviour the Cloudflare policy exists to prevent. The volume is small, but the direction is wrong.
2. `registrationError` reads `after 10 transport attempts: Unexpected character ('<' ...)`. That sends the operator to look at the network when the real cause is a block or whitelisting problem. Wrong diagnoses are the recurring cost on this project (see the CLAUDE.md "Bets never settle" section).

Fix:
- Exclude `com.fasterxml.jackson.core.JacksonException` (or `JsonProcessingException`) before testing for `IOException`.
- Better: classify where the failure happens. Only an exception thrown by `httpClient.send` is transport. Anything after a response exists is an answer.
- Add a test case to `aTransportFailureHasItsOwnBudget` with a `JsonParseException` cause, and assert that it gets the 3-attempt refusal budget.

### [smell] S1/S2/S4 in `d760f6d` have no tests, so reverting any of them fails nothing
`bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudget.java:647-662, 678-699, 502-505`; `UnlimitedGatewayBudget.java:81-90`

Mutation check, one by one:
- **S1:** move `runAfterUnlock(deferred)` out of the `finally` in `admitWaiters` / `stamp` / `release` / `cancelScope`, or go back to a returned list. No test throws inside a pass after an earlier tier has been admitted, so nothing fails.
- **S2:** change the gate back to `!deferredHere.isEmpty()`. Today the two conditions are equivalent, as the commit itself says, so no test can tell them apart. That is acceptable for a refactor, but the comment claims "no test failing" would be the risk, and there is still no test.
- **S4:** revert `UnlimitedGatewayBudget.maxWait` to `ZERO`. No test calls `UNLIMITED.maxWait`.

I checked the S1 fix itself by inspection and it is correct:
- All five callers own the list.
- `runAfterUnlock` runs after `unlock()` in the same `finally`, so nothing re-enters while the lock is held.
- Each action is isolated by a `try/catch` per action, so the original exception is not masked.
- `admit()` already used this shape.

There is no remaining path that dequeues and stamps a waiter without adding its completion:
- `consumeReservationLocked` and `stampLocked` sit between `pollFirst` and `deferred.add`, but neither can throw on a record scope.
- I found no double admission: a waiter is admitted only once it has been removed from its deque under the lock, and `dequeue()` is identity-based and reports `false` if the waiter was already admitted.

Fix: add one test to `SlidingWindowGatewayBudgetAdmissionTest`. Scope the PRIORITIZED head's `cancelled` supplier so it throws only from its second call onward, so the arrival succeeds and the pass throws. Queue an ESSENTIAL waiter behind a full window, free the room with a `reserve().release()`, and assert the ESSENTIAL waiter's `execute` returns. Also add a one-line assertion that `GatewayBudget.UNLIMITED.maxWait(tier)` is `null`.

### [smell] The circuit-open `break` leaves already-queued waiters parked, which contradicts A16.2, and nothing re-arms the timer once the window drains (relevant to Phase 5, not introduced here)
`SlidingWindowGatewayBudget.java:731`, `:823-826`

`admitWhileRoomLocked` stops at `circuitOpen.get()` but does not fail the queued waiters. New arrivals are refused (A16.2: "no tier parks on a block"), but an ESSENTIAL waiter that was queued before the circuit opened has `maxWait == null` and stays parked. When the open circuit stops all traffic, the stamps expire, `stamps.peekFirst()` returns `null`, and `scheduleWakeUpLocked` stops arming a timer. After that, the waiter is released only by an explicit pass when the circuit closes, or by `cancelScope`. If the circuit stays open for a day, a bot thread holding a `bot.creation.parallelism` permit is parked for a day. That is the P13 shape.

The code predates these commits and nothing sets `circuitOpen` at `f1cbc32`. I am raising it here because Phase 5 is being written now. Fix: in the transition to open, drain every queue with `completeExceptionally(new GatewayCircuitOpenException(...))` through the deferred list. Alternatively, have the pass do that instead of `break`.

### [smell] `RegistrationNotProgressing` counts failed attempts as progress
`prometheus/alerts.yml:525-527`

`registration_accounts_total` includes `outcome="failed"`, which `recordFailure` increments on every refused or transport attempt. The rule's premise is "zero accounts created in 30 minutes". Any group retrying anywhere in the JVM satisfies the `increase(...) > 0` side, so it masks a starved group. With S3's 10-attempt budget that mask now lasts about 5 minutes per failing index, and longer when a flaky gateway advances an index now and then (`attempts.remove(id)` on progress resets the count). Fix: `sum(increase(registration_accounts_total{outcome!="failed"}[30m]))`. Also update `AlertRuleMetricsTest` so it increments `failed` and still expects the rule to fire.

### [smell] `currentTarget` treats a deleted document as "keep the old target"
`RegistrationWorker.java:726-734`

`findById(id)` returning `Optional.empty()` means the document was deleted, which is different from a Mongo error. Both fall back to the cached target, so a group deleted by any path that does not go through `BotGroupService.delete` (that path calls `cancel` first) keeps registering accounts until the loop finishes. Examples are a manual Mongo delete, or a future bulk delete. The javadoc's reasoning ("a Mongo hiccup is not a delete") covers the exception arm but not the empty arm. Fix: on `Optional.empty()`, log at DEBUG and `return` from the pass, the same way the `cancelled` check does. The completion write already handles a missing document.

### [smell] `isTransportFailure` can loop forever on a cause cycle longer than one
`RegistrationWorker.java:683-690`

The `cause.getCause() == cause` guard is dead code, because `Throwable.getCause()` already returns `null` for a self-cause. A two-element cycle (A to B to A, possible through `initCause`) then spins the worker thread forever. That thread is the only registration thread in the JVM. This is rare, but the failure mode is total. Fix: cap the walk depth (for example 16), or track visited causes in an identity set.

### [smell] In-pass gauge refresh does not cover a pass that is parked inside the budget
`RegistrationWorker.java:445` (`refreshGaugesIfStale`)

The refresh runs once per index. A single `registerOne` can park for `registration.max-wait` (15 m), and for that whole time `registration_failed_groups` is stale. That is the same blind spot S5 set out to close, just shorter. Its own `for: 15m` makes this mostly harmless for `RegistrationStalled`. It is worth one sentence on the field's javadoc, which currently says the staleness is bounded by a tick.

### [style] The startup line does not show the two new settings
`RegistrationWorker.java:239-241`

`Registration worker started (...)` prints tick, max-attempts, display-name retries and failure backoff, but not `max-transport-attempts-per-user` or `deferral-backoff-seconds`. These are the two values that decide how B2 and S3 behave on a given box, and both are floored by the constructor, so the effective value can differ from the configured one.

## Notes

- **QA F-1 is fixed correctly, and the targeted `$set` cannot overwrite a concurrent PATCH.**
  - `persistProgress` and `recordCompletion` write only `registeredCount`, `namedCount`, `updatedAt`, and (for completion) `registrationState`/`registrationError`. Neither writes `botCount`.
  - The completion's `botCount <= done` condition is evaluated by Mongo, and `done = min(registered, named)` when a name pool exists agrees with `isComplete`.
  - The reverse race is still there and is documented: `BotGroupService.update` does a whole-document `repository.save`, so it can overwrite worker progress. I walked the cases. A PATCH saved after the completion write puts `PENDING` back, together with a counter that may be stale. The next pass either completes without any request or re-registers one index as `EXISTED`. Nothing gets stuck.
  - One edge that is outside this diff: a PATCH that read `PENDING` and saves after the worker wrote `FAILED` silently returns the group to `PENDING` with a fresh attempt budget. That skips the "needs a human" hand-off once.
- **Mutation check on `39405bb`'s tests:**
  - Reverting the per-index re-read: caught (`aMidPassBotCountRaiseIsHonoured`, `times(4)`).
  - Dropping `.and("botCount").lte(done)`: caught by the query assertion.
  - Dropping `recordDeferral`: caught (`aBudgetDeferralDoesNotStarveTheOtherGroups`).
  - Making a deferral charge a refusal: caught (`aBudgetDeferralSpendsNoAttempt`).
  - Making `isTransportFailure` always `false`, or removing the transport cap: caught, both directions.
  - Deleting the `matched == 0` early return: **not caught**. The no-match stub keeps the completion `$set` out of the ledger whatever the code does, so the test cannot see the branch. The behaviour difference (an INFO line plus `attempts.remove`) is harmless, so this is noted, not raised as a finding.
  - B2's test passes with or without finding 1's defect; see above.
  - None of these tests deceives itself the way the earlier ones did. The rewritten `setBackoffElapsed` helper (rebuilding the record by component, and throwing instead of returning silently) is a real improvement.
- **S6 pruning is safe.** `retainAll` runs only on the single worker thread between passes. A registration request parked in the budget parks that same thread, so the scope predicate can never see a pruned entry for a live job. `delete()` calls `cancel` before `deleteById`, so a tick in between keeps the entry.
- **Admission (pre-existing, outside these commits):** the arrival fast path checks only its own tier's queue before taking free room. Between a stamp expiry and the wake-up (1 ms slack plus scheduler latency), a lower-tier arrival can take a slot ahead of a queued higher-tier waiter, but only if a burst of expiries drops the window below the lower tier's ceiling at once. Strict priority is therefore "almost always". Worth a sentence if A6 claims more than that.
- `reserve()` now runs the superseded-reservation WARN in the same list as, and before, the completions it admits. The old code also logged first, so this is not a regression. But the WARN goes to the `blocking = true` track-1 appender, and the principle "complete first, count second" from F3 would put completions ahead of log lines here too.
