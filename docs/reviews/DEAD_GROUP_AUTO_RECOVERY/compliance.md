# Compliance — DEAD_GROUP_AUTO_RECOVERY

Branch: `feature/dead-group-auto-recovery`
Plan reviewed: `docs/plans/DEAD_GROUP_AUTO_RECOVERY.md` — now **tracked**, landed in
`6409002`; re-read top to bottom at that commit.
Diff reviewed: `git diff 7d0ec86..HEAD` (tip `6409002`) — 21 commits, 19 files.
`main` is `8a5ee1a` and is an ancestor of `7d0ec86`; this branch is stacked on the
PLUGIN_HOT_RELOAD line, so `7d0ec86` is the feature's true base and the only diff worth
reading.
Build: user-verified full build, **2084 tests, 0 failures, 0 errors**. Independently
re-run here for the seven affected classes — `RecoveryEligibilityTest` (16),
`RecoveryCandidateSelectorTest` (7), `DeadGroupRecoverySchedulerTest` (20),
`EnvironmentProbeSchedulerTest` (11), `EnvironmentWsProbeClassificationTest` (11),
`BotGroupBehaviorServiceRestartTest` (28), `BotGroupBehaviorServiceTest` (nested, incl.
`MonitorHealthTests` 5) — all green.

**This is a re-verification.** The previous verdict on this branch was
**SEND_BACK_TO_DEV**, on one blocking item: AD-5's `STOPPED` opt-out was not enforced in
the predicate. That text is preserved verbatim in git at `6409002`
(`git show 6409002:docs/reviews/DEAD_GROUP_AUTO_RECOVERY/compliance.md`).

## Verdict

**PLAN_AMENDED**

The blocking finding is closed. The remediation is accepted in full. Two statements in the
plan are falsified by the shipped branch, and in both cases the **plan** was wrong rather
than the code, so they are amended rather than sent back — see "Amendments to the plan".

The branch is compliant and may go to staging.

## The blocking finding is closed

`a411ac6`. Verified against the code, not the commit message.

`RecoveryEligibility.isCandidate` now carries an explicit condition-2a veto, placed ahead
of condition 2's disjunction:

```java
if (persistedTarget == BotGroupStatus.STOPPED) {
    return false;
}
```

- The disputed shape is now false: `(STOPPED, null, null, 20, DEAD, runtimeGroupDead=true)`
  → `false`, pinned by `RecoveryEligibilityTest.stoppedWithADeadRuntimeIsStillNeverACandidate`.
  The old test asserting the opposite as "current behaviour" is deliberately flipped, and
  so is `RecoveryCandidateSelectorTest`'s selector-level twin.
- **The disjunct still does its job.** `theStoppedVetoDoesNotBreakTheSwallowedSaveCase`
  pins `(ACTIVE, …, runtimeStatus=DEAD, runtimeGroupDead=true)` → `true`, which is the
  `handleBotGroupDeath`-swallowed-save case condition 2's second arm exists for. Nothing
  was lost to buy the veto.
- **The scheduler companion drives the shape through the path that actually produces it.**
  `DeadGroupRecoverySchedulerTest.stoppedGroupReachedThroughTheDeadRuntimeUnionIsNeverAttempted`
  stubs `findByTargetStatus(DEAD)` empty and `listDeadRuntimeGroupIds()` returning the id,
  which is the AD-4 union route — a `STOPPED` row is never in the DEAD query. Five ticks
  an hour apart, `startForRecovery` never called, no counter, no INFO. That is the test my
  ruling asked for.
- **The javadoc that asserted this as fact is now true of the predicate it documents.**
  `DeadGroupRecoveryScheduler`'s gate-2 text says the veto is enforced by
  `RecoveryEligibility`'s explicit condition-2a "rather than inferred from condition 2's
  disjunction, which only asks 'not DEAD'", and `RecoveryEligibility` carries the
  reachability reasoning for both routes — the `PATCH {"targetStatus":"STOPPED"}` route and
  the lost-write route through `startLocked`'s zero-bot guard. Both routes are named; the
  second is the one QA found and the one that needs no operator action at all.
- The veto sits after condition 3 and before condition 2, which is immaterial — the
  predicate is a conjunction of vetoes, so ordering cannot change the result.

## Phase-by-phase

### Phase 1 — Environment WebSocket reachability probe, observe-only
Status: **implemented** (hardened after review; the AD-3 defect that lived in this phase's
`RecoveryEligibility` is fixed)

Everything in my first pass still holds and was re-checked after the churn: the probe is
anonymous end to end (no `ApiGatewayClient` / `BotCredentials` / `TokensProvider` /
`VingameWebSocketClient` reachable — re-checked against the imports; the only client type
is `java.net.http.HttpClient`), the scheduler follows the `ActivationScheduler` idiom, the
`MultiGauge` refresh is the `InfoGaugeRefresher` shape, metrics carry explicit
`{environmentId, product, outcome}` tags rather than MDC (AD-13), state maps are still
pruned every tick (`states.keySet().retainAll`, `envUrls.keySet().retainAll`), and all
four Phase 1 properties are present with the plan's values and run independently of
`bot.recovery.enabled`.

