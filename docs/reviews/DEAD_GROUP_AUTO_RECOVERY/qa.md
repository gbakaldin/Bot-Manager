# QA — DEAD_GROUP_AUTO_RECOVERY

**Verdict:** PASS — for what this branch actually ships, which is Phases 1–3 **inert**
(`bot.recovery.enabled=false`). Two findings below are **blocking for Phase 4**
(the staging enable) and must be resolved or consciously accepted before the flag
is flipped anywhere. Nothing here blocks landing the branch.

**Build:** `mvn test` → 1224 tests, 0 failures, 0 errors (baseline before my
additions: 1208 / 0 / 0, so +16 and still green).

---

## Tests added / updated

- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/RecoveryCandidateSelectorTest.java`
  — **new file, 7 tests.** The shared AD-3/AD-4 join had no direct cover: it was
  only exercised transitively through the two schedulers, so a defect in it was a
  defect in both at once with no test naming it. Covers the persisted-DEAD query,
  the memory-only-DEAD union, the ACTIVE-runtime override, a dead-runtime id whose
  Mongo row is gone, non-duplication across the union, and **F1** below.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/model/RecoveryEligibilityTest.java`
  — +3: the `STOPPED` + DEAD-runtime disjunction gap (**F1**), the MANUAL_OFF
  counterpart that shows the gap is scoped to legacy / `MANUAL_ON` groups, and
  condition-3 ordering (an ACTIVE runtime wins even when `runtimeGroupDead` is set).
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/DeadGroupRecoverySchedulerTest.java`
  — +4: backoff index clamping (`backoff[min(n-1, len-1)]` against a table shorter
  than the budget), the earliest-due tie-break in both of its forms, and **F2**
  below.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceRestartTest.java`
  — +2: `stop()` on an unknown id now propagates `ResourceNotFoundException`
  (the Phase 2 behaviour change), and `startForRecovery()` refusing a `MANUAL_OFF`
  group under the lock (only the `STOPPED` opt-out was covered).

Three of these are marked **`QA FINDING`** in their javadoc and pin *current*
behaviour that contradicts a stated architecture decision, so that any future fix
has to change the assertion deliberately rather than silently.

---

## The four invariants I was asked to weigh

### 1. The money invariant — **holds, and the registration half is genuinely proven**

Dev's `startForRecovery_neverRegistersOrDeposits` reasons that
`BotGroupService.save`'s registration branch is unreachable because it is gated on
`isNewGroup`. **I verified that reasoning independently and it holds:**

- `registerUsers` has exactly **one** call site in the whole of `src/main`
  (`BotGroupService.java:164`), inside `if (isNewGroup)`.
- `isNewGroup = (botGroup.getId() == null || botGroup.getId().isEmpty())`.
- The test captures **every** `save(...)` argument on the recovery path and asserts
  `getId()` equals `"g-1"` — neither null nor empty. The branch is therefore
  provably not taken.

So it is a real structural proof, not a proxy. Two caveats on the rest of it:

- **The log-scan assertion is close to vacuous.** `captureBehaviorServiceLogs`
  attaches only to `BotGroupBehaviorService`'s logger, so a registration line from
  `BotGroupService` or a deposit line from `Bot` could never have appeared in it
  even if one had fired. It proves nothing about registration or deposits; the
  captor assertion above is what carries the weight.
- **"Never deposits" is true of the recovery code and false of the recovered
  group.** `startLocked` builds `BotBehaviorConfig` with
  `autoDepositEnabled(group.isAutoDepositEnabled())`, and a bot with auto-deposit on
  tops up from `BettingMiniGameBot` / `SlotMachineBot` once its balance falls below
  minimum. That is **identical to a manual `/restart`** and is the right standard —
  the plan's V9 states it correctly. But `startForRecovery`'s javadoc says flatly
  "never deposits", and someone will read that as "recovery cannot move money".
  Suggest softening it to "adds no deposit a manual restart would not make".

