# Code Review — BOT_PROVISIONING (Phase 1)

Branch: feature/bot-provisioning
Reviewed diff: `git diff 970ddcf..HEAD` (4792b0e, ffc8eac, 2c4bd51; d4d01fb is the plan only)

## Verdict

CHANGES_REQUESTED

Two `bug` findings, both small fixes. Everything else is advisory, but the first two smells
(blocked DEAD marking, dropped restarts) should be decided before Phase 2 adds deposits to this path.

## Findings

### [bug] The catch-up attach stacks a second reservation on top of the start's still-live one
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1238` (calls `attachLocked` -> `:1452`); the start's reservation is only released at `:1258`

`startLocked` calls `attachRemainderLocked(id)` from inside its `try`. The start's
`reservation` is released only in the `finally` at `:1258`, *after* the catch-up returns. The
attach then calls `budget.reserve(ESSENTIAL, added*3, forBot(id, …))`, and both reservations map to
the same key: `SlidingWindowGatewayBudget.reservationKey` = `tier|botGroupId` (`:1554-1559`).
`reserve` handles a second live reservation on a key as an anomaly (`:1473-1481`):

1. It logs `WARN "a second ESSENTIAL reservation arrived for … while one was live"`. That fires on
   every start whose catch-up builds anything, and it is a WARN against a documented invariant
   ("One start per group at a time … not expressible").
2. The start's unconsumed remainder can no longer be consumed, and the TTL can no longer see it.
   When the catch-up runs, the start's bots have only just been handed to `runtime.startBot`.
   Their WS upgrades and first balance reads, i.e. up to 2 x botCount requests, are still
   pending. Those requests now draw down the **attach's** reservation, so the attach is
   under-reserved for its own flood. The start's whole outstanding amount meanwhile keeps
   shrinking the DEFAULT/PRIORITIZED ceilings until the paced catch-up build finishes. That can
   take many minutes.

Scenario: a 1,000-bot start with a 200-account raise completing mid-build. The catch-up runs
about 600 requests of paced build. During it, up to about 2,000 phantom-reserved permits squeeze
drift reads and registration, and one spurious WARN goes to Loki.

Fix: call `reservation.release()` (idempotent) before `attachRemainderLocked(id)` at `:1238`. The
start's own admissions are finished by then.

### [bug] A completion event can still be lost between the holder's last re-read and `startAttempts.finish`
`BotGroupBehaviorService.java:1388-1392` (loop exit), `:595` / `:638` / `:852` (finish); refusal at `:552-556`

2c4bd51 closes the "refused as already in flight" race only while the holder is still inside
`attachRemainderLocked`. The holder's last `attachLocked` pass re-reads the group, sees nothing new
and returns false. After that the attempt stays open while the code unlocks, returns through
`start`/`restart`/`attach` and reaches `startAttempts.finish(id, …)` in `submit`'s `finally`
(`:595`). On `/restart` that also includes `restart()`'s tail (`findById` + zero-bot check). If
`RegistrationWorker` writes a completion and publishes in that window,
`attachIfRunning -> submitLifecycle -> begin` is refused. Nothing re-delivers the event.

Scenario: a +5 raise on a running group whose registration finishes about 1 ms after an
unrelated attach's final re-read. The 5 accounts are registered but never play until the next
restart. The only trace is the INFO line `"attach ignored — a start is already in flight"`
(`:553`), which reads as benign. There is no metric and no WARN. The window is short, but the
failure is silent and permanent.

Fix shape (pick one): (a) on a refused `begin` for `StartOrigin.ATTACH`, set a `pendingAttach`
flag on the open attempt, and have the paths that close an attempt (`submit`, `startTracked`,
`startForRecovery`) call `attachIfRunning(id)` after `finish` when the flag is set; or (b) call
`attachIfRunning(id)` unconditionally after `finish` on any successful start/attach, guarded
by the in-memory `builtUpTo < botCount` check plus the existing re-read. (a) avoids the DB read.
Either one also removes the need for the 5-pass cap to be correct (see below).

### [smell] A long attach blocks the health monitor from marking the group DEAD
`BotGroupBehaviorService.java:3522-3532` vs. the attach lock hold at `:1361-1368`

`handleBotGroupDeath` does `tryLock(2s)` and on failure logs
`"a start/stop holds its lock — leaving the status to that operation"`. Before this diff that was
true: the only long lock holder was a start, and the runtime was STARTING with no health monitor
running yet. An attach holds the lock for its whole paced build on an **ACTIVE** runtime whose
monitor is running. It does not take over the status either. `attachLocked` only re-checks
`ACTIVE`, which nothing could change, so it keeps adding bots to a group that is dying.

Scenario: a +1,000 raise means about 3,000 requests, roughly 15+ minutes of attach. An upstream
outage starts 1 minute in. Every 30 s tick logs that WARN and returns. The group is not marked
DEAD, `EnvironmentGroupDead` and recovery stay blind, and the full 1,000 bots get built into the
outage. The group is marked DEAD only on the first tick after the attach ends.

Suggested: have `attachLocked`'s build loop check the monitor's verdict (or a "death requested"
flag set by `handleBotGroupDeath` when `tryLock` fails) and abandon the attach. At minimum, fix the
WARN text so it does not claim the holder owns the status.

### [smell] `/restart` and a booked scheduled restart are silently dropped while an attach runs
`BotGroupBehaviorService.java:552-556`, `:2358`

The plan accepts "a second `/start` during it is the existing no-op". The same `begin` refusal
also swallows `/restart` and `scheduleRestart`'s one-shot `restartAsync(…, SCHEDULED_RESTART)`.
Before this diff that only happened while a group was still coming up. Now it can happen to an
ACTIVE, playing group for as long as an attach runs. A booked 04:00 restart that lands during a
raise is gone, and the only trace is one INFO line. Either let restart preempt an ATTACH attempt
(cancel it, then proceed), or log the dropped scheduled restart at WARN.

### [smell] `/status` reports the attach's `botsUp` against the whole group's `botCount`
`StartAttemptRegistry.java:81` (`begin` clears `last`), `BotGroupBehaviorService.java:3407`

A standalone attach is its own attempt, so `botsUp` counts only the attached bots. The DTO renders
`botsUp` against `botCount`, so a 100-bot group attaching 20 reads `STARTING, botsUp 3/120` while
100 bots are playing. After the attach, `last` retains `botsUp 20` against 120 for an ACTIVE
group. The previous start's retained record is also wiped. This is misleading on the one field
operators poll. Suggested: seed the ATTACH attempt's `botsUp` with `runtime.getBotInstances().size()`,
or expose the attach range on the attempt.

### [smell] `expectInitialized(added)` can overwrite the start's open aggregation in the catch-up
`BotGroupBehaviorService.java:1458`; `GroupLifecycleAggregator.java:202-211`

If the start had any failed bot, its `Pending` is still waiting for the 5 s idle flush when
the catch-up calls `expectInitialized(id, name, added)`. That overwrites `expected` on the same
entry, which still carries the start's `count`. The next `recordInitialized` then emits a line
like `group X: 48/3 bots initialized`. This is a tier-1 INFO line that is wrong in both numbers.
The fix falls out of finding 1's reordering if the catch-up waits for, or force-flushes, the
start's pending entry first, or if the attach uses a distinct aggregation key.

### [smell] The 5-pass cap strands the remainder with no signal
`BotGroupBehaviorService.java:1387-1393`

If pass 5 still built something, the loop exits with work possibly remaining and logs nothing.
The comment says "its next completion event resumes it", but that event already happened and
was refused, which is the whole reason the remainder exists. The cap is reasonable as a lock-hold
bound. When it is hit, log a WARN and hand off via finding 2's post-`finish` re-check rather than
relying on a future raise. The cap is reachable only by 5+ raises that each complete during the
previous pass, so this is rare. It should still not be silent.

### [smell] A failure mid-way through `startBot` cleans up bots the runtime already owns
`BotGroupBehaviorService.java:1481-1505`

If `runtime.startBot` throws on bot *k* (e.g. executor rejected), bots `1..k-1` are already in
`botInstances` and running. `started` is false, so the `finally` calls `bot.cleanup()` on all of
them. That leaves cleaned-up zombies in the runtime, and `builtUpTo` is not advanced, so the next
attach rebuilds the same indices (duplicate usernames). This is unreachable today, because the
executor is only shut down under the lock. Clean up only `bots.subList(k, size)`, or set
`builtUpTo` before the loop.

### [style] "attach ignored" wording
`BotGroupBehaviorService.java:553`

For `action="attach"` the line says the attach was ignored, but the holder is expected to catch
up. Something like `"attach deferred to the attempt in flight (…)"` would stop it reading as data
loss. (Finding 2 is the case where it really is lost.)

## Notes

- **Lock/lifecycle interactions otherwise hold up.** `/stop`, DELETE (`stopAndLogout`) and the
  activation STOP all cancel the attempt before taking the lock. `attachLocked` re-reads the runtime,
  `targetStatus` and `registrationState` under the lock and checks cancellation before handing bots
  over. Cancelled/abandoned bots are cleaned up in the `finally` and the reservation is always
  released. A DEAD or absent runtime is left for the reclaim path, which builds `1..botCount`. I
  found no double-build window: `builtUpTo` is set from the same `group` object the start built
  from, and a lowered-then-raised `botCount` correctly builds nothing until it exceeds `builtUpTo`.
- **Strategy slice** is correct, given that `StrategyAssignment.assign` is deterministic. It uses the
  *current* mix, so a mix PATCHed since the start applies to the new bots only. That is consistent with
  the plan's "drift by one slice" acceptance.
- **Logging tier**: the new INFO lines are once per attach (group-level). Per-bot lines are DEBUG. OK.
- **Extra DB read on every start** (`attachLocked`'s `findById` in the catch-up): one Mongo read per
  group start, which is negligible next to 3 gateway requests per bot. Acceptable. Note that the
  catch-up does extend the recovery reconciler's blocking tick and the startup daisy-chain whenever
  it actually builds. That is acceptable but worth a line in the plan.
- **Pre-flight (AD-4)**: correct. It runs post-merge, so a PATCH that changes `environmentId`
  together with a raise is validated against the new product's cap.
- **Operational note, not this diff's code**: once a running group is raised, its registration is
  `REGISTRATION_PENDING` and `validateStartable` rejects every restart path for the duration. That
  includes `startForRecovery` if the group dies meanwhile, which burns recovery budget on 400s.
  Phase 1 makes "raise a running group" the intended flow, so this pre-existing guard is now on the
  hot path. Worth an explicit decision before Phase 2.

## Re-check — dad2f44 (on 2a89b01)

Scope: only the Phase 1 findings above, plus any race the fixes introduced. Line numbers below
refer to dad2f44.

**Verdict: PASS.** Both bugs are fixed. One new narrow race remains (smell, below). It is
advisory and is not blocking Phase 2.

| Finding | Status |
|---|---|
| [bug] double reservation | **Fixed.** `reservation.release()` now runs before `attachRemainderLocked` in `startLocked`. Release is idempotent, so the `finally` stays harmless. |
| [bug] lost completion event | **Fixed.** `defer()` flags the follow-up via `open.computeIfPresent`, and `finish()` uses `open.remove` on the same CHM key, which makes them mutually atomic. Either the flag lands before the remove, and `finish` (which reads the set *after* the remove) returns it, or `defer` sees `NO_ATTEMPT` and the `while (!begin)` loop retries. All three closers (`submit`, `startTracked`, `startForRecovery`) run `runFollowUps(finish(...))`. `runFollowUps` hands off via `attachIfRunning`/`restartAsync`, both of which catch, so nothing throws into a `finally`. A follow-up attach that finds nothing new returns without re-deferring, so there is no follow-up loop. |
| [smell] 5-pass cap strands silently | **Fixed.** The remainder is deferred onto the holder's own attempt (or `attachIfRunning` if none is open) and logged at INFO. |
| [smell] attach blocks DEAD marking | **Fixed, with a new narrow race (below).** Cancel, then one more 2 s `tryLock`, then fall through to the next tick. The WARN text is now accurate. The cancelled attach's follow-ups are dropped, which is correct for a group about to be marked DEAD. |
| [smell] restart dropped during attach | **Fixed.** REST `/restart` returns 409 (`ConflictException`, INFO-logged in the handler). `runWithManualOverrideAsync`'s synchronous catch restores `activationMode` on it. A scheduled restart is deferred as `FollowUp.RESTART`. `defer()` re-checks `origin == ATTACH` atomically, so a restart cannot be queued behind a start that replaced the attach. If the attach is cancelled, the dropped restart gets a WARN. |
| [smell] `/status` botsUp | **Not addressed** (still advisory). |
| [smell] tier-1 expectation clobbered | **Fixed.** `expectAdditional` adds to the open `Pending`. In the catch-up, the start's own `recordInitialized` calls are all done (they happen inside `createBotsInParallel`), so the non-atomic `expected +=` has no concurrent writer there. |
| [smell] mid-loop `startBot` failure cleans owned bots | **Fixed.** Cleanup now covers only `bots` minus `toStart`. Bot uses identity `equals`, so `removeAll` is exact. |
| [style] "attach ignored" | **Fixed.** The line now reads "attach deferred to the attempt in flight". |
| QA: `builtUpTo` past refused indices | **Fixed.** It advances only over the prefix below `min(refused)`. Bots built past the gap are cleaned up unstarted, with one group-level WARN. Cancellations are excluded from `refused`. On a refusal `attachLocked` returns false, so the loop does not hammer an open circuit. The pending tail waits for the next trigger (completion or start), as the WARN says. |

### [smell] (new) `handleBotGroupDeath` can cancel the attempt that *succeeds* the attach
`BotGroupBehaviorService.java:3688-3695`

`openOrigin(id) == ATTACH` and `cancelAttemptAndScope(id)` are two separate steps. The attach
could finish between them: its closer runs `finish` and then `runFollowUps` on the same thread,
and that immediately `begin`s the next attempt. In that case `startAttempts.cancel(id)` flags the
**successor**. If the successor is the deferred `SCHEDULED_RESTART`, `restart()` runs its stop
half, the start half sees `isCancelled` and returns, and `restart()` logs "cancelled by a stop"
and returns normally. The group is left with no runtime and `targetStatus` still ACTIVE.
Recovery will not pick it up (it is not DEAD) and no reconciler starts it until the JVM restarts.

The window is microseconds wide and needs a death verdict, a deferred restart and an attach
ending at the same moment, so this is advisory. Fix: add an atomic
`StartAttemptRegistry.cancelIf(id, origin)` (implemented with `computeIfPresent`) and only
`cancelScope` when it returns true.

### Notes on the re-check
- Cancelling for death also calls `budget.cancelScope(id)`, and while the attempt is open,
  `startCancelled` makes every bot of the group refuse admission. That includes the running bots'
  queued first-balance reads and reconnect logins, not just the attach's own requests. A bot
  whose first read is cancelled takes `onStartFailed`'s "called off" branch and does not
  reconnect. This is acceptable because the group has already been judged DEAD and recovery or
  `/restart` rebuilds it. But the abort is not attach-only, and the same holds if the second
  `tryLock` fails and the next tick then finds the group healthy.
- In that transient case, the aborted attach's `ATTACH` follow-up is dropped without a log line
  (only a dropped `RESTART` is logged). The accounts wait for the next trigger. The "aborting the
  attach" WARN is the trace.

---

# Code Review — BOT_PROVISIONING (Phase 2: deposit at registration)

Branch: feature/bot-provisioning
Reviewed diff: `git diff dad2f44..bbb5381` (d818d86 cancelIf, f73ce2e depositForRegistration, bbb5381 worker/ledger/retry/validation)

## Verdict

CHANGES_REQUESTED

The ledger design holds up well against what it was built for: stale whole-document saves and two
workers racing each other. `markInFlight`'s CAS-upsert means two JVMs can never both send the same
index. Three holes remain. (1) A 200 is taken as credited without reading the body, and gwms is known
to put errors in the body at HTTP 200. (2) `credit` only records a 200 if the marker is still there,
and `resolve` acts on whichever marker the ledger holds at that moment. Together they let an
operator's answer turn a successful deposit into a second one. (3) The ledger is keyed by group, not
by account.

## Findings

### [bug] HTTP 200 is classified CREDITED without reading the gwms envelope
`bot-engine/.../client/ApiGatewayClient.java:1162`

gwms reports errors in the body at HTTP 200. We have evidence of this already. The register
endpoint answers `{"status":"EXISTED","code":409}` at **HTTP 200**
(`docs/reviews/GATEWAY_REQUEST_BUDGET/gwms-register-envelope.md`, plan Open Item 13). The TIP prod
funding runbook (`docs/plans/TIP_PROD_GAME_COVERAGE.md` Phase 4) treats a deposit as good only when
`$2==200 && $3 ~ /"code":200/`, which shows the team already expects a 200 with a failing body. The
one captured success is `{"status":"OK","code":200,"message":"Nạp tiền thành công"}`
(`BOT_LIVENESS_SEMANTICS/release-prod.md`).

What goes wrong: suppose a deposit fails at the business layer, for example an unknown or locked
account, a limit, or a drained agency float, and comes back as HTTP 200 with `code != 200`. It is
classified CREDITED. `credit()` moves the high-water mark past that index, so the index is never
tried again. The group completes and starts with an account that has no money.
`registration_deposit_amount_total` and the completion line (`N funded x A = …`) then report money
that never moved, and that metric is this feature's audit trail.

Which way each misclassification errs:
- 200 + failure body read as CREDITED: **under-pay**, silent, and the audit trail is wrong.
- 4xx read as REFUSED, then retried: double pay **only** if gwms credits and then answers 4xx. No
  evidence of that. Accept it.
- 5xx, timeouts and I/O errors after the send read as UNKNOWN: never double. Costs an operator
  question.

Fix shape: parse the body inside `depositForRegistration`.
- `code == 200` (or `status == "OK"`) → CREDITED.
- A well-formed envelope with a 4xx `code` → REFUSED, with `status`/`code`/`message` in the detail.
- Anything else (unparseable 200, 5xx `code`) → **UNKNOWN**. That errs toward the operator, never
  toward a second send.

### [bug] A 200 can be "un-recorded" by a racing `resolve`, after which the index is sent again
`bot-app/.../service/DepositLedger.java:90` (`credit`), `:115` (`resolve`); `BotGroupService.java:528-562`; `RegistrationWorker.java:595-605, 627-631`

`credit` is conditioned on `depositInFlight == index`. `resolve` is conditioned only on "the marker
names whatever index I just read from the ledger". The operator never names the index they
checked. The retry also has no atomic FAILED→PENDING step: it checks `isFailed` on a read, then
does a whole-document `save`.

Scenario A, two overlapping JVMs (rolling deploy, blue/green, or a future hot-reload; the worker
holds no lease):
1. JVM A is funding the group.
2. JVM B selects the same group while A's marker for index `k+1` is live. B correctly refuses to
   send, but `recordDepositUnknown` marks the group **FAILED**. A carries on regardless. Its loop
   never re-reads `registrationState`, so it marks and credits `k+2`, `k+3`, ….
3. The operator reads "unknown outcome for k+1" and checks the balance: funded. They then call
   `retry?depositOutcome=…`. `resolve` clears **A's live marker for `k+5`**, not `k+1`.
4. If they answered `not-credited`: A's `k+5` comes back 200 and `credit()` matches 0. The
   catch-all reports it via `recordDepositUnknown`, but the ledger now holds `(k+4, null)`, not a
   marker. The retry's `save` makes the group PENDING (or the next retry needs no answer, because
   the ledger has no marker). The next pass sends `k+5` **a second time**.
5. If they answered `credited`, and A's `k+5` turns out REFUSED or NOT_SENT: the ledger says funded,
   so the account is under-paid.

Scenario B, one JVM, a double-submitted retry (UI resubmit or double click):
1. Retry #2 reads the group as FAILED before retry #1 saves.
2. Retry #2 reads the ledger after the worker has already marked `k` for the resend that #1
   authorised.
3. `resolve(k, not-credited)` clears the live marker. The 200 is then lost exactly as in step 4
   above, and `k` is sent a third time.

The same applies to an UNKNOWN that races a `not-credited` resolve. `recordDepositUnknown` sets
FAILED, but the ledger marker is gone, so the next retry needs no answer and resends.

The comment at `RegistrationWorker.java:597` ("The marker is (probably) still set") is wrong in
exactly this case. `matched == 0` **means** the marker no longer names `index`.

Fix shape, three changes, each cheap:
- A 200 is authoritative. `credit` should do `$max depositedCount: index` filtered by `_id` only,
  and clear `depositInFlight` only if it equals `index`. Two updates are fine, or one pipeline
  update.
- On UNKNOWN, and when the credit write fails, re-assert the marker
  (`$set depositInFlight: index` where `depositedCount < index`) before going FAILED.
- Make the operator name the index they checked (`?index=k`, or compare against the
  `registrationError`/mirror they were shown) and reject a mismatch. Flip the state with a
  conditional `updateFirst(registrationState == FAILED → PENDING)` instead of check-then-`save`.

### [bug] The ledger is per (group, index), not per account: an existing account is funded again
`RegistrationWorker.java:543-551, 583-588`; `BotGroupService.save` (no prefix check)

The worker deposits right after `registerOne` regardless of whether the answer was
`ALREADY_EXISTED`. A group's ledger knows nothing about other groups.

Two everyday ways to fund the same account twice:
- **Delete and recreate with the same `namePrefix`.** This is the natural operator recovery for a
  botched group. `depositLedger.delete` runs, the new group id starts at 0, every existing account
  answers EXISTED, and every one is funded again.
- **Overlapping prefixes.** `"tp" + 15` and `"tp1" + 5` are both `tp15`. Nothing rejects a
  prefix that collides with another group's.

If "no account is ever deposited twice" is a per-account property, and that is how the task states
it, this breaks it with no race at all.

Fix shape: at minimum, when `initialDeposit > 0` on create or raise, reject a `namePrefix` that
equals another group's prefix in the same environment, or extends it with a digit.

Stronger: treat `ALREADY_EXISTED` on an index this job has not registered (`registered < index`)
as a stop-and-ask for a funded group. The only false positive is a crash between `registerOne`
and `persistProgress`, which is a tiny window.

If re-funding is accepted policy, write it into AD-9 and say so in the API description.

### [smell] Lowering `botCount` clears an unknown-deposit FAILED and leaves the marker behind
`BotGroupService.java:478-486`

The "PATCH botCount down to what registered" exit compares against `registeredCount` only. Two
consequences:

1. **A group that stopped on a REFUSED deposit at `k` is declared startable with `k` unfunded.**
   `registered = k`, `deposited = k-1`. A later raise then seeds past it (AD-9), so `k` is never
   funded.
2. **A group that stopped on an UNKNOWN at `k` keeps the ledger marker.** Its mirror
   `depositInFlight` also still shows while state is null. On the next raise,
   `seedAtLeast(before)` moves `depositedCount` past `k`. The worker then finds the stale marker
   and FAILs the group with a question about an index that is already "funded". Answering
   `not-credited` logs "it will be sent once more" but sends nothing.

It does not double-pay, but it misleads the operator about money. Use
`min(registered, deposited)` for deposit groups, or refuse to clear FAILED while
`ledger.depositInFlight != null`.

### [smell] `shutdownNow()` turns routine deploys into money questions
`RegistrationWorker.java:290`

Suppose a deploy interrupts the worker between `markInFlight` and the credit write. That window
covers the whole HTTP call. The result is UNKNOWN, a FAILED group, and a human balance check. On a
funded job the HTTP call is a large share of each cycle, so many deploys during a job will produce
one of these.

Fix shape: `shutdown()`, then `awaitTermination` for about `GATEWAY_REQUEST_TIMEOUT` + slack,
then `shutdownNow()`. Or check a stopping flag between stages, so the in-flight deposit finishes
and is recorded.

### [smell] `recordDepositUnknown` can fire its ERROR every tick, and hides its own failure at DEBUG
`RegistrationWorker.java:844-866`

If the FAILED write throws, the group stays PENDING, and every 10 s tick reselects it and logs the
ERROR again. The javadoc's "fires once per incident" does not hold. The write failure itself is
DEBUG, so Loki shows a repeating ERROR with no cause.

Log the write failure at WARN, and throttle per group, for example by recording a deferral.

### [smell] Credited-but-unrecorded asks the operator a question we know the answer to
`RegistrationWorker.java:595-605`

A 200 followed by a Mongo blip goes straight to FAILED. Retrying `credit` a couple of times first
would avoid most of these, and once the `$max` fix above is in, retrying is idempotent. The reason
text should also say "answer `credited`". `fundedThisJob` is not incremented on this path, so the
completion line under-reports.

### [smell] A REFUSED deposit carries only "HTTP 4xx"
`RegistrationWorker.java:880`, `ApiGatewayClient.java:1165`

The operator gets no gwms `status`/`code`/`message`. That is the difference between "IP not
allowlisted" (401) and "bad amount". `bodyForLog` already exists. The login path does the same
thing (GATEWAY_REQUEST_BUDGET row 13).

### [style] Inline fully-qualified names
`ApiGatewayClient.java` (`java.util.concurrent.atomic.AtomicBoolean`, `java.net.ConnectException`, `java.util.Map`), `BotGroupService.java` (`java.util.Locale`), `RegistrationWorker.java` (`java.io.IOException`)

Use imports. The rest of these files uses them.

## Notes

- **The ledger is sound against what it set out to fix, and the deviation from the plan is right.**
  - `BotGroup` has no `@Version`, so moving the authoritative count off the document is the only way
    a stale `replaceOne` cannot roll back `depositedCount`.
  - `markInFlight`'s upsert collides on `_id` whenever the entry disagrees, so a second worker
    (other JVM) or a stale local `deposited` can never send an index twice.
  - `seedAtLeast` and `resolve(credited)` use `$max` and only move forward.
  - What is missing is set out in the second bug above: `credit` must not depend on the marker, and
    `resolve` must not act on a marker the operator did not see.
- **Outcome classification otherwise checks out.**
  - The marker is written after budget admission **and** after the stream permit
    (`ApiGatewayClient.java:~1144-1152`), so a `StreamWaitTimeoutException` is truly not-sent.
  - The budget runs the callable on the caller thread, so no detached send outlives a timeout.
  - `ConnectException` only comes from the TCP connect of a new connection, so nothing was on the
    wire. `HttpConnectTimeoutException` lands in the IOException arm as UNKNOWN, which is safe.
  - The JDK does not retry POSTs (no `jdk.httpclient.enableAllMethodRetry` anywhere).
  - `CloudflareBlockDetector` flags only 403/429 CF pages, so 52x origin errors (e.g. 524, where the
    origin did get the request) stay UNKNOWN. That is correct.
  - `DepositMarkerException` is not an outcome and never clears the marker. That is correct.
- Runs at `RequestTier.DEFAULT` with the registration wait. Never PRIORITIZED. ✓
- Metrics: `initRegistrationDepositSeries` pre-registers all four outcome series and the amount
  series at 0 under the same `mdcTags()` the increments use, at the top of every pass. ✓
- Logging tiers: per-account at DEBUG, one completion INFO per group, an INFO for the operator's
  resolution, an ERROR for the money hand-off. No token is logged (`masked(xToken)`). ✓
- d818d86 `cancelIf`: atomic with `begin`/`finish` through the CHM, and `cancelled` is volatile.
  This closes the re-check advisory. ✓
- Two JVMs: even with the fixes, B turns A's healthy in-flight deposit into a FAILED group (a false
  UNKNOWN). That is safe but noisy. A worker lease (e.g. a `registrationOwner` + expiry claimed by
  conditional update) would remove it. Not required for this phase if prod is single-instance.

## Phase 2 re-check: 32b718f, 7f11467, 6318a91 (on 3e379a9)

Scope: only the Phase 2 findings above. Verdict: **PASS.** All three bugs are fixed. I found no
double-deposit path that a single JVM can reach. One residual case remains for two JVMs, and it
also needs an operator to give a wrong answer at a bad moment. It is listed below as advisory.

| Finding | Status | How it is fixed |
|---|---|---|
| bug 1: 200 read as credited | **Fixed** | `classifyDepositAnswer` (`ApiGatewayClient.java:1166-1216`) parses the body. HTTP 200 with body code 200 is CREDITED. HTTP 200 with a 4xx body code, or any HTTP 4xx, is REFUSED. HTTP 200 with no code, another code or an unreadable body is UNKNOWN, and so are 3xx and 5xx. The gwms status, code and message are carried into the detail. Every case that is not certain goes to UNKNOWN. |
| bug 2: a racing `resolve` un-records a 200 | **Fixed** | `credit` now raises the funded count to at least `index` unconditionally (Mongo `$max`, filtered by `_id` only), then clears the marker only if it still names `index`. It is idempotent and retried 3 times. On UNKNOWN or a failed credit write, `reassertInFlight` puts the marker back, but only when `depositedCount < index`. The retry must name `depositIndex`, and a mismatch is a 400. FAILED to PENDING is one conditional update. Two concurrent retries: the second fails `resolve` and gets a 400. |
| bug 3: per-group, not per-account | **Fixed (errs toward under-funding)** | An EXISTED answer on an index this pass registers calls `DepositLedger.skip`. That write uses the same guard as `markInFlight` (indices up to `index-1` done, nothing in flight), and it lands before `registeredCount` is written. After a crash, the re-register finds the index already settled. The cost: our own account is skipped if a crash lands between the register call and the `persistProgress` write. That is documented and goes to one WARN per group. |
| smell: lowering botCount orphans the marker | **Fixed** | `depositTargetMet` refuses to clear FAILED while a marker is set, or while the remaining indices are not all funded. Skipped indices count as done. |
| smell: ERROR every tick | **Fixed** | ERROR fires once per `group:index` (the set is cleared by `enqueue`). A failed write logs WARN and calls `recordDeferral`. |
| smell: credited-but-unrecorded | **Fixed** | 3 write retries. The reason says "answer credited". `fundedThisJob` now counts it. |
| smell: REFUSED carried only "HTTP 4xx" | **Fixed** | gwms status, code and message are in the detail. |
| smell: `shutdownNow` | Open (advisory) | Unchanged. A deploy during a funded job can still produce an operator question. This never causes a double deposit. |
| style: inline FQNs | Open (advisory) | Unchanged. |

**Attacks tried against the new code, none of which double-pays in one JVM:**
- **Credit-write retry, partial landing.** Say `$max` lands and the clear fails three times. Then
  `reassert` does nothing (the count is not below `index`). The group is FAILED with the marker
  equal to `index`. Either answer then leaves the count at `index`, so nothing is re-sent.
  - Small wart: answering `not-credited` logs "it will be sent once more", which is not true here.
- **Skip vs funded.**
  - `skip` only fires when `deposited < index`. With the new `min()` index, that means
    `deposited == index-1` exactly, so the guard holds.
  - A crash after `skip` makes the re-register answer EXISTED again. By then `deposited == index`,
    so neither the skip nor the deposit stage runs.
  - Across two JVMs, `skip` and `markInFlight` are mutually exclusive on the same guard. The loser
    throws and sends nothing.
- **`min()` index.** If the funded mark is ahead of the name/register mark (an AD-9 seed), the
  deposit stage is skipped because `deposited >= index`. If it lags (a refused deposit), the
  earlier stages are skipped because they are already done. Every iteration moves the lowest mark
  forward, so no index is skipped and none loops forever.
- **Concurrent retries.** The second `resolve` matches nothing and gets a 400.
- **Stale whole-document saves.** They cannot reach the ledger. A save that brings back FAILED makes
  the next retry name the ledger's index. If it brings back PENDING, the worker sees the marker
  and stops.

**Still open (advisory, two JVMs only):**

[smell] **An operator answer given while the original request is still in flight.**
- Sequence: JVM B marks the group FAILED because of A's *live* marker `k`. The operator checks the
  balance before A's request has landed and answers `not-credited&depositIndex=k`. `resolve`
  clears the marker.
- B's next pass marks `k` again and sends a second deposit. If A's first request did credit, the
  account is paid twice: A records its 200 with `$max`, and B gets its own 200.
- A's `credit` then clears *B's* marker for the same `k`. That costs nothing extra, because the
  funded count is already `k`.
- Window: the 10 s `GATEWAY_REQUEST_TIMEOUT`, and only while two workers overlap.
- Cheap fix: store `markedAt` on the ledger entry. The retry rejects an answer while the marker is
  younger than `GATEWAY_REQUEST_TIMEOUT` plus some slack ("a request may still be in flight, check
  again in 30 s"). The longer-term fix is a worker lease.

[smell] **`resolve` runs before the conditional FAILED to PENDING update.**
- If that update then matches nothing, the operator's answer has *already been applied*, but the
  400 says "another retry was already accepted".
- This is safe. The answer was the operator's own, and the marker is gone. But the message should
  say the answer was recorded (`BotGroupService.java:~574-590`).
