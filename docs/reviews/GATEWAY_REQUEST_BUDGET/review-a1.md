# Code Review — GATEWAY_REQUEST_BUDGET, staging anomaly A1 (A33)

Branch: feature/gateway-request-budget
Reviewed diff: `git diff ec77d9a..95f8fbc` (2 commits: `7818a8c` stream bound, `95f8fbc` first-read retry + reconnect hand-off)

## Verdict

CHANGES_REQUESTED

There is one `bug`, and it is a narrow one with a two-line fix: a `/stop` during a paced start is
now reported as N per-bot "session-setup" reconnects. The semaphore half (`7818a8c`) is correct
and I would ship it as it is. All other findings are advisory.

## Findings

### [bug] A cancelled start (operator `/stop`, activation STOP, DELETE) turns every queued first read into a reconnect
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:1470` (`onStartFailed`)

`readInitialBalance` passes every `GatewayBudgetException` through without retrying it, which is
right. `onStartFailed` then treats the exception like any other start failure. The cancellation
case is not one:

1. During a paced start, each bot's first read waits in the budget at ESSENTIAL (an unbounded wait
   that can be cancelled).
2. A `/stop` (or `stopAndLogout`, or the activation reconciler's STOP) calls `cancelStartInFlight`
   **before it takes the group lock**. `budget.cancelScope(id)` completes every queued waiter of
   the group with `GatewayRequestCancelledException`, and does so immediately.
3. Each bot thread wakes inside `start()`. `stopped` is still `false`, because `cleanup()` only
   runs once the stop has the lock and reaches `stopAllBots`. `reconnecting` is also `false`. So
   `onStartFailed` calls `triggerFullReconnect("session-setup failed: …")` for every one of those bots.
4. For each bot that produces: one WARN on track 1 (Loki); one
   `bot_reconnects_total{reason="session-setup"}` increment; one `notifyReconnectEscalation()`,
   which feeds the reconnect-burst scoped-DEBUG trigger for a group that is being torn down; one
   `RECONNECTING` transition; and one new `reconnect-<user>` virtual thread. That thread runs
   `performReauth` at PRIORITIZED. If `startAttempts` no longer reports the attempt as cancelled
   (the attempt has finished, so `cancel(id)` returned false but `cancelScope` ran anyway), the
   re-login is **not** cancelled by scope. It sits in the queue until `cleanup()` sets `stopped`,
   or it is sent if the window has room.

The outcome is that an operator decision gets reported as a reconnect storm on that group. Up to
`botCount` WARNs and counter increments come out of one `/stop` on a large paced start, and from
every SCHEDULED group whose window closes while its build is still queued. This is the opposite of
AD-9: a request the JVM chose not to send is not a failure. The `bot_reconnects_total` series is
the one the `GatewayBudgetQueueStuck` runbook tells operators to check ("a reconnect storm on a
sick group … `bot_reconnects_total` by group").

The tests do not cover it. `aBudgetOutcomeIsNotRetriedInPlace` uses `GatewayCircuitOpenException`
and asserts that it **does** reconnect, which is a defensible choice for the circuit, and
`aStoppedBotIsNotReconnected` sets `stopped` first.

**Fix shape:** in `onStartFailed`, also return when
`e instanceof GatewayRequestCancelledException || requestCancelled()`. Leave circuit-open going to
the loop, because a bot that met an open circuit after its login still needs a way back. Add a
test that throws `GatewayRequestCancelledException` from `getBalance` while `stopped == false` and
asserts that `authenticate(…, PRIORITIZED, …)` is never called and the status does not change to
RECONNECTING.

### [smell] A first read retried inside the reconnect loop still runs at ESSENTIAL, three times per attempt
`Bot.java:681` (tier chosen by `lastFetchedBalance < 0`), `Bot.java:868` (`readInitialBalance`)

The tier is picked by "has this bot ever read its balance", not by "is this the start path".
Before this change a bot whose first read failed became a zombie and never re-entered the loop.
Now it re-enters through `tryReconnectWs → start() → onStart`, so every reconnect attempt makes
**3 ESSENTIAL reads**. The reconnect loop's own tier is PRIORITIZED (AD-3).

Consider a failure that persists after a re-login: verifytoken returns 5xx, an empty `data` array,
or a changed body shape for a whole brand. Each bot then makes up to about
`10 cycles × 7 attempts × (1 WS upgrade + 3 reads) + 10 logins ≈ 290` requests over the ~51-minute
reconnect budget. For a 100-bot group that is about 2,800 per 5 minutes against a hard cap of 900.
The budget still bounds the total, so the Cloudflare policy holds. The cost is that for those 51
minutes the requests sit at the **top** tier, competing on equal terms with other groups' real
starts and pushing DEFAULT/PRIORITIZED to the back of the queue on the whole environment. After
that the group goes DEAD and `DeadGroupRecoveryScheduler` can run the cycle again (up to 6
attempts). Before this change the same fault cost one read per bot.

Each failed attempt in the loop also takes ≥ 3.5 s (500 ms in-method sleep ×3, plus 1 s + 2 s),
which is harmless on a reconnect thread.

**Fix shape:** take the tier from context. Use `reconnecting.get() ? PRIORITIZED : ESSENTIAL`, and
make one attempt instead of three when inside the loop, because the loop's backoff already does
the retrying.

### [smell] The session-path watchdog invariant does not account for the new permit wait
`ApiGatewayClient.java:363` (`acquireStream`), as seen from `Bot.sessionBudgetWait()`

`sessionBudgetWait()` sizes each budget wait on the message-processor thread at watchdog/4 (45 s
of 180 s). Its javadoc says the remaining quarter is "for the request itself". Each request on that
path can now also wait up to 10 s for a permit, on top of its own 10 s timeout and the existing
500 ms sleep. The worst case for the three-call `onNewSession` chain (pre-deposit refresh, deposit,
confirming read) is 3 × (45 + 10 + 10 + 0.5) ≈ 196 s, which is more than the 180 s watchdog. That
is exactly the "pacing manufactures a reconnect" case F1 was written to prevent.
`BotSessionBudgetWaitTest` still passes because it checks only the budget part.

The AD-10 drift read (`sendIfAdmitted`, "never parks") can now park for up to 10 s on a permit
before it sends. It could already block for up to 10 s on `send`, so this doubles an existing
bound rather than breaking a property that held. It needs both saturations at once (window full
**and** 32 slow requests in flight), so this is advisory.

**Fix shape:** for `sendIfAdmitted`, try the permit without waiting (or with a short wait) and
return `Optional.empty()` when there is none, which is what deferral already means there. For the
session-path `getBalance`/`deposit` calls, take the permit wait out of the caller's `maxWait`, or
at least restate the invariant in the javadoc and the test so they include it.

### [smell] Logging volume of the persistent-failure path: ERROR with stack trace on every attempt
`BettingMiniGameBot.java:1089`, `SlotMachineBot.java:493`, `BotGroupRuntime.java` (`startBot` catch)

Before this change, `onStart`'s `log.error("… initial session setup failed", e)` fired at most
once per bot per start. It now fires on **every** reconnect attempt of a bot whose first read keeps
failing, with the full stack trace, on track 1 and in Loki: up to about 70 per bot over 51 minutes,
so about 7,000 ERROR traces for a 100-bot group. The initial failure also logs ERROR a second time
in `BotGroupRuntime.startBot` ("Bot failed in virtual thread"), about a bot that is now RECONNECTING
and may recover. CLAUDE.md says ERROR should stay low-volume and is suitable for paging. The new
lines in this diff are tier-compliant: the retry line is DEBUG, and the hand-off reuses
`triggerFullReconnect`'s existing per-bot WARN, which matches the watchdog-expiry class. The
problem is how the existing ERROR now repeats.

**Fix shape:** in `onStart`, log at WARN without the throwable, or DEBUG, when the bot is
`reconnecting`. Alternatively, drop the `onStart` ERROR, since the rethrow is already logged by the
caller.

### [smell] The semaphore's exception-path release is untested (a mutant survives)
`ApiGatewayClient.java:337-346` (`httpCall`)

I moved `inFlight.release()` out of the `finally` so it runs only on the success path, which is a
permit leak on every `IOException` and on `reportEdgeBlock` throwing the circuit-open exception.
All 190 `*Gateway*` / `ApiGatewayClient*` tests in `bot-engine` still pass. The leak would be
silent and cumulative: after 32 failures, every gateway request on the environment times out after
10 s, for the life of the JVM. The production code is correct today, as listed in Notes. Only the
guard is missing.

**Fix shape:** a small unit test that makes 33+ requests answer with an exception (a stub returning
a Cloudflare block page under `enforce`, or a closed port), followed by one request that must
succeed without a 10 s wait.

### [smell] Local stream saturation is indistinguishable from a slow gateway
`ApiGatewayClient.java:363`

The timed-out permit wait raises `HttpTimeoutException`, the same type and arm as the request's own
timeout. `bot_verify_token_total{outcome="failure"}` / `bot_login_total{outcome="failure"}` count
it, and `EnvironmentAuthDown` alerts on those, so local saturation of our own connection would page
as an auth outage. The only difference is the message text. Nothing exposes permits in use or
waits that timed out, so if the gateway's real stream limit turns out to be below 32, or the JDK's
reservation lag is larger than assumed, nothing will show it.

**Fix shape:** a gauge for `MAX_IN_FLIGHT_REQUESTS - inFlight.availablePermits()` and a counter for
permit timeouts, per environment. Optionally, keep permit timeouts out of the auth-failure
counters.

### [style] The interrupt check in `readInitialBalance` cannot see the interrupt it is meant for
`Bot.java:884`

`ApiGatewayClient.readBalance` catches `InterruptedException` (from its `Thread.sleep(500)`, from
budget admission, and now from `acquireStream`) and wraps it in `RuntimeException` **without
restoring the flag** (`ApiGatewayClient.java:1089`, pre-existing). So
`Thread.currentThread().isInterrupted()` is false on exactly the path it guards. Teardown is not
affected in practice: `stopAllBots` calls `cleanup()` (which sets `stopped`) before
`shutdownNow()`, and the `stopped` check breaks the loop. The check is still misleading.
Restoring the flag in `readBalance`, or checking `e.getCause() instanceof InterruptedException`,
would make it do what it says.

## Notes

**Semaphore, checked against the questions in the brief:**
- **No permit leaks on any exit.** `acquireStream()` is outside the `try`, so an interrupt during
  `tryAcquire` or a timeout never reaches `release()`. Every exit after a successful acquire is
  inside `try/finally`: `send` throwing, `classified` throwing, and `reportEdgeBlock` throwing
  `GatewayCircuitOpenException` under `enforce`.
- **No lock-ordering hazard.** `SlidingWindowGatewayBudget.execute/tryExecute` run the callable
  after `admit` returns, outside `lock`, so no thread waits on the semaphore while holding the
  budget lock. `reportEdgeBlock` takes the budget lock while holding a permit, but nothing that
  holds the budget lock ever waits for a permit, so there is no cycle.
- **Priority:** the permit is taken after admission and held only for one round trip (≤ 10 s). The
  fair FIFO queue therefore serves requests in the order the budget admitted them, which is already
  priority order. A DEFAULT request admitted earlier can delay an ESSENTIAL one by at most the
  queue ahead of it divided by 32 and multiplied by the RTT. That is bounded and is not starvation.
  The cost of fairness is negligible next to a network round trip.
- **Mass interrupt / `/stop`:** an interrupted `send` releases its permit at once, while the JDK may
  still be resetting the stream. With A1's numbers (about 92-96 of 100 concurrent requests got
  through, so the server limit is about 100), 32 leaves enough headroom for that lag. **32 is safe
  against the likely real limit** (RFC 9113 recommends ≥ 100; the common defaults for nginx and
  Cloudflare are 128 and 256). A gateway advertising fewer than 32 would still fail, and nothing
  would show it (see the observability smell above).
- **A stamped request that times out unsent** over-counts the window. That is the safe direction
  and it is never a re-send. This is correct.
- **Other clients:** the circuit clearance probes (`GatewayBudgetRegistry.probeClient()`, a separate
  lazily built JDK client, one probe at a time), `EnvironmentWsProbe` (its own JDK client) and
  `GameMsClient` (its own static client) do not share `ApiGatewayClient.httpClient`. They do not
  need the permit and do not take it. `ApiGatewayClient` is `@Scope("prototype")`, so the bound
  applies per environment as the javadoc says.
- Keeping the bound a constant rather than a `@Value` (to avoid the `Semaphore(0)` hazard) is a good
  call, and `boundIsBelowTheRfcFloor` guards it.

**Defect 2, checked against the questions in the brief:**
- **Re-entrancy:** `tryReconnectWs → start()` and the periodic-logout `restart() → start()` (which
  normally runs with `reconnecting` already true, because `logout()`'s close triggers the
  pre-existing P15 disconnect loop) are both no-ops in `onStartFailed`. `triggerFullReconnect`'s
  CAS makes a second loop impossible even if the pre-check races. When the hand-off closes the
  socket, the late `onDisconnect` from that socket loses the CAS. I found no path that starts a
  second loop.
- **Leaks:** the failed client is closed by `triggerFullReconnect`, and every client the loop builds
  is closed by `tryReconnectWs` on failure. In the stop-during-reconnect race, the loop's
  `start()` → first read is refused by the cancelled scope, so the fresh client is closed on that
  path as well.
- **Double handling by the caller:** `BotGroupRuntime.startBot` only logs the rethrow (see the
  logging smell), and `botsUp` / `StartAttemptRegistry` do not count `start()` failures. Nothing is
  counted twice.
- **Circuit open:** this deliberately goes to the loop, which charges refusals as attempts. A block
  lasting longer than about 51 minutes will therefore take these bots to DEAD and hand them to
  dead-group recovery. That is better than a permanent zombie, and the block also affects every
  other reconnecting bot.
- **`SlotMachineBot` is covered:** its `onStart` also calls `onNewSession() → checkBalance()` before
  `addScenario`.

**Tests:** I ran them in a clean detached worktree at `95f8fbc` (now removed).
`ApiGatewayClientStreamLimitTest` 3/3, `BotFirstBalanceReadFailureTest` 5/5 and
`BotSessionBudgetWaitTest` 4/4 pass. The H2c stub test passed 5 out of 5 runs at about 5.4 s each.
The flakiness risk in CI goes the safe way: on a very slow box the **control** test could fail to
reproduce A1 and go red, but the bounded test cannot go falsely green, and its 10 s permit wait is
about 4× the roughly 2.4 s the 100-request burst needs. Mutation checks: removing the bound fails
`boundedBurstSucceeds` (so that test is not vacuous); the exception-path release mutant survives
(smell above). The control-then-bound design and the comment about a literal `1` in
`aTransientFailureIsRetried` are good anti-vacuity practice.

---

## Fix round — `95f8fbc..3a19562` (`ddc999b`, `3a19562`)

### Verdict

PASS (APPROVE)

The bug is fixed and verified, and so are all five smells and the style item. Nothing in the
expanded scope is a `bug` or `security` finding. Three new advisory items are below. The one worth
doing before prod is FR-1, a one-token change.

### Each original finding, checked where it was fixed

| Original finding | Status | Where / how verified |
|---|---|---|
| [bug] a cancelled start turns into a reconnect | **Fixed** | `Bot.onStartFailed` now tests `stopped \|\| requestCancelled() \|\| isCancellation(e)` (it walks the cause chain) before anything else. That path logs DEBUG and rethrows the original: no WARN, no `bot_reconnects_total`, no escalation, no loop. **Mutation check:** narrowing the guard back to `stopped` makes `BotFirstBalanceReadFailureTest` fail. |
| [smell] a first read in the loop runs at ESSENTIAL ×3 | **Fixed** | `readInitialBalance` uses `reconnecting.get() ? PRIORITIZED : ESSENTIAL` and makes 1 attempt inside the loop. The periodic-logout `restart()` never gets here, because `lastFetchedBalance >= 0` by then. |
| [smell] the permit wait is outside the watchdog bound | **Fixed** | `send(…, maxWait)` computes the deadline before `execute`, and `permitWait` = min(time left, 10 s), never below zero. Budget wait plus permit wait is at most `maxWait`, so the worst case is back to 166.5 s. Every `send(…, maxWait)` caller gives a non-null value: `readBalance`/`deposit` send `null` down the 3-argument overload, and registration is floored by `RegistrationWorker.MIN_REGISTRATION_WAIT`. So `maxWait.toNanos()` cannot throw an NPE. **Mutation check:** ignoring the deadline is caught. For the drift read, see (b). |
| [smell] ERROR with stack trace on every attempt | **Fixed** | The try/log in `onStart` is gone from both subclasses, and `SessionSetupLogGuardTest` pins that. Each outcome now logs exactly one line: called off is DEBUG; inside the loop is DEBUG, plus `tryReconnectWs`'s existing DEBUG; handed off is `triggerFullReconnect`'s WARN, and the caller logs DEBUG; final (DEAD) gets the caller's ERROR. All per-bot lines are DEBUG or WARN. |
| [smell] the exception-path release is untested | **Fixed for `httpCall`** | `permitsAreReleasedOnEveryFailurePath` now catches my round-1 mutant (release only on success). The **drift-read** release is a new gap, FR-2. |
| [smell] local saturation looks like a slow gateway | **Fixed** | `StreamWaitTimeoutException` is excluded from `incLogin(false)`/`incVerifyToken(false)`. `gateway_client_inflight_requests` and `gateway_client_stream_wait_timeouts_total{environmentId}` are registered at zero in `init`. |
| [style] the interrupt check is dead | **Acknowledged** | The comment now says `stopped` is what ends the loop. That is fine. |

### The focus items you asked about

**(a) Who now sees which exception.** `onStart` already rethrew before this round. Removing the
try/catch only removed the log line, so no caller sees an exception it never got before. The only
new thing is the wrapper type. Callers of `Bot.start()` in main code:

- `BotGroupRuntime.startBot`: catches `SessionSetupHandedOffException` → DEBUG, catches
  `GatewayRequestCancelledException` → DEBUG, and everything else → ERROR, as before. It is one
  executor task per bot, so the start loop for the remaining bots is never aborted.
  `BotGroupRuntimeStartFailureLogTest` covers it.
- `Bot.tryReconnectWs`: `catch (Exception)` → DEBUG, attempt charged. Inside the loop
  `onStartFailed` never wraps, so the loop sees what it saw before.
- Periodic-logout `restart()` (`BotGroupBehaviorService:~3415`): the new `catch
  (SessionSetupHandedOffException)` sits before `catch (Exception)`. The arms are mutually
  exclusive, because the wrapper is not a `GatewayBudgetException`. A circuit-open that used to hit
  the budget arm now hits the hand-off arm. Both log DEBUG.
- Dead-group recovery: goes through `startLocked → startBot`, so it is the same as the first bullet.
- **`botsUp`** is counted in the creation task (`startAttempts.botUp` after `createSingleBot`), not
  from `start()`. A handed-off bot is still "up", and it is not counted as failed.
  **`monitorHealth`** marks a group DEAD only on the `DEAD` count, and `RECONNECTING` is not
  counted. So a wave of hand-offs at group start cannot mark the group DEAD by itself.
- Other subclasses: `TaiXiuGameBot` inherits `BettingMiniGameBot.onStart`. There are no other
  `Bot` subclasses in main code.

**(b) `sendIfAdmitted`.** The permit is taken with `tryAcquire()` before admission. A `finally`
releases it on every path: a refusal (`Optional.empty()`), a throw out of `tryExecute` (circuit
open or cancelled at admission), and a throw from `sendClassified`, including `reportEdgeBlock`.
The inner callable calls `sendClassified` directly rather than `httpCall`, so it is never acquired
twice. `GatewayCallSiteGuardTest` still passes, with a single `httpClient.send(` in
`sendClassified`. The production code is correct, but the release is untested (FR-2), and
`tryAcquire()` without arguments barges (FR-1).

**(c) `permitWait` sharing the `maxWait` deadline.** The arithmetic is correct. The clock is
`System.nanoTime()` and the result is clamped at zero. When the budget wait uses up the whole
deadline, the permit wait is `tryAcquire(0, ns)`. The timed form honours fairness, so it fails
immediately if others are queued, as a deadline should. Callers with no deadline (`send` with 3
arguments, and the login) keep the 10 s cap.

**(d) Other consumers of `StreamWaitTimeoutException`.**
- `RegistrationWorker.isTransportFailure`: it is an `IOException`, so it is charged to the larger
  transport budget (10). That is correct; it is a transport-shaped "neither yes nor no".
- `classifyCreationFailure`: a login permit timeout reaches it as `UpstreamLoginException`, which
  is labelled `"auth"`. That is a mislabel (FR-3), but no alert reads it.
- `Bot.performReauth`: same wrapper → `catch (Exception)` → **marked DEAD**. That is FR-3 too.
- `ApiGatewayClient.deposit`: `catch (IOException)` → ERROR with stack plus
  `bot_auto_deposit_total{failure}`. No money moves and nothing is sent. That is FR-3 too.

**(e) The gauge holds the client strongly.** `strongReference(true)` on `this::inFlightRequests`
keeps the `ApiGatewayClient`, and with it the JDK `HttpClient` and its selector thread, alive until
a later `init` for the same environment id replaces the gauge. That adds **no new leak**:
`EnvironmentClientRegistry.removeClients` has no caller in main code, so the registry map already
holds every client for the life of the JVM. Re-creating a client swaps the gauge to the new one.
If `removeClients` is ever wired to environment delete, it must also remove the gauge, or the old
client survives through Micrometer. That belongs as a note on `removeClients`. While old bots are
still using a replaced client, its in-flight count is invisible. That is acceptable.

### New advisory findings

#### FR-1 [smell] `tryAcquire()` without arguments barges the fair semaphore
`ApiGatewayClient.java` (`sendIfAdmitted`, `if (!inFlight.tryAcquire())`)

`Semaphore.tryAcquire()` "will acquire a permit if one is available, whether or not other threads
are waiting". This is documented as ignoring fairness. Under saturation, a permit released for the
head of the FIFO queue can be taken by a drift read instead. Drift reads are the hottest call site:
an attempt per bot per round **before** the budget is asked. The javadoc says that holding the
permit "cannot starve anyone", which is overstated. In practice each barge holds the permit only
for an immediate budget refusal, or for one round trip at DEFAULT's small admitted rate, so the
effect is a delay, not starvation.
**Fix:** `inFlight.tryAcquire(0, TimeUnit.NANOSECONDS)`. It still never parks, and it honours
fairness.

#### FR-2 [smell] The drift read's permit release is unpinned (a mutant survives)
`ApiGatewayClient.java` (`sendIfAdmitted`, `finally { inFlight.release(); }`)

I deleted that one line. All 235 `ApiGatewayClient*`/`Bot*` tests in `bot-engine` still pass.
In production that is a permit leaked on every drift read, which is the most frequent gateway call.
The environment would wedge after 32 rounds' worth of reads, and every login, deposit and first
read would time out with `StreamWaitTimeoutException`. `theDriftReadNeverWaitsForAPermit` tests
only the case where no permit is available.
**Fix:** extend it with N > 32 admitted drift reads, or refused ones, followed by a request that
must get a permit at once and a check that `inFlightRequests() == 0`.

#### FR-3 [smell] "The gateway was never asked" is honoured by the metrics but not by the reactions
`ApiGatewayClient.authenticate` → `Bot.performReauth`; `ApiGatewayClient.deposit`;
`classifyCreationFailure`

The round gave local saturation its own type precisely because it is not a gateway failure. Three
consumers still treat it as one:
- A re-login that finds no free stream marks the bot **DEAD**, terminally, through `performReauth`'s
  `catch (Exception)`. AD-9's reasoning (the JVM declined to send, so the result is RETRYABLE)
  applies word for word.
- A deposit logs ERROR with a stack trace per bot and counts an auto-deposit failure.
- A login is labelled `bot_creation_failures_total{reason="auth"}`.

This was already true of `HttpTimeoutException` from the request's own timeout, and local
saturation tends to coincide with a slow gateway, so this is advisory. The cheapest win is the
first one: map a cause chain containing `StreamWaitTimeoutException` (or make it a public type) to
`ReauthOutcome.RETRYABLE`.

#### [style] The comment names a test that does not exist
`ApiGatewayClient.httpCall`: "pinned by `ApiGatewayClientStreamPermitReleaseTest`". The test is
`ApiGatewayClientStreamPermitTest.permitsAreReleasedOnEveryFailurePath`.

### Tests (clean detached worktree at `3a19562`, since removed)

- **`bot-engine` full suite:** 691 run, 0 failures, 0 errors.
- **`bot-app` targeted:** `BotGroupRuntimeStartFailureLogTest`, `PeriodicLogoutBudgetOutcomeTest`,
  `GatewayCallSiteGuardTest`, `RegistrationWorker*Test`, `PerBotInfoLogGuardTest`. 50 run, 0
  failures.
- **Mutation checks** (each confirmed to compile):

| Mutant | Result |
|---|---|
| `httpCall` releases only on success | **killed** |
| `onStartFailed` guard narrowed to `stopped` | **killed** |
| `permitWait` ignores the deadline | **killed** |
| `sendIfAdmitted` with no release | **survives** (FR-2) |

- **Side effect:** to run the `bot-app` tests, I ran `mvn install -DskipTests` in the worktree.
  That put `3a19562` SNAPSHOT jars of the modules into `~/.m2`. The next build from the main tree
  overwrites them.

Fix-round verdict: PASS
Findings (this round): 0 bug, 0 security, 3 smell, 1 style
