# Code Review — DEAD_GROUP_AUTO_RECOVERY

Branch: `feature/dead-group-auto-recovery`
Reviewed diff: `git diff 7d0ec86..feature/dead-group-auto-recovery` (9 commits, whole branch)

Build check: `mvn -o -DskipTests compile` is clean on JDK 21.

## Verdict

CHANGES_REQUESTED

## Findings

### [bug] Every `group_recovery_*` counter is emitted with an empty MDC, so AD-13's tags never land
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/DeadGroupRecoveryScheduler.java:265-296`
(with `bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:684, 1014, 625`)

`attempt()` sets the group MDC at :265, logs the attempt line (correctly tagged), then calls
`startForRecovery`. **Every** path through `startLocked` calls `BotMdc.clear()` on the caller
thread before it returns:

- the reclaim path — `teardownRuntimeMemory` sets group context and clears it in its `finally`
  (`BotGroupBehaviorService.java:1008-1014`). This is the *primary* recovery path (lingering
  DEAD runtime);
- the normal build path — `createBotsInParallel`'s result-collection loop sets group context
  and clears it in its `finally` (`:703-721`), and it runs on every start;
- the failure path — the outer `finally` at `:614-625` does the same when a runtime was
  registered.

`BotMdc.clear()` removes the keys outright; there is no save/restore. So by the time control
returns to :276-292 the MDC is empty and both `botMetrics.incGroupRecoveryAttempt(...)` (:280,
:307) and `incGroupRecoveryExhausted()` register through `mdcTags()` with **no**
`botGroupId` / `environmentId` / `product`. The `BotMdcTagsMeterFilter` safety net does not
apply either — it only touches `bot_`-prefixed names, as the new `BotMetrics` comment itself
notes.

Runtime consequences:
- `group_recovery_attempts_total{outcome="success"}` is one global series. Phase 4's planned
  `EnvironmentGroupRecoveryFlapping` (`increase(...[6h]) >= 3`) would then fire on the *sum*
  across the fleet — three different groups each self-healing once looks identical to one
  group flapping three times — and, with no `product` label, Alertmanager cannot route it to a
  product room (it falls to `viptalk.ops-room-id`).
- `group_recovery_exhausted_total` is likewise unattributable; only the ERROR message text
  carries the group id.
- The success / failure log lines lose their MDC fields in `console.log` JSON, so in Loki the
  arming line for a recovery is tagged and its outcome line is not.
- The counters `BotMetrics.incGroupRecoveryAttempt`'s javadoc promises ("Called under the
  recovery scheduler's per-group MDC, so the series carries botGroupId / environmentId /
  product exactly like `group_dead_seconds_total`") is false as written.

`DeadGroupRecoverySchedulerTest.successIsLoggedAndCounted` asserts exactly these tags and
passes, because the mocked `startForRecovery` never clears MDC — the test cannot see the
defect. Note that this is a mixed-shape failure, not a uniform one: a `startLocked` that throws
*before* `runningGroups.put` (e.g. `BadRequestException` on a null `environmentId`) never
reaches a `clear()`, so `outcome="error"` will sometimes be tagged and sometimes not, producing
two series shapes for one counter name.

Fix shape: re-assert the group context immediately after `startForRecovery` returns, in both
branches, before logging/counting — e.g. capture `product` once at :265 and call
`BotMdc.setGroupContext(id, group.getEnvironmentId(), product)` again at the top of the
`if (up)` / `else` block and inside `catch`. (Making `BotMdc` save/restore instead is the
deeper fix but it touches every existing caller and is out of scope here.)

### [bug] A timed-out probe leaks the connection: the `buildAsync` future is never cancelled
`bot-app/src/main/java/com/vingame/bot/infrastructure/probe/EnvironmentWsProbe.java:126-156`

`webSocket = builder.buildAsync(...).get(timeout.toMillis(), MILLISECONDS)` — on
`TimeoutException` (:139) the local `webSocket` is still `null`, so the `finally` at :150-155
aborts nothing, and the `CompletableFuture` is dropped without `cancel(...)`. `get(timeout)`
does not cancel the underlying task. If the handshake completes a moment later the JDK opens a
real WebSocket with a no-op listener that nobody ever aborts: the socket (and its
`HttpClient` connection-pool entry) stays open until the peer or an idle timer kills it.

This fires once per tick per URL against exactly the sort of upstream this feature exists for —
a slow/degraded origin — i.e. up to 60 leaked sockets per hour per environment, for as long as
the environment is sick. Given this repo's history (the 2026-06-30 staging outage was unbounded
reconnect threads exhausting the JVM), an unbounded-socket path in the component whose job is
to watch a broken origin is worth closing before the flag is turned on.

Fix shape: hold the future, and on the timeout path do
`future.cancel(true); future.whenComplete((ws, t) -> { if (ws != null) ws.abort(); });` —
cancel alone is not enough because the WebSocket may already be constructed.

### [bug] The Phase 2 `stop()` change makes a *failed* `/restart` opt a DEAD group out of recovery permanently
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:951-970`, reached from `:1118`