**Never recreates the DB group:** confirmed — every save on the path is an update of
an existing id, and `BotGroupService.save` only mints a UUID in the `isNewGroup` arm.

### 2. AD-5 opt-out — **not absolute. Dev's reachability claim is narrowly right but not complete.**

Dev escalated that AD-3 condition 2 is a disjunction
(`persistedTarget != DEAD && !runtimeGroupDead` → reject), so a persisted `STOPPED`
row with a lingering DEAD in-memory runtime passes eligibility. **Confirmed, and
confirmed end to end**: it passes `RecoveryEligibility`, it survives
`RecoveryCandidateSelector.select`, and `startForRecovery` re-asserts the *same*
predicate under the lock, so the re-assert does not catch it either. There is no
second gate.

I verified the reachability claim myself.

**Dev is right that a real Stop cannot produce it.** `stop()` takes the group lock,
and on the with-runtime path `teardownRuntimeMemory` removes the entry from
`runningGroups` **before** the `STOPPED` write. So the pair "persisted STOPPED +
runtime in the map" is not producible by `POST /stop`. `stopAndLogout` also removes
the runtime and never writes `STOPPED`. `restart()` is `stop()` then `start()`, and
the only DEAD runtime `start()` can leave is the zero-bot guard's, which writes
`DEAD`.

**But `PATCH` is not the only other path.** `startLocked`'s zero-bot guard
(`BotGroupBehaviorService.java:548-579`) does `runtime.markAsDead()`, deliberately
leaves the runtime in `runningGroups`, and *then* writes `targetStatus=DEAD`. If
that `botGroupService.save(group)` throws, the DEAD runtime stays in the map while
Mongo keeps its previous value. Start a `STOPPED` group whose bots all fail auth and
lose that one write, and you have exactly the shape — no PATCH involved. It needs a
Mongo write failure, which is the same class of fault the second disjunct exists to
paper over in the first place.

**Scope limiter worth knowing:** `BotGroupController.runWithManualOverride` flips a
non-legacy group to `MANUAL_OFF` on `/stop`, and condition 4 is *not* a disjunction.
So the gap can only bite **legacy (`activationMode == null`) and `MANUAL_ON`**
groups — which is precisely the group class the plan's V3/V8 instruct the releaser
to test with.

**Fix, if taken:** condition 2 should reject `STOPPED` outright rather than only
requiring "not DEAD", e.g. `if (persistedTarget == STOPPED) return false;` ahead of
the disjunction. That keeps the swallowed-save case working (persisted `ACTIVE` +
DEAD runtime) while making the opt-out absolute. One line; I did not make it,
because production code is Dev's.

**Adjacent hole, not disclosed, same invariant (F3 below):** `handleBotGroupDeath`
takes no lock and can write `DEAD` *after* a real `stop()` wrote `STOPPED`, which
makes an operator-stopped group a full recovery candidate through the *first*
disjunct. See F3 for why I judge it narrow rather than likely.

### 3. Inertness at the shipped default — **proven, with one honest caveat**

`disabledIsCompletelyInert` is the strongest form available: `verifyNoInteractions`
on all four collaborators, both `group_recovery_*` meter families absent from a real
`SimpleMeterRegistry`, and no INFO. The `enabled` check is the first statement of
`reconcileAll`, and `BotMetrics` registers its counters lazily inside the `inc*`
methods, so a series genuinely cannot exist until an attempt happens.

Caveat, by design and per plan: **the probe is not gated by the flag.**
`EnvironmentProbeScheduler` runs at the shipped default and will open real anonymous
WebSocket handshakes against any environment that owns a DEAD group, once per URL
per 60 s, and will publish `env_ws_probe_*`. "Phases 1–3 are inert" is true of
*group lifecycle* and of `group_recovery_*`; it is not true of outbound network
traffic or of the probe's own metrics. Deployment note, not a defect — the probe
touches no account (verified: `EnvironmentWsProbe` depends on nothing but
`java.net.http.HttpClient` and a timeout).

