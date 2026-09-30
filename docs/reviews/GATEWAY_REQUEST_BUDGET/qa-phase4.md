# QA — GATEWAY_REQUEST_BUDGET Phase 4 (asynchronous account registration)

**Verdict:** PASS
**Build:** `mvn test` → **2,422 tests, 0 failures, 0 errors, 0 skipped** (566 test classes)

Scope: `5e46af5` (Phase 3 fix round, F1–F16 — never previously QA'd or reviewed),
`13b0084` (Phase 4), `636a2b5` (A30). Diff base `7326eb7`.

---

## The build number, and why it is not dev's

Dev reported **2,596**. The committed tip measures **2,409** before my additions and
**2,422** after.

The 187-test gap is **not a discrepancy in the branch — it is the uncommitted RIK/ZicZac
working tree**. `git status` at the tip carries ~20 untracked test files
(`BettingMiniGameBotRikDispatchTest`, `ZicZacBetTest`, `RikStockCommitTest`, the capture
fidelity tests, `GameRequestFactoryCapabilityTest`, …) plus untracked production sources
under `bot-messages/.../g3/rik/`. Counting `@Test`/`@ParameterizedTest` in those files
gives **183**, which with parameterised expansion is the gap.

So dev measured the working tree, not the three commits under review. I measured the
commits, in a detached `git worktree` at `636a2b5`, which is the only number a releaser
can act on. **MEMORY's "uncommitted-files provenance hazard that put an unvalidated build
on prod" is the same shape**; worth naming even though nothing here is wrong with the
branch itself.

- Committed tip, no additions: **2,409 / 0 / 0**
- Committed tip + this QA round's tests: **2,422 / 0 / 0**

---

## Hard rule: no test touches a real gateway — VERIFIED

Dev's claim is that the only tests making real HTTP calls are
`ApiGatewayClientRegisterOneTest` and the pre-existing `GatewayBudgetEscalationIT`, both
against `StubGateway` on loopback. Checked specifically, four ways:

1. **`StubGateway` cannot name a non-loopback host.** It binds
   `new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)` and `baseUrl()` returns a
   literal `"http://127.0.0.1:" + port`. There is no constructor, setter or property that
   accepts a host.
2. **Only those two classes reference it** (`grep -rln StubGateway`), and both configure
   `ApiGatewayClient.init(gateway.baseUrl(), …)`.
3. **Every other `ApiGatewayClient.init(...)` in test sources** takes either
   `http://127.0.0.1:1/never-reached` (`ApiGatewayClientTierTest`,
   `ApiGatewayClientBudgetPassthroughTest`) or `https://api.example.test`
   (`ApiGatewayClientSetDisplayNameWithRetryTest`). The latter is an RFC 2606 reserved TLD
   that cannot resolve, *and* every `setDisplayName` in that class is stubbed through a
   `Mockito.spy`, so no request is built at all. The remaining `.init(` hits are
   unrelated (`factory.init()`, `service.init()`) or Mockito verifications.
4. **No test resource sets a gateway URL.** `find */src/test/resources -name "application*.properties"`
   matches nothing that carries `url`/`gateway`/`bot.ip`; `bot.ip` is injected by
   `ReflectionTestUtils` as `127.0.0.1` in both stub-driven classes.

The one host-shaped string I chased down and cleared: `https://118.stgame.win` in
`EnvironmentServiceTest` is an `Origin` **header value** placed in a map and asserted on,
never a request target. `https://api.viptalk.org` in `VipTalkClientTest` goes to an
injected sender lambda that returns a canned `RawResponse`.

**No path could reach a real host — including via a config default, a leaked environment
property, or an assembled URL.** No blocker.

---

## Tests added / updated

All five are new files; no existing test was modified. Every one was
**mutation-verified** — the production fix was temporarily reverted, the test was confirmed
to fail, and the production file was restored (`git diff --stat` clean).

| File | Covers | Mutation result |
|---|---|---|
| `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/RegistrationWorkerCancellationScopeTest.java` | A28.6's **parked-inside-the-budget** half of cancellation: the scope predicate is live, not a snapshot; `cancelScope` is delivered; the loop stops at the next account; `cancel` on a budget-less environment does not conjure one | Snapshotted predicate → **fails**; all 15 shipped `RegistrationWorkerTest` tests **pass** |
| `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/RegistrationMetricsTagIdentityTest.java` | A28.7/A30.3: exactly **one** series per outcome, carrying the group MDC's `botGroupId`/`environmentId`/`product`; a tag *value* substituted, never a *key* dropped | `initRegistrationSeries` with empty tags → **fails** |
| `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/RegistrationConcurrentPatchTest.java` | A30.5: the worker writes only its own five fields (so a concurrent `botCount` raise is not reverted); **and characterises the completion-write defect below** | n/a (see Gaps/F-1) |
| `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/PerAccountRegistrationInfoLogGuardTest.java` | The tier rule against the source, for the **per-account** rate: `registerOne` / `setDisplayName` / `setDisplayNameWithRetry` contain no `log.info`/`warn`/`error`; the three demoted messages stay demoted; `RegistrationWorker`'s INFO budget is 5 and none names a username | `log.info` in `registerOne` → **fails**; `PerBotInfoLogGuardTest` **passes** (4/4) |
| `bot-engine/src/test/java/com/vingame/bot/infrastructure/client/BoundedLoginClientReleaseTest.java` | Phase 3 **F6**: the JDK `HttpClient` is released on the **success** and **library-failure** paths, not only the timeout | `if (false && settled)` → **2 of 3 fail** |

### On the one I discarded

I wrote and then **deleted** a test for F3/F4 (`SlidingWindowGatewayBudgetAdmissionPassIsolationTest`)
because its premise was wrong — see Gaps/G-3. Shipping it would have been a test that
fails for a reason unrelated to the finding it named.

### Notes on the two that need them

- **`BoundedLoginClientReleaseTest` is guarded against vacuity by construction.** It counts
  platform threads matching `^HttpClient-\d+-SelectorManager$`, which is only worth
  anything if the name is right, so `theProbeCanSeeALiveLoginsHttpClient` runs first and
  asserts the count **rises** while a login is genuinely in flight. Without it, a typo in
  the pattern would make both release assertions read zero and pass for the worst possible
  reason. I confirmed the thread name and `shutdownNow()`'s prompt exit empirically on the
  project JDK before writing it.
- **`RegistrationConcurrentPatchTest.aMidPassBotCountRaiseIsErased` pins a defect on
  purpose** and says so in its javadoc and its failure message. It will fail when the
  defect is fixed, and it is the place to record the new behaviour, not the place to
  delete. Green build, undeniable defect, fixer reads the note.

---

## The A30 claim that the plan was factually wrong — CONFIRMED

This was the highest-value item and it holds, at the source, not just in the test.

**A17.3/A28.3 budgeted a resumed index at three DEFAULT requests
(`register + login + update-fullname`). A30 says two. A30 is right, and is in fact
conservative.**

1. **The parameter was genuinely never read.** `git show 7326eb7:…/ApiGatewayClient.java`
   gives the pre-Phase-4 signature `setDisplayName(String username, String sessionToken,
   String displayName)`, and the body references `sessionToken` **nowhere**: the request is
   built with `.header(SESSION_TOKEN_HEADER, xToken)` — the per-environment admin `X-TOKEN` —
   and identifies the account by `Map.of("username", username, "fullname", displayName)`.
   This is measured from the old source, not inferred from the new javadoc, and it is the
   independent confirmation the claim needed.
2. **`resumingDoesNotLogIn` proves what it claims, and cannot pass vacuously.** The
   `isZero()` on `countFor("login.aspx")` would indeed pass against an unconfigured stub —
   but `StubGateway.handle` routes **everything it does not recognise** to
   `loginResponse()` and stamps it first, so a login *would* be counted. And the test
   pins `totalReceived() == 3` alongside it, which is an exact total: any fourth request,
   login or otherwise, fails it. It also deliberately does **not** call `gateway.reset()`,
   with a comment explaining that resetting would turn the resume into a fresh
   registration and test nothing. This is a well-built test.
3. **No other caller depended on the parameter.** A repo-wide grep for
   `setDisplayName|setDisplayNameWithRetry|registerUsers|UserRegistrationResult|user.registration.parallelism`
   across `*.java`/`*.properties`/`*.yml`/`*.sh` finds production callers only in
   `RegistrationWorker` (both on the new signatures) and no surviving reference to the
   deleted bulk method or its DTO outside prose. There is no rename-and-forget.
4. **The worker actually spends fewer than two on the common resume.** `registerOne`'s
   `$set` of `registeredCount` lands *before* naming, so the ordinary interrupted state is
   `registered = k, named = k-1`, `index = k`, `registered < index` is false — no
   re-register at all, **one** request. Two is the worst case (crash between the gateway
   creating the account and the persist landing), where the re-register answers `EXISTED`.
   Never three. The arithmetic in A30 is safe in the conservative direction.

---

## Coverage of the diff

| Production file | Test file(s) | What is covered |
|---|---|---|
| `RegistrationWorker.java` (new, 581 ln) | `RegistrationWorkerTest` (15), + `RegistrationWorkerCancellationScopeTest`, `RegistrationMetricsTagIdentityTest`, `RegistrationConcurrentPatchTest` (QA) | in-order registration, resume from the high-water mark, `EXISTED`, budget-refusal-costs-nothing, gateway-refusal-spends-budget, backoff, serialisation, naming half, no-pool completion, observe pacing, gauges, series pre-registration; **+ live cancel predicate, tag identity, field-scoped persistence** |
| `ApiGatewayClient.registerOne` / `setDisplayName*` | `ApiGatewayClientRegisterOneTest` (4, real loopback HTTP), `ApiGatewayClientTierTest`, `ApiGatewayClientSetDisplayNameWithRetryTest`, `ApiGatewayClientDisplayNameTakenStatusTest`, + `PerAccountRegistrationInfoLogGuardTest` (QA) | body-based `EXISTED` classification at HTTP 200, fail-closed on an unrecognised envelope, DEFAULT tier + caller's scope verbatim, retry/collision, batch-deadline propagation, **+ the per-account tier rule** |
| `BotGroupService` (save/update/retry/delete) | `BotGroupServiceTest` (incl. `deleteCancelsRegistrationFirst` with `InOrder`), `RegistrationStartGuardTest`, `CascadeDeleteChainTest` | async create shape, A2.7 raise → PENDING, retry 400 on a non-FAILED group, **cancel-before-lock ordering** |
| `BotGroupMapper` / `BotGroupDTO` | `BotGroupRegistrationRenderingTest` (6) | render-only in both directions, by value |
| `GatewayBudget.observeModePacing` (4 impls) | `GatewayBudgetObserveModePacingTest`, `GatewayBudgetSettingsTest` | 600 ms under observe, ZERO under enforce, 1 s variant, `UNLIMITED` |
| `Bot.sessionBudgetWait` (F1) | `BotSessionBudgetWaitTest` (4) | the invariant against the **shipped** `bot.watchdog.timeout.seconds` read from `application.properties` |
| `BoundedLogin` (F6) | `BoundedLoginTest` (4), + `BoundedLoginClientReleaseTest` (QA, 3) | bound, abort, token passthrough, exception type, **+ release on success and on library failure** |
| `SlidingWindowGatewayBudget` (F3, F4, F9–F12) | — | **nothing new.** See G-3 |
| `prometheus/alerts.yml`, `ObservabilityConfig` | `AlertRuleMetricsTest`, `AlertmanagerRoutingTest` | rule/series agreement |

### The four focus items I was asked to judge, beyond A30

**Cancellation (item 2) — correct, and the shipped test was weaker than it looked.**
`BotGroupService.delete` reads the group, calls `registrationWorker.cancel(id, envId)`, and
only then `behaviorService.stopAndLogout(id)`; `BotGroupServiceTest.deleteCancelsRegistrationFirst`
pins it with `InOrder`. On the live-deadlock worry: `cancel` takes the **budget** lock and
`stopAndLogout` takes the **group** lock, the worker thread never takes the group lock, and
the scope predicate is a non-blocking `ConcurrentHashMap.newKeySet().contains`. No cycle.

The gap was the other half. `RegistrationWorkerTest.cancelStopsTheLoopAndWakesTheBudget`
calls `cancel` *before* the tick, so its `verify(budget).cancelScope(GROUP)` is discharged
by the direct call and its `never()).registerOne(...)` only proves a pre-cancelled group is
not started. **Proof it was weak: with the scope's predicate replaced by a snapshot
(`() -> snapshot`), all 15 shipped tests still pass.** My new test fails. That is the
difference between a waiter that comes off in milliseconds and one that waits out fifteen
minutes of `registration.max-wait`.

**Render-only fields (item 3) — correct, and the test is value-level.**
`@JsonProperty(access = READ_ONLY)` on `targetStatus`, `registeredCount`, `namedCount`,
`registrationError`; absent from both `BotGroupMapper` write paths.
`BotGroupRegistrationRenderingTest.neitherWritePathCarriesRegistrationState` builds a
hostile DTO (`registeredCount=500`) and asserts on **values** through both the Lombok
builder path (`toEntity`) and the merge path (`updateEntityFromDTO` — that a persisted 63
is still 63), and `theDtoFieldsAreReadOnly` round-trips a hostile JSON body through a real
`ObjectMapper` in both directions. Not a presence check.

**Counter pre-registration (item 4) — correct; dev's gauge call is right.**
`initRegistrationSeries` and `incRegistrationAccount` both take their tags from the same
`BotMetrics.mdcTags()`, and `initRegistrationSeries` is called at the top of every pass
*inside* the `BotMdc.setGroupContext` scope, with `exists` present from day one.
`registration_failed_groups` as a **gauge** is right and A30.3's argument is sound — the two
gauges are registered by `ObservabilityConfig.registrationGauges` at context refresh, so
they exist from boot and have no first-sample problem, and "a group is half-registered
*right now*" is the thing `RegistrationStalled` should read.

One correction to my own initial read, for the record: I judged
`RegistrationWorkerTest.outcomeSeriesArePreRegistered` to be vacuous because
`find(name).tag("outcome", …).counter()` matches on `outcome` alone. It is **weaker** than
it appears but not vacuous — mutating `initRegistrationSeries` to `Tags.empty()` does fail
it, because two matching series per outcome make `Search.counter()` return an arbitrary
one. That is teeth by accident rather than by construction, which is why the explicit
"exactly three series, and here are their tag values" assertion is worth having.

**Concurrency (item 5) — the documented race is fine; an undocumented one is not.** See F-1.

**Logging tiers (item 6) — verified against the source.** All five `log.info` sites in
`RegistrationWorker` are process-scoped (worker started, shutting down, boot resume) or
group-scoped (cancelled at n/m, complete n/m); none names a username. The two per-user
WARNs dev claims to have demoted are demoted — `"No display names available…"` and
`"Failed to get random display name on attempt"` are both `log.debug` in the diff. The
per-account WARN that remains (`"Could not set a display name for {} after {} attempts"`,
`RegistrationWorker:392`) is a WARN, not INFO, so it is outside the rule CLAUDE.md states,
and its javadoc justifies it (a nameless account stalls a RIK ziczac room). It only fires
when all five names collide. Noted, not a finding. **`PerBotInfoLogGuardTest` did not cover
any of this** — it passes with a `log.info` inside `registerOne` — which is why I added the
per-account guard.

**Phase 3 fix round (item 7).** F1 is well covered:
`BotSessionBudgetWaitTest.theBoundIsBelowTheShippedWatchdog` parses
`bot.watchdog.timeout.seconds` out of the shipped `application.properties` and asserts
`sessionBudgetWait() < watchdog`, and `theBoundTracksTheWatchdog` asserts it across
2/20/60/180/600/3600 s so the bound is derived rather than a literal. All three session-path
call sites in `Bot.java` pass `sessionBudgetWait()`; the fourth (`RequestTier.ESSENTIAL`,
`Bot.java:658`) is the first balance read, explicitly and correctly exempted — it runs in
`onStart` before any scenario is installed, so it cannot reach a message-processor thread.
F6 is now covered by the new test. F16 / the QA flake warning: resolved — see below.

**The previous round's QA flake warning is fixed.** `GatewayBudgetEscalationIT`'s
`observedMax <= HARD_CAP + threads` (84 against a measured 79–84, i.e. zero headroom) is
now `HARD_CAP + 2 * threads`, with a comment that correctly re-derives the bound as
admission rate × latency jitter rather than as the two concurrency semaphores, and that
withdraws the stale attribution of 84 to the stale-clock defect. The exact assertion on the
budget's own window keeps no allowance at all. Good fix.

---

## Gaps

### F-1 — DEFECT (found, characterised, not fixed): a `botCount` raise that lands mid-pass is erased

A30.5 documents the worker↔PATCH race as self-correcting: *"the narrow race that remains is
the other way round (a PATCH's read-modify-write reverting a counter advanced in the
millisecond between) and is self-correcting: the re-registered index answers `EXISTED` and
costs one request."* That is true **of the counter** and not of the **completion decision**.

`RegistrationWorker.register` captures `int target = group.getBotCount()` once, before the
loop, from a document it may hold for the whole job. `recordCompletion` then
unconditionally `$set`s `registrationState: null` when the loop reaches that stale target.
So:

1. worker picks up a group with `botCount = 10`, starts the loop with `target = 10`;
2. at index 5 an operator PATCHes `botCount` to 20 — `BotGroupService.update` sets
   `registrationState = PENDING` (already PENDING) and persists `botCount = 20`;
3. the worker reaches 10, calls `recordCompletion`, clears `registrationState`;
4. the group is now `botCount = 20`, `registeredCount = 10`, **not PENDING** — so no tick
   ever selects it again. The ten extra accounts are never created, the group reports
   complete, and the bots built on the missing indices fail to authenticate at start.

**This is not a millisecond window — it is the entire duration of a registration pass**,
which for a 500-account group under the budget is hours. It contradicts A2.7's claim that
raising `botCount` is how more accounts are asked for, exactly when the feature is under
load. It presents as an auth outage rather than as a create that lied, which is the
specific misdiagnosis `BotGroupRegistrationRenderingTest` was written to prevent by the
other door.

It is **recoverable** (PATCH `botCount` again after completion and the group goes PENDING),
which is why I am not calling this a FAIL. Pinned as
`RegistrationConcurrentPatchTest.aMidPassBotCountRaiseIsErased`, which asserts the current
behaviour and names itself a defect.

**Suggested fix (Dev's call, not mine):** make the completion write conditional — add
`Criteria.where("registeredCount").gte(…)` / `botCount` to `recordCompletion`'s query so it
only clears the state for a document that is genuinely complete; or re-read `botCount`
inside the loop. The first is one line and cannot regress the counter behaviour.

### G-1 — the legacy group-less registration scope is still constructible

`GatewayRequestScope.registration(String name)` (no group, `NEVER_CANCELLED`) has no
production caller since Phase 4 and is deliberately retained with a javadoc saying a
group-less call site is now "a deliberate statement". Nothing enforces that. A Phase 5
caller that reaches for the shorter overload gets a silently uncancellable registration —
the exact defect A28.6 fixed. Cheap to close with a one-line `GatewayCallSiteGuardTest`
entry (`registration(` with a single argument is zero outside the record itself); not worth
blocking on.

### G-2 — no test drives the whole worker through a real HTTP exchange

`RegistrationWorkerTest` mocks `ApiGatewayClient` and `ApiGatewayClientRegisterOneTest`
drives `registerOne` against `StubGateway`, but nothing joins them — nothing asserts the
worker's *resume* path (`registered = k, named = k-1` → update-fullname only, no
re-register) against real envelopes. The seam exists (`StubGateway` is in
`bot-engine/src/test`, the worker is in `bot-app`), so this would need the stub promoted to
a shared test fixture. Deferred: the two halves are each covered, and the join is a
Phase 5 / integration-harness item.

### G-3 — Phase 3's F3, F4 and F9–F12 are untested, and F3/F4 are not testable as written

`5e46af5` added 256 lines to `SlidingWindowGatewayBudget` with no new budget test. I tried
to close F3 and F4 — the two whose failure mode is a waiter *in no queue and on no timer*,
i.e. an unbounded ESSENTIAL waiter holding the group lock for the life of the JVM with
nothing in the log — and concluded **there is no external seam**:

- The only foreign code the pass calls is `head.scope.isCancelled()`. Making it throw does
  not work as a trigger, because `admit` consults the predicate on the **arrival** path too
  (`SlidingWindowGatewayBudget:507`), so the waiter never queues.
- Making it throw only *after* queueing does get into the pass, but
  `admitWhileRoomLocked` calls `isCancelled()` on the head **before dequeuing it**
  (`:693`), and the tier loop visits ESSENTIAL first. So a throwing head wedges every
  subsequent pass regardless of F3/F4: F4's `finally` faithfully re-arms the timer, and the
  next pass throws at the same head. **There is no observable "the healthy waiter still
  gets through".**
- F3's deferred actions are `CompletableFuture.complete`/`completeExceptionally` calls,
  whose dependents' exceptions are captured by the future rather than propagated to the
  completing thread, and `log` calls. Neither can be made to throw from outside.

So F3 and F4 are **defensive coding without an observable contract**. They are almost
certainly the right change and I am not asking for them to be reverted. What I am
recording is that "F3/F4 fixed" currently rests on reading the diff, and that F4's stated
benefit — *"every queued waiter waits out its max-wait for room that exists"* — is narrower
than the comment implies: it holds for a throw from `prune` or
`expireStaleReservationsLocked`, where a later pass would succeed, and **not** for a throw
from the head's predicate, where the re-armed timer only produces a silent permanent retry
loop that never drains. Worth a sentence on the comment, and worth a seam
(a package-private `admitOnce()` returning the pass's outcome) if Phase 5 wants them
pinned. Flagging rather than fixing, because adding that seam is a production change.

### G-4 — `A30.7`'s Open Item 14 assertion is load-bearing and lightly worded

`RegistrationStartGuardTest.aGamelessGroupRegistersAndNeverStarts` is what A30.7 leans on
to say the account-factory follow-up is "a change to `BotGroupDTO.gameId`'s `@NotBlank` and
nothing else on this path". The test does assert both halves (registers normally; refused a
start with the existing 400). No gap in the test — just noting that the *claim* is broader
than the test, since the test covers the start path only and the follow-up would also touch
create-time validation.

---

## Failures

None. `mvn test` at `636a2b5` plus this round's five test files:
**2,422 tests, 0 failures, 0 errors, 0 skipped.**

Verified in a clean detached `git worktree` at the committed tip, so the number excludes
the uncommitted RIK/ZicZac working-tree changes.