Three review-driven changes, all accepted:

- `c86dd8e` — a timed-out probe no longer leaks its WebSocket. `get(timeout)` does not
  cancel the task, so a handshake completing a moment later left a live JDK WebSocket with
  a no-op listener that nothing would ever abort. `abandon()` cancels *and* aborts on
  completion. In this JVM — whose last outage was unbounded reconnect threads — that is the
  right call in the component whose whole job is polling a sick origin.
- Same commit: a `WebSocketHandshakeException` with a **null** response used to read as
  status `0`, and `0 < 500`, so it classified `HTTP_4XX` = **healthy**. The one
  unclassifiable condition in the class landed on the side that authorises a recovery
  attempt. It is `ERROR` now. This is a small departure from the plan's literal
  "`WebSocketHandshakeException` → `getResponse().statusCode()`" and it is the correct
  reading of an absent response; it errs toward not recovering, which is the safe
  direction.
- `expect` joins `RESTRICTED_HEADERS`. The plan listed `Host`/`Connection`/`Upgrade`/
  `Content-Length`/`Sec-WebSocket-*`; the JDK's actual disallowed set includes `expect`, so
  an environment carrying it would have thrown on every single probe and been permanently
  unrecoverable. A superset of the plan's list, for the plan's stated reason.
- `5e8e2f6` — AD-10's live-sibling short-circuit now counts only **ACTIVE** runtimes
  (`countOpenWsByEnvForActiveRuntimes`). This one matters: at `dead.threshold=0.80` a DEAD
  group still has up to 20% of its bots connected and `handleBotGroupDeath` does not stop
  them, so the most common death shape let a group's own surviving minority declare its
  environment healthy and skip the independent evidence the gate exists to require.
  Filtering on ACTIVE excludes every candidate structurally, since eligibility condition 3
  rejects any ACTIVE-runtime group. `countOpenWsByEnv` is untouched, so
  `ws_connections_open_by_env` is unaffected.

The user's V4 amendment still reads true: `OUTCOME_LIVE_SIBLING` is still stamped, so the
healthy outcome set on the wire is still `{open, http_4xx, live_sibling}`, and the escape
hatch ("pick a `$G` on an environment where no other group is running") is if anything
sharper now — it means no other *ACTIVE* group.

### Phase 2 — `stop()` parks a runtime-less DEAD group as `STOPPED`
Status: **drifted — accepted, and the plan is amended (A1)**

The operator `/stop` entry point behaves exactly as specified and V6 is unaffected:
runtime-less + not already `STOPPED` → INFO `Bot group {} has no runtime — persisting
STOPPED (was {})` + save; already `STOPPED` → the historical WARN, no second write; the
with-runtime path byte-for-byte unchanged.

What drifted is the plan's sentence "`restart` (`:1009`) is untouched" (`8cccef0`).
`restart` **is** touched, it had to be, and the plan was wrong to assert otherwise — see
Amendment A1 below. `stopAndLogout` really is untouched, as the plan says.

### Phase 3 — The recovery reconciler (inert)
Status: **implemented**

Re-verified after the churn, item by item:

- **AD-1 holds.** `startForRecovery` is still the only new entry point and still a thin
  wrapper: same `groupLocks` `ReentrantLock`, re-read inside the lock, re-assert
  `RecoveryEligibility`, call the existing private `startLocked`, catch nothing, return
  `rebuilt != null && actualStatus == ACTIVE && runningBotCount > 0`. No second lifecycle
  path, no new teardown. The lock is taken at exactly four sites in the service —
  `start`, `startForRecovery`, `stop`, and now `handleBotGroupDeath` — and the fourth is a
  `tryLock` that writes nothing (A2).
- **AD-6 holds and the inertness is intact.** `if (!enabled) return;` is still the first
  statement of `reconcileAll`, ahead of every collaborator read; neither counter is
  registered eagerly. `disabledIsCompletelyInert` still asserts `verifyNoInteractions` on
  all four collaborators plus an empty counter set plus no INFO. The V7 boot line still
  prints `enabled=...` and still reads `enabled=false` from `application.properties`. V2,
  V5 and V7 will hold.
- **AD-8 holds**, including the two things the remediation changed. Budget is spent only on
  attempts; the probe-health gate is still evaluated *before* the due check, so a down
  environment costs zero budget; `backoffAfter` is still `backoff[min(n-1, len-1)]`;
  exhaustion is still exactly one ERROR + one counter, guarded by `exhaustedReported`, and
  the group is then left alone. `4236614` adds the second call site for that hand-off — see
  the ruling below.