`restart(id)` is `stop(id)` → sleep → `start(id)`. For a runtime-less DEAD group — the ordinary
shape after an app restart, and the shape the feature's own exhaustion ERROR points the operator
at (`operator action required (POST /api/v1/bot-group/<id>/restart)`) — `stop(id)` now persists
`targetStatus=STOPPED` + `lastStoppedAt` where it previously persisted nothing. On the happy
path `start()` overwrites it with `ACTIVE`. On the unhappy path (`start` throws, which is
common for exactly the groups being restarted here: auth gateway still sick) the group is left
persisted **STOPPED** where it used to be left **DEAD**.

Under AD-5, `STOPPED` is the permanent opt-out. So the documented remedy for an exhausted
budget, when it fails, silently and invisibly disables auto-recovery for that group forever;
the group also disappears from `findByTargetStatus(DEAD)`, so it is no longer a probe candidate
and no longer counted by any dead-group signal that reads the persisted status. Nothing logs
"this group is now opted out". The same applies to any operator `/restart` on a runtime-less
DEAD group, not just the post-exhaustion one.

Fix shape: either have `restart` bypass the new persist branch (call the teardown directly, or
pass a flag so the runtime-less park is only taken from the operator `/stop` entry point), or
have the runtime-less branch skip the write when the prior status is `DEAD` and the caller is
`restart`. The behaviour `/stop` needs (park a runtime-less DEAD group) does not require
`restart`'s internal `stop` to do it.

### [bug] AD-10's "live sibling" can be the dying group's own bots, so the evidence gate self-authorises
`bot-app/src/main/java/com/vingame/bot/infrastructure/probe/EnvironmentProbeScheduler.java:284-293`
(with `BotGroupBehaviorService.countOpenWsByEnv`, `:1739-1752`)

The short-circuit is documented as "any **running** group on the same environment"
(AD-10: "any ACTIVE runtime"), but `countOpenWsByEnv` iterates **all** entries of
`runningGroups`, including the DEAD runtime of the very group being evaluated, and
`hasLiveSibling` only checks `count > 0` for the environment — it never asks whether the
contributing runtime is ACTIVE or is a *different* group.

