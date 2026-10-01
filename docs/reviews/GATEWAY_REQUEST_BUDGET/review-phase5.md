# Code Review — GATEWAY_REQUEST_BUDGET Phase 5 (Cloudflare block detection + circuit breaker)

Branch: feature/gateway-request-budget
Reviewed diff: `git diff f1cbc32..5b862bf` (14 commits, through `5b862bf`). The uncommitted working tree (RIK/Aviator) is out of scope.

I ran the tests in a detached worktree at `5b862bf` and removed it afterwards. I ran 24 classes covering every Phase 5 surface: the circuit, the probe, the detector, block handling in `ApiGatewayClient`, the in-repo login, the WS edge block, all the `SlidingWindowGatewayBudget*` tests, recovery, the probe scheduler, the WS probe, the registration worker, the alert rules and the call-site guard. 232 tests ran, with 0 failures.

## Verdict

CHANGES_REQUESTED

There is one `bug`. It is a narrow race, but it breaks the property this phase exists to provide: a request the circuit refused never leaves the JVM. Everything else is advisory. The circuit state machine, the refusal of queued waiters, the probe cadence and observe-mode behaviour are all correct as far as I can tell.

## Findings

### [bug] A waiter whose wait times out just as the circuit refuses it (or a cancel removes it) is sent anyway
`bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudget.java:639-645` (`await`, the `TimeoutException` arm), together with `:819-834` (`admitWhileRoomLocked`, the new circuit-refusal branch from `b8644ec`)

The `TimeoutException` arm reads `!dequeue(waiter)` as "it was admitted in the instant between the timeout and the lock" and returns `true`, so the caller goes on to `call.call()`. That reading was only safe while admission was the only way out of a queue. Since `b8644ec`, the pass on circuit-open also removes waiters, by refusing them. Cancellation removes them too, both in the pass and in `cancelScope`. In both cases the removal happens under the lock, and `completeExceptionally` runs later from `runAfterUnlock`.

How it fails:
1. A bounded waiter (PRIORITIZED or DEFAULT) has its `get(timeout)` fire. Before it can take the lock in `dequeue`, `reportEdgeBlock` takes the lock and runs its pass. The pass removes the waiter and defers the refusal.
2. `dequeue` returns `false`.
3. `await` returns `true` and the request is sent, without a stamp, into an edge that has just been seen blocking us.

The response is another block page, so `reportEdgeBlock` throws and the caller still ends up with the right exception type. But the request did go out, which the "never retried into" rule forbids. The same race on the cancellation path sends a request for a stopped bot or a deleted group. For a WS upgrade that is exactly the AD-8 hazard. The window is small, but it is widest exactly when the queues are deepest: a throttled fleet at the moment the edge blocks it.

Fix: when `dequeue` returns `false`, the remover is guaranteed to complete the future: admission and refusal both put the completion first in the deferred list, and `runAfterUnlock` isolates each action. So do not assume admission. Wait for the outcome:
```java
if (!dequeue(waiter)) {
    try { waiter.admitted.get(); return true; }
    catch (ExecutionException ee) { /* same mapping as the ExecutionException arm below */ }
}
```
You could also record the outcome on the `Waiter` under the lock and have `dequeue` return it. Add a test on a manual clock: queue a DEFAULT waiter, make its wait expire, call `reportEdgeBlock` before the timed-out thread reaches `dequeue` (a latch inside a `CircuitProbe` or the scope predicate can hold the lock), and assert that the callable never ran.

### [smell] The drift read throws into the message pipeline when that read is the one that finds the block
`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:976-988, 1011-1018`; `bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:676-691`

AD-10 says `getBalanceIfAdmitted` never parks and never throws a budget outcome, and `await`'s soft branch was written to keep that true for refused waiters. However, the request that *detects* the block has already been admitted. Its `reportEdgeBlock` throws `GatewayCircuitOpenException` from inside the callable, `tryExecute` does not catch it, `readBalance` rethrows it, and `checkBalance` has no handler. The exception then propagates out of `onEndGame → onNewSession` on the ws-parser message-processor thread.

