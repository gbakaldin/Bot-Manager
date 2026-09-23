# QA — GATEWAY_REQUEST_BUDGET (Phase 1, observe mode)

Branch: `feature/gateway-request-budget`
Diff under test: `git diff feature/dead-group-auto-recovery..HEAD`
Dev commits `7c7c3b1`..`f3ddb28`; QA test commit `024597b`.

**Verdict:** PASS
**Build:** `mvn test` → **2,196 tests, 0 failures, 0 errors, 0 skipped** (branch tip
`024597b`, in a clean detached `git worktree`, run **three times** — identical each time).

Baselines, so the numbers are comparable:

| Tree | Tests | Failures | Errors |
|---|---|---|---|
| Dev's tip `f3ddb28`, clean worktree | 2,169 | 0 | 0 |
| QA tip `024597b`, clean worktree (×3) | **2,196** | 0 | 0 |
| Main working tree (`024597b` + the ~59 uncommitted RIK/Aviator entries) | 2,391 | 0 | 0 |

The working tree was not staged, stashed, reverted or `git add -A`'d; only the seven test
paths listed below were staged, by explicit path. Nothing was pushed.

---

## The invariant that mattered most: Phase 1 is inert

Observe mode's promise is "the window becomes observable and **nothing else changes**". I
went looking for a way to make the facade block, delay, reorder or refuse, in either mode,
and did not find one. The production reading:

- `SlidingWindowGatewayBudget` has **no waiter queues, no `Condition`, no
  `ScheduledExecutorService`, no `await`, no `sleep`, no `Future.get`** — the only
  synchronisation in the class is a `ReentrantLock` held across `prune()` + `addLast()` +
  `size()` on an `ArrayDeque`. There is nothing for a caller to park on.
- `execute` / `run` / `tryExecute` are `admit(); call()`. `runWsUpgrade` is
  `if (countWsUpgrades) admit(); upgrade.run()`. `count` is `stamp()`. `cancelScope` is one
  DEBUG line. `reserve` is an `AtomicInteger` add. **None of them reads `settings.mode()`**,
  which is exactly why enforce cannot half-enforce: there is no branch for it to take.
- The only `mode()` reader in the whole feature is `GatewayBudgetRegistry.logStartupPosture`
  (the WARN) and `Snapshot.mode()` (reporting).
- `admit()` cannot throw: `stamp()` is lock-and-arithmetic, `waitTimers.get(tier)` and
  `counter(tier, "admitted")` are pre-resolved at construction (the `IllegalArgumentException`
  arm in `counter()` is unreachable for the four pre-registered outcomes), and the DEBUG line
  is behind `isDebugEnabled()` with a null-safe `scope.describe()`.
- The call sites preserve behaviour: `Bot.connectUnderBudget` wraps the same `connect()` in a
  `Runnable`; `ApiGatewayClient.send`/`underBudget` rethrow `IOException` /
  `InterruptedException` / `RuntimeException` unwrapped, so every existing `catch` in
  `Bot`, `BotFactory` and `registerUsers` still matches by type.

That reading is now pinned by tests rather than left as a reviewer's opinion — see
`SlidingWindowGatewayBudgetObserveModeTest` below, which drives **every** entry point 1,500
deep on a frozen clock **in `ENFORCE` as well as `OBSERVE`** and asserts admission, zero
queue depth, zero `timeout`/`cancelled` counters, and exact stamp accounting under 1,600
concurrent admissions.

## No test can reach a gateway

Checked exhaustively across the diff's test files, and the answer is clean:

- `ApiGatewayClientTierTest` runs the budget in `RecordingGatewayBudget.Mode.BLOCK`, which
  records the submission and throws `Sentinel` **without invoking the recorded `Callable`** —
  so `httpClient.send(...)` and `new AuthClient(...).authenticate()` are never executed. The
  fixture host is `http://127.0.0.1:1/never-reached` as a second line of defence.
- `BotGatewayTierTest`: `VingameWebSocketClient`, `ApiGatewayClient`, `ClientFactory`,
  `TokensProvider` are all Mockito mocks; the "upgrade" is a no-op method call on a mock.
