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