- **AD-9 holds.** One single-threaded virtual-thread reconciler named `dead-group-recovery`,
  `max-per-tick=1`, blocking tick, first tick jittered by `jitter-seconds`. Sort is
  earliest-due-first, then `deadSince` `nullsLast`, then id. The `LocalDateTime.MAX`
  sentinel is gone in favour of `null` + `nullsLast`, which says the same thing.
  `deadSince`'s javadoc now states plainly that it is `lastStoppedAt ?: lastStartedAt` and
  therefore *time since last start* for an in-flight death — that is a correction of a
  javadoc that overclaimed, and it matters because every fresh candidate ties at
  `nextDue == EPOCH`, so this key decides the order on the first tick after a mass death.
  The property AD-9 needs (fair rotation, no starvation) is unaffected.
- **AD-11 holds.** `ConcurrentHashMap` for both maps, nothing persisted, `expireStates`
  still prunes every tick with the settle-window exception the plan specifies.
- **AD-12 holds.** `healthy-streak=2`, enforced in `isHealthy`. The new `healthyStreak(envId)`
  accessor exposes the raw streak for the log line and does not weaken the gate.
- **AD-13 holds, and is now actually true on the wire.** `f8e87ca` is a real find: every
  path through `startLocked` calls `BotMdc.clear()` on the caller's thread, so by the time
  `attempt()` reached `incGroupRecoveryAttempt` the MDC was empty and both counters
  registered through an empty `mdcTags()` — no `botGroupId`, no `environmentId`, no
  `product`, and `BotMdcTagsMeterFilter` is no safety net because it only touches
  `bot_`-prefixed names. Worse, the loss was non-uniform (a `startLocked` that throws
  before `runningGroups.put` never reaches a `clear()`), so one counter name would have
  carried two series shapes. The MDC is now re-asserted in a `finally` around
  `startForRecovery`, and `reportExhaustionOnce` sets it itself when called from the skip
  branch. Without this, **V8's tag assertion would have failed on staging** and Phase 4's
  `EnvironmentGroupRecoveryFlapping` rule could not have distinguished three groups
  healing once from one group flapping three times.
- **AD-14 holds.** Still one INFO per attempt, one per outcome, one per probe *transition*,
  one ERROR on exhaustion. The new lines added by remediation are incident-rate, not
  bot-rate: `handleBotGroupDeath`'s two WARNs fire at most once per 30 s monitor tick and
  only while a lifecycle operation holds the lock, and `restoreStatusAfterFailedRestart`'s
  WARN fires at most once per failed `/restart`. Nothing new lands in a per-bot class;
  `PerBotInfoLogGuardTest` passes.
- **The attempt INFO line no longer drops the documented field.** The plan specifies
  `env <envId> healthy for <k> probes`; it now prints
  `env {} healthy for {} probe(s)` off `probeScheduler.healthyStreak(envId)`. My first
  pass listed this as minor drift and offered "or drop the phrase from the plan" — Dev
  took the better half and made the code match the plan. V8's grep
  (`auto-recovery attempt 1/6`) still matches, as do V8's success grep and V11's exhaustion
  grep, all re-checked against the format strings.