`handleBotGroupDeath` (`BotGroupBehaviorService.java:2123-2135`) only marks the runtime DEAD; it
does not stop the bots. With `bot.group.dead.threshold=0.80`, a group is DEAD while up to 20% of
its bots are still connected, and those bots keep running until a reclaim. So for the common
in-JVM death — the group dies but a minority of bots survive — the environment is declared
healthy on the strength of the dead group's own sockets, no network probe is ever made, the
streak reaches `healthy-streak` in two ticks, and gate 3 ("positive evidence the origin is
serving again") is satisfied by the thing being recovered. `Bot.isConnected()` is also known to
stay true for server-side-pruned zombie bots (CLAUDE.md, "Server-Side Subscriber Pruning"), so
the "stronger evidence than a probe" claim in the class javadoc does not hold in that state
either.

The blast radius is one budgeted attempt, and in the reconstructed incident (all 40 bots dead)
the code would have probed correctly. But the whole point of the probe gate is that recovery
never acts without independent evidence, and as written the most common death shape bypasses it.

Fix shape: restrict the short-circuit to open sockets owned by runtimes that are ACTIVE **and**
whose group id is not itself a candidate — e.g. a new accessor that counts open WS per env over
`runtime.getActualStatus() == ACTIVE` only, or subtract the candidate group ids before testing.

### [smell] A success charges the budget, so a success on the last attempt can strand a group silently
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/DeadGroupRecoveryScheduler.java:276-283, 220-225, 340-354`

On success `state.attempts = attemptNumber` — deliberate, so a flap inside `settle-minutes`
resumes the same budget. But if attempt `max-attempts` is the one that succeeds and the group
re-dies inside the settle window, the retained state has `attempts >= maxAttempts` and
`exhaustedReported == false`. The next tick takes the `continue` at :220-225 and the group is
never attempted again, never emits the hand-off ERROR, and never increments
`group_recovery_exhausted_total`. The only trace is a DEBUG line that, per LOG_VOLUME_TIERING
Phase 4, never reaches Loki. The group sits DEAD with no operator-visible signal that recovery
gave up.

Related: `expireStates`' javadoc claims the state is dropped when "a manual `/start` or
`/restart`" resolved the group. That is only true when the action *succeeds* (or, per the
`restart` finding above, when it fails and leaves `STOPPED`): a failed manual `/start` leaves
`targetStatus=DEAD`, so the group stays a candidate with an exhausted budget and nothing ever
retries it. Worth either resetting the budget on an observed manual start attempt or correcting
the javadoc to say what the code actually keys on — "stopped being a candidate".

### [smell] The probe cannot send `Host`, and every environment is required to configure one
`bot-app/src/main/java/com/vingame/bot/infrastructure/probe/EnvironmentWsProbe.java:60-63, 188-207`

`EnvironmentService.validateAndMergeWsHeaders` (`:126-134`) *rejects* an environment that does
not supply `Host` and `Origin`, and merges `Connection` / `Upgrade` /
`Sec-WebSocket-Version` / `Sec-WebSocket-Extensions` defaults into every record. So the filter
is exercised on 100% of environments and, correctly, strips `Host` — the JDK client will not
let a caller set it. The consequence is not stated anywhere in the code: on any environment
whose configured `Host` differs from the URL authority (which is presumably why the field is
mandatory), **the probe reaches a different vhost than the bots do**. Combined with AD-2's
`status < 500 ⇒ healthy`, a default vhost answering 404 while the real origin is down reads as
"the environment is serving again" and authorises attempts.

Worth at least a comment at the filter and a note in the plan's Open Items; the mechanical
escape hatch if it ever matters is the `jdk.httpclient.allowRestrictedHeaders=host` net
property.

### [smell] The restricted-header set and its javadoc do not match the JDK
`bot-app/src/main/java/com/vingame/bot/infrastructure/probe/EnvironmentWsProbe.java:52-63, 209-226`

Three small inaccuracies in one place:

1. The javadoc says these are the names `WebSocket.Builder#header` "rejects with
   `IllegalArgumentException`". It does not — `BuilderImpl.header` only records the pair; the
   `Sec-WebSocket-*` names are rejected later in `OpeningHandshake`'s constructor and the other
   four in `HttpRequest.Builder.header` (`Utils.DISALLOWED_HEADERS_SET`), both during
   `buildAsync`. That matters for the reader because it explains why the throw is caught by the
   generic `RuntimeException` arm at :145 rather than at the `builder.header` call.
2. `Utils.DISALLOWED_HEADERS_SET` is `{connection, content-length, expect, host, upgrade}` —
   `expect` is missing from `RESTRICTED_HEADERS`. An environment configured with an `Expect`
   header would make every probe throw `IllegalArgumentException` → `Outcome.ERROR` → that
   environment can never be healthy and never recovers, with nothing but an `outcome="error"`
   series to explain it. Unlikely with today's data, one word to fix.
3. `restrictedNames(...)` is production-dead — the DEBUG line at :204-206 builds its own
   `skipped` list — yet its javadoc says it is "for tests and for the DEBUG line above". The
   only caller is `EnvironmentWsProbeClassificationTest`, which therefore asserts a *parallel
   copy* of the filter rather than the loop that runs. Either use it in `applyHeaders` or drop
   it and assert through `applyHeaders`.

### [smell] `classify` maps an unknown handshake response to the healthy side
`bot-app/src/main/java/com/vingame/bot/infrastructure/probe/EnvironmentWsProbe.java:162-168`

`handshake.getResponse() == null` yields `status = 0`, `0 < 500`, hence `HTTP_4XX`, hence
`healthy()` — and the detail string reads `HTTP 0`. Every other unclassifiable condition in this
class lands on `ERROR` (unhealthy); this one silently lands on the side that authorises a
recovery attempt. Given the class's own framing ("a failure to probe *is* the measurement"),
the null-response case should be `ERROR`.

### [smell] "First tick one full period in, so a boot that auto-starts groups is not probed mid-startup"
`bot-app/src/main/java/com/vingame/bot/infrastructure/probe/EnvironmentProbeScheduler.java:131-134`

A single 60 s offset does not achieve what the comment claims: `onStartup` auto-starts every
`ACTIVE` group serially and a multi-group fleet takes minutes, not seconds. The behaviour is
harmless (probing during startup costs nothing and the reconciler is separately gated), but the
comment asserts a guarantee the code does not provide, which is exactly the kind of line the
next reader will trust. Either state the real reason ("nothing useful to probe in the first
tick") or drop it.

### [style] `LocalDateTime.MAX` as an "unknown" sentinel
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/DeadGroupRecoveryScheduler.java:372-382`

`deadSince` returns `LocalDateTime.MAX` to mean "no stamp", and `deadSinceText` then
string-compares against the sentinel to print `"unknown"`. `Comparator.nullsLast` on the `Due`
sort plus a plain null would say the same thing once, without a magic value that also has to be
special-cased at render time. Minor, but this is a tie-break key that a future reader could
easily reuse elsewhere and be surprised by.

## Notes

Things that are right and worth keeping:

- **The lifecycle reuse is genuine.** `startForRecovery` is a thin wrapper: same
  `groupLocks.computeIfAbsent` lock as `start()`/`stop()`, the persisted group re-read *inside*
  the lock, the shared `RecoveryEligibility` re-asserted, then the existing private
  `startLocked`. No teardown, no executor, no scheduler and no persistence logic is duplicated.
  I could not find a second lifecycle path anywhere in the diff.
- **Resource lifecycle is clean.** Recovery routes through `teardownRuntimeMemory` →
  `stopAllBots(botMetrics)`, so the open group-dead window is credited exactly once
  (`creditGroupDeadSeconds` nulls the stamp before crediting), the health monitor, the
  periodic-logout scheduler, the group executor and the per-bot watchdogs are all shut, and the
  old runtime is removed from `runningGroups` before the rebuild. No double-teardown is
  reachable: `startLocked` reclaims at most once per call and the lock serialises against
  `stop()`. Both new schedulers shut their executors in `@PreDestroy`.
- **The operator/scheduler races are covered.** Condition 3 (`runtimeStatus == ACTIVE` wins over
  a persisted `DEAD`) is evaluated first and guards the documented `handleBotGroupDeath`
  race; the re-assert under the lock closes the decide-then-act window against `/stop`;
  `ActivationScheduler` only issues `STOP` for a *running* group so it never reaches the new
  runtime-less branch, and `ActivationEvaluator` still returns `NONE` for anything DEAD, so the
  two reconcilers cannot both act. `RecoveryState` is confined to the single reconciler thread.
- **Backoff arithmetic is correct.** `backoffAfter` uses `min(max(n-1,0), len-1)`, so
  `2,5,15,30,60,60` over six attempts spans 112 min before the hand-off, matching AD-8's "~2 h";
  the empty/null config falls back to `{5}` rather than an `ArrayIndexOutOfBounds`; jitter is
  applied to the first tick only and `nextInt(jitterSeconds + 1)` is inclusive as documented.
  `maxPerTick` is floored at 1 and `jitterSeconds` at 0.
- **Shipped-inert is genuinely inert.** `reconcileAll` returns before touching any collaborator,
  and the test asserts `verifyNoInteractions` rather than just "no start" — the right assertion.

Smaller observations that did not rise to findings:

- The failure detail string reports `actualStatus=` from `getActualStatus(id)`, which returns
  `STOPPED` whenever no runtime exists. After a `startLocked` that threw, the WARN will read
  `actualStatus=STOPPED` for a group whose persisted status is `DEAD`. Pre-existing accessor
  semantics, but the line is operator-facing.
- `EnvironmentWsProbe`'s `HttpClient` is never closed (Java 21 makes it `AutoCloseable`). It is a
  singleton for the JVM's life so nothing leaks in practice; mentioning it only because the rest
  of the diff is careful about `@PreDestroy`.
- Both schedulers call `RecoveryCandidateSelector.select` on their own 60 s tick, so there are
  two identical `findByTargetStatus(DEAD)` queries per minute. Negligible, and the shared
  selector is the right trade — the two sets must not drift.
- `docs/plans/DEAD_GROUP_AUTO_RECOVERY.md` is **untracked** in the working tree: the branch's own
  plan is not in the branch. Worth landing with the code so the AD references in the javadoc
  resolve for the next reader.
- Commits `26da6f3` and `f52739d` add and then remove an empty `Aviator.js`. Net-zero on the
  branch diff and the working-tree state was restored as found; noted only so a reader of
  individual commits is not confused.
- The AD-5 / AD-3 inconsistency Dev escalated (a `STOPPED` row with a lingering DEAD runtime
  passes condition 2's disjunction) is real and reachable — `PATCH {"targetStatus":"STOPPED"}`
  leaves the runtime untouched — but it is Architect-2's call and I am not duplicating the
  analysis here. Whichever way it is resolved, the `restart` finding above is independent of it.
