# Compliance — DEAD_GROUP_AUTO_RECOVERY

Branch: `feature/dead-group-auto-recovery`
Plan reviewed: `docs/plans/DEAD_GROUP_AUTO_RECOVERY.md` (working-tree copy, untracked; V4
amendment by the user included)
Diff reviewed: `git diff 7d0ec86..HEAD` (`main` == `7d0ec86`, so this is also
`git diff main..HEAD`) — 9 commits, 13 files, +2744/-3
Build: `mvn -pl bot-engine,bot-app -am test` — **1208 tests, 0 failures, BUILD SUCCESS**

## Verdict

SEND_BACK_TO_DEV

One blocking item: **AD-5's opt-out is not enforced in code.** It is a one-line
predicate change plus two tests. Everything else in Phases 1-3 is faithful, and the
absence of Phases 4-5 is per instruction, not a gap.

## Phase-by-phase

### Phase 1 — Environment WebSocket reachability probe, observe-only
Status: **implemented** (with the AD-3 defect below, which lives in a Phase 1 file)

- `RecoveryEligibility` is pure, static, Spring-free and I/O-free, with the plan's exact
  signature; conditions 1 and 7 are correctly left to the scheduler. Conditions 3, 4, 5, 6
  are implemented as specified; condition 2 is the defect.
- `EnvironmentWsProbe` matches AD-2 point for point: shared `HttpClient` with
  `connectTimeout` + `followRedirects(NEVER)`, `buildAsync(...).get(timeout, ...)`,
  `webSocket.abort()` in a `finally`, and the full failure taxonomy
  (`WebSocketHandshakeException` → `<500` / `>=500`, `HttpConnectTimeoutException` /
  `TimeoutException` → `TIMEOUT`, `SSLException` → `TLS_ERROR`, `ConnectException` /
  `UnresolvedAddressException` → `CONNECT_ERROR`, else `ERROR`). `healthy() == OPEN ||
  HTTP_4XX`. Restricted headers are **filtered**, not caught-and-ignored, with the
  once-per-URL DEBUG line; `sec-websocket-*` is matched by prefix.
- The probe is anonymous end to end. No `ApiGatewayClient`, no `BotCredentials`, no
  `TokensProvider`, no `VingameWebSocketClient` is reachable from it — checked against the
  imports and the constructor. The Implementation-Notes prohibition holds.
- `EnvironmentProbeScheduler` follows the `ActivationScheduler` idiom (`@PostConstruct` /
  `@PreDestroy`, single virtual thread named `env-ws-probe`, per-item try/catch, MDC),
  de-duplicates by `webSocketMiniUrl`, short-circuits on AD-10, keeps the per-URL streak,
  exposes `isHealthy(envId)` (which fails **closed** for an un-probed environment — right),
  and emits **zero** traffic with no candidates. Metrics are registered directly on the
  `MeterRegistry` with explicit `{environmentId, product, outcome}` tags, never from MDC
  (AD-13); the `MultiGauge` is re-registered with `overwrite=true` each tick per the
  `InfoGaugeRefresher` idiom.
- Config: all four Phase 1 properties present, with the plan's values, placed after
  `bot.group.dead.threshold`, and the probe genuinely runs independently of
  `bot.recovery.enabled`.
- Tests: all three plan-named classes exist. `EnvironmentProbeSchedulerTest` covers
  candidate selection, URL de-dup, streak arithmetic, transition-only INFO, live-sibling
  short-circuit, and the zero-candidate silence.

### Phase 2 — `stop()` parks a runtime-less DEAD group as `STOPPED`
Status: **implemented**

Exactly the specified change and nothing more: under the same per-group lock, the
`runtime == null` branch loads the persisted group, keeps the historical WARN + no write
when it is already `STOPPED`, otherwise logs `Bot group {} has no runtime — persisting
STOPPED (was {})` at INFO and saves `STOPPED` + `lastStoppedAt`. `stopAndLogout` and
`restart` are untouched; the with-runtime path is byte-for-byte unchanged and is pinned by
`stop_withRuntimeIsUnchangedByTheRuntimeLessBranch`. Both plan-named tests are present.
The INFO string matches what V6 greps for.

### Phase 3 — The recovery reconciler (inert)
Status: **implemented** (subject to the same AD-3 defect, and one minor drift below)

- **AD-1 holds.** `startForRecovery` is the only new entry point, is a thin wrapper, takes
  the same `groupLocks` `ReentrantLock`, re-reads the persisted group **inside** the lock,
  re-asserts `RecoveryEligibility`, calls the existing private `startLocked`, catches
  nothing, and returns `runtime != null && actualStatus == ACTIVE && runningBotCount > 0`.
  No second lifecycle path, no new teardown, no new locking.