- **The money invariant holds, and is now asserted by something that can fail.**
  `a4ee3f2` is right that the old log-scan for `registr`/`deposit` was vacuous: the
  appender attaches to `BotGroupBehaviorService`'s logger while those lines come from
  `BotGroupService` and `Bot`, both mocks in that test. The registration half was and
  remains a real structural proof (one call site, gated on a null id; every captured save
  carries `g-1`). The deposit half is replaced by the fact that decides it — the
  `BotBehaviorConfig` handed to every bot carries the group's own `autoDepositEnabled`,
  unchanged and unforced. The javadoc correction ("never deposits" → "adds no deposit a
  manual `/restart` would not make") aligns the code with what the plan's **V9 already
  said**, so this is a doc/test correction, not a scope change.

### Phases 4 and 5
Status: **out of scope by instruction — and still correctly absent.**
*(Superseded for Phase 4 by the appended "Compliance — DEAD_GROUP_AUTO_RECOVERY, Phase 4"
section at the bottom of this file, 2026-09-11.)*
`prometheus/alerts.yml`, `docker-compose.yml` and `CLAUDE.md` are untouched by the branch.
Confirmed against `git diff --name-only 7d0ec86..HEAD`: 19 files, all of them Phase 1-3
sources, tests, `application.properties`, `BotMetrics`, and the four docs.

## Rulings on the two items Dev decided explicitly

### 1. A successful recovery still charges the attempt budget — **accepted**

Not drift at all: it is what the plan already says. AD-8 spends the budget "**only** on
attempts", and a success *is* an attempt. Phase 3's success branch is specified as "record
`lastSuccess` and schedule the budget reset for `+settle-minutes`" — a reset that is
*scheduled*, not granted, which is only meaningful if the attempt was charged in the first
place. The shipped code does exactly that: `state.attempts = attemptNumber`,
`state.lastSuccess = now`, and `expireStates` withholds the reset until the group has
stopped being a candidate *and* the settle window has passed.

Dev's argument is also correct on the merits and is the plan's own anti-flap rationale:
refunding makes the budget unbounded for exactly the mode it bounds — a group that comes
up and re-dies inside the settle window would restart the whole six-attempt table on every
flap, which is the group-scale version of the reconnect hot loop AD-8 is modelled on.

**QA finding 7 is genuinely the cost of that choice, and moving the hand-off is the right
response.** Verified in code: `reconcileAll`'s exhausted-skip branch now calls
`reportExhaustionOnce(state, group)` before its DEBUG line, and `recordFailure` still calls
it when the last attempt fails. `exhaustedReported` keeps it to exactly one ERROR and one
`group_recovery_exhausted_total` per episode from either site, and `reportExhaustionOnce`
sets the group MDC itself when it does not already own it (with `ownsMdc` guarding the
clear so it cannot wipe `attempt()`'s context mid-attempt). Without this, the one shape
where a *success* spends the last of the budget would have been skipped forever on a DEBUG
line — which since LOG_VOLUME_TIERING Phase 4 never reaches Loki — with no counter and no
ERROR, so Phase 4's `EnvironmentGroupRecoveryExhausted` could never fire for it. It must
not be possible to exhaust silently; now it isn't.

The consequence I flagged last time stands and is now documented in `attempt()` rather
than implicit: a group that recovers and re-dies inside the settle window resumes at
attempt *n+1*. That is intended.

### 2. `handleBotGroupDeath` takes `tryLock(2, SECONDS)` rather than `lock()` — **accepted**

Dev's reasoning is correct. I checked each claim against the code rather than the commit
message:

- **The 30 s hazard is real.** `BotGroupRuntime.stopAllBots` credits the dead-window,
  cleans up bots, then `executor.shutdown()` + `awaitTermination(30, SECONDS)`, and only
  *then* `healthMonitor.shutdownNow()` — all of it inside `stop()`'s lock. `lock()` is
  uninterruptible, so a monitor thread blocking there would park behind its own shutdown
  for that window.
- **It is not a deadlock either way.** Nothing awaits the health monitor's termination, so
  `lock()` would not have hung `stop()`. `tryLock` is a latency choice, not a
  correctness-vs-deadlock choice — but it is the right latency choice, and the correctness
  is carried by the identity re-check below rather than by the lock mode.
- **Losing the lock is not a dropped death-write.** The lock has exactly four takers.
  `stop()` writes `STOPPED` (or, on restart's teardown call, defers to the start that
  follows); `start()`/`startForRecovery()` reach `startLocked`, which writes `ACTIVE` on
  success or `DEAD` on the zero-bot guard. The single non-writing holder is `startLocked`'s
  "already running" no-op, and that costs one 30 s tick of delay — `scheduleAtFixedRate`
  keeps running and `handleBotGroupDeath` sets no flag that would suppress a retry. The
  bounded loss is: `groups_dead_by_env` may under-report one group for up to one tick,
  and only while an operator lifecycle action is in flight on that same group.
- **The identity re-check is verified sound in production, not just in the unit test.**
  `runningGroups.put(id, runtime)` happens at `BotGroupBehaviorService:473`, long before
  `startHealthMonitoring(runtime)` at `:603`, whose first tick is at +10 s. So a live
  runtime can never fail its own identity check. The test had to add
  `runningGroups().put(...)` to `shouldMarkDeadAtOrAboveThreshold` — that is the fixture
  catching up with the production precondition, not a weakened assertion.
- **It closes a second, unrelated pre-existing bug.** `stopAllBots` calls
  `creditGroupDeadSeconds` *first*, so a straggler tick calling `markAsDead()` afterwards
  re-opened a `groupDeadSince` window nothing would ever credit again. The identity check
  stops that.
- Two new tests pin both branches (`shouldNotWriteWhileAnotherLifecycleOperationHoldsTheLock`,
  `shouldNotMarkATornDownRuntime`), and `MonitorHealthTests` is green.

The behaviour change is real and I am recording it as such: **a death write can now be
skipped for one monitor tick.** That is strictly better than the alternative it replaces,
which is a persisted `DEAD` landing after an operator's `STOPPED` and silently reversing
the one opt-out this feature has. See Amendment A2 for why the plan's dismissal of this
race ("the DB self-corrects") was wrong.

## Drift

All drift is accepted. Two items are plan errors (A1, A2 below). The rest are corrections
Dev made to its own Phase 1-3 code in response to review/QA, each of which moves the code
*toward* the plan rather than away from it:

| Change | Direction |
|---|---|
| `f8e87ca` MDC re-assert + `healthy for <k> probes` | code now matches AD-13 and the plan's INFO format |
| `c86dd8e` probe socket leak, null-response → ERROR, `expect` filtered | closes defects the plan did not anticipate; the classifier change errs safe |
| `5e8e2f6` live sibling must be an ACTIVE runtime | makes AD-10 mean what AD-10 says (independent evidence) |
| `4236614` hand-off from the skip branch too | makes AD-8's "then hand to a human" unconditional |
| `a4ee3f2` money-invariant javadoc + test | aligns the code's claim with the plan's own V9 |

## Out-of-scope changes

- **`Aviator.js`** — unchanged from my first pass. Swept in by a pre-staged index, untracked
  again in `f52739d`; net effect on the branch diff is zero, and the working-tree state is
  exactly as found (`AM`, empty blob `e69de29` staged, 729 bytes on disk).
- `deploy.sh`, `TaiXiuMessages/*.js`, `docs/plans/AVIATOR_BOT.md`,
  `docs/reviews/VIPTALK_ALERTING_V2/release.md` and the untracked
  `docs/reviews/PLUGIN_HOT_RELOAD/review-2d.md` remain uncommitted and untouched by the
  branch and by this review.
- `6409002` added the plan and the review/compliance verdicts to git. The plan was untracked
  before, so the branch did not carry the document its javadoc AD-references resolve
  against; tracking it is right. Dev's two edits to it (AD-3's `STOPPED` wording, V11's
  five-gaps correction) are the ones my previous verdict directed and left for "whoever
  lands the fix". Both check out: the AD-3 blockquote states the veto the code now
  implements without narrowing AD-5, and V11's "five gaps, not six" is arithmetically
  right for `backoff[min(n-1, len-1)]` over six attempts (2+5+15+30+60 = 112 min).
- Nothing else.

## Amendments to the plan

Added as a marked `## Amendment — 2026-09-07 (compliance re-verification)` section at the
bottom of `docs/plans/DEAD_GROUP_AUTO_RECOVERY.md`, plus a one-line pointer at each of the
two falsified passages. Nothing above was rewritten, and no AD, condition, budget, probe
predicate or verification step changed.

### A1 — Phase 2's "`restart` (`:1009`) is untouched" cannot hold once AD-5 exists

Phase 2 makes `stop()` persist `STOPPED`; AD-5 makes `STOPPED` **permanent**; `restart()`
is `stop()` then `start()`. Therefore a `/restart` whose start half throws left the group
parked `STOPPED` where it used to be left `DEAD` — permanently opted out of auto-recovery,
absent from `findByTargetStatus(DEAD)` and from every dead-group signal that reads the
persisted status, with nothing logged. And Phase 3's exhaustion ERROR sends the operator to
exactly that endpoint, for exactly the groups whose start is most likely to fail. The plan
asserted "nothing else changes" without evaluating it against the meaning AD-5 had just
given `STOPPED`. That is a technical error in the plan, not a Dev preference, which is why
this is an amendment and not a send-back.

Accepted shape (`8cccef0`), verified in code: the public `stop(String)` is unchanged and
still parks; a private `stop(String, boolean parkRuntimeless)` lets `restart`'s internal
teardown skip the park (restoring pre-Phase-2 behaviour for that one caller); and a start
that throws restores the pre-restart status, but **only** when the status is `STOPPED` now
and was not before — so a genuinely parked group stays parked, and an `ACTIVE` or a
zero-bot `DEAD` written on purpose by the start path is left alone. Net effect on `restart`
relative to `main`: unchanged, plus a restore on the failure path. V6 is unaffected because
it exercises the operator `/stop`.

### A2 — "the DB self-corrects at the next `stop`/`start`/death" is false for the STOPPED variant

The Implementation Note on `handleBotGroupDeath`'s missing lock reasoned about one ordering
only — a death write landing after a recovery's `ACTIVE` — and correctly said AD-3
condition 3 covers it. The reverse ordering is covered by nothing: a monitor tick between
its `findById` and its `save` when an operator's `/stop` lands leaves Mongo at `DEAD` with
**no runtime**, which is a textbook recovery candidate for a group an operator just
stopped, is indistinguishable from an ordinary death to any predicate, and does **not**
self-correct — nothing writes that group again until a human does. It is also the one AD-5
hole reachable with no `PATCH` at all. I recorded this in my first pass as "not this
phase's problem"; with the feature now enforcing AD-5 in the predicate, leaving the one
route that defeats it open would have been incoherent. The plan's claim is a falsifiable
statement about the code and it is false.

Accepted shape (`f4a436b`): `tryLock(2, SECONDS)`, an identity re-check against
`runningGroups` under the lock, and no write when either fails. Rationale and the residual
behaviour change are in the ruling above.

---

# Compliance — DEAD_GROUP_AUTO_RECOVERY, Phase 4

*Appended 2026-09-11. Everything above is the Phase 1-3 pass and stands unchanged; its
"Phases 4 and 5 — out of scope by instruction" entry is superseded by this section for
Phase 4 only.*

Branch: `feature/dead-group-auto-recovery`
Plan reviewed: `docs/plans/DEAD_GROUP_AUTO_RECOVERY.md` at `c49bd42` (the state Dev worked
from), re-read top to bottom.
Diff reviewed: `9214902`, `4543734`, `6457e59` — 5 files, 4 of them config/docs and one a
test fixture. No production Java changed in this phase.
Build: `AlertRuleMetricsTest` (8), `AlertRulesAudienceTest` (10), `AlertmanagerRoutingTest`
(8) re-run here under JDK 21 — all green.

## Verdict

**PLAN_AMENDED**

Phase 4 as landed is faithful to the plan. All three plan steps are implemented; the two
new rules match the plan's `expr`, `severity` and `audience` character for character; the
compose switch is the `${...:-false}` shape the plan specified; the CLAUDE.md subsection
covers every fact Phase 4 step 3 asks for.

Dev reported three places where the plan was wrong or under-specified. **All three were
verified independently and all three hold** — one of them by reproducing the failure, not
by reading the commit message. They are plan defects, not Dev drift: in each case the plan
made a falsifiable claim about this repository and the claim is false. Amended, in a marked
`## Amendment — 2026-09-11 (Phase 4 compliance)` section at the bottom of the plan plus
three in-place pointers (B1, B2, B3).

A **fourth** amendment (C) records the user's decision to skip Phase 5's staging soak and
enable this on prod directly. That is not Dev's deviation and nothing in these three
commits assumes it; it is recorded so the sequencing does not read as though it happened
silently.

One item is **not** accepted silently and is listed under Drift: the operator-facing text
these commits ship re-states a money claim that this branch's own Phase 3 pass had already
been required to qualify. It is two sentences and no code, it does not block the enable,
and it must be corrected.

## Phase-by-phase

### Phase 4 step 1 — `prometheus/alerts.yml`
Status: **implemented**

- `EnvironmentGroupDead` description no longer claims a DEAD group never recovers. The new
  text says auto-recovery will restart it when the probe reports healthy, that the alert may
  resolve with nobody acting, that still-firing after ~10 minutes means recovery is failing
  or the environment is still down, names `env_ws_probe_healthy` and the `auto-recovery
  attempt` lines as the discriminator, and names `/stop` as the opt-out. That is step 1's
  full ask plus the two facts an operator actually needs.
- `expr: groups_dead_by_env > 0` and `for: 5m` are byte-identical to `a07e103`. AD-15 honoured.
- `EnvironmentGroupRecoveryExhausted`: `increase(group_recovery_exhausted_total[15m]) > 0`,
  `severity: critical`, `audience: product` — exactly as specified, no `for:` (the plan
  specified none, and an event counter should not have one).
- `EnvironmentGroupRecoveryFlapping`:
  `increase(group_recovery_attempts_total{outcome="success"}[6h]) >= 3`, `for: 5m`,
  `severity: warning`, `audience: product` — exactly as specified.
- Neither rule is `audience: both`, so neither needs `public_summary`; `AlertRulesAudienceTest`
  passes. Neither gets an Alertmanager child route, so both fall through to the parent
  `viptalk` receiver — `AlertmanagerRoutingTest.everyAlertRuleStillReachesVipTalk`
  enumerates `alerts.yml` and proves it, and adding a `continue: true` child would have
  dragged in the mandatory-sibling rule for no gain. Correct call.

### Phase 4 step 2 — `docker-compose.yml`
Status: **implemented**

`BOT_RECOVERY_ENABLED=${BOT_RECOVERY_ENABLED:-false}` sits next to `BOT_LOG_LEVEL` in the
`bot-manager` environment block, with a comment that says what it gates (the reconciler,
never the probe), why it lives outside the jar, and that the value belongs in the
uncommitted `secrets.env`/`.env` merge. The compiled default in
`application.properties:172` is still `false`. `deploy.sh` is untouched. Nothing sets the
value in git, which is correct.

Note for the record: `enabled` is a constructor `@Value` on `DeadGroupRecoveryScheduler`
and is read once. Flipping it is a container restart, as the plan intends; there is no
runtime toggle and `/actuator/loggers`-style live flipping does not apply.

### Phase 4 step 3 — `CLAUDE.md`
Status: **implemented** (with the step itself corrected — Amendment B2)

The new Architecture subsection covers the trigger (persisted `targetStatus`, and *why* not
`runningGroups`), the evidence gate and the `< 500` predicate, the fact that the probe runs
whether or not the reconciler does, `STOPPED` as the only opt-out plus the two `stop`/
`restart` changes that making it load-bearing required, the budget including the fact that a
success charges it and that both paths emit the hand-off, the structural staggering, the
alert consequences, the switch, and — usefully — what the feature does **not** cover (a
group parked at 79% dead). That is more than step 3 asked for and all of it checks out
against the code.

The Backlog entry is **added**, not corrected, because there was nothing to correct
(Amendment B2). The commit message says so plainly rather than quietly doing something
other than what it was told, which is the right handling.

### Phase 5
Status: **not taken — and deliberately so.** Step 1 (compiled default → `true`) is not in
this diff and must not be. Steps 2-3 are superseded by Amendment C.

## Verification of Dev's three reports

### 1. `AlertRuleMetricsTest` is absent from the plan — **confirmed, reproduced**

The plan names `AlertRulesAudienceTest` three times (Findings, AD-15, Phase 4 step 1) and
`AlertRuleMetricsTest` zero times. To test the claim rather than accept it, I removed the
three fixture lines `9214902` added to `AlertRuleMetricsTest.setUp` and re-ran the class:

```
Tests run: 8, Failures: 3
  everyReferencedApplicationMetricExists        — metric in alerts.yml, in no exposition
  annotationLabelsSurviveTheRulesAggregation    — {{ $labels.botGroupId }} → `<no value>`
  productRoutedRulesKeepTheProductLabel         — audience: product, no product label
```

Three failures, exactly the three Dev described, in that shape. The file was restored
immediately and the working tree is clean. Amendment B1 records the general rule.

### 2. The Backlog line does not exist — **confirmed**

`git show a07e103:CLAUDE.md`, Backlog section: the only restart-adjacent entries are the
open "Restart lifecycle bug" (`/restart` failing with `ValidationException`, unrelated) and
"Review `Bot.java` methods". `git log --all -S"DEAD_GROUP_RESTART" -- CLAUDE.md` and
`-S"one-click restartable" -- CLAUDE.md` each return exactly one commit: `6457e59`, this
phase's own. DEAD_GROUP_RESTART never touched CLAUDE.md. The manual framing lived only in
the alert descriptions and in the file's silence. Amendment B2.

### 3. `alerts.yml:222` and the `for: 5m` comment — **both confirmed**

`a07e103:prometheus/alerts.yml:222` *is* the `EnvironmentGroupDead` description and *does*
open "A DEAD group does not recover on its own" — the reference was accurate. And
`:208-212` is the `for: 5m` rationale: *"it never clears by itself — a DEAD group stays DEAD
until an operator restarts it. The window exists only so an operator-initiated restart in
progress does not page anyone."* Same falsified premise, four lines above the line the plan
names, justifying the one knob AD-15 tells Dev **not** to change. Correcting the description
and leaving that comment would have left the rule's stated reason for existing in
contradiction with the rule's behaviour.

Dev also corrected two further instances the plan did not name — `alerts.yml:47-51` (the
file header) and `alertmanager/alertmanager.yml`'s evidence-route comment. Both are the same
sentence, both are comment-only, and the alertmanager one genuinely changes direction
(evidence pinning matters *more* when a group can self-heal without anyone opening the box).
In scope. Amendment B3.

## Drift

**One item, docs-only, must be corrected. It does not block the enable.**

`9214902`'s `EnvironmentGroupDead` description tells operators auto-recovery rebuilds the
runtime "reusing the existing accounts, **no new users and no new deposits**", and
`6457e59`'s CLAUDE.md says "Existing accounts are re-authenticated; nothing registers users,
**nothing deposits**, no DB group is recreated."

Unqualified, the deposit half is false, and this branch already knows it is. `a4ee3f2`
landed precisely this correction on `startForRecovery`'s javadoc after the Phase 1-3 pass,
and the Phase 1-3 verdict above accepted it in those words: *"never deposits" → "adds no
deposit a manual `/restart` would not make"*. The javadoc now warns in terms:

> **It "never deposits" only in the sense that it adds no deposit a manual `/restart` would
> not make.** ... Read "recovery cannot move money" into this and you will be wrong about a
> group with auto-deposit on.

The mechanism is `BettingMiniGameBot.onNewSession` (`:341-355`, and `SlotMachineBot:178`):
`if (behavior.isAutoDepositEnabled() && balance < getMinBalance()) deposit()`, evaluated at
**every new session**, and `startLocked` builds the config with the group's own
`autoDepositEnabled` (`BotGroupBehaviorService:829`). So a recovered auto-deposit group tops
up on its first round. The plan's own V9 states the correct standard — "only auto-deposit
lines if the group has `autoDepositEnabled=true` ... exactly the same lines a manual
`/restart` produces" — so the plan is right and the text drifted from it.

Why it matters more now than it would have on staging: these two sentences are the
operator-facing statement of what this feature costs, in a VipTalk product room and in the
file every future session reads, on a release that enables autonomous restarts on four
funded prod groups. `tptxg2` is recorded in MEMORY as `autoDeposit off`, so today's prod
groups are probably unaffected in fact — which is exactly how a claim like this survives
until the group where it is false.

**Required correction** (no code, no test, two sentences):

- `prometheus/alerts.yml`, `EnvironmentGroupDead.description`: "no new users and no new
  deposits" → **"no new users, and no deposit a manual `/restart` would not also make — a
  group with auto-deposit on still tops up when it resumes playing"**.
- `CLAUDE.md`, the recovery subsection: "nothing deposits" → the same qualification, or
  simply cite the javadoc's wording.

Everything else in these three commits is faithful.

## Out-of-scope changes

- `alertmanager/alertmanager.yml` (comment-only) and `alerts.yml:47-51` (comment-only) are
  not named by Phase 4 step 1 but carry the same falsified sentence; correcting them is the
  step's intent, not an expansion of it. No route, matcher, receiver or `continue:` flag
  changed — verified line by line.
- `AlertRuleMetricsTest`'s fixture addition is not named by the plan either, and is
  mandatory: without it the phase does not build (see above). Amendment B1.
- Nothing else. The three commits touch 5 files. No production Java, no
  `application.properties`, no `deploy.sh`.
- The working tree's unrelated uncommitted files (`Aviator.js`, `bc.js`, the WIN79/119
  sources, `deploy.sh`, `TaiXiuMessages/*.js`) are unchanged by this phase and unchanged by
  this review.

