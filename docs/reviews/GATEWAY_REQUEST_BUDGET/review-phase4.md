# Code Review — GATEWAY_REQUEST_BUDGET Phase 4 (asynchronous account registration)

Branch: `feature/gateway-request-budget`
Reviewed diff: `git diff 7326eb7..HEAD` (3 commits: `5e46af5`, `13b0084`, `636a2b5`)
Read first: `CLAUDE.md`, `docs/plans/GATEWAY_REQUEST_BUDGET.md` **A28 and A30** (A30 wins where it
collides with A17.3/A28.3), `docs/reviews/GATEWAY_REQUEST_BUDGET/review-phase3.md` (F1–F16 — the
fix round `5e46af5` is in scope and has never been reviewed).

Scope note: production code only. Plan compliance is Architect-2's, coverage is QA's; I name a test
only where it *asserts something weaker than it claims*, because that is a property of the code
under review, not of the test plan. The ~58 dirty RIK/Aviator working-tree entries were left
untouched; only this file is written.

Build check, in a detached worktree at `636a2b5` (not the dirty working tree):
`JAVA_HOME=…/openjdk-21.0.2 mvn -o -DskipTests test-compile` — clean across all five modules.

Phase boundary: **clean**. `circuitOpen` still has no setter, no block classification exists, and
nothing from A29 has leaked forward.

## Verdict

CHANGES_REQUESTED

Five `bug` findings and one `security` finding. Ranked by consequence: **B1** silently re-registers
and **renames** every account of every legacy group in production on its next PATCH; **B2** lets one
starved environment stop account registration for the whole JVM; **B3** pins a running scheduled
group outside its activation window; **B4** makes the recovery path this feature documents in three
operator-facing places a dead end; **B5** is the residual half of review F6, on the path where it
matters most. **SEC1** is pre-existing but was carried through a rewritten method unexamined and is
live on staging today.

The core of the phase is good and the fix round is largely honest work. F1 is genuinely fixed — the
bound is derived from the real watchdog, it is capped by the tier's own wait, and
`BotBudgetOutcomeTest.everySessionPathCallIsBounded` pins all three call sites and the absence of
the unbounded overloads, which is exactly the assertion that makes the fix un-revertable by
accident. F5, F7, F8, F9, F10, F11, F12, F13, F14, F15 and F16 are all real fixes at the named
sites, not annotations; F2 is dissolved by the deletion A25 predicted. A30's central factual claim
is **correct** (see Notes 1). The worker's serial/in-order/high-water-mark design is the right shape
and its javadoc is the best in the module.

What is not in shape is the **edges of the state machine** — what happens to a group that is not a
fresh 500-account create. Four of the five bugs are the same root cause in different clothes:
`registeredCount == 0` means two different things (*"nobody has tracked this group's accounts"* and
*"this group has no accounts"*), and the DTO layer reads it the first way while the update path
reads it the second.

## Findings

### [bug] B1 — any PATCH of a legacy or `existingGroup=true` group re-registers, and **renames**, every one of its accounts

`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:341-344`

```java
if (existing.getBotCount() > existing.getRegisteredCount()
        && !RegistrationState.isFailed(existing.getRegistrationState())) {
    existing.setRegistrationState(RegistrationState.PENDING);
}
```

The javadoc and A2.7 describe this as "raising `botCount` extends the registration target". The
predicate does not test that. It tests `botCount > registeredCount`, which is **unconditionally true
for every group that was not asynchronously registered** — every group created before this feature
(Mongo has no `registeredCount` field, Spring Data maps the absent field to the `int` default `0`)
and every group created with `existingGroup=true` (`save`'s `skipRegistration` branch writes no
counter). So the trigger is not "this PATCH asked for more accounts"; it is "this group predates
Phase 4", and *any* PATCH fires it — lowering `maxBet`, changing the strategy mix, renaming the
group.

What then happens to a 250-bot legacy group on its next routine PATCH:

1. It becomes `REGISTRATION_PENDING`, so `validateStartable` answers **400** on `/start`,
   `/restart`, the startup chain and auto-recovery until the worker finishes.
2. If it is currently running and `SCHEDULED`, B3 below pins it outside its window.
3. `RegistrationWorker` walks indices 1..250. Each `registerOne` answers `EXISTED` — 250 requests
   that buy nothing.
