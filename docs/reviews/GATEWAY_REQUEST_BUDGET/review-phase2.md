# Code Review — GATEWAY_REQUEST_BUDGET Phase 2 (async start/restart, daisy-chain, progress)

Branch: `feature/gateway-request-budget`
Reviewed diff: `git diff d947c09..HEAD` (6 commits, `b2a6799`..`750fc91`)
Read first: `CLAUDE.md`, `docs/plans/GATEWAY_REQUEST_BUDGET.md` **including Amendments A1-A9**
(working tree, uncommitted), `docs/reviews/GATEWAY_REQUEST_BUDGET/{qa,review,compliance}.md`.

Scope note: production code only. Test quality is QA's, plan compliance is Architect-2's, and
nothing the plan or its amendments decided is reported here as a defect unless the
implementation diverges from it. The ~57 dirty RIK/Aviator entries in the working tree were
left untouched; only this file is staged.

## Verdict

CHANGES_REQUESTED

Five `bug` findings and one `security` finding. The state machine itself is in good shape —
the `cancel → cancelScope → lock` ordering is right, the `750fc91` race fix is correct, and
Dev's contradiction of AD-16 is correct (verified independently below). What is not in shape
is the **perimeter around the widened enum** (R1) and the **three lifecycle paths that were
previously unreachable because the app was unreachable** (R4, R5, R6).

Ranked: R1 and R2 are the two that should block; R3-R6 are real but narrower; R7-R13 are
judgement and hygiene.

## Findings

### [bug] R1 — `targetStatus` is client-writable, so `STARTING` **can** be persisted; A1's rollback invariant is not actually enforced