## What enabling newly exposes in the already-committed earlier phases

None of these is a defect in the landed code. They are properties of Phases 1-3 that were
harmless while `bot.recovery.enabled=false` and become live the moment the switch is set —
which the plan expected to happen on staging, with a soak, and which is now happening on
prod. Listed so the releaser and the operator can act on them rather than discover them.

1. **The first enabled tick acts on prod's Mongo history, not on today's incidents.** AD-4
   drives the reconciler off the **persisted** `targetStatus`, which is the feature's best
   property and, at first enable, its sharpest edge: every group in prod that is `DEAD`
   today — including any that died long ago and that nobody intends to bring back — is a
   candidate, one per 60 s, as soon as its environment probes healthy twice. **Enumerate
   them before the restart that enables the flag and park anything that should stay down
   with `POST /{id}/stop`.** Added to the plan as verification step **V7p**.
2. **`BOT_RECOVERY_ENABLED=false` is not a full rollback.** It gates the reconciler only.
   Three behaviour changes in this image are unconditional: Phase 2's `stop()` persisting
   `STOPPED` for a runtime-less group, A1's `restart()` status-restore on a failed start
   half, and A2's `handleBotGroupDeath` now taking the per-group lock with
   `tryLock(2, SECONDS)` on the health-monitor thread. The last one sits in the death path
   of four live prod groups and runs whatever the flag says. Reverting those means rolling
   back the image.