4. Because `namedCount` is also `0` and `hasDisplayNames()` is true, `setDisplayNameWithRetry` runs
   for **every index**, so all 250 live accounts are **renamed to fresh random names from the pool**
   — at ≥250 more requests, more with the ~43% collision rate MEMORY records.

That is ~500+ gateway requests, i.e. half of the Cloudflare 1,000-per-5-minutes allowance the whole
feature exists to stay under, spent on a config edit — and an unrequested mutation of production
accounts. The RIK hand-naming recipe in MEMORY (`project_rik_display_name_existed`) is exactly the
kind of work this silently undoes.

A30 §5 acknowledges the `EXISTED` cost ("two requests each and no harm") for the deliberate
botCount-raise case on a migrated group. It does not cover the display-name overwrite, and it does
not cover the trigger firing when nothing was raised.

The same pair of fields is read the *other* way three files over —
`BotGroupMapper.toDTO` and `BotGroupBehaviorService.renderedRegisteredCount` both treat
`registrationState == null && registeredCount == 0` as "this group was never asynchronously
registered, render nothing", with a comment explaining that this and "zero accounts exist" are
opposite situations. The update path infers the second from the same state.

Fix shape: decide the re-registration from the **PATCH**, not from the document — compare the merged
`botCount` against the pre-merge `botCount` and only extend when it rose — and additionally refuse
to infer "no accounts" from `registrationState == null && registeredCount == 0`, which is the
encoding the DTO layer already treats as "unknown". Whichever way, `namedCount` must not be allowed
to drive `update-fullname` over accounts that were never tracked.

`BotGroupServiceTest`'s two PATCH cases both use `registeredCount(100)` and `registeredCount(120)`;
no test PATCHes a group with `registeredCount == 0`, which is every group in production today.

### [bug] B2 — one starved or circuit-blocked environment stops registration for every group in the JVM

`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/RegistrationWorker.java:295-316`
(selection) against `:409-416` (the `GatewayBudgetException` arm).

Selection is "oldest `createdAt` among PENDING, not cancelled, and `isDue`", and `isDue` consults
only `attempts`, which is written **exclusively by `recordFailure`**. A budget deferral deliberately
writes nothing (A2.6, correctly). The consequence is that a group whose environment cannot admit a
DEFAULT request is re-selected on *every* tick, forever, and nothing else is ever looked at:

- `register()` blocks inside `registerOne` for up to `registration.max-wait` (15 m) on the single
  worker thread;
- it throws, logs one DEBUG, returns;
- 10 s later the same group is the oldest PENDING group again.

So during a large start on env A — the normal state this feature exists for, where an ESSENTIAL
reservation of `botCount × 3` drives DEFAULT's effective ceiling to zero — **no group on any other
environment registers a single account**, for the whole build. Under Phase 5 the same arm catches
`GatewayCircuitOpenException` (A30 §8 says so approvingly), and that exception's own message says the
block "may last a day or more": one brand's Cloudflare block then suspends account creation for
every other brand for a day.

Nothing surfaces it. `RegistrationStalled` reads `registration_failed_groups`, and a deferred group
is `PENDING`, not `FAILED`; there is no alert on `registration_pending_groups`. The only symptom is
"`registeredCount` is not moving", on a feature whose whole progress contract is that the operator
polls exactly that number.