### 4. No recovery storm — **holds**

`max-per-tick` is enforced (`atMostMaxPerTickPerTick`), the backoff advances only on
failure and is honoured to the second, exhaustion emits the ERROR and the counter
exactly once and then stops attempting, and earliest-due ordering rotates rather
than starving. I added the missing arithmetic case (clamping past the end of the
table). The tick is single-threaded and blocking, so the serialisation is structural
rather than dependent on a limiter. No storm shape is reachable in these tests.

---

## Findings

### F1 — `STOPPED` + a lingering DEAD runtime is still a recovery candidate (AD-5)

Severity: **medium.** Consequence is an unwanted group start against operator
intent; reachability is narrow (see above). **Blocking for Phase 4** in the sense
that it should be decided before the flag is on, not necessarily fixed.
Pinned by `RecoveryEligibilityTest.stoppedWithDeadRuntimeIsStillACandidate` and
`RecoveryCandidateSelectorTest.stoppedRowWithDeadRuntimeIsStillSelected`.

### F2 — a success on the *final* budgeted attempt, then a re-death, is abandoned with no hand-off signal

Severity: **medium.** `attempt()` charges the budget on success too
(`state.attempts = attemptNumber`) but never emits the exhaustion ERROR or counter,
and `expireStates` only resets a state for a group that has *stopped being a
candidate*. So: 5 failures, a 6th attempt that succeeds, then a re-death inside
`settle-minutes` → the group is a candidate again with `attempts == maxAttempts`,
takes the `continue` at the top of the loop with a DEBUG line, and stays there
**forever** while it remains a candidate. No further attempt, no
`group_recovery_exhausted_total`, no ERROR — so Phase 4's
`EnvironmentGroupRecoveryExhausted` rule cannot fire for it, and the flapping case
AD-8 exists to bound is exactly the case that produces it.

It is not invisible — the group is DEAD, so `EnvironmentGroupDead` still fires — but
the recovery-specific hand-off AD-8 promises is missing. Fail-safe direction (it
under-recovers rather than over-recovers), which is why this is not a FAIL.
Pinned by
`DeadGroupRecoverySchedulerTest.successOnTheFinalAttemptThenReDeathIsSilentlyAbandoned`.

Cheapest fix: report exhaustion from the `continue` branch the first time it is
taken (guarded by `exhaustedReported`), rather than only from `recordFailure`.

### F3 — `handleBotGroupDeath` can overwrite an operator's `STOPPED` with `DEAD`

Severity: **low** (narrow race), but it is the *other* AD-5 hole and it needs no
PATCH. `handleBotGroupDeath` runs on the health-monitor thread and takes **no group
lock**, while `stop()` writes `STOPPED` only *after* `teardownRuntimeMemory`
returns. If a health-monitor tick is inside `handleBotGroupDeath`'s
`findById` → `save` when `stop()`'s own write lands, and the monitor's write lands
last, Mongo ends up `DEAD` with no runtime — a fully eligible candidate, through
disjunct 1, for a group the operator just stopped.

Why I judge it narrow: `stopAllBots` waits up to **30 s** on the bot executor before
`healthMonitor.shutdownNow()`, so a monitor tick during teardown almost always
completes *before* `stop()`'s write, and `STOPPED` wins. The losing order requires
the monitor to be mid-`handleBotGroupDeath` at the instant of `shutdownNow`, to
survive the interrupt, and to be slower than one `findById` + one `save` on the same
Mongo. Pre-existing code; only the *consequence* is new. Not unit-testable without a
production seam, so there is no test for it — recorded here instead.

Related, pre-existing, out of scope: that same straggler tick calls
`runtime.markAsDead()` after `creditGroupDeadSeconds` already ran, re-opening a
group-dead-seconds window that is then never credited.

### F4 — the earliest-due tie-break measures time since last **start**, not time dead