- **AD-6 holds and is genuinely inert.** `if (!enabled) return;` is the first statement of
  `reconcileAll`. Neither `group_recovery_*` counter is registered eagerly — both are
  lazily built inside `incGroupRecoveryAttempt` / `incGroupRecoveryExhausted`, reachable
  only past that gate. `disabledIsCompletelyInert` asserts `verifyNoInteractions` on all
  four collaborators plus an empty counter set plus no INFO. V2/V5/V7 will hold.
- **AD-8/AD-9/AD-12 hold.** Budget spent only on attempts; the probe-health gate is checked
  *before* the due check, so a down environment costs zero budget; backoff is
  `backoff[min(n-1, len-1)]`; exhaustion emits one ERROR + one counter, guarded by
  `exhaustedReported`, and then stops attempting. One single-threaded reconciler,
  `max-per-tick` default 1, blocking tick, earliest-due-first sort with dead-duration and
  id tie-breaks, first tick jittered. `healthy-streak=2` is enforced in `isHealthy`.
- **AD-11 holds.** `ConcurrentHashMap<String, RecoveryState>` and
  `ConcurrentHashMap<String, ProbeState>`, nothing persisted, both pruned each tick
  (`expireStates`, `states.keySet().retainAll`, `envUrls.keySet().retainAll`) so neither
  can grow.
- **AD-13 holds.** Both counters copy the `GROUP_DEAD_SECONDS_TOTAL` shape — `.tags(mdcTags())`,
  no `bot_` prefix, so `BotMdcTagsMeterFilter` leaves them alone — and are called under the
  group MDC set in `attempt()`.
- **AD-14 holds.** One INFO per attempt, one per outcome, one per probe *transition* (every
  individual result DEBUG), one ERROR on exhaustion. Nothing per-bot; no new `log.info(`
  lands in a per-bot class, so `PerBotInfoLogGuardTest` is unaffected — and it passes.
- **The money invariant holds.** `startForRecovery` reaches accounts only through
  `botFactory.createBot` (re-auth). `startForRecovery_neverRegistersOrDeposits` verifies
  exactly N `createBot` calls, that every `save` carries the existing id (registration in
  `BotGroupService.save` is gated on a null id, so it is structurally unreachable), and
  that no captured log line contains `registr` or `deposit`.
- Config: all six Phase 3 properties present with the plan's values; `backoff-minutes`
  binds as `int[]`.
- Tests: `DeadGroupRecoverySchedulerTest` covers every case the plan enumerates —
  unhealthy env, max-per-tick, backoff-on-failure-only, exhaust-once-then-stop, STOPPED,
  MANUAL_OFF, closed SCHEDULED window, earliest-due rotation — plus the two named
  `BotGroupBehaviorServiceRestartTest` cases.

### Phases 4 and 5
Status: **out of scope by instruction** — and correctly absent. `prometheus/alerts.yml`,
`docker-compose.yml` and `CLAUDE.md` are untouched by the branch, which is right: the
alert rules and the doc update are Phase 4 work.

## Drift

### BLOCKING — AD-5's opt-out is not enforced; AD-3 condition 2 admits a `STOPPED` group

`RecoveryEligibility.isCandidate(STOPPED, null, null, 20, DEAD, /* runtimeGroupDead */ true,
now, zone)` returns **`true`**. Condition 3 does not fire (the runtime is DEAD, not ACTIVE);
condition 2's second disjunct is satisfied by `runtimeGroupDead`; 4, 5, 6 pass. The same
shape survives `startForRecovery`'s re-assert, because it is the same predicate.

It is reachable through the selector, not only in theory:
`RecoveryCandidateSelector.select` unions `behaviorService.listDeadRuntimeGroupIds()` into
the persisted-DEAD set and then re-reads each of those ids with `repository.findById`, so a
row whose persisted target is `STOPPED` enters the candidate list by the memory-dead route
and is never re-checked against `STOPPED`.

The diff also asserts the opposite as fact.
`DeadGroupRecoveryScheduler`'s class javadoc, gate 2, reads:

> **`STOPPED` is the opt-out and it is permanent** (AD-5): a group an operator stopped
> fails the predicate forever, and there is no other way to express "leave it down".

That sentence is false of the predicate it documents. Independent of any reachability
argument, shipping it alongside a two-line predicate that contradicts it is a defect.