The class javadoc argues the oldest-first order so "a 500-account group cannot be starved by a stream
of small ones". The converse — a permanently deferred group starving every other one — is the
failure `DeadGroupRecoveryScheduler` explicitly designs against ("a permanently failing group cannot
starve the others", CLAUDE.md), and this is the one place the idiom was copied without it.

Fix shape: give a budget deferral its own short `notBefore` on the group (the `Attempt` record and
`isDue` already exist and cost no attempt to reuse with `count` unchanged), or select the
earliest-due group that is not already known-deferred. One line of state, and it restores the
staggering property the class claims.

`RegistrationWorkerTest.aBudgetRefusalCostsNothing` drives three ticks against one group and
asserts only that nothing was marked FAILED; it has no second group, so the starvation is invisible
to it.

### [bug] B3 — the activation reconciler's registration guard suppresses STOP as well as START

`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/ActivationScheduler.java:129-143`

The early `return` sits above `ActivationEvaluator.decide(...)`, so it removes the group from
reconciliation entirely — including the `case STOP` arm. The guard's own comment justifies only the
start half ("`startLocked`'s 400 would otherwise be thrown, caught and logged here once a minute").

A group can acquire a `registrationState` **while it is running**: `update()` sets `PENDING` on a
live group (that is A2.7's advertised "register 200 more bots for this group" feature, and after B1
it is any PATCH at all). From that moment the reconciler never evaluates it again, so:

- its activation window closes and it **keeps playing** — real bets, real money, outside the window
  an operator configured;
- if registration then goes `FAILED`, it never stops at all until a human notices, because `FAILED`
  is equally non-null and equally skipped.

Fix shape: evaluate the decision first and gate only `case START`, e.g. skip the start when
`RegistrationState.isIncomplete(...)` and let STOP and NONE through. Stopping a group whose accounts
are half-created is always safe; it is the start that is not.

`RegistrationStartGuardTest.theActivationReconcilerSkipsARegisteringScheduledGroup` pins the START
half only.

### [bug] B4 — "PATCH `botCount` down and start with the accounts that exist" does not work, and the test that says it does asserts something else

`BotGroupService.update:341-344` + `BotGroupBehaviorService.validateStartable:~685-693`.

`REGISTRATION_FAILED` is cleared in exactly one place — `retryRegistration`. `update()` can only ever
*set* `PENDING`, and it is explicitly gated off for a `FAILED` group. So after the documented repair:

1. group is `FAILED` at 63/500;
2. operator PATCHes `botCount` to 63 → `63 > 63` is false, `registrationState` stays `FAILED`;
3. `/start` → `validateStartable` → **400**, repeating the same advice that just failed.

The advice appears in three operator-facing places, all shipped in this phase:
`validateStartable`'s 400 ("PATCH botCount down to %d to start with the accounts that exist"),
`RegistrationWorker.recordFailure`'s hand-off ERROR ("or PATCH botCount down to %d to use the
accounts that exist"), and `RegistrationStalled`'s alert description. The group is recoverable — PATCH
down *then* `/registration/retry`, which completes immediately and clears the state — but no message
says so, and the one an operator reads at 3 a.m. sends them in a circle.

This is also the phase's **self-deceiving test**:
`BotGroupServiceTest.loweringBotCountNeverUnregisters` ends with the comment *"The group is now
startable with what it has"* and asserts only `registeredCount == 120` and `never().enqueue(...)`.
It never asserts the resulting `registrationState`, which is the only thing that determines whether
the sentence is true — and it is false.

Fix shape: either clear `FAILED` in `update()` when the merged `botCount <= registeredCount` (the
group genuinely has what it needs), or change all three messages to name
`POST /{id}/registration/retry` as the second step. The first is better: the state is a statement
about an unmet target, and lowering the target meets it.

### [bug] B5 — `BoundedLogin` still leaks the JDK `HttpClient` and its `SelectorManager` platform thread on the interrupt path, while claiming "every path"

`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/BoundedLogin.java:119-150`

The F6 fix is `boolean settled`, set on the success (`:122`) and `ExecutionException` (`:132`) paths,
with `if (settled) client.abort();` in the `finally` (`:146`). `result.get(...)` has a **fourth**
exit: `InterruptedException`, which the method declares and propagates. On that path `settled` is
`false`, no `catch` arm matches, and the `finally` does nothing — so the `HttpClient`, and the
platform `SelectorManager` thread it owns, are left for GC exactly as before the fix.

The subclass's new javadoc states the opposite in bold: *"Called on **every** path — the timeout …,
the success and the library failure"*. Three of four.

The interrupt path is not exotic; it is the mass path. Every executor teardown that reaches a login
in flight goes through it — `BotGroupRuntime.shutdown`, `stopAndLogout`, `/stop` on a building
group, `RegistrationWorker.shutdown`'s `shutdownNow()`, JVM shutdown. A `/stop` on a 500-bot group
mid-build leaks one platform thread per interrupted login, which is precisely the Bot-1 thread-leak
sawtooth shape MEMORY records and the plan's own Findings item ("a 3k-bot start briefly spawns ~3k
platform threads").

Fix shape: `finally { client.abort(); }` unconditionally. `abort()` already swallows its own
`RuntimeException`, and `HttpClient.shutdownNow()` is idempotent, so the stated reason for excluding
the timeout path ("calling it twice … buys nothing") costs nothing either — and the `settled` flag
disappears with it.

### [security] SEC1 — `registerOne` logs the per-environment admin `X-TOKEN` in full and the bot password in the request body, at DEBUG, and staging runs at DEBUG

`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:420-422`

```java
log.debug("[Register] POST {} | X-TOKEN: {} | body: {}",
        apiGateway + registrationPath, xToken, requestBody);
```

`xToken` is the environment's admin credential — the same one that authorises `update-fullname` and
`deposit` for *every* account on that brand — and `requestBody` is the serialised
`UserRegistrationRequest`, which carries the bot `password`. Both are rendered in full.

**Pre-existing**, and I say so plainly: the line is unchanged context inside a method this commit
rewrote, and the same shape exists on `[UpdateFullname]`. Raising it here because (a) the method it
lives in was rewritten in this diff and the line was carried through unexamined, (b) `BOT_LOG_LEVEL=DEBUG`
on staging means it is being written today, once per account, on the one feature that creates
accounts in bulk, and (c) CLAUDE.md states the rule and even gives the house idiom
(`tokens.getAuthToken().substring(0, 10) + "..."`). It lands in track 2 only, which bounds the blast
radius to the box and 12 h / 3 d retention, so this is low severity, not an incident.

Fix shape: the existing truncation idiom for `xToken`, and log the username/index rather than the
serialised body (the body is reconstructible from the two fields that matter and the password is
never diagnostic).

### [smell] S1 — F3 is narrowed, not closed: a throw inside the locked loop still strands every waiter admitted in that pass

`SlidingWindowGatewayBudget.java:658-670` (`admitWaitersLocked`), `:636-647` (`admitWaiters`).

The per-action `try/catch` in `runAfterUnlock` is right and fixes the case F3 named. But the
`deferred` list is built **inside** `admitWhileRoomLocked`, and if that method throws,
`admitWaitersLocked` propagates without returning it:

```java
List<Runnable> deferred = new ArrayList<>();
try { admitWhileRoomLocked(now, deferred); }
finally { scheduleWakeUpLocked(now); }   // re-arms — but does not hand back `deferred`
return deferred;
```

`admitWaiters()` then never reaches `runAfterUnlock(deferred)` because the assignment did not
happen. Every waiter the loop had already dequeued and stamped before the throw is in no queue, on
no timer, and its future is never completed — an ESSENTIAL waiter parks for the life of the JVM,
which is the exact FOLLOWUPS P13 outcome the fix was written for.

The reachable way in is the one the new javadoc on `admitWhileRoomLocked` names itself:
`head.scope.isCancelled()` is foreign code called under the lock, "non-throwing today". Since Phase 4
there is a third implementation of that predicate (`RegistrationWorker`'s `cancelled::contains`), so
"today" now covers one more author.

Fix shape: run or return the partial list from the same `finally` that re-arms the timer — e.g.
`catch (RuntimeException e) { runAfterUnlock(deferred); throw e; }`, or hoist `deferred` into
`admitWaiters` the way `admit()` already hoists `deferredHere`.

### [smell] S2 — `admit()` runs an admission pass only when a leaked reservation produced a **log line**

`SlidingWindowGatewayBudget.java:491-495`

```java
deferredHere.addAll(expireStaleReservationsLocked(now));
if (!deferredHere.isEmpty()) {
    deferredHere.addAll(admitWaitersLocked(now));
}
```

The F12 fix is correct today only because `expireStaleReservationsLocked` returns a warning
`Runnable` on exactly the condition that creates room (`remainder > 0`). So "should I walk the
queues?" is currently decided by "did we emit a WARN?" — two questions that happen to have the same
answer and no comment saying they are coupled. Throttling that WARN, or logging it at DEBUG when the
remainder is small, would silently restore F12 with no test failing.

Fix shape: have `expireStaleReservationsLocked` report the freed remainder (or a boolean) separately
from its log lines, and gate the pass on that.

### [smell] S3 — a transport failure is charged as a gateway refusal, so a 90-second network blip can stall a 500-account create permanently

`RegistrationWorker.java:421-427`

The classification is binary: `GatewayBudgetException` → deferral, **everything else** →
`recordFailure`. `registerOne` declares `IOException`, and `ApiGatewayClient` wraps its own
`HttpTimeoutException`/`IOException` into `RuntimeException` on the naming path, so a DNS hiccup, a
connection reset or three 10-second timeouts on the same index spend the whole
`max-attempts-per-user=3` budget and leave the group `FAILED`, requiring a human, with
`registrationError` reporting a socket message as though the gateway had rejected the account.

The javadoc's own definition is "a **gateway refusal** costs one attempt … it does not say 'this
account cannot be created'". A `ConnectException` says neither. `failure-backoff-seconds=30` spreads
the three attempts over ~60 s, which is inside any ordinary blip.

Fix shape: give `IOException` (and the wrapped timeout) its own arm — either a deferral like the
budget's, or an attempt charged against a separate, larger budget. CLAUDE.md's "don't broaden to
`Exception` without justification" is the standing rule and the justification here covers only the
envelope-refusal case.

### [smell] S4 — `maxWait(RequestTier)` gives `Duration.ZERO` a third meaning, one method after F5 removed the second

`GatewayBudget.java:262-276`, `UnlimitedGatewayBudget.java:80-86`.

F5's whole point is that `ZERO` from a caller means *now or never*, always. The new accessor's
contract is "`null` when unbounded … `ZERO` rather than `null` for unbounded, deliberately", and
`UnlimitedGatewayBudget` returns `ZERO` to mean a third thing: *"no wait is needed here"*. It is safe
today only because the single consumer (`Bot.sessionBudgetWait`) special-cases
`tierWait.isZero()`; a future caller doing the obvious `execute(tier, scope, call, budget.maxWait(tier))`
gets fail-fast semantics from the budget that is supposed to admit everything.

Fix shape: return `null` from `UnlimitedGatewayBudget.maxWait` (there is no policy, which is what
`null` means on this method) and let the ambiguity stay dead, or state the third meaning on the
interface so the next `isZero()` check is not left to be re-derived.

### [smell] S5 — the two registration gauges are refreshed only inside `tick()`, so they freeze for the whole of a long pass and forever if the worker stops

`RegistrationWorker.java:149-150`, `:296-298`, read by `ObservabilityConfig.registrationGauges`.

`pendingGroups`/`failedGroups` are written at the top of `tick()` and nowhere else, and a tick blocks
for the entire duration of one group's registration — hours for a 500-account group under `enforce`,
or 15 minutes per deferral under B2. During that time both gauges report the state at the start of
the pass. If the scheduled task dies (`tickQuietly` catches `Exception`, not `Throwable`) or the
executor is shut down, they keep reporting the last values indefinitely and `RegistrationStalled`
never fires.

This is the same "a vanished sampler reads as the healthiest thing there is" trap CLAUDE.md spells
out for `LogQueueSaturated`'s vanished appender and for `AsyncQueueMetrics`. Worth either a
heartbeat/freshness dimension, or a second, cheap refresh outside the long pass. At minimum it
belongs in the gauge javadoc, which currently sells the caching as a pure win.

### [smell] S6 — the `cancelled` set grows without bound

`RegistrationWorker.java:143`, added in `cancel()` (`:265`), removed only in `enqueue()` (`:238`).

Group ids are UUIDs, so an id put there by a `DELETE` is never removed — every group ever deleted in
the life of the JVM stays in the set and is consulted on every tick's `filter`. Tiny (a string per
deletion), but it is unbounded and it is consulted from inside the budget lock via the scope
predicate. The natural place to drop it is right after `repository.deleteById(id)` in
`BotGroupService.delete`, or when a tick observes that the group is no longer PENDING.

### [smell] S7 — "an enqueue that is lost costs at most `tick-seconds`" is not true while the worker is working

`RegistrationWorker.java:229-249` (javadoc), against `scheduleWithFixedDelay` at `:183`.

Fixed **delay**, measured from the completion of the previous run, is the right choice and is well
argued. Its consequence is that the claim above holds only when the worker is idle: a create landing
while a 500-account group is being registered waits for that job plus `tick-seconds`, which the same
javadoc says is "legitimately a long time". The same is true of an enqueue that is *not* lost — it
queues behind the running task on the single thread. Both are acceptable; the sentence should say
so, because "costs one tick" is what the next reader will rely on when deciding whether the enqueue
path needs hardening.

## [style]

- **T1 — `await`'s javadoc contradicts its own first line of code.**
  `SlidingWindowGatewayBudget.java:~552-560`: the javadoc still reads *"`maxWait` of `Duration.ZERO`
  is an unbounded wait for ESSENTIAL only"*, three lines above the F5 comment stating that `ZERO`
  never reaches the method and `null` is the only unbounded encoding. F5 fixed the code and left the
  paragraph that caused it.
- **T2 — `EXISTED` is matched case-insensitively in one place and case-sensitively in the other.**
  `ApiGatewayClient.registerOne` uses `STATUS_EXISTED.equalsIgnoreCase(status)`;
  `isDisplayNameTaken` uses `STATUS_EXISTED.equals(status)`. Same constant, same gateway, two
  strictnesses — and the constant's javadoc is specifically about not getting the two endpoints'
  readings backwards.
- **T3 — leftovers from the deletion.** `BotGroupService.java:6` still imports
  `UpstreamRegistrationException`, which the class no longer throws. Stale prose references to the
  deleted symbols survive in `RecordingGatewayBudget.java:56` ("`registerUsers` fans out across
  virtual threads under a semaphore"), `BotGroupBehaviorServiceRestartTest.java:1251` ("`registerUsers`
  has one call site" — prose only, the test asserts nothing about it) and
  `scripts/bulk-create-accounts.py:401` ("mirrors `user.registration.parallelism`", a property that
  no longer exists). Everything load-bearing is gone: no `.java`, `.properties`, compose or deploy
  file references the removed symbols, `UserRegistrationResult` is deleted, and
  `EnvironmentClientRegistry` is out of `BotGroupService` and its tests.
- **T4 — `ApiGatewayClient.send`'s javadoc promises a Phase 4 feature that Phase 4 did not ship.**
  `:184-188`: *"From Phase 4 this is also where every response is classified before it is parsed, so
  a Cloudflare block page becomes 'edge block, cf-ray …'"*. `send` is
  `underBudget(tier, scope, httpCall(request))` and classifies nothing — that is A29/Phase 5. The
  sentence was written in Phase 3; it now names the phase a reader has just been told is complete.
- **T5 — `onStartup`'s javadoc overclaims.** *"Announce, **and immediately resume**, any registration
  a previous JVM left unfinished"* — it announces. Resumption is the tick's, up to `tick-seconds`
  later, which the third paragraph then says. A30 §6 is accurate; the method javadoc's first line is
  not.
- **T6 — `BotSessionBudgetWaitTest` can silently skip itself.** `shippedProperties()` uses
  `assumeTrue(path != null, …)`, so a module layout change turns the F1 guard into a green skip. The
  file is committed and its two candidate paths are exhaustive, so a hard `assertThat(path).isNotNull()`
  costs nothing and removes the one way this guard can stop guarding.
- **T7 — `tick()` loads full `FAILED` documents to compute a count** (`:298`). `countByRegistrationState`
  is the query; negligible today, but it runs every 10 s forever and the value is only ever `.size()`.
- **T8 — `GatewayRequestScope.registration(String)` now has no production caller** (two test callers).
  Same category as F13's `run()`, and the javadoc already says so — noted only so the next
  dead-code sweep does not have to re-derive it.

## Notes

### 1. A30's central claim is correct, and the deletion is the right call

I checked the claim against `7326eb7`'s source rather than against the amendment.
`setDisplayName(String username, String sessionToken, String displayName)` builds its request from
`Map.of("username", …, "fullname", …)` and `.header(SESSION_TOKEN_HEADER, xToken)` — the
*environment's* admin token. `sessionToken` appears in the signature and nowhere in the body. The
plan's arithmetic (`register + login + update-fullname`) rested on a parameter that was never read,
so **A17.3/A28.3 were wrong and A30 is right**: a resumed index costs two requests.

On the deletion, which the brief asks me to judge separately: keeping the parameter would have
preserved nothing. A token-authenticated variant cannot be reached from the parameter alone — the
token would have to *come from somewhere*, and the only source for a resumed index is the login the
amendment is arguing against, so the parameter and the login are one decision, not two. Re-adding
both later is the same edit either way. Meanwhile the dead parameter was actively propagating the
wrong cost model into two amendments. Delete was right.

The measurement behind it is also stronger than usual. `ApiGatewayClientRegisterOneTest` drives a
real HTTP exchange against a loopback stub, deliberately does *not* `reset()` between the register
and the resume, and asserts `login.aspx` count is **zero** and `totalReceived() == 3`. One caveat
worth recording: that proves *our client* sends no login; the claim that the real gwms accepts an
X-TOKEN-only `update-fullname` rests on `gwms-register-envelope.md`. The decisive argument is neither
— it is that the parameter was never read, so **this is the code that has been naming production
accounts all along**. Nothing changed upstream.

### 2. Repository-driven selection (brief question 2), judged

The choice is right and the reasoning is right: a group interrupted by a restart is absent from every
in-memory structure, which is the longest-time-to-notice case, and copying
`DeadGroupRecoveryScheduler`'s idiom means one fewer shape in the codebase. The three consequences
asked about:

- **Tick cost against group count** is fine. Two indexed queries every 10 s, returning only PENDING
  and FAILED groups, which is a handful in any realistic fleet. (T7 is the one gratuitous part.)
- **A group stuck PENDING forever** is B2, and it is the one place the idiom was copied without the
  anti-starvation half that makes it safe.
- **"A lost enqueue costs one tick" is false while the worker is busy** — S7. It is true in the case
  the sentence was written for (idle worker, fresh create) and the design does not depend on it, so
  it is a documentation defect rather than a design one.

### 3. Targeted `$set` versus `repository.save` (brief question 3), judged

The choice is correct and the stated reason is the real one: the worker holds a document read
minutes ago and a whole-document save would revert a concurrent PATCH, including the `botCount` raise
A2.7 sells as a feature.

The `$set` sets are complete and mutually consistent — `persistProgress` writes
`{registeredCount, namedCount, updatedAt}` after *every* index, so `recordFailure`'s
`{registrationState, registrationError, updatedAt}` and `recordCompletion`'s
`{registrationState:null, registrationError:null, registeredCount, namedCount, updatedAt}` never
need to carry a counter the document does not already have. A `updateFirst` against a deleted
document is a no-op rather than an upsert, so a `DELETE` racing the worker loses nothing.

The acknowledged residual race — a PATCH's read-modify-write reverting a counter advanced in the
millisecond between — does self-correct as claimed, at one `EXISTED` request. Worth noting that the
*same* race is what B1 turns from one request into `2 × botCount` requests plus a rename, which is
why B1 is a bug and this is not.

One inconsistency, not a finding: `retryRegistration` uses `repository.save(group)`, the
whole-document write the worker's javadoc argues against. It is safe (the group is FAILED, so the
worker is not touching it) but it is the same class of write in the same feature, and the next reader
will notice the asymmetry before the reason for it.

### 4. `observeModePacing()` abstract rather than `default` (brief question 4), judged

Right call, and the cost is real but small: four implementations, two of them fixtures, and the
enforcement is a compile error rather than a runtime surprise. The argument — a `default` returning
zero silently unpaces the one unpaced caller in the system — is exactly the failure mode the method
exists to prevent, and it is the same argument `requireComplete` makes for per-tier settings one
class over. `GatewayBudget` being an interface other phases extend is the counter-cost, but a phase
that adds an implementation is a phase that must answer "how do you pace an unpaced caller", so
being forced to answer is the feature.

The derivation `window / ceiling(DEFAULT)` cannot divide by zero — `GatewayBudgetSettings` validates
every ceiling positive (`:130-134`) — and returning `ZERO` under `enforce` keeps the mode test in one
place, which is the part I like most. S4 is the only thing I would change in the neighbourhood.

### 5. Cancellation ordering (brief question 5), judged

**No deadlock.** `BotGroupService.delete` → `registrationWorker.cancel` → `budget.cancelScope(id)`
takes the budget lock; `behaviorService.stopAndLogout` takes the group lock. The worker thread never
takes the group lock, and nothing takes the group lock while holding the budget lock, so there is no
cycle. `InOrder` in `BotGroupServiceTest.deleteCancelsRegistrationFirst` pins the ordering.

**The parked-request window is covered, in both directions.** The flag (`cancelled.add`) stops the
loop between accounts; `cancelScope` wakes a request already inside the budget — and the scope now
carries the group id (A28.6), without which `cancelScope` could not reach it at all. `find()` rather
than `forEnvironment()` is right: conjuring a budget and a fresh set of `gateway_budget_*` series as a
side effect of a delete would be a lie about the fleet.

Two residuals, neither a finding: a worker already *past* the `cancelled` check and inside
`persistProgress` can write counters to a document that is about to be deleted — harmless, the
delete wins; and `cancelled` is never cleaned up (S6).

### 6. The `ApplicationReadyEvent` listener (brief question 6), judged

**Swallowing is correct here and the comment gives the right reason** — an exception out of an
`ApplicationReadyEvent` listener closes the context and exits the JVM, and losing an instance over a
log line would be self-inflicted. It is `log.error` with the throwable, not a silent `catch`, so it
is not the swallow CLAUDE.md warns about.

**Logging-only is also correct**, for the reason A30 §6 gives: selection reads the persisted state, so
there is nothing to enqueue. The listener costs one throwaway `findByRegistrationState` query at boot
that the first tick repeats 10 s later; that is a fair price for the boot line, since a group created
five minutes before a deploy is otherwise indistinguishable from one nobody created. Only the javadoc's
first line overclaims (T5).

### 7. Error surface and tiering (brief question 7), judged — dev's claim holds

I walked every new and changed log statement in the diff.

- **Nothing per-account reaches INFO.** The worker's INFO lines are: one at
  `@PostConstruct`, one at `@PreDestroy`, one at boot (conditional on non-empty), one per group on
  cancellation, and one per group on completion. The per-account lines (`Registered {} ({}/{})`,
  `Set display name …`, the budget deferral) are all DEBUG, and the deferral one deliberately so.
  `BotGroupService.save`'s "will register N accounts in the background" is one line per create.
- **The two pre-existing per-user WARNs were genuinely demoted**, not just moved:
  `setDisplayNameWithRetry`'s "No display names available" WARN → DEBUG, its "Failed to get random
  display name" WARN → DEBUG, and its terminal `log.error("Failed to set display name after {}
  attempts")` → DEBUG. The single WARN that survives is the worker's, once per account that ends up
  nameless after all retries — which is WARN-appropriate under CLAUDE.md's model (same class as
  "deposit failure for a single bot") and is rare, since it needs all five pool draws to collide.