Severity: **cosmetic**, but the javadoc is wrong. `deadSince()` is
`lastStoppedAt ?: lastStartedAt ?: MAX`, and there is no persisted died-at stamp —
`handleBotGroupDeath` writes neither timestamp, while every successful start does
`setLastStoppedAt(null)`. So for a group that died **in flight** — the whole point
of the feature — `lastStoppedAt` is null and the value is `lastStartedAt`. A group
that ran three days and died a minute ago therefore sorts *ahead* of one started an
hour ago and dead for 59 minutes, which is the opposite of "how long the group has
been down" as the method claims. Only a tie-break, and every fresh candidate ties at
`EPOCH`, so it does decide the order on the first tick after a mass death.
Pinned by the two `tieBreak*` tests, which assert the behaviour that exists.

### F5 — plan step V11 expects six backoff gaps; only five are observable

Not a code defect. `backoff-minutes=2,5,15,30,60,60` with `max-attempts=6` produces
**five** inter-attempt gaps — 2, 5, 15, 30, 60 ≈ 1 h 52 m — because exhaustion fires
at attempt 6 and the sixth `60` is computed but never elapses. V11 as written asks
the releaser to observe "the documented gaps (2, 5, 15, 30, 60, 60 minutes)" between
six attempts. The implementation's `backoff[min(n-1, len-1)]` is correct and the
clamp is now tested; it is V11's expectation that is off by one.

### Phase 2's 404 — no REST-visible change

`stop()` on an unknown id now propagates `ResourceNotFoundException` where it used
to WARN and return. This is **not** an API change: the only REST caller,
`BotGroupController.runWithManualOverride`, already calls `service.findById(id)`
before delegating, so an unknown id was always a 404 there. The other two in-process
callers (`ActivationScheduler`, `restart()`) act on ids they have just loaded.
The change is visible only to a direct in-process caller; pinned by
`stop_propagatesNotFoundForAnUnknownIdWhenThereIsNoRuntime`. One behavioural
side-effect worth knowing: `restart()` on a runtime-less group now performs an extra
`STOPPED` write before the start, where it previously wrote nothing.

---

## Coverage of the diff

| Production file | Test file | What is covered |
|---|---|---|
| `domain/botgroup/model/RecoveryEligibility.java` | `RecoveryEligibilityTest` (15) | all of conditions 2–6, both disjuncts of 2, condition-3 precedence, the F1 gap |
| `domain/botgroup/service/RecoveryCandidateSelector.java` | `RecoveryCandidateSelectorTest` (7, **new**) | DEAD query, memory-DEAD union, non-duplication, missing row, ACTIVE override, F1 through the real selector |
| `domain/botgroup/service/DeadGroupRecoveryScheduler.java` | `DeadGroupRecoverySchedulerTest` (16) | inertness, all three opt-outs, probe gate, max-per-tick, rotation, backoff progression + clamp, exhaustion once, success path + MDC tags, error path, settle-window reset, tie-break, F2 |
| `BotGroupBehaviorService.startForRecovery` | `BotGroupBehaviorServiceRestartTest` | re-assert under the lock for `STOPPED` and `MANUAL_OFF`, rebuild-through-`startLocked`, the money invariant |
| `BotGroupBehaviorService.stop` (Phase 2) | `BotGroupBehaviorServiceRestartTest` | runtime-less DEAD → `STOPPED` + `lastStoppedAt`, idempotent already-`STOPPED`, with-runtime path unchanged, unknown id → 404 |
| `BotGroupBehaviorService.listDeadRuntimeGroupIds` | via `RecoveryCandidateSelectorTest` / `EnvironmentProbeSchedulerTest` | indirectly; no direct test (trivial accessor) |
| `infrastructure/probe/EnvironmentWsProbe.java` | `EnvironmentWsProbeClassificationTest` (10) | every outcome arm, the `< 500` healthy predicate, restricted-header filtering, tag rendering |
| `infrastructure/probe/EnvironmentProbeScheduler.java` | `EnvironmentProbeSchedulerTest` (10) | no-candidate silence, URL de-dup, streak arithmetic and reset, AD-4 union, AD-10 short-circuit, transition-only INFO, row/streak eviction |
| `infrastructure/observability/BotMetrics` (2 counters) | `DeadGroupRecoverySchedulerTest` | both, with the full `botGroupId`/`environmentId`/`product` tag set from MDC |
| `application.properties` (10 new keys) | — | none; see Gaps |