Test coverage has the same hole: `RecoveryEligibilityTest.stoppedIsNeverACandidate` and
`DeadGroupRecoverySchedulerTest.stoppedGroupIsNeverAttempted` both exercise `STOPPED` with
**no runtime**, which the predicate rejects on condition 2's first disjunct alone. Neither
touches the disputed shape.

**(a) Dev's reachability analysis — checked writer by writer, and it is correct.** Every
persisted writer of `STOPPED`:

| Site | Runtime state it leaves | Verdict |
|---|---|---|
| `BotGroupBehaviorService.stop()`, with-runtime (`:984`) | `teardownRuntimeMemory` ends in `runningGroups.remove(id)` **before** the save, all under the per-group lock | no runtime survives ✓ |
| `BotGroupBehaviorService.stop()`, runtime-less (`:968`, new in Phase 2) | entered only when `runningGroups.get(id) == null` | no runtime by definition ✓ |
| `BotGroupMapper.updateEntityFromDTO:181` (PATCH) | touches no runtime at all | **the hole** ✗ |

And the non-writers I checked on top of Dev's list:
- `stopAndLogout` sets only `runtime.actualStatus`, removes the runtime, and persists
  nothing (its javadoc says so; the sole caller deletes the document next). ✓
- `ActivationScheduler` never writes `targetStatus`; its only STOP action is
  `behaviorService.stop(id)`, and its `dead` flag (`targetStatus == DEAD ||
  getActualStatus(id) == DEAD`) makes `ActivationEvaluator.decide` return `NONE` for exactly
  the groups that carry a DEAD runtime — so it cannot even reach `stop()` on one. ✓
- `BotGroupController.runWithManualOverride` writes `activationMode`, never `targetStatus`. ✓
- `startLocked` writes only `ACTIVE` (`:589`) or `DEAD` (`:571`), and its `finally` removes
  the runtime from `runningGroups` on any failed start — so no failed start can leave a
  runtime behind a stale `STOPPED` row. ✓
- `handleBotGroupDeath` (`:2130`) writes `DEAD`. ✓

**One correction to Dev's framing.** Dev calls the shape "an override rather than a Stop",
which is true, but the *other* half of it is not exotic at all: a lingering DEAD runtime is
the **ordinary** in-JVM post-death state. Both `handleBotGroupDeath` and the zero-bot start
guard deliberately leave the runtime in `runningGroups` — the zero-bot branch says so in a
comment ("The runtime is intentionally NOT removed from runningGroups"), and that lingering
runtime is the whole premise of DEAD_GROUP_RESTART's reclaim. So the only unusual element is
choosing `PATCH` over `/stop` — and `PATCH {"targetStatus": ...}` is a live, exercised
surface: the plan's own V3 and V8 use `PATCH {"targetStatus":"DEAD"}` as the standard way to
manufacture a candidate. The shape is one keystroke away from the plan's own verification
script.

**A second, narrower path Dev did not list** (recorded, not blocking): `handleBotGroupDeath`
does not take the group lock — the plan's Implementation Notes already flag this. A
health-monitor tick already inside `monitorHealth` when an operator `/stop` runs can persist
`targetStatus=DEAD` *after* `stop()` persisted `STOPPED`, with the runtime already torn
down. Result: DB `DEAD`, no runtime — a textbook recovery candidate, an operator Stop
silently reversed. It is milliseconds wide, it is pre-existing, and no eligibility predicate
can see it (AD-3 condition 3 covers only the ACTIVE-runtime variant of the same race). Not
this phase's problem; worth a line in the Phase 4 CLAUDE.md subsection.

**(b) Ruling: AD-3 gains an explicit `STOPPED` veto. AD-5's wording is not narrowed.**

- AD-5 is the *only* operator opt-out from a feature that autonomously starts
  money-spending bots. An invariant of that weight belongs in the predicate, not in a
  whole-program reachability argument spanning five call sites — an argument that is
  correct today and that any future change to `stop()`, to the mapper, or a new bulk/admin
  status endpoint invalidates **silently**, with no test failing.
- The veto costs one line and loses nothing. Condition 2's disjunct exists to catch
  `handleBotGroupDeath`'s swallowed save (persisted `ACTIVE` + runtime `DEAD`); vetoing
  `STOPPED` leaves that case fully covered. There is no scenario in which recovering a
  group whose persisted target is `STOPPED` is desirable.
- The cost of error is asymmetric: veto-when-you-need-not means a group stays DEAD until an
  operator clicks (the status quo this feature improves on); no-veto-when-you-should means
  bots an operator deliberately parked start spending money unattended.