3. **The probe runs with the flag off, and will run on prod.** By design (the plan requires
   it), but it means prod's `webSocketMiniUrl` receives an **anonymous, token-less** WS
   upgrade from the bot host, once per 60 s per URL, whenever a DEAD candidate exists. It is
   bounded and it touches no account, but it is a new traffic shape from a host whose
   relationship with the gateway is IP-whitelisting, so it is worth knowing it exists before
   someone asks what it is.
4. **A missing/unreadable `Environment` splits the recovery counters.**
   `DeadGroupRecoveryScheduler.resolveProduct` (`:505-517`) returns `null` on any exception
   or missing product, `BotMdc.setGroupContext` skips null values, and `mdcTags()` therefore
   omits the tag — so an attempt taken during a Mongo blip registers a *second* series of
   `group_recovery_attempts_total` without `product`. Impact is small: `botGroupId` and
   `environmentId` still land, and Alertmanager's room resolution falls back to
   `environmentId` → Mongo, so the routing survives. Worth knowing before someone reads two
   series for one counter as a bug in the fixture that B1 added.
5. **The zero-bot guard's `DEAD` is a recovery candidate.** `BotGroupBehaviorService:495`
   marks a group `DEAD` when it starts 0/N bots — a credentials or gateway-auth problem, not
   an outage. On prod that group will burn all six attempts over ~1 h 52 m and then page
   `EnvironmentGroupRecoveryExhausted` (critical). That is the designed behaviour and the
   alert says the right thing; it is simply the most likely first firing of a brand-new
   critical rule, and it should not be read as a recovery bug.