At fleet scale the drift read is one of the likeliest requests to meet a block first, because it runs per bot per round on bots whose sockets are already open. Every drift read in flight at that moment hits this. This is not a regression in observe mode, where an HTML body already failed with a `RuntimeException` parse error. But under enforce, the soft contract this phase strengthened has a third exit that is not soft. `ApiGatewayClientBlockTest` covers `verifytoken` only through the blocking `getBalance`.

Fix: in `readBalance`, when `deferrable` is true, catch `GatewayCircuitOpenException` and return `OptionalLong.empty()`. The bot then marks the figure stale, and the next pre-deposit refresh is refused cleanly. Add a test for the deferrable read against a blocked stub.

### [smell] The detector treats any origin HTML 403 behind Cloudflare as a brand-wide edge block
`bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/CloudflareBlockDetector.java:86, 91`

Cloudflare rewrites `server: cloudflare` and adds `cf-ray` to every proxied response. That makes conjunct 2 true for every gwms answer, so the whole rule reduces to "403/429 + `text/html`". The gwms gateways are `.aspx`/IIS, and IIS serves its own HTML 403 pages (for example 403.6 "IP address rejected", or 403.14). If one endpoint ever answers like that, for instance a deposit or update-fullname path that this host's IP is not allow-listed for, enforce opens the circuit for the whole brand.

The circuit then cycles: the verifytoken probe gets JSON back and closes the circuit an hour later, and the next deposit reopens it. The javadoc's premise ("the block is the one real 403 in the system") is an empirical claim nobody has verified. The cost of being wrong is an hour-long outage per cycle on every bot of the brand.