---

## Gaps

- **`EnvironmentWsProbe.probe()` itself is never executed** — only `classify()` and
  the header filter are. The socket path (`buildAsync` → `get(timeout)` →
  `abort()`), and in particular the `finally`-block abort and the `URI.create`
  failure arm, have no cover. Deliberate: exercising it needs either a real network
  or an embedded server, and neither belongs in this suite. It is what plan steps
  V4/V5 exist to verify on the box.
- **The `@PostConstruct` scheduling of both schedulers is untested** — jitter, the
  initial delay, the `enabled=false` boot line V7 greps for, and `@PreDestroy`. The
  tests correctly drive `reconcileAll(Instant)` / `probeAll()` directly to stay
  deterministic. Anything wrong in the scheduling wrapper would show as "V1 or V7
  finds no line", which the releaser checks.
- **No test binds the new properties through Spring.** `bot.recovery.backoff-minutes`
  is bound as `int[]` from a comma-separated string; the unit tests pass an `int[]`
  straight into the constructor, so a binding failure would surface only at context
  refresh. `ApplicationContextLoadsTest` does load the real context, which is
  partial cover for the default values but not for an overridden
  `BOT_RECOVERY_BACKOFF_MINUTES` — and V11's short form asks the releaser to set
  exactly that from `.env`. Worth a smoke of the boot line after any override.
- **F3 is not testable at unit scope** and has no test. It needs a seam in
  `handleBotGroupDeath` (taking the group lock would both fix it and make it
  testable).
- **Nothing here proves end-to-end behaviour with the flag on** — all recovery cover
  is unit-level with `startForRecovery` mocked at the scheduler boundary, and the
  one integration-ish test drives the real `startLocked` with a mocked `BotFactory`.
  Phase 4's V8–V11 on staging remain the real proof.
- Alert rules, compose wiring and the CLAUDE.md subsection are Phase 4 and are not
  in this branch, so `AlertRulesAudienceTest` has nothing new to check yet.

## Failures

None. `mvn test` → 1224 run, 0 failures, 0 errors, 0 skipped.

---

# QA — DEAD_GROUP_AUTO_RECOVERY — **Phase 4** (alerting, docs, staging enable)

Commits reviewed: `9214902` (alert rules), `4543734` (compose switch), `6457e59`
(CLAUDE.md), plus `a07e103` (WIN79 P_119 providers, carried on the same branch).

**Verdict:** PASS
**Build:** `mvn clean install -Dmaven.javadoc.skip=true` → **2,106 tests, 0 failures,
0 errors, 0 skipped**, `BUILD SUCCESS` across all five modules.

## The build-number discrepancy in Dev's report, resolved

Dev's detail said "2,098 tests, 0 failures"; its closing line said "Build: PASS
(`mvn clean install -DskipTests`)". **The detail is right and the closing line is a
mislabel** — I ran the full build myself, before touching anything:

| Module | Tests |
|---|---|
| Bot - API | 138 |
| Bot - Strategies | 126 |
| Bot - Messages | 167 |
| Bot - Engine | 431 |
| Bot - Application | 1,236 |
| **Total (branch as landed)** | **2,098 — 0 failures, 0 errors, 0 skipped** |

No `-DskipTests` anywhere in the run, and `grep -c "Tests are skipped"` on the log is
`0`. With my additions the application module goes 1,236 → 1,244 and the total to
**2,106**, still green. (Note the Phase 1-3 verdict above quotes `1224`, which was the
**bot-app module** count under `mvn test`, not the reactor total — the two numbers are
not comparable.)

## Tests added / updated

- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/RecoveryKillSwitchWiringTest.java`
  — **new, 3 tests.** The Phase 4 switch, pinned across the two files that must agree:
  compose passes `BOT_RECOVERY_ENABLED=${BOT_RECOVERY_ENABLED:-false}` on the
  `bot-manager` service; `application.properties` still declares
  `bot.recovery.enabled=false` **exactly once**; and the SCREAMING_SNAKE name
  relaxed-binds to the dotted property `DeadGroupRecoveryScheduler` reads (the
  `LoggingLevelOverrideTest.EnvVarRelaxedBinding` idiom, no Spring context).
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/RecoveryAlertRuleWiringTest.java`
  — **new, 4 tests.** `EnvironmentGroupRecoveryFlapping` selects
  `group_recovery_attempts_total{outcome=...}` with the value equal to
  `DeadGroupRecoveryScheduler.OUTCOME_SUCCESS` (this is why the class lives in that
  package — the constant is package-private); `EnvironmentGroupRecoveryExhausted` reads
  `BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL` through `increase(...)` and is `critical`;
  `EnvironmentGroupDead`'s description no longer contains any "(does not|never|cannot)
  recover" claim and does name auto-recovery and the `STOPPED` opt-out; and its
  `for: 5m` is unchanged (AD-15).
- `bot-app/src/test/java/com/vingame/bot/infrastructure/observability/AlertRuleMetricsTest.java`
  — **+1 test**, reusing the existing fixture (no new fixture, no god object):
  `everyLabelMatcherSelectsAnEmittedValue` — every **equality** label matcher on an
  application metric must select a value that appears in the rendered exposition.
  Equality only; `!=`, `=~`, `!~` are out of scope by design (`bot_messages_total`'s
  `cmd=~"startGame|spin"` is a legitimate union). It covers 5 selectors today
  (`EnvironmentAuthDown`, `EnvironmentLoginFailing`, `EnvironmentDeadBotRatioHigh`,
  `GameNoRounds`, `EnvironmentGroupRecoveryFlapping`) and has a non-vacuity floor.

**All eight were mutation-checked**, not just observed green. With
`BOT_RECOVERY_ENABLED` deleted from compose, `bot.recovery.enabled` flipped to `true`,
`outcome="success"` drifted to `"succeeded"`, and the old "does not recover on its own"
sentence restored, the run is **5 failures** — one per mutation, each naming the file to
fix. The working tree was restored afterwards (`git checkout --`, verified clean).

## Coverage of the diff

| Phase 4 change | Test | What is pinned |
|---|---|---|
| `docker-compose.yml` `BOT_RECOVERY_ENABLED` | `RecoveryKillSwitchWiringTest` (new) | presence on `bot-manager`, the `${...:-false}` form, and that the name binds |
| `application.properties` `bot.recovery.enabled=false` | `RecoveryKillSwitchWiringTest` (new) | single declaration, value `false` — Phase 5 not taken |
| `prometheus/alerts.yml` `EnvironmentGroupRecoveryExhausted` | `RecoveryAlertRuleWiringTest` (new), `AlertRuleMetricsTest`, `AlertRulesAudienceTest`, `AlertmanagerRoutingTest` | counter name + `increase()` + `critical`/`product`; metric exists in a real exposition; `$labels.*` resolve; reaches the VipTalk receiver |
| `prometheus/alerts.yml` `EnvironmentGroupRecoveryFlapping` | same four | plus the `outcome` **label value** against the scheduler's own constant |
| `EnvironmentGroupDead` description + `for:` | `RecoveryAlertRuleWiringTest` (new) | the falsified claim cannot drift back; the 5 m window is not widened |
| `alertmanager/alertmanager.yml` (comment-only change) | `AlertmanagerRoutingTest` | unchanged routing still delivers every rule, including the two new ones, to VipTalk |
| `CLAUDE.md` subsection | — | prose; read it against the code by hand, see below |