- Dev was right to escalate rather than pick a side silently. This is the resolution.

**The change to make** — in `RecoveryEligibility.isCandidate`, ahead of condition 2:

```java
// (2a) STOPPED is the operator opt-out and it is permanent (AD-5). It vetoes
// unconditionally, ahead of condition 2's disjunction: a persisted STOPPED with a
// lingering DEAD runtime otherwise satisfies condition 2 through its second
// disjunct, and a lingering DEAD runtime is the ordinary post-death state.
if (persistedTarget == BotGroupStatus.STOPPED) {
    return false;
}
```

Plus two tests, because the existing two do not reach this line:
1. `RecoveryEligibilityTest.stoppedWithADeadRuntimeIsStillNeverACandidate()` —
   `candidate(STOPPED, null, null, 20, DEAD, true, ...)` is `false`.
2. A `DeadGroupRecoverySchedulerTest` companion that feeds the shape through
   `listDeadRuntimeGroupIds` rather than through `findByTargetStatus(DEAD)`, since the
   selector's union is the path that actually produces it.

And the doc comment in `DeadGroupRecoveryScheduler` gate 2 becomes true rather than
aspirational.

**On the plan text.** AD-3 condition 2 should read, in substance: *persisted
`targetStatus == DEAD`, or an in-memory runtime exists with `isGroupDead()` — but a
persisted `targetStatus == STOPPED` vetoes unconditionally, whichever disjunct holds
(AD-5).* I have **not** edited the plan. My constraints permit a plan edit only under a
`PLAN_AMENDED` verdict, and `PLAN_AMENDED` means accepting the diff — which I cannot do
here, because the correct resolution is a code change, not a plan change. The plan wording
above should be folded in by whoever lands the fix.

### Minor drift (non-blocking, Dev's call)

1. **The attempt INFO line drops a documented field.** The plan specifies `... env <envId>
   healthy for <k> probes ...`; the code logs `env {} probe-healthy` with no `k`, because
   `EnvironmentProbeScheduler.isHealthy` returns a boolean and does not expose the streak.
   Cosmetic — V8's grep (`auto-recovery attempt 1/6`) still matches. Either expose the
   streak or drop the phrase from the plan at Phase 4.
2. **A successful recovery charges the budget.** `attempt()` sets `state.attempts =
   attemptNumber` and advances `nextDue` on success as well as failure. The plan's Phase 3
   text for the success branch says only "record `lastSuccess` and schedule the budget
   reset for `+settle-minutes`". Consequence: a group that recovers and re-dies inside the
   settle window resumes at attempt *n+1* rather than *n*. I read this as intentional and
   consistent with the plan's own anti-flap rationale for `settle-minutes`, and the
   `outcome="success"` counter still gives AD-15's `EnvironmentGroupRecoveryFlapping` rule
   exactly what it needs. Flagged so it is a decision, not an accident.

## Out-of-scope changes

- **`Aviator.js`** was picked up by the first Phase 1 commit (a pre-staged index entry) and
  untracked again in `f52739d`. **Net effect on `main..HEAD` is zero** — it does not appear
  in the branch diffstat — and the working-tree state is exactly as found (`AM`, empty
  blob staged, 729 bytes on disk). Handled honestly and documented in the commit message;
  no action needed, recorded for the record.
- `deploy.sh`, `TaiXiuMessages/*.js`, `docs/plans/AVIATOR_BOT.md` and
  `docs/reviews/VIPTALK_ALERTING_V2/release.md` remain uncommitted and untouched by the
  branch, as required.
- Nothing else. The branch touches 13 files, all of them named by Phases 1-3.

## Sanity check of the user's V4 amendment

**Reads correctly, and it is right about the code.** `EnvironmentProbeScheduler` defines
`OUTCOME_LIVE_SIBLING = "live_sibling"` and stamps it as the `outcome` tag on
`env_ws_probe_total` whenever `hasLiveSibling` short-circuits — so the healthy outcome set
on the wire is genuinely `{open, http_4xx, live_sibling}`, and the original two-value list
would have been satisfiable by a path it did not name. The amendment's escape hatch is also
accurate: `hasLiveSibling` fires only when `countOpenWsByEnv` reports `> 0` for that
environment, so picking a `$G` on an environment with no other running group does force a
real socket probe. And `live_sibling` feeds the streak like any other healthy result, so
`env_ws_probe_healthy{...} 1.0` still holds under the short-circuit — which is what the
step asserts next to it. No correction needed.

## Amendments to the plan

None. See "On the plan text" above for the AD-3 wording that should accompany the code fix.
