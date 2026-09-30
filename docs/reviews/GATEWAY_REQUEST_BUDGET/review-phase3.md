# Code Review — GATEWAY_REQUEST_BUDGET Phase 3 (enforcement behind `mode=enforce`)

Branch: `feature/gateway-request-budget`
Reviewed diff: `git diff b818e04..HEAD` (12 commits, `6a8d69e`..`4bad37a`)
Read first: `CLAUDE.md`, `docs/plans/GATEWAY_REQUEST_BUDGET.md` **including Amendments A1-A20**
(A20 is this phase's absorb list), `docs/reviews/GATEWAY_REQUEST_BUDGET/review.md` and
`review-phase2.md` (both sections).

Scope note: production code only. Plan compliance is Architect-2's and test coverage is QA's;
where the plan asked for something I judge the *code*, and I say so when the plan is the reason
a hazard exists. The ~58 dirty RIK/Aviator working-tree entries were left untouched; only this
file is staged.

Build check, in a **detached worktree at `4bad37a`** (not the dirty working tree, because that is
what `08c52a3` was about): `mvn -o -DskipTests test-compile` clean across all five modules. The
branch tip is self-contained — `9a4ef87`'s provenance accident is genuinely undone.

## Verdict

CHANGES_REQUESTED

Two `bug` findings. No `security` finding — I checked all four budget exception messages and
they carry an environment id, a tier, a scope description and a duration, never a host, a port or
an upstream body, so A20.8's requirement holds on `GET /{id}/status` and on the two new HTTP
arms.

The core is in better shape than the finding count suggests. The window arithmetic is right, the
clock-before-lock fix is correct and complete on the paths that matter, ceilings are evaluated
against a genuinely consistent in-lock snapshot, the deferred-completion design is the right way
to keep foreign continuations out of the budget lock, and I was able to *prove* that an ESSENTIAL
waiter cannot park indefinitely in the window (see Notes, item 1) — which is the one property
`essential.max-wait=0` stands on.

What is not in shape is **where the wait lands**. F1 is a 10-minute park on the library's
per-client message-processor thread, armed immediately after a 180-second watchdog on the same
bot; F2 is the one remaining synchronous path, registration, which still reports our own pacing as
N upstream failures at ERROR and answers 502. Ranked: F1 blocks, F2 blocks or needs a recorded
deferral, F3-F4 are liveness holes I would want closed before this ships under `enforce`, F5-F11
are real but narrower, F12-F16 are taste and documentation.

## Findings

### [bug] F1 — the deposit path parks a ws-parser message-processor thread for up to 10 minutes, on a bot whose watchdog expires at 180 s

`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:534-545` (`depositIsWarranted`'s
blocking PRIORITIZED read), `:463-465` and `:483-490` (`deposit`'s two PRIORITIZED calls), reached
from `BettingMiniGameBot.java:341-364` / `SlotMachineBot.java:175-198` (`onNewSession`).
Against `BettingMiniGameBot.java:647-648` and `bot.watchdog.timeout.seconds=180`.

`onNewSession` runs on the library's **per-client** message-processor worker
(`VingameWebSocketClient:186-191`, `netty-ws-message-processor-<name>-`, fed by a bounded
`ArrayBlockingQueue` with a message TTL). `onEndGame` calls `scheduleWatchdog()` and then
`onNewSession()` on the next line. Under `enforce`, `onNewSession` can now block on the budget
three times on that thread, each bounded by `prioritized.max-wait=10m`:

1. `depositIsWarranted` → `getBalance(…, PRIORITIZED, …)` — blocking by design (AD-10);
2. `deposit()` → `ApiGatewayClient.deposit(…, PRIORITIZED, …)`;
3. the confirming read inside `deposit()`, also PRIORITIZED.

And the trigger condition is not exotic — it is the normal state during the thing this feature
exists for. A group start reserves `botCount × 3` at ESSENTIAL, so for any group of ≳250 bots
`hasRoomLocked` gives DEFAULT an effective ceiling of 0 (`SlidingWindowGatewayBudget:626-633`):
every drift read is deferred, every auto-deposit bot on that environment therefore sets
`balanceReadDeferred`, and PRIORITIZED is starved for the same window. So the blocking read is
both reached *and* likely to run its full 10 minutes before throwing.

What happens while it is parked:

- the client's message queue backs up and messages are dropped on TTL, so the bot misses rounds it
  is nominally connected for;
- the watchdog fires at 180 s — a 33× margin — logs the WARN, moves
  `bot_watchdog_expired_total`, arms scoped DEBUG for the group, and calls `triggerFullReconnect`
  → `runAuthThenWsLoop` → a PRIORITIZED re-auth **and** a PRIORITIZED WS upgrade, i.e. more
  window consumption from a bot that is not actually broken;
- when the original thread finally returns it does so against a client the reconnect has since
  replaced.

That is the reconnect-storm shape A15.3 identifies as a plausible *cause* of an edge block,
manufactured by the pacing. AD-10 does specify a blocking PRIORITIZED refresh, so the shape is the
plan's — but nothing in the plan says the bound should be the tier's own 10 minutes when the
thread being parked is watched at 3. `ApiGatewayClient` already has the seam: `send(tier, scope,
request, maxWait)` (`:203-216`) is exactly the wait override registration uses.

Fix shape: give the pre-deposit refresh and both deposit calls an explicit wait that is
unambiguously below `bot.watchdog.timeout.seconds` (single-digit seconds is enough — the point of
the refresh is "is my figure stale?", and "we could not find out" is already a first-class answer
that returns `false`), or do the refresh off the message thread entirely. Whatever the number, the
invariant to write down next to it is `wait < watchdog`.

### [bug] F2 — a budget refusal during registration is counted as a registration failure, logged at ERROR once per user, and a complete refusal answers 502

`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:413-418`
(`catch (Exception e)` → `failureCount++`, `errors.add(...)`, `log.error(...)`),
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:169-177`
(`isCompleteFailure()` → `UpstreamRegistrationException` → 502 via `handleUpstream`).

Every other path in the phase got an AD-9 arm, and each one is well argued: `performReauth`'s
tri-state, `deposit`'s skip-the-round, `checkBalance`'s deferral, `periodic logout`'s DEBUG
(`ad2ae5b`), `classifyCreationFailure`'s `"budget"` tested first, and A4's two passthrough arms
with their deliberate absence of `incLogin(false)` / `incVerifyToken(false)`. Registration is the
one path left, and it is the *only* path where a budget outcome can still reach an HTTP response:

- `registerSingleUser` / `setDisplayName` now wait `registration.max-wait=15m` (`:518`, `:572`),
  so a starved 300-user registration produces up to 300 lines of
  `Failed to register budregt7: Gateway request budget exhausted for environment … (tier
  DEFAULT); retry in 42s` **at ERROR** — the page-worthy tier in CLAUDE.md's model — for the
  budget working exactly as designed. This is the precise mistake the rest of the phase was built
  to avoid, restated in the one place it was not fixed.
- a complete refusal answers **502** with our own budget messages concatenated into the body,
  i.e. "the gateway failed" about a gateway that was never asked. AD-11's whole argument for 429
  is that this is the wrong thing to say, and `b106a45`'s commit message makes it in so many
  words.
- `POST /api/v1/bot-group/` can now hold a Tomcat worker for 15 minutes per starved user.

**Judging the deferral, as asked.** Deferring the *429 conversion* is defensible: A7 ships Phases
2-5 as one deployment, and Phase 4 deletes `registerUsers` outright (A6 Phase 4 item 5), so on the
intended release path no artifact ever runs this code in `enforce`. That argument is sound as far
as it goes. Three things it does not cover:

1. The *classification* is not the same thing as the status code, and Phase 4 does **not** delete
   it — A2.6 requires the worker to treat a budget timeout as "not now" rather than as a failed
   account, so the distinction has to exist either way. Making it now is one `catch
   (GatewayBudgetException e)` arm, identical to the five that already exist.
2. It is recorded nowhere. Not a comment at either site, not a `FOLLOWUPS.md` entry (P13/P14 are
   Phase 2's; nothing was added), not a line in any of the twelve commit messages. A deferral
   whose only trace is a reviewer noticing it is indistinguishable from an oversight, and the next
   reader will find `classifyCreationFailure`'s careful `"budget"` arm and assume the rule is
   applied everywhere.
3. The branch as it stands cannot satisfy V3d ("expect either `200` within 15 minutes or `429`
   … never `502`"), which is in the single release sequence.

Fix shape: the one `catch (GatewayBudgetException)` arm in the per-user lambda — counted and
logged as a deferral rather than a failure — plus either AD-19's 429 conversion in
`BotGroupService.save` or an explicit, written deferral naming Phase 4 as the deleter.

### [smell] F3 — `runAfterUnlock` has no per-action isolation, so one throw strands every later waiter in the pass — permanently, for ESSENTIAL

`bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudget.java:612-616`,
with the deferred actions built at `:590-592` and `:602-605`.

Deferring completions out of the lock is the right design and the javadoc's reasoning
(`:567-574`) is correct. But a waiter admitted in a pass is **already dequeued and already
stamped** before `runAfterUnlock` runs, so its future is the only thing left that can release it.
A throw from an earlier action in the list — `recordAdmitted` does a Micrometer `Timer.record`, a
`Counter.increment` and, when DEBUG is on, a `log.debug` that itself calls `windowRequests()` —
skips every remaining `head.admitted.complete(null)`. Those waiters are in no queue and on no
timer: an ESSENTIAL waiter (unbounded *by design*) then parks for the life of the JVM, holding one
of `bot.creation.parallelism`'s permits and, transitively, the group lock. That is FOLLOWUPS P13
exactly, produced from inside the class whose javadoc says P13 "has one fewer way to happen"
(`:79-81`).

Low probability, hence a smell rather than a bug — but the cost of closing it is one `try/catch`
per action, and the failure mode is the single worst one in the feature.

While in there: `recordAdmitted` runs **before** `complete(null)` in the same lambda (`:602-605`),
so the Kth admitted waiter's wake-up is delayed by K metric records and, on staging where
`BOT_LOG_LEVEL=DEBUG`, K DEBUG lines each re-acquiring the budget lock through `windowRequests()`
(`:712-714`). Swapping the two lines decouples wake-up latency from bookkeeping for free.

### [smell] F4 — `admitWaitersLocked` can throw before it reschedules the wake-up, and the scheduler swallows it silently

`SlidingWindowGatewayBudget.java:575-610` (`scheduleWakeUpLocked(now)` is the pass's last
statement), `:676` (`scheduler.schedule(this::admitWaiters, …)`).

The whole wake-up chain is self-perpetuating: a pass re-arms the timer at its end. If anything in
the loop above throws, the timer is not re-armed, and on the scheduler thread the exception goes
into a one-shot `ScheduledFuture` nobody calls `get()` on — so it is not logged anywhere, not even
at DEBUG. With no other traffic on that budget there is no later event to re-arm it, and every
queued waiter then waits out its max-wait for room that exists.

The reachable way in is worth naming, because it is a contract that is not written down: the pass
calls **foreign code under the budget lock**. `head.scope.isCancelled()` (`:584`) reaches
`Bot.requestCancelled()` → `configuration.getStartCancelled()` → `startAttempts.isCancelled(id)`,
and the group reservation's predicate is `() -> startAttempts.isCancelled(id)`
(`BotGroupBehaviorService.java:1032`). Both are cheap and non-throwing today.
`GatewayRequestScope`'s javadoc says the predicate is asked "cheaply, possibly repeatedly,
possibly from another thread" — it does not say it is asked *while the budget lock is held*, which
is the constraint that matters: a future predicate that blocks, logs, or takes another lock is a
stall or a lock-ordering deadlock on every gateway request of that environment.

Fix shape: `scheduleWakeUpLocked` in a `finally`; wrap the scheduler's `admitWaiters` entry in
try/catch-and-log; and state the under-the-lock contract on `GatewayRequestScope.cancelled`.

### [smell] F5 — `Duration.ZERO` means two opposite things, and `tryExecute` disambiguates by tier

`SlidingWindowGatewayBudget.java:461` (`maxWait.isZero() && !settings.isUnboundedWait(tier)`),
`:495` (`unbounded = maxWait == null || maxWait.isZero()`), against `GatewayBudget.java:136-141`
("`Duration.ZERO` means *admit now or not at all*").

`ZERO` from the settings means "wait forever" (ESSENTIAL) and `ZERO` from a caller means "never
wait", and the code tells them apart by asking whether the *tier* is configured unbounded. So
`tryExecute(ESSENTIAL, scope, call, Duration.ZERO)` — a literal reading of the interface's own
javadoc — parks unboundedly, on the one tier where that is not recoverable. There is no such
caller today (`sendIfAdmitted` is DEFAULT only, `ApiGatewayClient.java:234`), which is why this is
a smell; it is a trap laid exactly where AD-10's "never park a message-processor thread" rule
lives.

The same ambiguity bites in reverse one field over. `GatewayBudgetSettings`' constructor
explicitly rejects `max-wait=0` for non-ESSENTIAL tiers *because* zero means unbounded
(`:135-139`, with a good message) — and then **accepts** `registrationMaxWait = 0` (`:92-95`
checks only null and negative), where the identical value means "never wait" and would make every
registration a 429 the moment `queues.get(DEFAULT)` is non-empty. Two fields, same type, same
literal, opposite meanings, one validated and one not.

Fix shape: an explicit sentinel for unbounded (`null`, or a named constant) so a caller's `ZERO`
always means now-or-never, and reject `registration.max-wait=0` with the same message its
siblings get.

### [smell] F6 — `BoundedLogin` releases the library's `HttpClient` only on the timeout path

`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/BoundedLogin.java:106-131`,
`:163-178`, against `AuthClient`'s `private final HttpClient httpClient = HttpClient.newHttpClient()`
(websocket-parser-core 3.0.5 sources, `AuthClient.java:25` — I extracted and read it).

The class is a good answer to A19/A20.1 and the abort mechanism is correct on the two questions
the brief asks:

- **it cannot abort an unrelated request.** One `HttpClientAbortableAuthClient`, and therefore one
  JDK `HttpClient`, is constructed per login (`:106`), so `shutdownNow()` can only reach the
  exchange it was called for. Verified by construction, not by intent.
- **on the timeout path it does release the selector thread**, which is what the leak-to-a-thread-
  nobody-can-see argument in the javadoc is about.

What it does not do is release the client on the **success** path or on the **library-failure**
path. Both return without touching `client`, so every login still leaves a JDK `HttpClient` — and
its `SelectorManager` platform thread — to be reclaimed whenever GC gets round to the unreachable
client. That is the plan's own Findings item ("a 3k-bot start briefly spawns ~3k platform
threads") and the shape MEMORY records as the Bot-1 thread-leak sawtooth. The subclass now exists
*precisely* to reach the client, so a `finally { client.abort(); }` once `result` has settled is
free and turns a GC-dependent release into a deterministic one. It is the difference between "this
class bounds the wait" and "this class owns the login's resources", and the name claims the
second.

Two smaller notes on the same class, neither worth its own finding:

- the worker thread is created with **no MDC**. log4j2's default `ThreadContext` map is not
  inheritable, so `AuthClient`'s two per-login INFO lines — including
  `User {}: Agency token: {} | auth token {}`, the token material CLAUDE.md AD-30 is about — now
  land in the detail track without `botGroupId`/`botId`, and stop being findable by the
  `grep "\[<GROUP_ID>/" logs/detail/detail.log` recipe CLAUDE.md documents. Our own lines are
  unaffected (they run on the caller).
- `shutdownNow()` unblocks a `send()` parked on a connection; it does not unblock a worker parked
  in address resolution. So "the worker fails and exits" is true of a connected exchange and
  best-effort otherwise. Harmless (it is a virtual thread) but the javadoc states it flatly.

### [smell] F7 — the 503's body promises a deadline A16.3 says must not be promised, and `b106a45` claims otherwise

`bot-app/src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:~150-175` (the
new `handleCircuitOpen` arm), rendering
`GatewayCircuitOpenException`'s message: `Gateway edge block on environment <id>; circuit open for
another 3600s`.

The header decision is right and is well argued both in the javadoc and in the commit message:
`Retry-After` carries the probe interval because an HTTP client needs a number. A16.3's other half
is that **the truth goes in the body** — "the block may require operator action and may outlive a
day" — and `b106a45`'s message states that it does ("the caveat lives in the body"). It does not.
The body says `circuit open for another 3600s`, which is the same promise the header was excused
for making, in the one place that had room to qualify it. This is the only claim in the commit
messages I found that the code does not support.

The exception is Phase 1 code and untouched here, so the fix is a one-line message change or an
override of `msg` in the handler arm — but the arm is what made it client-visible, so it belongs to
this phase.

### [smell] F8 — `GatewayCallSiteGuardTest` lost the repo-wide `new AuthClient(` rule

`bot-app/src/test/java/com/vingame/bot/infrastructure/gateway/GatewayCallSiteGuardTest.java:138-167`,
against the whole-tree scan next door at `:196-235`.

The rewrite is a genuine improvement in what it asserts *about the two files it reads* — zero
`new AuthClient(` in `ApiGatewayClient`, exactly one `BoundedLogin.login(`, and three structural
assertions that the one constructing site is the one that can give up. But AD-21's rule was that
the needle appears **nowhere in production code**, and that property is now unguarded:
`onlyKnownClassesBuildAnHttpClient` matches only `HttpClient.newHttpClient()` /
`HttpClient.newBuilder()`, and `AuthClient` builds its client *inside the library*. So a new
production class doing `new AuthClient(ctx, factory).authenticate()` is an unbudgeted, uncounted,
unbounded login against a gwms host that neither guard sees — which is the entire class of thing
this test exists for.

Fix shape: add `new AuthClient(` to the existing whole-tree scan's needle list with
`BoundedLogin.java` on the allow-list and the reason written down, exactly as the other five
entries are.

### [smell] F9 — `reserve()` reads the clock before the lock: the last sibling of the defect `660fcf1` fixed

`SlidingWindowGatewayBudget.java:826`.

```java
TrackedReservation reservation = new TrackedReservation(tier, declared, key, nanos.getAsLong());
...
lock.lock();
```

`createdNanos` is the base for the `2 × window` leak TTL, and it is captured before the lock under
exactly the contention the class javadoc now makes a headline of ("The clock is read inside the
lock, and that is load-bearing", `:87-92`). The direction is safe — a reservation is retired
sooner, never later, and the staleness is tiny against ten minutes — but this is the one remaining
out-of-lock read that the *accounting* uses, and the claim above it is unqualified.

I checked the other three and they are fine: `windowRequests()` (`:787`) under-prunes, so the
gauge reads conservatively high; `retryAfter()` (`:802`) over-reports, so the advice is never too
optimistic; `reportThrottleState()` (`:1070`) only drives a re-arm comparison. `admitWaiters`,
`stamp`, `cancelScope` and `release` all evaluate `nanos.getAsLong()` as an argument **inside** the
`try` after `lock.lock()` — I read each one, because that is precisely the kind of line that looks
identical and is not.

### [smell] F10 — the leaked-reservation WARN is emitted while holding the budget lock, which the class elsewhere forbids

`SlidingWindowGatewayBudget.java:888-892`, inside `expireStaleReservationsLocked`, called from
`admit()` (`:438`, the hottest path in the budget) and from every admission pass (`:578`).

`reportThrottleState`'s javadoc states the rule this breaks, verbatim: *"Called **outside** the
lock, always: … emitting it from inside the budget lock would put a log-appender queue wait on the
critical path of every admission."* Track 1's appender is `blocking = true` by deliberate choice
(CLAUDE.md, AD-24), so a saturated queue parks the emitting thread **with the budget lock held**,
stalling every gateway request on that environment until the appender drains. Once per leaked
reservation, so narrow — but it is the same hazard, and the class already has the mechanism to
avoid it (return the line as a deferred `Runnable`). The `log.debug` in `scheduleWakeUpLocked`'s
`RejectedExecutionException` arm (`:680`) is the same shape, smaller.

### [smell] F11 — a superseded reservation on the same key escapes the TTL that exists for exactly that case

`SlidingWindowGatewayBudget.java:830-837` and `:876-896`.

`reserve` does `reservations.put(key, reservation)`, and the comment is right that the older one
"still releases its own remainder". But `expireStaleReservationsLocked` iterates the **map**, so
an evicted reservation is unreachable by the TTL: if its `release()` never runs, its contribution
to `reserved{tier}` shrinks the lower tiers' effective ceilings for the life of the JVM, with no
backstop and no WARN. That is the exact failure the TTL was added to bound, in the one state the
TTL cannot see.

Unreachable today (`StartAttemptRegistry.begin`'s `putIfAbsent` gives one start per group) and the
comment says so honestly. But "not expressible" is a whole-program reachability argument about a
map key, and this codebase has a standing position on those (`RecoveryEligibility`'s javadoc,
updated in this very diff, argues the opposite way for a weaker case). Cheapest close: make the
collision loud — `if (reservations.put(key, reservation) != null) log.warn(...)` — so the state the
guard cannot cover cannot arrive silently.

### [smell] F12 — `admit()` can retire a leaked reservation and then not walk the queues, contradicting its own justification

`SlidingWindowGatewayBudget.java:432-438`.

The comment for skipping an admission pass on the arrival path is good and mostly correct: *"Every
event that can CREATE room already runs one — a stamp expiry, a reservation release, a
cancelScope, a probe's stamp."* `expireStaleReservationsLocked(now)` is called two lines below it
and creates room for the tiers below by dropping `reserved{tier}`, and this path runs no pass. So
a queued DEFAULT or PRIORITIZED head can time out against ceiling room that was freed moments
earlier, if nothing else touches the budget before its max-wait elapses.

Bounded and not a liveness hole — ESSENTIAL is structurally immune (see Notes, item 1) and the
lower tiers are bounded by max-wait by construction — but the invariant as stated is not quite
true, and it is the sentence a future reader will use to justify skipping a pass somewhere else.

### [style] F13 — `GatewayBudget.run(tier, scope, Runnable)` has no production caller

Grepped all five modules' `src/main`: the live budget surface is `execute` (both overloads),
`tryExecute`, `runWsUpgrade`, `count`/`countWsUpgrade`, `reserve`, `cancelScope`, `snapshot` and
`registrationMaxWait`. A5.1 asked that enforcement reach `run` as well as `runWsUpgrade`, and it
does (`:317-321`) — but nothing calls `run`, in this phase or any earlier one. Either delete it or
say in the javadoc that it is the non-WS twin kept for symmetry, so the next reader does not go
looking for the caller.

### [style] F14 — `count()` charges every probe stamp to `tier="ESSENTIAL"`, unexplained

`SlidingWindowGatewayBudget.java:724-729`.

`outcome="counted"` is the right fifth value and the reconciliation argument for it (class
javadoc, `:104-108`) is correct — without it the counter and the gauge provably could not agree.
The *tier* label is an arbitrary choice with no note anywhere: `gateway_budget_requests_total{tier=
"ESSENTIAL",outcome="counted"}` folds the anonymous environment probe (and, from Phase 5, the
circuit clearance probe) into the tier an operator reads as "bots trying to come up". One sentence
on `count`'s implementation saying which tier it charges and why would stop someone summing
`{tier="ESSENTIAL"}` and getting probes.

### [style] F15 — `runAuthThenWsLoop`'s RETRYABLE comment says the opposite of what the code does

`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:932-940`:

> The budget declined the immediate re-auth (AD-9). Do **NOT** try the WS upgrade on the strength
> of tokens we did not refresh — fall straight into the backoff loop

`runWsReconnectLoop(1)` sleeps one backoff and then calls `tryReconnectWs()`, which performs
exactly that WS upgrade with exactly those unrefreshed tokens (`:884-890`). The *behaviour* is
fine and matches what the success-path fall-through does, and `tryReconnectWs` catching `Exception`
means a budget refusal there is one charged attempt rather than a death — I checked that too. But
this is the comment on the tri-state whose entire justification is that the three outcomes are not
interchangeable, and it describes an avoidance that does not happen.

### [style] F16 — `GatewayBudgetNearCap` now depends on two series where it depended on one

`prometheus/alerts.yml`, `GatewayBudgetNearCap`:
`gateway_budget_window_requests / gateway_budget_hard_cap > 0.89`.

Expressing the threshold against the exported cap is the right call and F5's complaint from Phase
1 is properly answered — lowering the cap now tightens the alert instead of silently disarming it.
The element-wise join is correct (same target, same `{environmentId, product}`, both registered in
`registerMeters`). The new failure mode is that a missing, renamed or not-yet-scraped denominator
makes the expression return **no samples**, and a rule with no samples never fires — the same
"healthiest reading there is" trap CLAUDE.md spells out for `LogQueueSaturated`'s vanished
appender. Worth one line on the rule, or an `absent(gateway_budget_hard_cap)` companion.

## Notes

### 1. Liveness: ESSENTIAL cannot park indefinitely in the window, and here is why

This is the property `essential.max-wait=0` stands on, it is not obvious from any one method, and
it is written down nowhere — so it is worth recording, because a future change to the ceiling
validation or to `scheduleWakeUpLocked` would break it silently.

An ESSENTIAL waiter is only ever enqueued when `hasRoomLocked(ESSENTIAL)` is false
(`:457`), and since `reservedAboveLocked(ESSENTIAL) == 0` (nothing is above it) that means
`stamps.size() >= min(hardCap, ceiling(ESSENTIAL))`. Both are validated positive, so **stamps are
non-empty whenever an ESSENTIAL waiter exists**, and `scheduleWakeUpLocked` (`:654-683`) therefore
always arms a timer for it — its two early returns are "nobody waiting" and "no stamps", and
neither can hold. When that wake-up fires, `prune` drops at least the stamp it waited on, so room
exists, and the pass admits the head. If every stamp expires, ESSENTIAL has room by definition and
the pass drains the queue completely. The chain re-arms itself at the end of every pass.

Corollaries worth knowing:

- **The lower tiers do not have this guarantee**, and correctly do not need it: a DEFAULT or
  PRIORITIZED waiter can be enqueued with an *empty* window, purely because an ESSENTIAL
  reservation zeroes its effective ceiling, in which case no timer is armed at all. They are
  bounded by max-wait instead, which is exactly why `GatewayBudgetSettings` refuses to let them be
  unbounded (`:135-139`). That validation is load-bearing for liveness, not just for tidiness.
- **Strict priority across tiers survives the arrival fast path only because the ceilings are
  validated monotonic.** `admit` admits immediately on `queues.get(tier).isEmpty() &&
  hasRoomLocked(tier)` with no cross-tier check, so a DEFAULT arrival could in principle overtake
  a queued ESSENTIAL waiter. It cannot, because a queued ESSENTIAL waiter implies
  `W >= ceiling(ESSENTIAL) >= ceiling(PRIORITIZED) >= ceiling(DEFAULT)`, so every lower tier is
  also out of room. The monotonic check in the constructor is therefore what makes AD-6 true on
  the fast path, and its javadoc only claims it for AD-5's ceiling semantics. Worth a sentence at
  `:457`.
- F3 and F4 are the two ways the above can be defeated, which is why I would want them closed
  before a box runs `enforce`.

### 2. The queue itself

- **Consistent snapshot, verified.** `hasRoomLocked` reads `stamps.size()` and the `reserved`
  atomics; every mutation of `reserved` (`reserve`, `consumeReservationLocked`,
  `TrackedReservation.release`, `expireStaleReservationsLocked`) happens under the lock, and so
  does every mutation of `queued` and `stamps`. The atomics are atomics only because the scrape
  thread reads them through gauges, which the comment at `:114-115` says. No torn read is
  possible.
- **No unbounded queue growth.** Waiters are bounded by live callers; `stamps` is bounded by
  `hardCap` under `enforce` (and deliberately unbounded under `observe`, unchanged from Phase 1
  and already weighed there).
- **Per-request allocation on the hot path** is one boxed `Long` per stamp, one `GatewayRequestScope`
  record per call, and a `Waiter` + `CompletableFuture` only when queueing. `checkBalance`'s drift
  path additionally allocates an `Optional` and an `OptionalLong`. All of it is dwarfed by the
  pre-existing unconditional `Thread.sleep(500)` in `readBalance`. Not worth changing.
- **Unfairness that becomes starvation:** FIFO-within-tier is enforced by the `queue.isEmpty()`
  half of the fast path, which is the right guard and is commented as such (`:455-456`). Across
  tiers, DEFAULT starvation during a large start is the designed behaviour (Open Item 5, closed by
  the user) and is bounded by max-wait plus the reservation draining as the build proceeds. I did
  not re-litigate the keying or the ceiling.

### 3. The window under load, and the clock fix

`660fcf1`'s fix is correct and, apart from F9, complete: the stamp now comes from a clock read
inside the lock on all four paths (`admit`, `stampAdmitted`, `stamp`, `admitWaitersLocked`).
The class javadoc's new paragraph on admission-vs-arrival is the honest framing of the residue and
I agree with both halves of it — the budget's own invariant is exact, and an external observer can
read `cap + in-flight`, which is what the 100-request margin is for. The IT asserting
`HARD_CAP + threads` rather than hiding it in a fudge factor is the right shape.

The `84 arrivals in a 60-request window` number checks out as `60 + 24 threads`, which is what
makes the diagnosis credible rather than just plausible.

### 4. Cancellation and stop latency

The ordering is right on both paths and both are commented with the reason:
`stop()` → `cancelStartInFlight(id)` → lock (`:1641`), and `stopAndLogout(id)` →
`cancelStartInFlight(id)` → lock (`:1776-1802`). `cancelScope` wakes rather than marks, matched on
`GatewayRequestScope.botGroupId`, which is the key `Bot.scope()` supplies
(`Bot.java:1128-1133`) and the key the reservation uses (`BotGroupBehaviorService.java:1032`) — I
checked all three agree.

**The arithmetic after a cancel is right, and the two places it could have gone wrong are both
handled.** A waiter cancelled by `cancelScope` or by the pass is dequeued *there*, with
`queued.decrementAndGet()` and the `cancelled` counter, and nothing is stamped; when its own
`await` then sees the `ExecutionException` it calls `dequeue(waiter)` again, which returns `false`
because the waiter is already gone, so there is no double decrement (`:1039-1050`). The timeout
path's `if (!dequeue(waiter)) return true;` is the correct resolution of the admitted-in-the-
instant-before-the-timeout race, and the comment explaining why reporting a timeout there would
desynchronise the counter from the window is exactly right. Cancelled bots returning `null` and
being counted neither up nor failed is Phase 2 behaviour and is unchanged; A20.2 already scopes
A4 to *paced* requests only.

One residual worth knowing rather than fixing: `cancelScope` reaches a waiter parked **inside** the
budget, and `BoundedLogin` bounds a login already admitted at 10 s — so V2c's "stop 200 within 5 s"
now depends on `bot.creation.parallelism` in-flight HTTP calls each bounded at 10 s, i.e. it is a
~10 s bound rather than a 5 s one, on paper. It was already that before this phase.

### 5. Design changes against the plan — judged on merits

- **`performReauth` becoming a tri-state: correct, and the boolean genuinely would have wedged a
  bot.** I traced it independently. `runAuthThenWsLoop` treats `false` as "stop the loop", so a
  budget refusal collapsed into `false` would leave the bot in whatever status `performReauth` set
  and **no loop running at all** — permanently RECONNECTING with nothing retrying, which is worse
  than DEAD because nothing selects it for recovery. Collapsing into `true` would proceed to a WS
  upgrade with unrefreshed tokens. The three-value enum is the only shape that works, the names
  are good, and entering `runWsReconnectLoop(1)` keeps `MAX_RECONNECT_CYCLES` honest. See F15 for
  the comment.
- **`getBalanceIfAdmitted` returning `OptionalLong` rather than switching tiers: better than the
  plan.** The tier is a fact the caller knows and the DEFAULT/never-wait pair is one decision, so
  binding them into one method removes the possibility of a caller asking for a deferrable read at
  ESSENTIAL — which, per F5, is the one combination that would park. `getBalance` keeping
  `orElseThrow` on the non-deferrable path is honest rather than defensive: it asserts an invariant
  of `readBalance` instead of inventing a value.
- **`GatewayCallSiteGuardTest`'s rule rewritten around `BoundedLogin`:** the direction is right
  (the guard now pins the *bound*, not just the count) but it dropped a property it used to have —
  see F8.
- **`block-cooldown` → `block-probe-interval` pulled forward:** correct to pull forward, since the
  refusal path needs a number for `Retry-After`, and the rename is total (property, `@Value`,
  record component, javadoc, `application.properties`). The 60 m default and the reasoning are
  A16.1's. `blockProbeInterval == 0` is rejected with a good reason, which is the right
  asymmetry against the tier max-waits — and the contrast with `registrationMaxWait` accepting 0
  is F5's second half.
- **The budget stays keyed per `Environment`, with a WARN on a shared gateway host:** not
  re-litigated, as instructed. I did check the seam is one line and it is:
  `GatewayBudgetRegistry.budgetKey(environmentId, apiGatewayUrl)` (`:117-123`) returns
  `environmentId` and ignores the URL, with the javadoc naming the two read-only consumers that
  would have to move with it. `recordGatewayHost` is once-per-host, sorted at render time, host-only
  (correct — the Cloudflare zone follows the hostname), and an unparseable URL is a DEBUG no-op
  rather than a startup failure. The three-arg overload used by `startLocked` and the probe
  scheduler passes `null` and skips the check, which is right because the client registry has
  already recorded the real URL by then — `recordGatewayHost` runs *before* `computeIfAbsent`, so
  the WARN cannot be lost to whichever caller creates the budget first.

### 6. Error surface

- **No existing HTTP path changed shape.** `GatewayBudgetException` is not an
  `UpstreamGatewayException`, so `handleUpstream`'s 502 is untouched; the three new arms are the
  only behaviour change, and before them these types fell through to `handleAny`'s sanitised 500.
  The bodyless 404, the two verbatim 400s, the `IllegalStateException` 500 and `msgForStatus`'s
  4xx/5xx split are all unchanged.
- **`Retry-After` floored at 1 s and omitted when unknown** is the right pair, and the reason
  given (a client obeying `Retry-After: 0` is a tight loop against the condition that produced it)
  is correct.
- **The terminal `GatewayBudgetException` arm is a good call** — turning a documented "never
  happens" into a 429 that names the cancellation rather than a 500 with a class name is exactly
  the right disposition for a "never".
- **`ClientSafeMessage`'s RR4 correction is right and complete**, and the restated rule ("our own
  hierarchy, whose messages we wrote") is the one that is actually true. The two enumerated
  exceptions are both accurate.
- Nothing client-visible names a host, a port or an upstream body. F7 is a truthfulness problem,
  not a leak.

### 7. Observability

- **`GatewayBudgetSustainedQueue` is the right rule**, and the three choices in its comment are
  each defensible: ESSENTIAL-only (DEFAULT queueing is the design), `min_over_time > 0` (so a
  large start passing through does not fire), and `for: 15m` on top of a 15 m lookback (the
  summary's "30 minutes" is therefore honest). It is also the only Prometheus-visible trace of
  FOLLOWUPS P13, which the entry now records.
- **Counters are pre-registered, and more strongly than CLAUDE.md requires**: `registerMeters`
  materialises all 15 `{tier, outcome}` counters at construction, and `counter()` *throws* on an
  unknown outcome rather than lazily creating one — so an unbounded label value is a crash, not a
  new series. `gateway_budget_ceiling` and `gateway_budget_hard_cap` are the right answer to Phase
  1's F5 and the dashboard now plots them instead of literals.
- **Cardinality** is `{environmentId, product}` × 3 tiers × (5 outcomes + queue + reserved +
  ceiling + wait histogram) — tens of series per environment, bounded, explicit tags, not
  `bot_`-prefixed. Unchanged conventions.
- **Tier model holds.** Nothing per-request or per-bot is at INFO even though queueing is now
  routine: admissions, deferrals, cancels and the probe stamp are DEBUG; the new INFO lines are
  the throttle-cleared line (edge-triggered, 5-minute re-arm, paired with its WARN — I traced the
  `warned`/`lastWarn` state machine and the pairing holds in both directions), the one-per-group
  start line, and the one-per-JVM posture line. `ad2ae5b` correctly demoted the periodic-logout
  refusal from ERROR to DEBUG. F2 is the one place a per-user ERROR survives.

### 8. Phase-boundary discipline: clean

- **No registration worker (Phase 4).** `registrationState`, `RegistrationWorker` and
  `CloudflareBlockDetector` appear in this tree only inside javadoc sentences describing future
  phases — I grepped all five modules' `src/main`. The `registration.max-wait` override is AD-19,
  which A6 puts in Phase 3.
- **No block detector and no circuit *trigger* (Phase 5).** `circuitOpen` has no `set` or
  `compareAndSet` call anywhere in `src/main`; it is read-only. The *consequences* of it being open
  — every tier refused, nothing parked, `outcome="circuit_open"` counted, the 503 arm, the
  `Retry-After` — are here, which is A16.2's explicit instruction and the right call: a phase that
  added the trigger without the refusal policy would have had to design it twice.
- **No compiled-default flip (Phase 6).** `application.properties` is still `observe`,
  `GatewayBudgetConfig`'s `@Value` fallback is still `observe`, `GatewayBudgetSettings.defaults()`
  still returns `OBSERVE`. The three-file warning is now written into the properties file next to
  the numbers it applies to, which is the right place for it.
- **`StubGateway` (A20.11) landed test-scope, without the block-mode half**, binds
  `InetAddress.getLoopbackAddress()` on an ephemeral port, serves on a virtual thread-per-task
  executor (so the IT's concurrency is real, not serialised by `HttpServer`'s default null
  executor), and contains no hostname. As specified.

### 9. Self-deceiving tests, and the two provenance facts

`4bad37a`'s diagnosis is right — recording the order from *inside* the admitted call records which
continuation the scheduler resumed, not which waiter the budget admitted — and the rewrite fixes it
the right way: one slot freed per step with a barrier after each, so the recorded order is the
admission order by construction, plus a `hasSize(expected)` at every step that would also catch
waking more than one waiter per freed slot (the failure that would actually breach the cap).

I looked for siblings of that class and did not find another. The remaining order-sensitive
assertions in the new suites (`oneFreedSlotWakesOneWaiter`, the cancellation tests' "other tiers'
order is preserved") assert *counts and membership at barriers* rather than continuation order,
which is the construction that cannot lie. All five new fake-clock suites carry a class-level
`@Timeout(30s)`, so a regression that made an admission park fails rather than hangs — the gap
Phase 1's F1 flagged is closed.

Two test-shape notes, both in the IT and both QA's call rather than findings here: the
`stub-sampler` is a busy `Thread.onSpinWait()` loop that calls `budget.windowRequests()` — and
therefore takes the budget lock — as fast as it can for up to 150 seconds, which both perturbs the
thing it is measuring and pins a carrier thread for the duration; and
`theCapHoldsAgainstTheReceiversOwnCount`'s `HARD_CAP + threads` bound is a soft one that would
absorb a real regression of up to 24 requests. Both are explained in comments, and the IT is
excluded from the default run, so neither is worth changing on its own.

**Provenance, as flagged.** `9a4ef87` staged `BettingMiniGameBot.java` whole and carried 134 lines
of the user's uncommitted RIK_114 work; `08c52a3` reverts them forward, leaving the RIK hunks in
the working tree. I verified the outcome rather than the claim: a detached worktree at `4bad37a`
test-compiles clean across all five modules, which is the property that matters — before the
revert the branch only built against someone else's uncommitted `GameRequestFactory`, and that is
the hazard that once put an unvalidated build on prod. `9a4ef87`'s commit message still describes
more than its diff contains; the revert says so, which is the right handling.

### 10. Claims I checked that do hold

- A4's passthrough arms are in the right order (`GatewayBudgetException` **ahead of** the
  `RuntimeException` rewrap in `authenticate`, and ahead of the `RuntimeException` arm in
  `readBalance`), and neither moves `bot_login_total` or `bot_verify_token_total`. This is the arm
  the whole of AD-9 rests on and it is correct.
- `BoundedLogin`'s `HttpTimeoutException` really does land in the pre-existing `IOException` arm
  and really is a login failure worth `incLogin(false)` — the request left the JVM. The comment
  correcting "unreachable today" to "reachable since A19" is accurate.
- A5.5's two tripwires were rewritten rather than deleted, in both directions:
  `SlidingWindowGatewayBudgetWindowTest.aCancelledScopeIsOnlyStampedWhenItIsReallySent` and
  `ObserveModeTest.aCancelledScopeIsRefusedAndNotStampedUnderEnforce`, and the enforce-mode WARN's
  *absence* is now asserted (`GatewayBudgetRegistryTest.enforceModeSaysNothingBeyondThePosture`).
  A20.7's "assert a cancelled scope whose bot is healthy" half is covered.
- A20.4 is honoured in the shape the amendment asked for: the reservation is taken **after**
  `runningGroups.put`, with the reason (a `/stop` in that window could not release it) written at
  the call site.
- `reservation.release()` is first in `startLocked`'s `finally` and every early return inside the
  `try` passes through it; the one return that does not (`reassertStartupIntent`) is before the
  `try` and before the reservation exists. Idempotent release is real, not asserted.
- RR1 and RR2 from the Phase 2 re-review are properly closed: intent is re-asserted on the locked
  read inside `startLocked`, and `getHealth` now goes through `getActualStatus(id)` in **both**
  branches.

### 11. One consequence worth stating for the release notes, not a finding

A group start of ≳250 bots reserves ≥750 ESSENTIAL permits, which drives PRIORITIZED's and
DEFAULT's *effective* ceilings to zero for the duration of the build — so for that window, on that
environment, no other group can re-authenticate, reconnect or deposit, and no registration
proceeds. That is Open Item 5 answered as the user answered it (`essential.ceiling = hard-cap`) and
it is not a defect. It is, however, the mechanism behind F1, and it is the thing an operator will
report as "the whole environment went quiet while I started one group". The `start admitted —
declared demand N requests, env window W/900, estimated M min` line is what makes that
diagnosable, and it is good that it now carries all four numbers.