- `EnvironmentClientRegistryBudgetWiringTest`: `ApiGatewayClient` is a mock; the real
  `GameMsClient`/`ClientFactory` constructed by `createClients` allocate but never connect.
- `EnvironmentProbeSchedulerTest`: `EnvironmentWsProbe` is a mock.
- `SlidingWindowGatewayBudget*Test`, `GatewayBudgetSettingsTest`, `RequestTierTest`,
  `GatewayBudgetRegistryTest`: pure, in-process, manual clocks. No sleeps anywhere.
- `GatewayCallSiteGuardTest`: reads source files off disk. No JVM, no socket.
- Grepped every changed/added test file for non-loopback URLs: only mock stubs
  (`http://gw.test`, `https://api.example.test`, `ws://example/ws`) and
  `EnvironmentProbeSchedulerTest`'s pre-existing `wss://tipclubgw-sock.stgame.win/ws`
  constant, which is a *string handed to a mock* (see Gaps, G5).

No test reads `bot.ip`, no test constructs a live `HttpClient` against a gwms host, and the
one test that could **hang** rather than fail now carries a preemptive timeout (G1).

---

## Tests added / updated (commit `024597b`, 27 new test methods)

- `bot-engine/src/test/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudgetObserveModeTest.java`
  (new, 6 tests) — the phase's central invariant, asserted rather than assumed:
  - `noEntryPointEverRefuses` — for **both** `GatewayBudgetMode` values: 1,500 `DEFAULT`
    requests (3× the tier ceiling, 1.67× the hard cap) on a clock that never advances, then
    `execute`, `run`, `runWsUpgrade`, `tryExecute(ZERO)`, `count` and `cancelScope` all
    succeed; the window lands at exactly 1,505 (a `cancelScope` sends nothing); the snapshot
    reports the configured mode honestly.
  - `aCancelledScopeIsAdmittedInEitherMode` — the Phase 3 tripwire that actually bites (see
    G2).
  - `nothingIsEverQueued` — all three `gateway_budget_queue_depth` gauges and all
    `outcome=timeout` / `outcome=cancelled` counters stay at zero; rollup fragment reads
    `gateway=1500/900 queued=0/0/0 circuit=closed`.
  - `theWindowIsExactUnderConcurrency` — 16 virtual threads × 100 admissions: the window is
    exactly 1,600 and `requests_total{outcome=admitted}` per tier agrees with it. Exactness
    is the assertion, because a lost stamp makes the window read **low**, which is the
    direction that ends in a block while the dashboard says there is room.
  - `anOversizedReservationDoesNotThrottleAnything` (9,000 declared against a 900 cap) and
    `aNegativeReservationIsClamped` (a zero/negative `botCount` must not hand the lower tiers
    room that does not exist).
  - Class-level `@Timeout(30s)` so a future parking regression fails instead of hanging.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/gateway/GatewayBudgetRegistryTest.java`
  (new, 8 tests):
  - `forEnvironmentIsIdempotent` / `concurrentForEnvironmentProducesOneBudget` — 24
    concurrent first calls for one id resolve to **one** object (`computeIfAbsent`'s mapping
    function running twice would give each caller its own window); the key is the id alone,
    so a renamed environment keeps its window.
  - `readsDoNotCreate` — `find` and `snapshotOrEmpty` leave `size()==0` and
    `meterRegistry.getMeters()` empty. The 5-minute rollup reads every running environment;
    if reading created a budget, a log line would grow a metric set.
  - `meterNamesAndTagsArePerEnvironment` — **37 series per environment**, counted with the
    arithmetic written out (1 window gauge + 3 queue + 3 reserved + 3 timers + 12
    `requests_total` + **15 `gateway_budget_wait.histogram` SLO-bucket gauges**), every one
    tagged explicitly `{environmentId, product}` and none `bot_`-prefixed (so
    `BotMdcTagsMeterFilter` leaves them alone).
  - `environmentsDoNotShareAWindow`, `theClockIsHandedToEveryBudget`.
  - `startupPostureIsLoggedOnce` — pins the exact `Gateway budget registry started
    (mode=observe … hard-cap=900, ceilings default=500 prioritized=750 essential=900 …)`
    string that release step **V1a** greps.
  - `enforceModeWarnsUntilPhaseThree` — the WARN an `enforce` box owes its operator. This is
    review finding **F4**'s requested fix shape: Phase 3 now cannot ship without touching a
    failing assertion.
- `bot-api/src/test/java/com/vingame/bot/common/exception/GatewayBudgetExceptionTest.java`
  (new, 5 tests) — the three typed outcomes: none is an `UpstreamGatewayException` (so none
  can be mapped to **502** for a request the JVM chose not to send, which would send an
  operator to inspect a working gateway), the three `TYPE` strings are the frontend contract
  and are distinct, `retryAfter` / `cfRay` round-trip, and a null `retryAfter` or `cfRay`
  degrades to words rather than an NPE on a failure path.
- `bot-app/.../domain/botgroup/service/BotGroupBehaviorServiceRestartTest.java` (+1 test) —
  `classifyCreationFailure`'s new `"budget"` arm. **Inert until Phase 3**, which is precisely
  why it needs a test: no staging signal would reveal it wired to `"auth"`, and mislabelling
  our own pacing as an auth failure would make `EnvironmentLoginFailing` fire on a decision
  we made deliberately.
- `bot-app/.../infrastructure/probe/EnvironmentProbeSchedulerTest.java` (+4 tests) — the
  probe's budget interaction, which the diff shipped uncovered: a probe that went out is
  stamped; **every** environment sharing a `webSocketMiniUrl` is charged for the one socket
  (one probe, two stamps); a `live_sibling` short-circuit is charged **nothing** and does not
  even create a budget; no candidate → no budget and no series at all.
- `bot-app/.../infrastructure/gateway/GatewayCallSiteGuardTest.java` (+2 tests) — teeth,
  plus one new rule:
  - `theScannerHasTeeth` — a source guard's characteristic failure is passing because it
    matches nothing (one more state in that hand-rolled comment/literal stripper and every
    `isEqualTo(1)` in the file would read `0` and go green). The scanner is pointed at a
    synthetic file holding three real offending call sites and the same four spellings inside
    a line comment, a block comment and a string literal, and must see exactly the real ones;
    a prose-only file must score zero.
  - `noRequestMethodDefaultsItsTier` — `authenticate` / `getBalance` / `deposit` each have
    exactly one declaration and each takes a `RequestTier`. A tier-less overload would compile
    and would be picked up by exactly the call sites in a hurry, while every count in that
    file still read 1.
- `bot-engine/.../infrastructure/client/ApiGatewayClientTierTest.java` (+1 test, +2
  timeouts) — see G1.

## Coverage of the diff

| Production change | Covered by | What is asserted |
|---|---|---|
| `RequestTier`, `GatewayRequestScope` (`bot-api`) | `RequestTierTest` | declaration order **is** the policy (`ordinal()`); exactly three tiers; null cancel predicate; registration scope carries no group |
| `GatewayBudgetException` + 3 subclasses | **`GatewayBudgetExceptionTest`** (added) | not `UpstreamGatewayException`; distinct `TYPE`s; retry/cf-ray; null tolerance |
| `GatewayBudgetSettings` validation | `GatewayBudgetSettingsTest` | monotonic ceilings, ceiling > cap, non-positive ceiling, `hard-cap >= 1000` rejected / `> 900` warns, missing tier, unbounded wait only for ESSENTIAL, negative durations, `describeCeilings()` order |
| `GatewayBudgetMode.parse` | `GatewayBudgetSettingsTest.modeParsing` | case/whitespace-insensitive; unknown value throws; null throws |
| `SlidingWindowGatewayBudget` window arithmetic | `SlidingWindowGatewayBudgetWindowTest` | stamp per admission, expiry at **exactly** one window, all tiers share the window, `count()` stamps without touching a tier counter, failed call still stamped, exception unwrapped, reservation idempotent release, `snapshot()`/rollup fragment, all 12 counters pre-registered at **zero** |
| …its **inertness** (both modes) | **`SlidingWindowGatewayBudgetObserveModeTest`** (added) | every entry point past the cap; no queue; concurrency-exact; oversized/negative reservations |
| `GatewayBudgetRegistry` | **`GatewayBudgetRegistryTest`** (added) + `EnvironmentClientRegistryBudgetWiringTest` + `ApplicationContextLoadsTest` | per-env identity (incl. concurrent), reads don't create, meter names/tags/count, startup INFO + enforce WARN, injected clock, same bean as `EnvironmentClientRegistry` |
| `UnlimitedGatewayBudget` | `SlidingWindowGatewayBudgetWindowTest.unlimitedIsAPassThrough`, `ApiGatewayClientTierTest.theFixtureSeamFallsBackToUnlimited`, `BotGatewayTierTest.aBotWithoutABudgetStillConnects` | pass-through; `hardCap()==0` sentinel; null budget → UNLIMITED, never an NPE on a bot thread |
| `GatewayBudgetConfig` binding | `ApplicationContextLoadsTest.gatewayBudgetIsWiredAndObserveOnly` | real refresh binds five `Duration`s and equals `defaults()`; mode is `observe` |
| `EnvironmentClientRegistry` / `EnvironmentClients` wiring | `EnvironmentClientRegistryBudgetWiringTest` | client, holder and registry hold the **same** object; keyed by `environmentId`; env name + numeric product on the labels; null `productCode` tolerated |
| `BotFactory.setGatewayBudget` | `BotGatewayTierTest`, `BotFactory*WiringTest` (updated) | the bot gets the environment's budget, from the same `EnvironmentClients` |
| `ApiGatewayClient` funnel + tiers | `ApiGatewayClientTierTest`, `GatewayCallSiteGuardTest` | one `httpClient.send(` site; `authenticate`/`getBalance`/`deposit` submit the caller's tier and scope; register + update-fullname are `DEFAULT` with a **registration** scope (no `botGroupId`) |
| `Bot` tiers + `connectUnderBudget` | `BotGatewayTierTest`, `GatewayCallSiteGuardTest` | login/upgrade/first-read `ESSENTIAL`; drift read `DEFAULT`; deposit + confirming read `PRIORITIZED`; `restart()` upgrade `DEFAULT`; reconnect re-auth + upgrade `PRIORITIZED`; exactly one `.connect()` site in `Bot` |
| `EnvironmentProbeScheduler.count("ws-probe")` | **`EnvironmentProbeSchedulerTest`** (+4, added) | charged per environment on a shared URL; nothing charged for `live_sibling`; nothing created with no candidate |
| `FleetRollupLogger` `gateway=` fragment | `FleetRollupLoggerTest` (updated) | the live window on the env line; the zeroed fragment when no budget exists (reading must not create one) |
| `classifyCreationFailure` `"budget"` | **`BotGroupBehaviorServiceRestartTest`** (+1, added) | typed arm wins over the message heuristic |
| `GatewayBudgetNearCap` + Grafana panel | `AlertRuleMetricsTest` (updated), `AlertRulesAudienceTest`, `MetricKeyDashboardParityTest` | `gateway_budget_window_requests{environmentId,…}` is in the real Prometheus exposition; `audience: internal`; dashboard JSON parses and the panel exists |
| `docker-compose.yml` `BOT_GATEWAY_BUDGET_MODE` | not covered by a test | compose is not under test in this repo; the bound default **is** (`ApplicationContextLoadsTest`) |

Two review findings are closed by this commit: **F1** (the invariant test is now on the
branch, so the Releaser builds *with* it) and **F4**'s fix shape (the enforce WARN is now
pinned by an assertion Phase 3 must update).

---

## Findings

### FAIL-worthy: none.

Nothing in the diff fails a test, and I could not construct a way for observe mode to change
fleet behaviour. Everything below is either a scope/hand-off issue for the Releaser or a
coverage note.

### G1 — a pre-existing hang, and why the workaround is sound but was not enough

`ApiGatewayClient.registerUsers` does `new Semaphore(registrationParallelism)` and then
`acquire()` on every registration thread. `registrationParallelism` is a `@Value` field, so a
client built with `new` (any test, any future ad-hoc tool) carries **0**, and
`Semaphore(0).acquire()` parks every thread forever. That is a **hang, not a failure** — the
worst shape a defect can have in a build.

Dev's `ReflectionTestUtils.setField(client, "registrationParallelism", 2)` is the right
workaround and is honest about itself in a comment: it sets the field to what the container
would set, changes no production code, and is confined to the one test that exercises
`registerUsers`. I kept it. What was missing is that a plain `@Timeout` **cannot rescue it**:
JUnit's default `ThreadMode.SAME_THREAD` can only report a timeout *after* the test method
returns, and a parked semaphore never returns. So:

- added `@Timeout(30s, threadMode = SEPARATE_THREAD)` on
  `registerUsersIsDefaultWithARegistrationScope` — this one aborts;
- added a class-level `@Timeout(60s)` for the rest;
- added `registrationParallelismIsZeroOutsideSpring`, which pins the precondition so the trap
  is a failing assertion rather than folklore in a `setUp()` comment. If the field ever gains
  a sane default, that test fails and points at the reflection line to delete.

**Not fixed, and deliberately so:** the fix belongs in production code (a floor of
`Math.max(1, registrationParallelism)`, or making it a constructor parameter), which QA does
not touch. Recommend filing it in `FOLLOWUPS.md`. It is not reachable in production —
`EnvironmentClientRegistry` always resolves the bean from the context.

### G2 — the Phase 3 tripwire Dev flagged does **not** bite; the one I added does

`SlidingWindowGatewayBudgetWindowTest.aCancelledScopeIsStillAdmittedInPhaseOne` is clearly
*labelled* as a tripwire (the method name, the DisplayName "…but only from Phase 3", and a
comment saying the assertion will have to change). But it builds its budget from
`GatewayBudgetSettings.defaults()`, i.e. **`mode=OBSERVE`** — and the plan's Phase 3 change
list says `observe` keeps Phase 1 behaviour *byte-for-byte*. So when enforcement lands, that
test will keep passing, and the assertion it was written to force a look at will not be
looked at.

`SlidingWindowGatewayBudgetObserveModeTest.aCancelledScopeIsAdmittedInEitherMode` builds the
budget in **`ENFORCE`** and asserts that a cancelled scope is admitted and stamped. Under
Phase 3 that is precisely the case that must throw `GatewayRequestCancelledException` and
stamp nothing, so the test fails twice over — once on the unexpected exception, once on
`windowRequests()==1`. Both tests carry a comment saying they must be **rewritten, not
deleted**, when Phase 3 lands.

### G3 — an unrelated RIK behaviour change is committed on this branch, and its test is not

Commit `888d890` carries two hunks that are not Phase 1 (same finding as review **F6**, noted
here because it changes the QA arithmetic):

1. `ApiGatewayClient.isDisplayNameTaken(status)` now also returns true for `"EXISTED"`, so a
   RIK/P_114 display-name collision returns `false` and re-rolls instead of throwing out of
   `setDisplayNameWithRetry`'s loop. A genuine fix — and a **behaviour change on every brand**,
   not just RIK.
2. The same hunk demotes `log.warn("Display name '{}' is already taken")` to `log.debug`,
   removing the only default-visible signal of a collision storm mid-registration (staging
   runs at DEBUG; prod does not).

The **test for it is not on this branch.**
`bot-engine/src/test/java/com/vingame/bot/infrastructure/client/ApiGatewayClientDisplayNameTakenStatusTest.java`
(8 tests) is `??` untracked, part of the RIK work, and green in the working tree but absent
from the 2,196. So if this branch ships alone, that production change ships **uncovered**.

I did not add a duplicate test: doing so would quietly legitimise the scope leak, and the
right resolutions are (a) restore both hunks to the pre-RIK base as `831e311`/`f3ddb28` did
for their siblings, or (b) commit the RIK test alongside them and say so in the release notes.
That is an Architect/Dev call, not a QA one. **Releaser: either way, this branch changes
display-name retry behaviour on every brand.**

### G4 — `count()` is outside the `requests_total` accounting (review F2/F3, QA angle)

`count("ws-probe")` stamps the window and increments no `requests_total` series, so
`sum(gateway_budget_requests_total{env=E}) < gateway_budget_window_requests{env=E}` by the
number of probes, permanently. I **pinned today's behaviour** rather than the target
(`SlidingWindowGatewayBudgetWindowTest.countStampsTheWindow` already asserts no tier counter
moves, and my probe tests assert the window does), because the class javadoc claims the two
"must agree" and they structurally cannot. Whoever resolves F3 — a fifth bounded outcome
`counted`, or a narrowed javadoc — will have to touch those assertions, which is the point.
Magnitude today: ≤5 stamps per window, only while a DEAD group is a recovery candidate.

### G5 — a real staging hostname in a test constant (pre-existing, not from this diff)

`EnvironmentProbeSchedulerTest.URL = "wss://tipclubgw-sock.stgame.win/ws"` predates this
branch (DEAD_GROUP_AUTO_RECOVERY) and is only ever handed to a **mocked** `EnvironmentWsProbe`,
so nothing connects — my four added tests use the same mock. It is worth one line here because
the hard rule for this feature is "no test ever touches a real host": if that mock is ever
replaced with the real probe (e.g. when the Phase 4 `StubGateway` arrives), this constant
becomes a live socket to staging. A `127.0.0.1` constant would cost nothing. Not changed, to
keep this commit scoped to the diff under test.

### G6 — not covered, and why

- **`mode=enforce` end-to-end.** Only the negative ("it does not enforce") is testable now;
  Phase 3 owns `SlidingWindowGatewayBudgetAdmissionTest` and friends.
- **`reserve`'s `scope` consumption** (AD-7). `TrackedReservation` logs the scope and drops
  it, so there is no consumption key to test yet (review F9). I covered the accounting and
  the clamping only.
- **Circuit breaker.** `circuitOpen` is a hardcoded `false`; asserted as such so the rollup
  fragment's shape is stable from day one. Phase 4 owns the state machine.
- **`docker-compose.yml`, `secrets.env` and the promtail/Loki path.** Not unit-testable here;
  the bound default is covered by `ApplicationContextLoadsTest`.
- **Real `Retry-After` / 429 / 503 REST mapping.** No handler arm exists yet (the three
  exceptions currently fall to `handleAny` → 500). Nothing throws them in Phase 1; Phase 3
  owns `RestExceptionHandlerTest`.
- **Grafana panel reference lines** (`900`/`750`/`500` as literals) and the alert's `800` —
  copies four and five of numbers that `GatewayBudgetSettings.defaults()` exists to keep
  single. Review F5 owns the fix shape (export a `gateway_budget_ceiling{tier}` gauge); a test
  is only worth writing once there is a metric to compare against.
- **Lock contention on `admit()`** at 3k bots. The critical section is a prune + append on an
  `ArrayDeque`; `theWindowIsExactUnderConcurrency` shows correctness at 1,600 concurrent
  admissions, not throughput at 3,000. Load behaviour is V3b's job on staging, not a unit
  test's.

## Flake hunt

- `RecordingGatewayBudget`'s `CopyOnWriteArrayList` fix is correct and necessary
  (`registerUsers` fans out across virtual threads under a semaphore, so all three of its
  lists are written concurrently). `submissions()` / `counted()` / `cancelledScopes()` return
  `List.copyOf`, so an assertion cannot see a torn read.
- Swept every other collection in the changed test files for the same class of defect: all
  three `CapturingAppender`s written from non-test threads
  (`BotGroupBehaviorServiceRestartTest`, `EnvironmentProbeSchedulerTest`,
  `FleetRollupLoggerTest`) already back onto `CopyOnWriteArrayList`;
  `PerBotInitLogLevelTest`'s and my own plain `ArrayList`s are single-threaded;
  `BotReconnectMdcTest` hands its cross-thread value over a `CompletableFuture`. My
  concurrent tests use `CopyOnWriteArrayList` / `CopyOnWriteArraySet` and bounded
  `CountDownLatch.await`, never a sleep.
- **Three consecutive clean full runs** on `024597b`, plus one on the dirty working tree.
  No test ordering dependence observed (`ApiGatewayClientTierTest` calls `budget.clear()`
  between phases; `GatewayBudgetRegistryTest` builds a fresh `SimpleMeterRegistry` per test).
- Longest new test: 1.5 s (`ApiGatewayClientTierTest`, from `getBalance`'s two pre-existing
  `Thread.sleep(500)`s). Everything else is milliseconds.

## Failures

None.