The two existing generic guards already cover the new rules **without anyone editing
them**, so I did not duplicate them: `AlertRulesAudienceTest` enumerates every alerting
rule and demands `labels.audience` (both new rules declare `product`), and
`AlertmanagerRoutingTest.everyAlertRuleStillReachesVipTalk` enumerates `alerts.yml` and
proves both fall through the parent route to the `viptalk` receiver — which is exactly
the claim `9214902`'s new alertmanager.yml comment makes.

I also read the CLAUDE.md subsection against the shipped code. Every falsifiable
statement in it checks out: compose form and property default (now tested), probe
independent of the flag (`EnvironmentProbeScheduler` takes no `enabled` flag), the
`STOPPED` veto being explicit rather than inferred (`a411ac6`), the success-charges-budget
rule and the dual hand-off sites (`4236614`), and "five gaps, not six".

## Findings from the earlier phases: status

`F1` (STOPPED + lingering DEAD runtime was still a candidate) and `F2` (silent
exhaustion after a success on the final attempt) were the two items the Phase 1-3
verdict marked **blocking for Phase 4**. Both are now fixed on the branch — `a411ac6`
and `4236614` — and the tests that pinned the defective behaviour have been inverted to
pin the fix. Nothing in Phase 4 reopens either. `F3` is closed by `f4a436b`
(`handleBotGroupDeath` takes the per-group lock with `tryLock(2, SECONDS)`).

## Gaps

- **Nothing here proves the flag actually turns recovery on.** The build can prove the
  switch exists, is spelled right, binds, and ships off. It cannot prove that a box's
  `.env` sets it — that file is uncommitted by design. **The boot line
  `Dead-group recovery scheduler ... enabled=true` is the only confirmation**, and it is
  a mandatory releaser check on every host this goes to.
- **The other nine `bot.recovery.*` keys are still untested as *values*.** I pinned only
  `enabled`, because it is the one whose wrong value is dangerous rather than merely
  suboptimal. `backoff-minutes` binding as `int[]` is still only covered transitively by
  `ApplicationContextLoadsTest` at the compiled default — an override typo'd in `.env`
  (V11's short form asks for exactly that) fails at context refresh, loudly, which is
  acceptable.
- **PromQL semantics are still unverified by the build.** These tests check that the
  rules select metrics and label values we emit; they do not run `promtool` and cannot
  tell you that `increase(...[6h]) >= 3` fires when you expect. V11b remains the real
  check that Prometheus loads both rules.
- **`env_ws_probe_*` has no alert rule**, so the Phase 4 alerting surface says nothing
  about an environment that never becomes probe-healthy. That is by design (the group
  stays DEAD and `EnvironmentGroupDead` keeps firing), but it means "recovery never
  attempted" and "recovery attempted and failing" are distinguishable only from
  `group_recovery_attempts_total` and the logs — which the corrected
  `EnvironmentGroupDead` description does now tell the operator.
- **Straight-to-prod skips V8-V11.** Everything in this feature's behavioural
  verification is a staging procedure that manufactures a DEAD group; the plan
  explicitly forbids doing that on a live prod fleet (V12: "run V0a, V0b, V1 and this
  step only"). So on Prod-Bot, with `BOT_RECOVERY_ENABLED=true` and no soak, the first
  real exercise of the reconciler will be a real incident. The unit suite covers the
  decision logic densely (43 tests across `RecoveryEligibilityTest` (16),
  `RecoveryCandidateSelectorTest` (7) and `DeadGroupRecoverySchedulerTest` (20), plus
  the `startForRecovery` cases in `BotGroupBehaviorServiceRestartTest`) and the money
  invariant is asserted structurally, but **no test exercises the enabled reconciler
  against a live gateway.** That is a deployment risk, not a test gap I can close; the
  mitigations that exist are the four opt-outs (`STOPPED`, `MANUAL_OFF`, closed window,
  `BOT_RECOVERY_ENABLED=false`) and `max-per-tick=1`.

## Failures

None. `mvn clean install -Dmaven.javadoc.skip=true` → 2,106 run, 0 failures, 0 errors,
0 skipped.