- **`recordFailure`'s ERROR is one per group**, at the point the group stops moving without a human —
  a hand-off, which is what the ERROR tier is for. Its content is the B4 problem, not its level.
- `ActivationScheduler`'s new skip line is DEBUG with the rate argument stated. `classifyCreationFailure`'s
  new `cancelled` arm correctly demotes a `/stop`-induced cancellation from ERROR to DEBUG while still
  counting it under its own label — that is a good fix and it is tested.

### 8. The rest of the F1–F16 round, verified at the named sites

F1 fixed and pinned at all three call sites (`BotBudgetOutcomeTest.everySessionPathCallIsBounded`
also asserts the *unbounded* overloads are never reached, which is the half that usually gets left
out). F2 dissolved by the deletion. F3 **partially** — see S1. F4 fixed (`wakeUp()` catches and logs;
`scheduleWakeUpLocked` in a `finally`; the under-the-lock contract is now on
`GatewayRequestScope.cancelled`). F5 fixed properly, via a single `resolvedWait` encoding, plus the
`registration.max-wait=0` rejection A28.1 asked for — T1 is the leftover paragraph. F6 **partially**
— see B5. F7 fixed in the exception message itself, which is the right place. F8 fixed: `new AuthClient(`
is on the whole-tree needle list with `BoundedLogin.java` allow-listed and the reason written down.
F9, F10, F11, F12 fixed at the exact lines named (F12 with the S2 coupling). F13/F14/F15 answered in
javadoc, which is what they asked for. F16 answered with a real companion rule
(`GatewayBudgetCapSeriesMissing`) rather than a comment — better than what was asked.

### 9. Concurrency and lifecycle, checked and clean

- `RegistrationWorker` is genuinely serial: one `newSingleThreadScheduledExecutor`, and `enqueue`
  submits onto the *same* executor rather than spawning, so "one group at a time" survives a create
  arriving mid-registration. `scheduleWithFixedDelay` over `AtFixedRate` is the right choice and the
  comment says why.
- `cancelled` is a `ConcurrentHashMap` key set and `attempts` a `ConcurrentHashMap`; `pendingGroups`
  / `failedGroups` are `volatile` for the scrape thread. The scope predicate is a plain set read,
  which satisfies the new under-the-budget-lock contract.
- MDC is set per group and cleared in a `finally`; tag values are substituted rather than dropped, with
  the Prometheus first-label-set reason written out — that is the `DeadGroupRecoveryScheduler` lesson
  correctly carried over, and `initRegistrationSeries` uses the same `mdcTags()` the increments use,
  which is the constraint CLAUDE.md says must not be broken.
- `@PreDestroy` shuts the executor down; `enqueue` handles `RejectedExecutionException` during context
  close. One gap of the standard kind: `tickQuietly` catches `Exception`, so an `Error` cancels the
  periodic task permanently and silently — same as the other schedulers in this codebase, so not a
  finding here.