Fix: on the HTTP path the body is available, so require the body marker there (`cf-error-details`, `Sorry, you have been blocked`, or Cloudflare's `Attention Required! | Cloudflare` title). Accept "text/html is enough" only on the WS path, where Netty keeps no body.

### [smell] A circuit opened by a WS-host block is closed by a probe against the API host
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:1202-1213`; `bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/GatewayBudgetRegistry.java` (`clearanceProbe`)

`connectUnderBudget` reports a WS-upgrade block to the environment's budget. The budget's only clearance probe is `GET <apiGateway>/gwms/v1/verifytoken.aspx`. Cloudflare counts per (egress IP × host). If the WS host is blocked and the API host is not, the first probe closes the circuit an hour later and the reconnect fleet's upgrades go straight back into the block. Each cycle sends a burst of in-flight upgrades into the block before it reopens.

Either probe the host that opened the circuit (keep the opening `GatewayEndpoint` and probe the WS URL for `WS_UPGRADE`), or write down in AD-13 the assumption that both hosts share one block. Today that assumption is implicit.

### [smell] The A27.1 waiter-MDC fix does not work under scoped per-group DEBUG, the case its javadoc names
`SlidingWindowGatewayBudget.java:986-999` (`recordAdmitted`)

`log.isDebugEnabled()` is evaluated *before* the waiter's MDC is restored. `ScopedDebugFilter` decides from the calling thread's `ThreadContext`. With the logger at INFO and group X scoped to DEBUG, a pass that runs on the `gateway-budget-<env>` scheduler thread (no MDC) gets `false`, and the line is never emitted. A pass on another group's thread is filtered twice, once per MDC. The fix only works under global DEBUG, which is what `SlidingWindowGatewayBudgetWaiterMdcTest` sets.

Fix: restore `mdc` before the `isDebugEnabled()` check. Add a test case with the logger at INFO and the waiter's group armed in a `ScopedDebugRegistry`.

### [smell] `group_recovery_skipped_total` is registered lazily
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/DeadGroupRecoveryScheduler.java:383`

This is the shape CLAUDE.md says not to reintroduce for `group_recovery_*`: a counter created at increment time first appears at 1, and `increase()` misses the first skip. No rule reads it today, so the impact is limited to the dashboard. But it is the family's only lazy series, and the first rule anyone writes on it ("recovery has been skipping for N hours") will under-read the first incident. Pre-register it at zero per environment, or note in its javadoc that it is dashboard-only.

### [smell] The `GatewayEdgeBlocked` description tells operators that a restart "closes it immediately"
`prometheus/alerts.yml:631`

That is true of the circuit, not of the block. A restart during a live block auto-starts every ACTIVE group, and each group's first logins go into the block before the circuit reopens. On a box with several brands that is one burst per brand, which pushes the "possibly until someone clears it manually" block further out. The next sentence ("before restarting, find what drove the window") does not say "only after the block is confirmed lifted". Reword it so that restarting is the step taken *after* SA confirms the block is lifted.

### [smell] A login request that fails to serialise counts as a login failure
`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:395, 403-417`

`loginRequest(ctx)` now runs inside the `try`, so a Jackson serialisation error (an `IOException`) takes the transport arm and calls `metrics.incLogin(false)` for a request that was never sent. That feeds `EnvironmentLoginFailing`, the alert the A4 comment protects from exactly this kind of miscount. Build the request before the `try`, or catch `JsonProcessingException` separately. This is minor, because serialisation failures are a code bug, not a runtime condition.

### [style] `connectUnderBudget` drops the handshake failure when the circuit-open refusal replaces it
`Bot.java:1206-1211`

Under enforce, `reportEdgeBlock` throws, and the original `WebSocketClientHandshakeException` (status, headers) is lost from the stack trace. Use `e.addSuppressed(...)` (or catch the refusal and add `e` to it as suppressed) so the evidence stays attached.

## Notes

**Fix-round verification (`review-phase4-fixround.md`).** I checked each fix at the site where the defect was, not just where the commit message cites it:
- **B2**, `deferralSeq` ordering at `RegistrationWorker.java` selection and `recordDeferral`: fixed. The ordering is never-deferred first, then least recently deferred, then `createdAt`, then id. `severalStarvedGroupsDoNotStarveAHealthyOne` drives two starved groups and expires each deferral except the latest, which is the scenario from the finding.
- **S3**, `JacksonException` checked before `IOException` in `isTransportFailure`: fixed. It is tested both direct and wrapped.
- **Circuit-open `break`**: replaced by refusal in `admitWhileRoomLocked`, and `reportEdgeBlock` runs the pass under the same lock that sets the flag. Fixed, apart from the `await` race above, which is the other half of the same invariant.
- **`RegistrationNotProgressing`**: `outcome!="failed"` is in place, and the test asserts the expression against a real scrape that includes the series being excluded.
- **`currentTarget` deleted → `DELETED`**: fixed, and the loop returns. A leftover `attempts` entry stays for the deleted id. It is harmless, but it is never pruned.
- **Cause-cycle walk**: identity set plus a depth cap of 16. Fixed and tested.
- **Startup line**: prints the effective transport attempts and deferral backoff. Fixed and tested.
- **S1/S4 tests** (`5b862bf`): present. Leaving S2 untested is reasonable.
- **Not addressed** (it was advisory): the javadoc sentence about the in-pass gauge refresh not covering a pass parked in the budget.

**Things checked and found correct:**
- **Circuit state machine.** The flag is written only under the lock. `reportEdgeBlock` opens once, and later blocks count but do not re-announce. There is exactly one probe timer, because `scheduleProbeLocked` cancels the previous one, and `probeInFlight` guards against overlap. The interval is constant. An unanswered or unbound probe re-arms. `probeQuietly` resets `probeInFlight` on an unexpected throw. `shutdown()` cancels the probe timer.
- **Observe mode.** `reportEdgeBlock` returns before touching the lock, so no circuit opens and the caller's outcome is unchanged. The WARN is throttled to one per environment per 5 minutes and carries the folded count.
- **Every request path while the circuit is open:**
  - Refused in-JVM: login, reauth, verifytoken (blocking and soft), deposit, register, update-fullname, the registration worker (through `registerOne` and `setDisplayName`), and WS upgrades whether or not upgrades are counted (`refuseIfCircuitOpen`).
  - Not sent at all: the anonymous WS probe (suppressed in the scheduler) and recovery (skipped).
  - The clearance probe is the only request that goes out, stamped via `count`.
  - The only exceptions are the `await` race and the WS-host/API-host mismatch above.
- **In-repo login.** It goes through the class's single `httpClient` with `.timeout(GATEWAY_REQUEST_TIMEOUT)`. There is no per-call client, so no exit path leaks a client or a platform thread. The `TokensProvider.of(agency, auth, jwt)` argument order matches the library. Every failure still reaches callers as a `RuntimeException`: `UpstreamLoginException`, or a `GatewayBudgetException` that is rethrown ahead of the `RuntimeException` arm.
- **Logging.** There is no token or password at any level: the login body goes through `withoutSecrets` and tokens through `masked`. The error-message `envelope()` carries status, code and message only, never the body. Deposit's non-200 WARN replaces the block page with its verdict, and the test asserts that no `<!DOCTYPE` reaches INFO+. The circuit ERROR is once per open, and the probe WARN is hourly. No new per-bot INFO lines.
- **ws-parser 3.0.5.** I checked the library source: `handshakeFuture().sync()` does rethrow Netty's `WebSocketClientHandshakeException`, so the WS entry point is reachable. The library's `HttpObjectAggregator(8192)` would turn a block page larger than 8 KB into a `TooLongHttpContentException` that is not classified. The captured page is 5 KB, so this is fine today.
- **Divergences the dev listed.** No half-open state, an unanswered probe keeps the circuit open, an observe-mode block is WARN only, and prod has no block alert until Phase 6. I accept all four. The circuit refuses everything while the probe is in flight, so a separate half-open value would add nothing. "We could not ask" is correctly not treated as "it cleared".
- **`EnvironmentWsProbe` EDGE_BLOCK.** It does not feed `reportEdgeBlock`, so an enforce box keeps probing a blocked WS host once per tick until a bot request opens the circuit. That is one stamp per tick, which is acceptable. It is worth a line in the plan saying so on purpose.

---

## Fix round — `82bdbc2..66c5d38` (13 commits)

Reviewed diff: `git diff 82bdbc2..66c5d38`. I ran the tests in a detached worktree at `66c5d38` and removed it afterwards. I ran 30 classes: the 24 from the first pass plus the new `GatewayWsClearanceProbeTest`, `BotGroupBehaviorServiceAsyncStartTest`, `ApplicationContextLoadsTest`, `PerBotInfoLogGuardTest` and the extended `CircuitRefusal`/`WaiterMdc`/`AlertRuleMetrics` tests. 252 tests ran, with 0 failures.

### Verdict

PASS

The bug is fixed at its site. Every advisory finding was addressed. There are no new `bug` or `security` findings. Two new smells and one note are below, and none of them blocks the merge.

### Each finding, checked where the fix was made

| Finding | Fix | Status |
|---|---|---|
| [bug] timeout/dequeue race | `SlidingWindowGatewayBudget.java:658-675`: when `dequeue` returns false, the waiter now waits on `admitted.get()` and maps the outcome through the shared `refusedOutcome` (`:702`) | **Fixed.** `aWaiterRefusedAtItsTimeoutInstantIsNotSent` holds the lock through the scope predicate inside the pass and lets the 300 ms wait expire behind it. Against the old `return true` it would fail on `sent`, so the test is not vacuous. |
| [smell] drift read throws | `ApiGatewayClient.readBalance`: for a deferrable read, `GatewayCircuitOpenException` returns `OptionalLong.empty()` | **Fixed**, and tested in `ApiGatewayClientBlockTest`. Cancellation still throws, which is intended. |
| [smell] detector false positive | `CloudflareBlockDetector.classify`: when a body exists, the Cloudflare page markers are required; `text/html` alone counts only when there is no body | **Fixed.** The new IIS-403 test case covers it. The WS-path limits (header-only, the 8 KB aggregator) are documented honestly. |
| [smell] WS circuit probed on the API host | `circuitOpenedBy` is kept; `runCircuitProbe` picks `wsCircuitProbe` for a `WS_UPGRADE` open and falls back to the API probe when no WS probe is bound; `GatewayBudgetRegistry.bindWebSocketProbe` (`:215`) is wired in `EnvironmentClientRegistry` right after `forEnvironment` | **Fixed.** See the smell on the timeout path below. |
| [smell] A27.1 under scoped DEBUG | `recordAdmitted`: the MDC is restored before `isDebugEnabled()` | **Fixed.** There is a new scoped-DEBUG case in `WaiterMdcTest`. |
| [smell] lazy `group_recovery_skipped_total` | `BotMetrics.initGroupRecoverySkipSeries` / `incGroupRecoverySkipped` under the group MDC; the first skip only materialises the series | **Fixed** to the letter, with the N-1 caveat (see question 8). |
| [smell] alert advises restart | `GatewayEdgeBlocked` now says "Do NOT restart while the block is live" | **Fixed.** |
| [smell] serialisation counted as a login failure | The request is built before the `try` and wrapped as `UpstreamLoginException` with no `incLogin` | **Fixed.** The caller still gets the same exception type. |
| [style] lost handshake evidence | `refusal.addSuppressed(e)` | **Fixed.** |
| Fix-round advisory: gauge staleness | Javadoc sentence added | **Fixed.** |
| Compliance S1 | `createBotsInParallel` collects the first `GatewayCircuitOpenException`; each refusal is logged at DEBUG; there is one group-level WARN; `lastFailureReason` names the edge block | **Fixed.** No per-bot ERROR, which matches the tier model. The persisted message is ours (environment id and cf-ray only), with no body or token. |

### The four questions you flagged

**(1) Can waiting on `admitted.get()` after the timeout block forever?** Not on any path that exists today.
- `dequeue` returns false only when another thread removed the waiter under the lock. There are four removers: the admission pass, the circuit-refusal pass, the cancelled-head branch of the pass, and `cancelScope`.
- Each remover adds the waiter's completion to its deferred list and runs that list in its own `finally`, right after `unlock()`: `admit`, `admitWaiters`, `stamp`, `reportEdgeBlock`, `cancelScope`, `release`, `runCircuitProbe`.
- The completion is the first statement of the admission action (`complete(null)`), and every action is isolated by its own `catch (RuntimeException)`. So the future is always completed promptly.
- **No deadlock:** the waiting thread holds no lock when it calls `get()`, and the remover never waits on the waiter.
- **No double admission:** removal is identity-based and happens under the lock, so a waiter can be removed only once.
- **No new strand under normal conditions.** The one theoretical hole is an `Error` (not a `RuntimeException`) thrown by an earlier deferred action, which would skip the later completions. Before this change, a bounded waiter that hit that hole still timed out. Now the lost-race waiter waits without limit. See the smell below.

**(6) WS-host clearance probe.**
- **One probe per interval:** yes. `runCircuitProbe` picks exactly one probe under the lock, with the same `probeInFlight` guard and the same single timer.
- **Stamped:** through `countWsUpgrade("circuit-probe")`, so it follows `count-ws-upgrades` exactly like the three bot upgrade sites and the environment WS probe. When the flag is false it is not stamped, which is the A5.3 decision and not a gap.
- **Anonymous:** no headers, no token, no AUTH frame. The socket is aborted as soon as the 101 arrives.
- **Fallback:** a `WS_UPGRADE` open with no bound WS probe uses the API probe rather than staying open for ever. That is the right choice.
- **HttpClient:** the shared lazy `probeClient` is reused. Nothing is created per probe, so there is no `HttpClient` or thread leak.
- **WebSocket objects can leak on the timeout path.** See the smell below.

**S2: does the `GatewayEdgeBlockObserved` PromQL work?** Yes.
- **Parse:** comparison operators bind tighter than `and`, so the expression is `(sum by (environmentId, product)(increase(...)) > 0) and on (environmentId) (gateway_circuit_open == 0)`.
- **Join:** `and` is a set filter, so there is no cardinality constraint. Matching `on (environmentId)` alone makes the `product` label irrelevant: it is on both sides (from the budget's `tags()`), but `and` keeps the left-hand side's labels either way.
- **Observe mode on the first block:** `gateway_edge_blocks_total{endpoint}` and `gateway_circuit_open` are both pre-registered at 0 in `registerMeters` when the budget is created. That happens in `forEnvironment`, which runs when the environment's clients are built, before any request. So the first block page moves the counter from 0 to 1, `increase()` is greater than 0, the gauge is 0, and the alert fires after its `for: 1m`. The 5-minute `increase` window keeps the condition true long enough for that.
- **Enforce mode:** the counter increments and the gauge goes to 1 in the same call, and a scrape sees both or neither, so the rule stays quiet and `GatewayEdgeBlocked` owns the page.
- **Residual gap:** a block seen only by `EnvironmentWsProbe` does not touch `gateway_edge_blocks_total`, so in observe mode it raises no alert. It still makes the environment probe-unhealthy, so recovery is held. This is acceptable.

**(8) "The first skip is not counted, so the series reads N-1": acceptable, or register earlier?** It is acceptable as shipped, and registering at candidate discovery would not help.
- In the usual case a group becomes a recovery candidate *because* the block killed it. Its first candidate tick is therefore already a skip, so materialising the series at discovery lands in the same tick, and the series still first appears at 1.
- The place that actually pre-registers it is earlier in the group's life, on a tick before any skip can happen. The natural site is `handleBotGroupDeath` (or `startLocked`), under the same group MDC, by calling `initGroupRecoverySkipSeries(OUTCOME_CIRCUIT_OPEN)` next to wherever `initGroupRecoverySeries` would be. Then every skip is counted, and the `skipSeriesMaterialised` set and its pruning can be deleted.
- Without a rule on this counter, N-1 is a diagnostics-only error. I would make this change before anyone writes an alert on it, but it does not block the merge.

### New findings

#### [smell] The WS clearance probe's timeout path can leave a socket open
`bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/GatewayBudgetRegistry.java:244-251`

`handshake.cancel(true)` completes *this* `CompletableFuture` with `CancellationException`. The JDK's `buildAsync` future is a dependent stage, so cancelling it does not abort the underlying connect and upgrade. The `whenComplete` added afterwards then only ever sees the cancellation (`ws == null`). If the upgrade completes after the 10 s `get`, which is possible because `connectTimeout(10 s)` covers only the TCP connect and not TLS plus the upgrade, the resulting `WebSocket` is never aborted. It stays open until the server drops an unauthenticated socket.

The impact is bounded: at most one socket per timed-out probe, at most hourly. The same leak happens if the probing thread is interrupted inside `get` (scheduler `shutdownNow`).

Fix: drop the `cancel(true)` and keep only the `whenComplete(... ws.abort())`. Attach the same `whenComplete` on the `InterruptedException` path too.

#### [smell] The lost-race `admitted.get()` waits without limit
`SlidingWindowGatewayBudget.java:669`

This is correct for every `RuntimeException`, as shown under question 1. But `runAfterUnlock` catches only `RuntimeException`. An `Error` from an earlier deferred action (for example an OOM in a Micrometer record) skips the later completions. A bounded PRIORITIZED or DEFAULT waiter that lost the race would then park for the life of the JVM, holding whatever its caller holds. That is the P13 shape, on a tier that was bounded before.

Fix: wait with a short cap, such as `admitted.get(5, SECONDS)`. On `TimeoutException`, treat the waiter as not admitted: count a timeout and throw `GatewayBudgetExhaustedException`. Not sending is the safe default, because the stamp at worst over-counts.

#### Note: an origin HTML 403 on the WS probe keeps a WS-opened circuit open
The WS path still accepts `text/html` on its own, because there is no body to check. If a brand's WS origin ever answered an *anonymous* upgrade with its own HTML 403, the WS clearance probe would read it as a block every hour, and a WS-opened circuit would close only on restart. Every WS host in the environment probe's history answers anonymous upgrades with 101 or a non-HTML refusal, so this is theoretical. It is worth one line in AD-13, next to the WS-path limit the detector javadoc already records.