`bot-app/src/main/java/com/vingame/bot/domain/botgroup/mapper/BotGroupMapper.java:181` (PATCH)
and `:92` (`toEntity`, POST), reaching
`BotGroupService.save` → `repository.save`; DTO field at
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/dto/BotGroupDTO.java:142`.

`BotGroupDTO.targetStatus` is a plain, unvalidated, client-supplied `BotGroupStatus`, and both
mapper directions write it straight through:

```java
entity.setTargetStatus(Optional.ofNullable(dto.getTargetStatus()).orElse(entity.getTargetStatus()));
```

`BotGroupConfigValidationService` does not look at the field, and `save()` does not normalise
it. **Before this diff the enum's three constants were the whole legal alphabet, so Jackson
rejected anything else with a 400.** Adding `STARTING`, `REGISTRATION_PENDING` and
`REGISTRATION_FAILED` turns `PATCH /api/v1/bot-group/{id} {"targetStatus":"STARTING"}` (and the
same key on `POST /`) into a 200 that persists exactly the value A1 spends a page explaining
must never reach Mongo. One curl call, no auth, on a running instance.

Runtime consequences, in the order an operator would meet them:

- **Current jar.** The group drops out of `findByTargetStatus(ACTIVE)`, so it never auto-starts
  again; `RecoveryEligibility.isCandidate` branches on `ACTIVE`/`STOPPED`/`DEAD` with an
  else-ineligible default, so auto-recovery will never touch it either. The group becomes
  silently unmanaged — the exact failure shape DEAD_GROUP_AUTO_RECOVERY exists to remove.
- **Older jar (the rollback case).** Any read path that *converts* the document throws
  `ConversionFailedException`: `GET /{id}` and, worse, `POST /{envId}/filter`, which is the UI's
  list view for the whole environment. One poisoned group 500s the list for every group beside
  it.

`BotGroupStatusPersistenceGuardTest` does not cover this and says so in its own javadoc ("the
scan therefore pins the literal form … a reviewer has to keep the variable ones honest"). The
mapper's argument is a variable, so the guard is green while the hole is open — which is worse
than no guard, because the guard is what the next reader will trust.

Two sub-points worth fixing at the same time:

1. The guard's stated rationale is **wrong on the mechanism**: `findByTargetStatus(ACTIVE)`
   filters server-side on the string `"ACTIVE"`, so a document holding `"STARTING"` is never
   returned and never converted — the boot query does *not* fail. The blast radius is the read
   paths above. Keep the guard; correct the "fails the whole startup query" claim in
   `BotGroupStatusPersistenceGuardTest` and in `BotGroupStatus`'s javadoc, or the next person
   will weigh the risk against the wrong model. (The plan makes the same claim in A1; that is
   Architect-2's to reconcile.)
2. `BotGroupBehaviorService.java:1655` (`group.setTargetStatus(statusBeforeRestart)`) is the
   other variable call site. It is safe *today* only because it reads the value back from
   Mongo — i.e. it launders whatever R1 let in.

Fix shape: treat `targetStatus` as system-managed on the DTO boundary, exactly as
`BotGroupMapper` already treats `lastStartedAt` / `lastStoppedAt` / `lastFailureReason` (see its
own trailing comment) — drop it from `toEntity` and `updateEntityFromDTO`'s write path. If it
must stay writable, reject anything outside `{ACTIVE, STOPPED, DEAD}` in
`BotGroupConfigValidationService` with a 400 naming the legal set, and extend the guard test to
assert that the mapper cannot pass an appended constant (a value-level test, not a source scan).

### [security] R2 — `lastError` publishes a raw `Throwable.toString()` on an unauthenticated endpoint, contradicting this codebase's explicit error-exposure policy

`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/StartAttemptRegistry.java:151`
→ `BotGroupBehaviorService.getLastStartError` →
`BotGroupController.statusDTO` → `BotGroupStatusDTO.lastError`.

```java
attempt.error = error == null ? null : truncate(error.toString());
```

Any `Throwable` from the build — including the ones no arm of `RestExceptionHandler` will
forward — now lands in a client-visible field on `GET /{id}/status`, on the `/start` ack and on
the `/restart` ack. That is a deliberate reversal of a documented policy sitting in the same
repo (`bot-app/.../RestExceptionHandler.java:190-205`):

> The body's `msg` is a fixed string — `e.getMessage()` for an unhandled exception can carry
> Mongo hostnames, Spring bean wiring failures, JDK `HttpClient` infra details, etc. These
> belong only in the server log, never in the response.

and again at `:120-133` for `IllegalStateException` ("carries internal class names that should
not reach the client"). The start path is the *most* exception-rich path in the application and
the API has no authentication in front of it yet (CLAUDE.md, Backlog: "Spring Security +
Keycloak"). Concretely reachable strings include Mongo/driver socket exceptions with internal
hostnames and ports, `ValidationException`/`WebSocketParserException` text from ws-parser —
whose login-failure messages embed a fragment of the upstream **response body**, on a library
that is separately known to log agency-token material — and fully-qualified internal class
names on every line via `toString()`.

Note that the one message this field genuinely needs to carry (`restart()`'s zero-bot
`IllegalStateException`) is self-authored and operator-safe; it is the unbounded rest that is
the problem.

Fix shape: keep the full throwable server-side (the ERROR line at
`BotGroupBehaviorService.java:452-456` already has it, with the stack trace) and expose a
classified `lastError`: message verbatim for the types `RestExceptionHandler` already declares
operator-safe by construction (`BadRequestException`, `UpstreamGatewayException`,
`IllegalArgumentException`) plus the restart's own zero-bot message, and a fixed string
(optionally plus `getClass().getSimpleName()`) for everything else. One classification, shared
with the exception handler, rather than a second policy that disagrees with the first.

### [bug] R3 — `GET /{id}/health` reports `STOPPED` for a group whose start is in flight

`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1698-1708`

`getHealth` still keys entirely off `runningGroups.get(id)` and returns a hard-coded
`status(BotGroupStatus.STOPPED)` when there is no runtime. `getActualStatus` (`:2645`) was
widened for precisely this window, and its javadoc names the reason:

> answering `STOPPED` there would tell an operator their start did nothing

`/status` and `filterSorted` (`:1925`, which feeds the list view) now say `STARTING`; `/health`
says `STOPPED` for the same group at the same instant. Before this phase the window was invisible
to any client — the runtime appeared inside the same synchronous request — so this is a
regression in honesty introduced here, on the endpoint MEMORY records as *the* public-facing UI
health feature, and under Phase 3 pacing it is minutes wide, not milliseconds.

Fix: build the runtime-less health response from `getActualStatus(id)` (which already resolves
`STARTING` / `STOPPED` correctly) and carry `botsUp` alongside `totalBots`, as A1's consumer
audit intended for this DTO.

### [bug] R4 — the startup daisy-chain has no shutdown signal, and `@PreDestroy` pulls its executor out from under it

`BotGroupBehaviorService.java:278-283` (`shutdown()`), `:342-351` (`runStartupChain`).

`shutdown()` calls `botCreationExecutor.shutdownNow()`. The chain is an untracked virtual thread
that nothing signals, so on a `docker compose restart` in the middle of a chain:

- every remaining group's `createBotsInParallel` submission throws `RejectedExecutionException`,
  the `catch (Exception e)` at `:347` treats it as a *group* failure, and the chain logs
  `Failed to auto-start bot group … ` **at ERROR, with a stack trace, once per remaining
  group**. On a fleet of a few hundred groups that is a few hundred ERROR lines per restart —
  and ERROR is the page-worthy tier in CLAUDE.md's model, with `bot_creation_failures_total`
  moving alongside it;
- in-flight bot-creation tasks are interrupted mid-login and counted as creation failures
  (pre-existing, but the chain now multiplies it);
- the chain keeps calling `botGroupService.findById` / `save` against a context that is closing.

A restart is not a group failure and must not be reported as N of them. `startupChain`'s javadoc
is right that *interrupting* the chain would be worse (`connect()` swallows interrupts), but a
cooperative flag is not an interrupt: set a `volatile boolean shuttingDown` in `shutdown()`,
check it at the top of the chain loop, and emit **one** INFO line naming how many queued groups
were abandoned. Optionally join the chain briefly so the last group finishes cleanly.

Related, same method: `runStartupChain` catches `Exception`, not `Throwable`. An `Error` from one
group now abandons the whole remaining fleet with nothing in `console.log` — the default handler
writes to stderr, which per CLAUDE.md's Phase 4 split reaches neither track 1 nor Loki. Under
the old `@PostConstruct` the same `Error` failed the boot loudly. Cheap to close while you are
in there.

### [bug] R5 — the chain starts groups an operator stopped while the chain was still running

`BotGroupBehaviorService.java:315-340` (snapshot) → `:342-351` (loop) → `startTracked` → `start`.

`onStartup` snapshots `findByTargetStatus(ACTIVE)` into `queued` before the first group is
touched, and the chain never re-reads the group. `startLocked` re-reads the document but only
for its configuration — it never re-asserts `targetStatus` — and then persists
`targetStatus=ACTIVE` at `:919-922`.

Pre-Phase-2 this was unreachable: the loop ran inside context refresh, before Tomcat bound its
port, so no operator could `/stop` anything while it ran. That is precisely what AD-14 changes.
Now the REST API is live from the first second and the chain can run for an hour, so a `/stop`
issued against a group still sitting in `queued` is silently overwritten minutes later — and
because `STOPPED` is DEAD_GROUP_AUTO_RECOVERY's *only* opt-out, the write that gets lost is the
one piece of operator intent the codebase treats as load-bearing. (`putIfAbsent` does not help:
the stop happens before the chain reaches that group, so there is no attempt to cancel.)

Fix shape: re-read the group inside the chain loop and skip unless it is still eligible
(`targetStatus == ACTIVE`, `activationMode != MANUAL_OFF`), logging one line when it skips. This
is the same "re-read inside the lock and re-assert eligibility" discipline
`startForRecovery` already applies for the identical race, and it is cheap — one `findById` per
group on a path that is about to spend thousands of gateway requests.

### [bug] R6 — DELETE during a build leaks authenticated bots and can resurrect the deleted document

`BotGroupBehaviorService.java:1498` (`stopAndLogout`, **no group lock**),
`:936-938` (`startLocked`'s teardown), `:919-922` (the ACTIVE persist),
`BotGroupService.java:334-337` (`delete`).

`stopAndLogout` is the one lifecycle entry point that does not take the per-group
`ReentrantLock`, so `DELETE /{id}` can interleave with a build that holds it. Cancellation
narrows the window correctly, but if the delete's teardown lands after the build passed the
cancel checkpoint at `:831`:

1. `stopAndLogout` calls `runtime.stopAllBots(...)` and `runningGroups.remove(id)`;
2. the build's `finally` does `BotGroupRuntime failedRuntime = runningGroups.remove(id)` → `null`
   → **`stopAllBots` is never called**, so every bot that finished authenticating *after* step 1
   (up to `bot.creation.parallelism`, plus any the `startBot` loop had not reached) keeps its
   WebSocket client, scheduler and threads for the life of the JVM, invisible to every
   accounting path — the shape of the 2026-06-30 thread-exhaustion outage;
3. if the build instead wins the race to `:922`, `botGroupService.save(group)` **re-inserts the
   document `repository.deleteById` just removed** (Spring Data `save` is an upsert by `_id`), so
   the operator's delete silently does not stick.

Pre-existing in shape; the async start widens the window from the ~50 s of a synchronous start
to the whole paced build, and "create a group, start it, watch it misbehave, delete it" is a
normal operator sequence. Fix shape: take the group lock in `stopAndLogout` (it is the only
lifecycle path without it, and `restart`'s stop→start is non-nested so there is no new
reentrancy), and have `startLocked`'s `finally` tear down the local `runtime` reference it
created rather than only whatever it can still remove from the map.

### [smell] R7 — `startForRecovery` ignores `begin()`'s return value and builds anyway

`BotGroupBehaviorService.java:640-651`

```java
boolean tracked = startAttempts.begin(id, StartOrigin.RECOVERY);
Throwable failure = null;
try {
    startLocked(id);
```

Every other entry point treats `false` as "do not build" (`submitLifecycle:435-439`,
`startTracked:479-483`). Recovery instead proceeds while a foreign attempt is open, and because
the registry is keyed on **group id, not attempt identity**, the build's `botUp` / `botFailed`
increments are then attributed to that foreign attempt while recovery's own failure is recorded
nowhere. It is harmless today only because the per-group lock plus the widened reclaim guard at
`:680-683` serialise the two — i.e. for exactly the reason the comment three lines above says
should be written down rather than relied upon.

It also makes the second half of Dev's AD-16 correction ("`putIfAbsent` means a restart is never
submitted while another start is in flight") true of every path *except* this one. Either return
early like the other two callers, or state in the javadoc why recovery is allowed to run
untracked and what happens to the progress counters when it does.

### [smell] R8 — progress is incremented in the sequential join loop, not in the per-bot task, so `botsUp` can read "stuck" while 2,990 bots are up

`BotGroupBehaviorService.java:1065-1074`

AD-17 puts the increments "in the per-bot creation task (`createBotsInParallel`'s lambda)". They
are instead in the result-collection loop, which joins `futures.get(i)` **in index order**. One
slow bot at index 5 therefore pins `botsUp` at 4 until it completes, however many later bots
have already come up. `BotGroupStatusDTO.botsUp`'s own javadoc says the field exists so an
operator can "tell a slow build from a stuck one" — this is the one shape that makes it lie in
the *stuck* direction, on a path whose whole premise is that a legitimate build lasts 33-50
minutes and someone will be staring at this number.

Fix: `startAttempts.botUp(...)` inside the lambda right after `createSingleBot` returns (and
`botFailed` in its failure path), or join with `allOf` and iterate completed futures. Keep the
index-ordered loop for the error logging, which genuinely wants deterministic ordering.

### [smell] R9 — a wedged build parks the group for the life of the JVM, with no operator escape and less visibility than before

`StartAttemptRegistry.java:112-124` (`cancel` deliberately leaves the attempt open), `:132-153`
(`finish` is the only closer), no TTL anywhere.

If a build thread never returns — a `createSingleBot` parked on a socket with no timeout, a
`semaphore.acquire()` that never gets a permit, a Phase 3 `ESSENTIAL` waiter whose max-wait is
by design unbounded — then: `/status` reads `STARTING` forever; `isGroupRunning` reports the
group running so `ActivationScheduler` resolves `NONE` and `RecoveryCandidateSelector` never
selects it; and `begin`'s `putIfAbsent` refuses every later `/start` and `/restart`. The group
becomes a silent black hole, where the pre-Phase-2 shape at least left an operator looking at a
hung HTTP request.

Leaving the attempt open on cancel is right (750fc91) and a TTL that *drops* an attempt would
re-open that hole. But the condition should at least be diagnosable and breakable: expose the
attempt's age (`StartAttempt.startedAt` already exists and `describe()` already renders elapsed
seconds into a log line only), and consider letting `begin` supersede — with one WARN naming the
age — an attempt older than some multiple of the expected duration.

Adjacent, and worth one line of thought before Phase 3: `stop()`'s latency bound is not
"immediate". It cancels, then parks on the group lock until the build unwinds, and the build
cannot unwind faster than the slowest `createSingleBot` already past the permit check (up to
`bot.creation.parallelism` of them, each with no cancellation check between its login, upgrade
and first balance read). V2c's "stop 200 within 5 s" is an assertion about gateway latency, not
about this code.

### [smell] R10 — a zero-bot `/start` leaves `lastError` null

`BotGroupBehaviorService.java:872-899`

The zero-bot path persists `targetStatus=DEAD` and a perfectly good
`lastFailureReason("Started 0/N bots — all bot creations failed")`, then **returns normally**, so
`submitLifecycle`'s `finally` calls `finish(id, null)` and `/status` answers
`actualStatus: DEAD, botsUp: 0, lastError: null`. `lastError` is documented on the DTO as "the
most recent start failure"; 0/N is the canonical one, and `/restart` only gets it right by
accident (because `restart()` throws afterwards). Either hand the same message to the attempt on
that branch, or have the DTO fall back to `group.lastFailureReason` when `lastError` is null.

### [smell] R11 — `submitLifecycle` can leak an attempt between `begin` and the thread starting; and each accept reads the group two or three times

`BotGroupBehaviorService.java:435-464`; `BotGroupController.java:210-226`, `:250-259`.

`begin` succeeds, then `Thread.ofVirtual()...start(...)` is outside any guard, as is
`BotMdc.setGroupContext` inside the body. Anything thrown there (OOM, a thread-creation failure
under the load this feature is built for) leaves an attempt open forever — R9's black hole, from
a path that is trivially closable with a `try { … } catch (Throwable t) { startAttempts.finish(id, t); throw t; }`
around the submit.

Separately, `/start` reads the group in `runWithManualOverrideAsync`, again in `submitLifecycle`,
and the DTO is then assembled from the *first*, pre-`setActivationMode` instance; `/restart`
reads it twice. Harmless, but the assembler could take the group the accept already loaded and
the accept could take the group the caller already loaded.

### [smell] R12 — the scheduled-restart path swallows its new synchronous rejection

`BotGroupBehaviorService.java:1674-1681`

`scheduleRestart`'s lambda is now `restartAsync(...)`, which validates **synchronously** on the
scheduler thread: a group deleted or de-configured between booking and firing throws
`ResourceNotFoundException` / `BadRequestException` straight into the
`ScheduledExecutorService`, where a one-shot task's exception is discarded with no log at all.
The booked restart simply never happens and nothing says so. Wrap the lambda in the same
try/catch-and-log the chain uses. (Roughly equal to the pre-diff behaviour, which is why this is
a smell and not a bug — but the diff is what moved the throw onto this thread.)

### [style] R13 — unused surface on `StartAttempt`

`StartAttemptRegistry.java:232-296`: `finishedAt` is written and never read anywhere;
`botGroupId()`, `phase()`, `startedAt()`, `isCancelled()`, `finishedAt()` and `error()` are
public accessors with no production reader. `botsFailed` is tracked on every failed bot and
reaches nothing but `describe()`'s log line (A1 dropped it from the DTO field list
deliberately, so this is consistent — just worth knowing that the counter's only consumer is a
string). If the accessors exist for tests, that is fine; if they exist in case someone needs
them, they are the beginning of an API nobody asked for.

## Notes

**The decisions I checked and agree with.**

- **Dev's contradiction of AD-16 is correct, and I verified it independently.** If
  `restart()`'s internal `stop(id, false)` cancelled the attempt, it would cancel *its own*
  attempt — `restartAsync` opens it before `restart()` runs, `isCancelled` is keyed on group id,
  and the build's per-bot check at `:1029` would then skip every bot and unwind at `:831`. Every
  `/restart` would become a `/stop` that persisted nothing. Gating the cancel on
  `parkRuntimeless` is the right discriminator for the three callers that exist. One caveat:
  `parkRuntimeless` now carries two unrelated meanings (persist STOPPED; cancel a start in
  flight). They coincide for all three of today's callers, and a fourth caller that wants a
  teardown without a statement of intent would silently inherit both. A named parameter or a
  small enum would make the next reader's life easier. The second half of the claim
  ("`putIfAbsent` prevents a restart being submitted during a start") holds for every path
  except `startForRecovery` — see R7.
- **`cancel → cancelScope → lock` in `stop()`** is in the right order, and `cancelStartInFlight`
  is right not to conjure a budget (`find`, not `forEnvironment`). It resolves the environment
  from the *runtime*, so in the accepted-but-not-yet-locked window it finds no budget — which is
  correct today and stays correct after Phase 3, because `runningGroups.put` happens at `:763`,
  before `createBotsInParallel` and therefore before any request or reservation can be queued.
  That invariant is load-bearing and is not written down anywhere; one sentence in
  `cancelStartInFlight` would protect it.
- **The per-task cancellation check under the semaphore permit** is the correct placement, and
  the reasoning in the comment is sound: with all N tasks submitted at once, only a task holding
  a permit is about to spend gateway requests. Keeping the pre-acquire check as well is worth its
  two lines. Returning `null` rather than throwing (so cancelled bots stay out of
  `bot_creation_failures_total`) is the right call, and the `skipped` counter keeps the
  accounting honest.
- **`clearRetained` vs dropping the whole attempt** — the `750fc91` fix is right and the javadoc
  explaining *why* an open attempt must survive a `/stop` is the single most valuable comment in
  the diff.
- **`/restart` validating before its internal stop is an improvement, not just a change.**
  Previously a `/restart` of a group with no `gameId` stopped the group and *then* answered 400 —
  a validation error with a destructive side effect. It is now a pure 400 that leaves the group
  running. Worth a release note (behaviour change on every brand), not a finding.
- **`StartAttemptRegistry` as an owned final field** rather than a `@Component`: accepted. The
  `@InjectMocks`-stays-non-null argument is real and the class has no dependencies. The cost is
  that nothing outside the service can read it — a future `start_attempts_open` gauge or a
  `/api/v1/metrics` view would have to go through accessors on a 2,700-line service — but that is
  a fair trade today.
- **`progress(id, Phase)` taking the phase explicitly** is better than an `advance()` that infers
  the next state; **`StartOrigin`** is a bounded, well-documented log field with one call site per
  constant; **the `GatewayBudgetRegistry` constructor parameter** is the only way `stop()` can
  reach `cancelScope` before the lock, and reading it through the non-creating `find` is right.

**Phase-boundary discipline (item 8): clean.** No queues, waiters, ceilings, reservations,
registration machinery or Cloudflare detection appear in the diff. The only budget surface
touched is Phase 1's existing `find`/`cancelScope` (still a debug no-op), `registeredCount` is
declared and left null with a comment saying why, and `BotGroupStatus`'s two registration
constants are declared-but-unproduced so Phase 4 cannot be tempted to insert. The seams are
seams: `BotConfiguration.startCancelled` is the hook Phase 3's queue needs, and the `run` /
`runWsUpgrade` enforcement A5.1 requires is untouched here, correctly.

**Logging and metrics (item 9): compliant.** Every new INFO line is once per JVM (the "queued for
daisy-chained start" line), once per group start ("start admitted — origin …, N bots"), or once
per operator action (duplicate-start ignored, stop-cancelled build, skipped bots). Nothing is
per bot, per round or per message. No metric was added, which the plan does not ask for at this
phase — but note the consequence: a fleet stuck in `STARTING`, an abandoned daisy-chain (R4) and
a cancelled start are all invisible to Prometheus, so Grafana cannot see any of the new states.
`StartOrigin`'s javadoc already anticipates being a counter label; if one is added later,
pre-register it at zero per CLAUDE.md.

**One thing I could not settle, for the author.** Moving `onStartup` to
`@EventListener(ApplicationReadyEvent.class)` means an exception from
`botGroupService.findByTargetStatus(ACTIVE)` now propagates out of `listeners.ready(...)`, which
Spring Boot handles by closing the context and exiting — *after* `Started Starter`, after Tomcat
bound its port and after `/actuator/health` first answered UP. The net outcome (the app dies) is
the same as the old `@PostConstruct`, so this is not a regression; but the observable shape is
"healthy, then gone", which reads as a crash rather than a boot failure. Wrapping the query in a
try/catch that logs ERROR and leaves the fleet un-started would make a transient Mongo blip at
boot survivable instead of fatal. Deliberate either way — just make it deliberate.

**Not a finding, but worth stating precisely:** `BotConfiguration.startCancelled` is
group-scoped and lives for the bot's whole lifetime, so it means "this group's start was called
off", not "the start this bot was built by was called off". Every cancellation today is followed
by a teardown of that group, so no live bot ever observes a cancellation it should not — but the
name suggests a narrower guarantee than the predicate gives. A `/start` on an already-ACTIVE
group opens (and quickly closes) an attempt whose cancellation would briefly apply to the whole
live fleet of that group; harmless because the only canceller is a stop.

---

# Re-review — GATEWAY_REQUEST_BUDGET Phase 2 fix round

Branch: `feature/gateway-request-budget`
Reviewed diff: `git diff 0558a51..HEAD` — the fix round is `7e118bf`..`d9be331` (6 commits);
`72bbb24` sits on top of it and is unrelated (see Notes).
Read first: `CLAUDE.md`, `docs/plans/GATEWAY_REQUEST_BUDGET.md` Amendments A1-A13,
`docs/plans/FOLLOWUPS.md` P13/P14.

Housekeeping, acknowledged: the original `review-phase2.md` above was left staged and rode into
`7e118bf` unedited. Nothing in it was altered by Dev; this section is the only addition, and it
is the only thing I have staged.

Build check: `mvn -o -DskipTests compile` clean; full reactor `mvn -o test` **BUILD SUCCESS**
(bot-app 1,350 tests, 0 failures). `BotGroupStatusRollbackSafetyTest.requestBodiesCannotPoisonTargetStatus`
— the test that carried QA's FAIL — now passes.

## Verdict

**PASS**

Both blockers are closed, and closed in the right places. R1 is fixed at the boundary rather
than in a validator, and I verified the perimeter is complete rather than merely plausible. R2
extracts a real shared policy and does not change one byte of what any existing HTTP path
exposes. R3-R8 and R10-R13 are each fixed as claimed, R6's new lock introduces no deadlock, and
nothing in the round reaches outside Phase 2 or weakens a Phase 1 guarantee.

No `bug` and no `security` finding. Eleven advisory items follow — **two of them (RR1, RR2) are
residual holes in the R5 and R3 fixes** and are the only ones I would ask to see before Phase 3
starts; RR3 is a note that belongs in P13; the rest is documentation drift and hygiene, and
RR6/RR7 matter only because they are stale claims a future reader will reason from.

## Findings

### [smell] RR1 — R5's re-read is on the wrong side of the lock, unlike the precedent it cites

`BotGroupBehaviorService.java:401` (the call), `:432-446` (`stillWantsToStart`), against
`:723-740` (`startForRecovery`).

The fix is right in substance and the window is now tiny, but it is not the discipline its own
javadoc claims:

> Same "re-read and re-assert eligibility" discipline `startForRecovery` already applies to the
> identical race

`startForRecovery` takes the lock **first** and re-reads **inside** it (`:724` then `:727`).
`stillWantsToStart` re-reads **before** `startTracked` → `start` → `lock.lock()`, and
`startLocked` re-reads the document only for its configuration. So the sequence

1. chain: `stillWantsToStart(g)` → `ACTIVE`, proceed
2. operator `/stop`: nothing open to cancel, lock free, runtime null → persists `STOPPED`
3. chain: `begin`, lock, build, `setTargetStatus(ACTIVE)` at `:1045`

still loses the `STOPPED` write — which is DEAD_GROUP_AUTO_RECOVERY AD-5's only opt-out, i.e.
the exact write R5 was about. The window shrinks from "the whole chain, up to an hour" to "one
`findById` plus one log line plus `begin`", which is why this is a smell and not a re-raise: the
same width exists for any `/start` racing any `/stop` and always has.

Fix shape: assert intent where the lock already is — a `startLocked` precondition, or an
`expectTargetStatus` argument threaded through `startTracked`, so the assertion and the ACTIVE
persist are under one lock acquisition. Failing that, drop the `startForRecovery` comparison from
the javadoc, because it is the sentence that will stop the next reader from looking.

### [smell] RR2 — R3 fixed only `getHealth`'s runtime-**less** branch; the reclaim window still answers `DEAD`

`BotGroupBehaviorService.java:1948` (`status(runtime.getActualStatus())`), against `:1916`
(`status(getActualStatus(id))`) and `:2872-2882` (`getActualStatus`).

`getActualStatus`'s javadoc names the case this leaves open, in so many words:

> where they differ is a reclaim — a DEAD runtime being rebuilt still reads DEAD until
> `startLocked` tears it down, and answering DEAD to an operator whose `/start` was just
> accepted is the one answer that is actively misleading

A `/start` on a DEAD group opens the attempt in `submitLifecycle` (`:530`), and the DEAD runtime
stays in `runningGroups` until `teardownRuntimeMemory` at `:806` — which is behind
`lock.lock()`. For the whole of that window `/status` says `STARTING` (correct) and `/health` —
the public-facing UI feature — takes the runtime branch and says **`DEAD`**. Under pacing the
lock can be held by another operation for minutes. There is a milder inverse too: a redundant
`/start` on a live group makes `/status` say `STARTING` while `/health` says `ACTIVE`.

R3's point was that the two endpoints must not disagree about the same group at the same
instant, and they still can — now in the direction the javadoc singles out as the worst one.
Fix: `status(getActualStatus(id))` in **both** branches; the runtime's own status is still the
right source for `playingStatus`, `startedAt` and the per-bot block.

(The second half of R3 — carrying `botsUp` on the health DTO — was not done. That needs a new
`BotGroupHealthDTO` field and A1 only promises `registeredCount` there, so skipping it is fine;
`totalBots: 0` on a group that is `STARTING` is honest, if terse.)

### [smell] RR3 — R6's lock gives a wedged build a second blast radius, and P13 does not record it

`BotGroupBehaviorService.java:1675` (`stopAndLogout`'s `lock.lock()`), `docs/plans/FOLLOWUPS.md`
P13, against the precedent at `:3008-3018` (`handleBotGroupDeath`).

Taking the lock is the right fix and I verified it is deadlock-free (below). The cost is that
`DELETE /{id}` now parks **uninterruptibly** on a lock a build holds. Normally that is bounded:
`cancelStartInFlight` runs first, the build polls the flag under its semaphore permit and
unwinds. But P13 is precisely the case where the build *never polls* — parked in
`semaphore.acquire()`, or on a socket read with no timeout, or (Phase 3) on an `ESSENTIAL`
waiter with an unbounded max-wait. Then the lock is never released and every DELETE, like every
`/stop`, permanently consumes a Tomcat worker. An operator who retries a few times spends a few
more. Before this round a DELETE at least returned.

This does not change my agreement with deferring R9, but P13 currently says the impact is "a
group that will not come up and cannot be retried" — it is now also "and every attempt to stop
or delete it costs a request thread for the life of the JVM". Two cheap mitigations, either of
which would close it independently of P13: `tryLock` with a timeout and a `409`/`503` (the
health monitor already does exactly this at `:3008`, with the reasoning written out), or bound
the acquire in `createBotsInParallel`.

### [smell] RR4 — `ClientSafeMessage`'s stated rule does not match the handler for two `BotManagerException` subtrees

`ClientSafeMessage.java:21-25` and `:69-70` (the claims), `:78-80` (the predicate), against
`RestExceptionHandler.java:77-84` and the absent 429/503 arms.

The extraction is the right shape and the forwarded set is the one I asked for. But the class
whose whole purpose is to be the single true statement of the policy states it wrongly in two
places, and `BotManagerException` is wider than the handler's typed arms:

- **`ResourceNotFoundException extends BotManagerException`**, and its handler arm returns a
  **bodyless 404** (`:77-84`) — the message is logged at INFO and never reaches a client. So
  `ClientSafeMessage` forwards verbatim a message the HTTP layer has never exposed. It is
  reachable on this path: a group deleted mid-build makes `startLocked`'s `findById` throw it,
  and `lastError` will then read `Bot group not found with id: …`. That is a *better* answer
  than `Internal server error (ResourceNotFoundException)`, so the behaviour is fine — the
  claim "every subclass of it is already message-forwarded by `RestExceptionHandler`'s typed
  arms" is not.
- **`GatewayBudgetException extends BotManagerException`** (plus `…Exhausted`, `…CircuitOpen`,
  `…RequestCancelled`) has **no arm in `RestExceptionHandler` at all** in Phase 2 — it falls
  through to `handleAny` and is sanitised into a 500. So "the handler already forwards all of
  them verbatim at 400/502/429/503" describes a Phase 3 state as present tense. The messages
  are self-authored (`GatewayBudgetExhaustedException: … (tier ESSENTIAL); retry in 12s`
  appears in the test log), so forwarding them is right — but it is a decision this class is
  making ahead of the handler, not a policy it is inheriting.

Fix shape: state the rule as "our own hierarchy, whose messages we wrote" and drop the appeal to
which arm forwards what, or enumerate the two exceptions to it. Both messages being safe is why
this is a smell; a policy class that misdescribes the policy is how the next widening gets
justified by a sentence that was never true.

### [smell] RR5 — the delete/resurrect race is narrowed, not closed: `deleteById` runs after the lock is released

`BotGroupService.java:334-337`, against `BotGroupBehaviorService.java:1675-1681`.

```java
public void delete(String id) {
    behaviorService.stopAndLogout(id);   // takes and releases the group lock
    repository.deleteById(id);           // outside it
}
```

A `/start` that acquires the lock in the gap between those two statements reads the document
successfully (it still exists), builds for minutes, and then `save()` **re-inserts** it — Spring
Data `save` is an upsert by `_id` — leaving a live runtime for a group the operator deleted. The
window has gone from "the whole build" to "between two adjacent statements", so the fix is a
real fix; but the clean shape is one lock acquisition covering both the teardown and the delete
(a behaviour-service method that ends in the repository call, or the lock hoisted into
`BotGroupService.delete`). The `stopAndLogout` comment currently asserts the stronger property
it does not quite have: "after which `BotGroupService.delete` removes a document nothing will
write again".

### [style] RR6 — `BotGroupStatusRollbackSafetyTest` was not brought along with R1, and now reads as a defect report for a defect that is fixed

`bot-app/src/test/java/com/vingame/bot/domain/botgroup/model/BotGroupStatusRollbackSafetyTest.java:29`,
`:78`, `:125`, `:150`.

Dev corrected the `ConversionFailedException` / boot-query model in `BotGroupStatus`,
`BotGroupStatusPersistenceGuardTest` and `BotGroupDTO` — and left the sibling file that
*measures* the mechanism carrying the old one:

- `:29` — "`findByTargetStatus(ACTIVE)` is on the application-ready boot path — one poisoned
  document does not degrade one group, it fails the whole boot query";
- `:78` — "which on the `findByTargetStatus(ACTIVE)` boot path means the whole query — and
  therefore the whole startup — fails";
- `:125` — "**This is a defect report, not a specification.** … The assertions below are the
  invariant A1 states; they fail today, and the fix belongs in production code";
- `:150` — `@DisplayName("neither create nor patch may persist an appended constant (currently
  they do)")`, on a test that now passes.

The same file already corrects the *exception type* at `:82-83`, so it is internally
inconsistent as well as stale. This is the last place in the tree still teaching the wrong
model, and it is the place a reader goes for the measured answer. (A1 itself still carries it
too — that is Architect-2's reconciliation and is correctly out of Dev's hands.)

### [style] RR7 — the Q3 MDC change invalidated a javadoc in `DeadGroupRecoveryScheduler` that the diff did not touch

`DeadGroupRecoveryScheduler.java:372-386` (the claim), `:419-422` (the now-redundant re-assert).

> **The MDC has to be re-asserted after `startForRecovery` returns**, and that is not defensive
> tidying. Every path through `startLocked` calls `BotMdc.clear()` on *this* thread before
> returning — the reclaim path's `teardownRuntimeMemory`, the normal build path's
> `createBotsInParallel` result loop, and the failure path's outer `finally`

All three named sites were converted to `snapshot`/`restore` in `95d551e` (`:1600/:1607`,
`:1206/:1236`, `:1064/:1086`). The re-assert at `:421` is now a harmless no-op, but a paragraph
that opens with "that is not defensive tidying" and names three specific sites as the reason is
exactly what someone will delete or rely on. Either drop the re-assert and the paragraph, or
restate it as belt-and-braces.

### [style] RR8 — two consecutive javadoc blocks on `INTERNAL_ERROR_MSG`, the first now orphaned and wrong

`RestExceptionHandler.java:62-75`.

The new block was added without removing the old one. Java attaches only the last, so the first
becomes a dangling comment that still describes the string as being defined here ("Fixed,
operator-safe message used in the 500 fallback"). Compiles, and it is the kind of thing that
makes the next reader think there are two constants.

### [style] RR9 — R11 is half-fixed: the MDC call is still outside the lambda's `try`

`BotGroupBehaviorService.java:551-553`.

```java
Thread.ofVirtual().name("group-" + action + "-" + id).start(() -> {
    BotMdc.setGroupContext(id, environmentId);   // outside the try
    Throwable failure = null;
    try { … } finally { startAttempts.finish(id, failure); … }
});
```

Wrapping `submit()` closes the thread-creation half, which was the larger one. A throw from
`setGroupContext` still leaves the attempt open forever — R9/P13's black hole from a
one-line-movable cause. Move it inside the `try`.

### [style] RR10 — R8's relocation split `botsFailed` from `bot_creation_failures_total` for non-`Exception` throwables

`BotGroupBehaviorService.java:1174` (`catch (RuntimeException e)`) against `:1225`
(`incBotCreationFailure` in the join loop's `catch (Exception e)`).

Moving the increments into the task is right and fixes the index-order lie. One invariant was
lost in the move: while both lived in the join loop they could not disagree. Now an `Error` from
`createSingleBot` — `NoClassDefFoundError` is not hypothetical in a tree heading for child
classloaders, and `OutOfMemoryError` is the load this feature exists for — skips
`catch (RuntimeException)`, so `botsFailed` misses it while the join loop's `catch (Exception)`
still catches the wrapping `CompletionException` and moves the metric. `catch (Throwable)` at
`:1174` restores it. Low stakes (`botsFailed` reaches nothing but `describe()`'s log line, per
A1), which is why this is style.

### [style] RR11 — `stopAndLogoutLocked` is the one teardown left on `clear()` rather than `snapshot`/`restore`

`BotGroupBehaviorService.java:1707`.

Four sibling teardown/aggregation blocks were converted in `95d551e`; this one was not. Correct
today — its only caller is `BotGroupService.delete` on a Tomcat thread with no outer scope — but
it is now the odd one out, and the `BotMdc.clear()` javadoc added in this very round tells the
reader to prefer `snapshot`/`restore` "when the scope you are leaving is nested inside another
one". Either convert it or say why it is exempt.

## Notes

### Item 1 — R1's perimeter, checked rather than assumed

`targetStatus` is `@JsonProperty(access = READ_ONLY)` on `BotGroupDTO:164` and gone from both
mapper write paths. I looked for a third way in and did not find one:

- **Other mappers:** `BotGroupMapper` is the only production construction path — one
  `BotGroup.builder()` call in the whole tree (`BotGroupMapper.java:66`), and exactly two
  persist sites, both in `BotGroupService` (`:207`, `:311`).
- **MapStruct-generated code:** every method on the interface is `default`, so the generated
  `BotGroupMapperImpl` is an empty `@Component` — verified in
  `bot-app/target/generated-sources/annotations/…/BotGroupMapperImpl.java`. There is no
  generated write path.
- **Direct `@RequestBody` onto the entity:** the botgroup controller binds `BotGroupDTO`,
  `BotGroupFilter` and `LocalDateTime` only. No endpoint anywhere accepts a `BotGroup`.
- **Mongo `update`:** `BotGroupService`'s `MongoTemplate` is used for `find` only (`:105`);
  `BotGroupRepository` declares four derived finders and no `@Query` update. Nothing partial
  writes this field.
- **Filter/sort surface:** `BotGroupFilter` carries `name`/`gameId`/`sortBy`/`sortDir` — no
  status criterion, so `READ_ONLY` cannot have broken filtering *by* status, because there was
  none. `BotSortKey.STATUS` sorts on `actualStatus()`, in memory.
- **Outbound rendering still works** on all three surfaces: `BotGroupMapper.toDTO:49` →
  `BotGroupDTO` (GET `/{id}`, `POST /{envId}/filter`, and the `POST /` and `PATCH` responses),
  `BotGroupController.statusDTO:301` → `BotGroupStatusDTO` (unannotated, untouched), and
  `BotGroupHealthDTO.status`. `READ_ONLY` is serialise-yes / deserialise-no, and the DTO
  deserialises through `@NoArgsConstructor` + setters with no `@JsonCreator`, so there is no
  creator-binding path around the annotation. The guard test asserts both directions through a
  real `ObjectMapper`, which is the right way to assert it.
- **The by-value assertion does survive an appended constant**:
  `theMapperNeverCarriesTargetStatus` loops `BotGroupStatus.values()` for both directions. Worth
  knowing that the *other* half does not — `NOT_PERSISTABLE` in
  `BotGroupStatusPersistenceGuardTest:69-70` is still a hand-written list of today's three, so a
  fourth appended constant is guarded by the mapper test and by nothing in the source scan. That
  is the right split (the scan exists for `setTargetStatus` literals, and the six real call
  sites are all internal), but it is a split, not a general guarantee.
- **Out of the perimeter by construction:** `seed.js` writes `botGroups` documents straight into
  Mongo, `targetStatus` included. Nothing can or should stop that; it is worth one sentence
  somewhere that the invariant is an *API* invariant, so that a future seed or a `mongosh`
  one-liner with `"STARTING"` is understood to be poisoning the collection deliberately.

**Rejecting the validator arm is the right call, and the argument is stronger than Dev states.**
The javadoc grounds it in Phase 4 rendering `REGISTRATION_PENDING`. It already bites *today*:
`toDTO` renders `ACTIVE`/`STOPPED`/`DEAD`, so any read-modify-write client already hands a
non-null `targetStatus` back on every PATCH. A validator restricted to the three appended
constants would be fine today and would start 400-ing legitimate PATCHes the day Phase 4 ships —
which is CLAUDE.md's recorded strategy-key trap exactly. Silent-ignore is also what the class
already does for `lastStartedAt` / `lastStoppedAt` / `lastFailureReason`, so `READ_ONLY` makes
the field consistent with its three neighbours instead of inventing a fourth behaviour. Agreed,
with the one consequence worth stating in a release note: a client that *intends* to change
lifecycle by PATCH now gets a `200` and no change.

### Item 2 — R2's refactor changes nothing the HTTP layer exposes

`INTERNAL_ERROR` is byte-identical to the old `INTERNAL_ERROR_MSG`
(`"Internal server error — see server logs"`), and `RestExceptionHandler` now reads the constant
from `ClientSafeMessage` without touching a single arm. Every existing path — the bodyless 404,
the two verbatim 400s, the synthesised type-mismatch 400, the 502, the sanitised
`IllegalStateException` 500, `handleAny`, `msgForStatus`'s 4xx-forwards-5xx-sanitises split —
is unchanged. Confirmed by reading all 297 lines, not just the diff.

**The one deliberate widening (the simple class name in `lastError`) is justified.** A
`lastError` holder has no request URI and no timestamp to correlate a server log against, which
is the thing the handler's policy leans on, and a bare `getSimpleName()` carries none of the
three leaks the policy names — no hostname, no bean-wiring detail, no `HttpClient` internals.
`Internal server error — see server logs (MongoSocketOpenException)` is materially more useful
than the bare string and materially less than `toString()`. Accepted. Two cosmetic notes:
`getSimpleName()` is `""` for an anonymous class, so the message can render as
`… server logs ()`; and `IllegalArgumentException` being in the safe set means a *library-*
authored IAE is forwarded verbatim — including, pleasingly,
`No enum constant com.vingame.bot.domain.botgroup.model.BotGroupStatus.STARTING` if a document
is ever poisoned by the one route R1 cannot close. That is the handler's existing 400 policy
applied consistently, and it is the set I asked for, so it is not a finding — just the one
FQCN-shaped hole in an otherwise clean rule.

### Item 3 — the rest of R3-R8, R10-R13

- **R4** fixed, and the mechanism is right. `shuttingDown` is `volatile`, set at `:291`
  **before** either `shutdownNow()`, and checked at the top of every iteration (`:395`) with one
  INFO line naming the abandoned count. `botCreationExecutor` is
  `Executors.newThreadPerTaskExecutor`, so there is no queue to reject from — the flag is what
  turns N rejections into zero, and the group that was mid-build still costs one ERROR, not N.
  `catch (Throwable t)` at `:404` closes the `Error`-swallowed-by-stderr half, and the log line
  now uses `t.toString()` so the type survives (the old `e.getMessage()` was `null` for plenty
  of the types this catches). P14 honestly records the drain residue.
- **R5** fixed in substance — see RR1 for the residual. `MANUAL_OFF` is not checked explicitly
  and does not need to be: `POST /{id}/stop` goes through `runWithManualOverride` *and*
  `stop(id, true)`, which persists `STOPPED` for a runtime-less group (`:1541-1553`), so the
  `targetStatus != ACTIVE` test covers it. The `SCHEDULED` arm is the right second test — a PATCH
  can move a queued group onto the schedule. A deleted group throwing out of `findById` into the
  chain's own catch is the right call, not a defect.
- **R6** fixed, and I could not construct a deadlock. All five `groupLocks` acquisitions
  (`:470`, `:724`, `:1526`, `:1675`, `:3008`) take exactly one per-group lock and never nest, so
  there is no ordering to get wrong; `handleBotGroupDeath` — the only other thread that contends
  for it on a schedule — uses `tryLock` with a timeout and gives up with a WARN, so the health
  monitor cannot park behind a DELETE. `stopAllBots` waits on the *runtime's* executor, which no
  lock-holder is ever inside, and the bot threads never touch `groupLocks`. The daisy-chain and
  `startForRecovery` are serial, one group at a time. The two hazards R6 named are genuinely
  serialised away: the ACTIVE-persist-then-delete order now completes before the teardown, and
  the `remove` → `null` → no-`stopAllBots` leak cannot happen because the build's `finally` and
  this teardown can no longer interleave. See RR3 for the cost and RR5 for what is left.
- **R7** fixed: `begin`'s `false` now returns `false` from `startForRecovery` (`:748-758`), and
  the check is inside the lock, next to the eligibility re-assert. One consequence to be aware
  of rather than to fix: `DeadGroupRecoveryScheduler:444-449` treats `false` as
  `OUTCOME_FAILED` and charges the attempt budget, so a foreign start that is open-but-not-yet-
  locked converts into a charged failed attempt. It is very narrow (`RecoveryCandidateSelector`
  screens on `isGroupRunning`, which is now attempt-aware, and the foreign start is about to
  persist `ACTIVE` and take the group out of the candidate set), and the pre-existing
  no-longer-eligible branch three frames up has had the same property all along — the `@return`
  javadoc even says so. Building anyway was worse. Accepted.
- **R8** fixed as AD-17 asked: `botUp` at `:1176` inside the task, immediately after
  `createSingleBot` returns and while the permit is still held; `botFailed` at `:1183` and
  `:1189`; both gone from the join loop, so there is no double count. The cancelled-and-returns-
  null path is still neither up nor failed, and `skipped` still accounts for it. See RR10 for
  the one invariant the move cost.
- **R10** fixed, and fixed in the better of the two ways I offered: `recordFailure` publishes
  the self-authored reason rather than the DTO falling back to `lastFailureReason`. I checked the
  ordering hazard it could have introduced — `recordFailure(zeroBotReason)` sits *after* the
  cancellation checkpoint at `:948`, so a `/stop` that cancels a build to zero bots returns at
  `:952` and never records a failure. A cancelled stop is still reported as the INFO it is. The
  `finish` precedence (`error != null && attempt.error == null`) and `recordFailure`'s
  last-writer-wins are both documented and both correct for the restart case.
- **R11** fixed for the thread-creation half; see RR9 for the other line.
- **R12** fixed. `catch (Throwable t)` around `restartAsync` in the scheduler lambda, one ERROR
  with the SLF4J `{}` form and the throwable attached. Swallowing is right here — a one-shot
  `ScheduledFuture` nobody calls `get()` on has nowhere else to put it.
- **R13** fixed: `StartAttempt` is `private`, `finishedAt` is gone, and the eight accessors with
  it. `Phase` stays public because it is a parameter of `progress`. Compiles clean, so nothing
  outside was reading them.

### Item 4 — the R9 / Q4 deferral

**Accepted, and the reasoning is correct where it matters most.** I verified the specific claim
that the obvious fix is wrong: the cancellation flag lives on the *open* attempt, and
`isCancelled` returns `false` when nothing is open (`StartAttemptRegistry:131-134`), so a TTL
that removes an open attempt makes the build's next poll read "not cancelled" and bring the
group up after a `/stop` already answered `200` — which is exactly the `750fc91` race, from the
other end. That is the same mechanism `clearRetained`'s javadoc spells out for the stop path,
and it applies unchanged to a timer. The named alternatives are the right two: a generation
token on the cancellation predicate (so a superseded build can be told "you are not the current
attempt" without the flag being reachable through a group-keyed lookup), or a bounded build —
and the observation that Phase 3's `essential.max-wait=0` is the input that turns this from
conceivable to expected is the useful half of P13. Flagging it as a Phase 3 pre-read is the
right disposition.

Two things to add to P13 rather than to the code:

1. **Discovery depends on a human trying a second `/start`.** P13 calls the condition
   "diagnosable today" on the strength of `describe()`'s `elapsed 3600s`, but that line is only
   emitted by a second start attempt. Nothing periodic reports it, no metric was added this
   phase, and `FleetRollupLogger` keys off running environments — so a fleet parked in
   `STARTING` is invisible in Grafana. The cheapest thing that would change that is one gauge
   (`start_attempt_age_seconds`, pre-registered at zero per CLAUDE.md), and it is a Phase 3
   line item, not a Phase 2 one.
2. **RR3** — the lock now makes the wedge cost request threads too.

**Q4's ERROR-storm half is genuinely fixed and the residue in P14 is honestly scoped.** "The
sockets drop when the process exits and the server prunes them" is right, and the pointer at
`PLUGIN_HOT_RELOAD`'s drain requirement is the correct home — a bounded `@PreDestroy` join is
the sort of thing that is cheap to write and expensive to get wrong against a `connect()` that
swallows interrupts.

### Item 5 — the `findByTargetStatus` mechanism, confirmed independently

Dev's adjudication is right and I re-derived it without reference to my earlier note:

- `BotGroupRepository.findByTargetStatus(BotGroupStatus)` is a Spring Data derived query. The
  enum parameter is converted to its `name()` string (the same conversion that writes the field,
  and there are no `MongoCustomConversions` anywhere in the app) and issued as
  `{"targetStatus": "ACTIVE"}`. **MongoDB does the filtering.** A document holding `"STARTING"`
  does not match, is never returned, and is therefore never handed to the converter. The boot
  query does not fail.
- What *does* convert the group is every read that returns it: `findById` → `GET /{id}`, and
  `BotGroupService:105`'s `mongoTemplate.find(query, BotGroup.class)` → `POST /{envId}/filter`,
  where the query is scoped by `environmentId` and one poisoned document therefore 500s the list
  for every healthy group beside it. That is the blast radius.
- On the current jar the same document silently leaves `findByTargetStatus(ACTIVE)` (no
  auto-start), `findByTargetStatus(DEAD)` in `RecoveryCandidateSelector:62` (no auto-recovery),
  and `RecoveryEligibility`'s `ACTIVE`/`STOPPED`/`DEAD` branches. Unmanaged, with nothing logged
  — which was the sharper half of R1 all along.
- The exception type correction is also right, and is now measured rather than asserted:
  `MappingMongoConverter.getPotentiallyConvertedSimpleRead` lets `Enum.valueOf`'s
  `IllegalArgumentException` out, which `BotGroupStatusRollbackSafetyTest:89-98` pins by message.

A1 still carries the wrong claim, correctly left to compliance. RR6 is the copy of it that Dev
*could* have fixed and did not.

### Item 6 — phase boundary and Phase 1 guarantees

**Clean.** The production diff is: `BotMdc` (two additive methods), `ClientSafeMessage` (new),
`RestExceptionHandler` (one constant), `BotGroupDTO` (annotation + javadoc), `BotGroupMapper`
(two removals + comments), `BotGroupStatus` (javadoc), `BotGroupBehaviorService` (the ten fixes
above), `StartAttemptRegistry` (`recordFailure`, the classified `finish`, the privatisation). No
queue, waiter, ceiling, reservation, tier decision, `registrationState` field, registration
machinery or Cloudflare detection appears anywhere. The only budget surface touched is Phase 1's
existing `find`/`cancelScope` in `cancelStartInFlight`, unchanged. `GatewayBudgetException` shows
up only as a javadoc reference in `ClientSafeMessage` and as a test fixture — see RR4, which is
a documentation issue, not machinery.

**No Phase 1 guarantee weakened.** `BotMdc.clear()` removes exactly the same nine keys it did
before (`ALL_KEYS` is the same list, and it is the complete set of the class's nine constants),
`snapshot`/`restore` are additive, and the full reactor — including
`GatewayCallSiteGuardTest`, `Log4j2TwinConfigTest`, `PerBotInfoLogGuardTest` and the rest of the
build-time guards — is green. One structural note for later: nothing pins `ALL_KEYS` against the
declared constants, so a tenth MDC key would be silently missed by `clear()`, `snapshot()` and
`restore()` alike. `clear()` had the same exposure before, so this is not a regression, but the
surface it affects has tripled.

**Logging** stays compliant. Every new INFO is once per JVM (the abandoned-chain line, the
`started by this chain` count), once per group start (the two skip lines, the recovery
already-in-flight line) or once per operator action. No new per-bot, per-round or per-message
line, no token material, `{}` form throughout, and every one carries the group MDC — which is
what `95d551e` was for.

### Behaviour and contract changes worth a release note

1. **`POST /api/v1/bot-group/` no longer honours `targetStatus` in the body.** A new group is
   created with `targetStatus = null`, which A1 says is the intended state ("byte-for-byte the
   state a synchronously registered group has had since day one"), so this is a correction. But
   a script that created groups pre-set to `ACTIVE` in order to have them auto-start on the next
   boot will silently stop doing that. Nothing in `seed.js` or the UI does, as far as I can see.
2. **`PATCH /{id}` with `targetStatus` is now a silent no-op**, not a 400 — deliberate, see
   item 1.
3. **OpenAPI/Swagger now marks `BotGroupDTO.targetStatus` `readOnly: true`**, so any client
   generated from `/v3/api-docs` will drop it from its create/update models. Correct, and
   visible.
4. **`lastError` text changed for every failure that is not a `BotManagerException` or an
   `IllegalArgumentException`** — previously the raw `toString()`, now
   `Internal server error — see server logs (ClassName)`. Anyone who was reading that field with
   a script is reading something different now.

### Smaller things, for the author, not findings

- `cancelStartInFlight:649-652` calls `describe(id)` *after* `cancel(id)` has already flipped the
  flag, so the operator-facing line always ends `", cancelled"` — visible in the test output as
  `elapsed 0s, cancelled) — cancelling it`. It is impossible to tell from that line whether an
  earlier `/stop` had already cancelled the build. Pre-existing from the previous round;
  capturing the description before the cancel would fix it.
- `BotMdc.snapshot()` / `restore()` are new public API on a class with its own dedicated
  `BotMdcTest`, and I do not see them exercised there. That is QA's call, not a finding here —
  flagging it only because they are the mechanism the whole Q3 fix rests on.
- `72bbb24`, the branch tip, is not part of the fix round and is not Phase 2: it touches
  `.claude/agents/releaser.md` and adds `docs/findings/gwms-register-envelope.md`. I left it
  alone, as instructed; noting it so the compliance and release passes are not surprised by a
  commit after `d9be331`.