## Amendments to the plan

Added as a marked `## Amendment — 2026-09-11 (Phase 4 compliance)` section at the bottom of
`docs/plans/DEAD_GROUP_AUTO_RECOVERY.md`, plus three in-place pointers (Phase 4 heading,
Phase 4 step 3, Phase 5 heading). Nothing above was rewritten; no AD, eligibility condition,
budget value, probe predicate or existing verification step changed.

- **B1** — "add an alert rule" in this repo costs an `AlertRuleMetricsTest` fixture emission
  under the right MDC, not just `labels.audience`. Stated as a standing rule for future
  phases, because every future rule pays it.
- **B2** — Phase 4 step 3's Backlog line does not exist; the step is corrected to *add* an
  entry.
- **B3** — `alerts.yml:222` was accurate but incomplete: the same falsified sentence is
  asserted at `:208-212` (the `for: 5m` rationale), at `:47-51` (file header) and in
  `alertmanager.yml`'s evidence route. Step 1 corrected to name all four.
- **C** — Phase 5's staging soak was bypassed. Records what the plan sequenced, what is
  actually happening (prod enable on 2026-09-11, four live groups, first enabled run
  anywhere, user's knowing decision), the two real mitigations (compiled default stays
  `false` so the kill switch is a container restart; both alert rules ship with it), that
  Phase 5's remaining content is one step, and that V8/V10/V11 are not runnable in this
  release — with the prod-enable verification set spelled out and the new **V7p**
  pre-enable inventory step added.
