# Compliance — GATEWAY_REQUEST_BUDGET, Phases 3 (fix round), 4 and 5 — the pre-release gate

Branch: `feature/gateway-request-budget`
Plan reviewed: `docs/plans/GATEWAY_REQUEST_BUDGET.md` at `f1cbc32` (A1-A31), read with the
amendments overriding the body. The body's "Phase 4" is real Phase 5 and its "Phase 5" is real
Phase 6 (out of scope). **A32 has been appended by this pass and is not committed.**
Diff reviewed: `git diff 4bad37a..5b862bf`. That is 29 commits and 95 files, +12,538 / −1,054. It extends
`compliance-phase3.md`, which covered `6a8d69e..4bad37a` (Phase 3 as first shipped) and
is not redone here. The range contains:
- the Phase 3 fix round (`5e46af5`, `391bdff`, `7326eb7`)
- Phase 4 and its fix round (`13b0084..f1cbc32`)
- Phase 5 (`26d770e..5b862bf`), which includes the fixes for `review-phase4-fixround.md`

Provenance: `git diff --name-only 4bad37a..5b862bf` has no RIK, ZicZac or Aviator file. The
uncommitted tree was ignored.

## Verdict

**Final (after the re-check at `66c5d38`, below): PLAN_AMENDED. The diff is accepted and the branch
is cleared for the single deployment, subject to the preconditions in A32.4.** S1 and S2 are
resolved. The fix round's six new divergences are accepted, and two of them correct AD-13
(A32.7).

### Initial verdict at `5b862bf` (superseded)

**SEND_BACK_TO_DEV.** The scope is narrow: two small items, S1 and S2. The plan is also amended
(A32): A30 is ratified, all fourteen Phase 5 divergences are accepted, and the release
verification sequence is corrected.

Phases 3, 4 and 5 are implemented. Every A28 and A29 obligation is discharged. Each of the
fourteen divergences Dev reported was checked and accepted, and three unrecorded ones from
earlier rounds were also accepted (A32.2). Two things stop the deploy:

- **S1 (drift).** A16.2 requires that a start refused by an open circuit report the edge block
  in `lastError`. The code reports `Started 0/N bots — all bot creations failed`, which on
  `GET /{id}/status` reads exactly like an auth outage.
- **S2 (plan oversight, now a requirement through A32.3).** `GatewayEdgeBlocked` reads
  `gateway_circuit_open`, and that gauge can only become 1 under `enforce`. Prod stays in
  `observe` for at least a week after this deploy. It is unpaced and has no block alert, and
  prod is where the one real block happened.

Both fixes are a few lines plus one test each. After they land, a **re-check of S1/S2 only** is
enough to clear this gate.

### Evidence taken for this pass (clean detached worktree at `5b862bf`)

| Check | Result |
|---|---|
| `mvn -o -DskipTests install`, all five modules | SUCCESS: the tip needs no uncommitted work |
| V3g with A24.2's exact command (`-pl bot-engine -am … -Dsurefire.failIfNoSpecifiedTests=false`) | **4 tests, 0 failures, 37 s**, so V3g is executable as written |
| Phase 5 classes: `GatewayCircuitBreakerTest`, `CloudflareBlockDetectorTest`, `ApiGatewayClientLoginTest`, `ApiGatewayClientBlockTest`, `GatewayClearanceProbeTest`, `BotWsEdgeBlockTest`, `SlidingWindowGatewayBudgetCircuitRefusalTest`, `DeadGroupRecoverySchedulerTest`, `EnvironmentProbeSchedulerTest`, `EnvironmentWsProbeClassificationTest`, `GatewayCallSiteGuardTest`, `AlertRuleMetricsTest`, `AlertRulesAudienceTest`, `RegistrationWorkerTest` | 153 tests, 0 failures, 0 errors |
| Full suite | not run: that is QA's step, measured in a worktree, never in the dirty tree |

Side effect: the `install` wrote this branch's SNAPSHOT jars into `~/.m2`.

---

## Item 1: A30, a subagent amending the spec it was measured against

**Technically sound, and ratified in A32.1. Procedurally it was not legitimate when written.**

- **Who wrote it.** A30 was committed in `636a2b5` by the Phase 4 dev session.
  `AGENTIC_WORKFLOW.md` reserves plan amendments for Architect-2. Without a check, the agent
  being measured would have moved its own yardstick. That check is this pass.
- **Whether it holds.** I verified it against the deployed lineage, not only against the
  branch.
  - `main` (`8a5ee1a`) already sends `update-fullname.aspx` with only the admin `X-TOKEN` and the
    `username` in the body. `sessionToken` was never read on any branch.
  - That fact is stronger evidence than the loopback test A30 cites. `resumingDoesNotLogIn`
    proves that *our code* makes no login call. It cannot prove that *gwms* accepts the call
    without a session.
  - Production can prove it. Every account named on prod, including the 500 `liengbot*`
    accounts and the RIK hand-naming, was named by exactly this request.
- **What A30 still gets wrong.** It says "TWO requests, not three". The worker persists
  `registeredCount` before it names the account, so:
  - The **ordinary** resume costs **one** request (`update-fullname`).
  - It costs **two** only if the JVM died between sending `register` and persisting the
    counter.
  - It is never three.
  - Display-name re-rolls on a pool collision (about 43%) add to every case, and no amendment
    priced those.

  A32.1 records the corrected figures.
- **Deleting the dead parameter was the right call.** As A30 argues, a dead argument is how the
  three-request arithmetic would be re-derived.

## Item 2: Phase 5's fourteen divergences (A32.2)

| # | Divergence | Verified | Ruling |
|---|---|---|---|
| 1 | circuit-open `break` → refuse queued waiters | `admitWaitersLocked` completes each waiter exceptionally, and `reportEdgeBlock` runs that pass under the opening lock. The test uses ESSENTIAL's unbounded wait without moving the clock, so reverting to `break` hangs it | accept: this is what A16.2 actually says |
| 2 | A29.2's "not conditional on recovery" holds vacuously | `evaluateCandidate` only runs when recovery is enabled. `EnvironmentProbeScheduler.anyCircuitOpen` runs regardless of the flag, ahead of the live-sibling short-circuit | accept |
| 3 | A27.2/A27.3 already done | The annotation is rewritten. The `"inert until Phase 3"` comment is gone (`BotGroupBehaviorService:1455`). A27.1 landed in `1f0cb2b` | accept |
| 4 | seven `endpoint` values | `GatewayEndpoint`: six request kinds plus `circuit-probe`, bounded, never a URL | accept |
| 5 | an unanswered probe keeps the circuit open | `runCircuitProbe`: `answer == null` re-arms the timer | accept: AD-13 says "response" |
| 6 | no separate HALF_OPEN | `probeInFlight` plus refusal throughout | accept |
| 7 | observe = count + throttled WARN | `reportEdgeBlock` returns early under observe, and `warnObservedBlock` re-arms after 5 m | accept: AD-23 verbatim |
| 8 | `group_recovery_skipped_total{outcome="circuit_open"}` | separate meter, so `EnvironmentGroupRecoveryFlapping`'s arithmetic is untouched | accept |
| 9 | probe classifies CF 403 as `EDGE_BLOCK` | checked before the `< 500` rule | accept. It is an exception to DEAD_GROUP_AUTO_RECOVERY AD-2, and CLAUDE.md must say so in Phase 6 |
| 10 | V4d's text is stale | the ERROR says `circuit open: nothing is sent … except one clearance probe every PT1H` | V-step corrected (A32.5) |
| 11 | probe URL bound late | bound on every `forEnvironment` call | accept: the WS probe scheduler can create the budget first |
| 12 | fixture IP → TEST-NET | diffed against `cf-block-raw.txt`: identical apart from `203.0.113.10` | accept |
| 13 | login errors carry status + envelope fields | `parseLoginResponse` / `envelope()`: never the body, no host | accept: AD-12's promise |
| 14 | prod has no block alert until Phase 6 | `GatewayEdgeBlocked` is `gateway_circuit_open == 1` | **plan oversight, see S2** |

Three more divergences no amendment had recorded, all accepted in A32.2:

- **The bound on AD-10's pre-deposit refresh.** It is now `min(max-wait, watchdog/4)`
  (`5e46af5`, review F1).
- **`essential.ceiling=850`.** It lowers the effective cap. It does not reserve headroom, so
  A9's "escape hatch" text is withdrawn.
- **AD-22's Netty WebSocket endpoint was never built.** Its only consumer was V4d's "second
  group completes", and A16.2 had already made that sentence false. **This is a judgment call.**
  The plan did ask for the endpoint. If the user wants a stub-backed group to reach ACTIVE on the
  laptop, this becomes a third send-back item.

## Item 3: the branch against the amended plan

### Phase 3 fix round (`5e46af5`) — implemented
All sixteen review findings are addressed (see `review-phase3.md` / `qa-phase3.md`). The plan
deviations in this round are F1's watchdog bound and the reversed description of
`essential.ceiling`. Both are recorded in A32.2.

### Phase 4 — asynchronous registration — implemented
A6 Phase 4, items 1-6:
- Item 1 (model fields): present.
- Item 2 (`save` / `update`): A31.1's raise-detection and seeding are implemented.
- Item 3 (worker): persisted-state selection, `$set` persistence, the observe pacing, and the
  group-carrying scope (A28.6) are all implemented.
- Item 4 (retry endpoint and guards): `startLocked`'s 400 and the ActivationScheduler guard
  (gates START only, A31.3) are in place.
- Item 5 (`registerOne`): `user.registration.parallelism` is deleted.
- Item 6 (metrics, `RegistrationStalled`): done, plus `RegistrationNotProgressing` (A31.4).

The counters are render-only (`READ_ONLY` plus excluded from the mapper; A28.2). A30 and A31
govern the divergences.

### Phase 5 — Cloudflare detection, circuit, in-repo login — implemented except S1
The original Phase 4 change list, items 1-7:
1. The detector, both entry points.
2. The circuit with `reportEdgeBlock`, the probe on the budget's scheduler, the meters
   pre-registered at zero, and the ERROR and INFO lines.
3. Classification in `httpCall`, the single insertion point (A29.4). The login is in-repo with
   `GATEWAY_REQUEST_TIMEOUT`. `BoundedLogin` is deleted, `AuthClient` is kept only for
   `generateFingerprint`, and the `RuntimeException` contract is unchanged.
4. The recovery skip.
5. Both alerts (A16.4), with no added route and no shorter repeat, plus the circuit panel.
6. Block mode on `StubGateway`, and `StubGatewayMain`.
7. The guard test.

A29.1 to A29.5 are all discharged, and A31.8's circuit-open case is a parameterised arm of
`aBudgetRefusalCostsNothing`. Every circuit outcome goes down the existing non-terminal paths:
- `authenticate`'s budget arm (no `incLogin(false)`)
- `deposit` and `setDisplayName`, which catch only checked exceptions
- the worker's deferral

### review-phase4-fixround.md — addressed in Phase 5's commits

| Finding | Commit | State |
|---|---|---|
| B2 (several starved groups) | `c5ebe13` | fixed with `deferralSeq` ordering, and the test fails without it |
| S3 (non-JSON counted as transport) | `35e9612` | fixed. The cause-cycle smell is fixed in the same commit |
| S1/S4 tests | `5b862bf` | pinned. S2 is deliberately not pinned (equivalent by construction; the reviewer accepted this) |
| circuit-open `break` | `b8644ec` | fixed |
| `RegistrationNotProgressing` | `550d779` | fixed |
| `currentTarget` on a deleted doc | `a97f335` | fixed |
| startup line | `82e7e6f` | fixed |
| in-pass gauge staleness sentence | — | **not done**. The field javadoc still says the stamp "bounds the in-pass staleness". This is a smell for the reviewer to judge, not a plan item |

## Drift

**S1: `lastError` on a start refused by an open circuit (A16.2).**

Where:
- `BotGroupBehaviorService` zero-bot branch, `:1177`: `zeroBotReason`.
- The per-bot loop, `:1387-1404`.

A16.2 says a start during a block "ends `0/N` with a `lastError` that names the edge block". Today:
- Every login is refused in the JVM.
- Each bot is tagged `"budget"` (correct).
- `/status` reads `Started 0/N bots — all bot creations failed`.
- No test covers this path in `bot-app` (`grep GatewayCircuitOpenException bot-app/src/test` finds none on the start path).

A20.8's "verified" claim is about a start that itself throws. It does not cover per-bot refusals,
which is the only form a circuit refusal takes during a build.

What should happen: when the build's failures are `GatewayCircuitOpenException`, put that
exception's message (ours, client-safe, environment and cf-ray only) into `lastError`. Add a test.
**Prefer the zero-bot branch to failing fast before the build.** A fail-fast leaves the group
neither running nor DEAD, so `ActivationScheduler` re-decides START every minute for the whole
day-long block. Persisting DEAD is what makes the group a recovery candidate once the probe closes
the circuit.

Related note for the reviewer, not a compliance item: the same refused start writes one ERROR,
with a stack trace, per bot (`:1398`). That is N lines per start into Loki. It is bounded per
start event (`ActivationEvaluator` answers NONE for DEAD), but a 500-bot `/start` during a block is
500 stack traces.

**S2: an `observe`-mode block alert (A32.3).** This is a plan oversight; see the Verdict and
A32.3 for the rule and its constraints. The counters are already pre-registered at zero. Extend
`AlertRulesAudienceTest` and `AlertRuleMetricsTest`. A user waiver is acceptable if recorded in
`release.md`.

## Out-of-scope changes

None found. Two items go beyond the plan's letter, both inside its intent and both accepted:
EnvironmentWsProbe's `EDGE_BLOCK`, and `group_recovery_skipped_total`.

## The release sequence: is it executable against what was built? (A32.4 / A32.5)

| Step | State |
|---|---|
| V0a-V0c | OK |
| V1a, V1e | **cannot pass on staging**, which runs `enforce` from this deploy. They run on prod (observe) only; on staging V3a replaces V1a |
| V1b-V1d | OK |
| V2a-V2i, V2k | OK. The V2c and V3c log texts were matched against the code |
| V2j | **broken by Phase 4**. It needs `existingGroup: true`, or `/start` answers 400 `still registering` |
| V3a, V3c, V3e, V3f | OK |
| V3b | `202` must be `200` |
| V3d | as corrected by A25 |
| V3g | OK, re-run green |
| V4a-V4d, V4f, V4g | OK. `still registering (n/m accounts)` matches |
| V4e | **stale grep**. The line is `Registration worker: N bot groups resuming account registration`, with no group id |
| block-detection steps (body "V4a-V4d") | **renamed V5a-V5d** (the names collided with A8's V4 block). V5a also covers `GatewayEdgeBlockUncleared` and the seven endpoint series. V5c is the staging-before-prod gate |
| V5d (was V4d) | **rewritten**. The old version used `PT15M`. Its "second group completes" contradicts A16.2, and the stub has no WS endpoint. It also never said the local app must run in `enforce`. The new form is HTTP-only: a login block, a registration deferral, probes at a `2m` interval, unblock, close, resume |
| V6a / V6b (were V5b / V5c) | Phase 6 |

Preconditions now written into A32.4. Each would have bitten at the deploy:

1. **Build from a clean worktree at the tip.** The dirty tree carries RIK display-name
   `EXISTED` work that overlaps the committed `isDisplayNameTaken`. This is open item 2 in the
   handoff and must be settled first.
2. **Staging V5c before any prod deploy.** It is the per-brand login gate in AD-12.
3. **Restart Prometheus with `docker compose restart prometheus`, not SIGHUP.** `alerts.yml` is
   a single-file bind mount, so a replaced file keeps its old inode inside the container. There
   is no `--web.enable-lifecycle`, so there is no HTTP reload either.
4. The Releaser targets prod only when briefed explicitly (MEMORY).

## Amendments to the plan

`## Amendment — 2026-10-01 (pre-release compliance on Phases 3-fix, 4 and 5; A32)`, appended,
**not committed**:

- **A32.1:** A30 ratified with corrected arithmetic and its real evidence; the process note.
- **A32.2:** the fourteen divergences and three unrecorded ones, each ruled on.
- **A32.3:** S1 (drift) and S2 (oversight → requirement).
- **A32.4:** release preconditions.
- **A32.5:** V-step corrections and renaming.
- **A32.6:** what Phase 6 inherits.

---

## Re-check at `66c5d38` (fix round `82bdbc2..66c5d38`, 13 commits on QA's tip)

Provenance: no RIK, ZicZac or Aviator file in `5b862bf..66c5d38`.

Evidence, from a clean detached worktree at `66c5d38`: the 18 affected test classes plus V3g
(`GatewayBudgetEscalationIT`) gave **142 tests, 0 failures, BUILD SUCCESS**. I did not run the
full suite; that is QA's step.

### S1 — RESOLVED (`2af6719`)
- `createBotsInParallel` keeps the first `GatewayCircuitOpenException` it meets.
- The zero-bot branch (`BotGroupBehaviorService:1189`) writes `Started 0/N bots — Gateway edge
  block on environment <env> (cf-ray …) …` to both `lastFailureReason` and `lastError`.
- The branch still persists DEAD, so ActivationScheduler does not re-decide START every minute
  during a block, and the group becomes a recovery candidate once the circuit closes.
- Each refused bot now logs at DEBUG. One group-level WARN (`:1449`) carries the count and the
  cf-ray.
- Every other per-bot creation failure keeps its ERROR.
- `BotGroupBehaviorServiceAsyncStartTest` pins the `lastError` prefix and cf-ray, the absence of
  any per-bot ERROR, and the single WARN.

### S2 — RESOLVED (`9189f5c`)
`GatewayEdgeBlockObserved` is at `alerts.yml:644`:
```
(sum by (environmentId, product) (increase(gateway_edge_blocks_total{endpoint!="circuit-probe"}[5m])) > 0)
  and on (environmentId) gateway_circuit_open == 0
```
- `critical`, `audience: product`, `for: 1m`.
- The annotation leads with stopping the traffic, which is right because nothing else stops it
  in observe. It then covers the SA ticket with the cf-ray from the observe WARN.
- PromQL precedence is correct: `==` binds tighter than `and`.
- The series are pre-registered at zero per endpoint, so the rule fires on the first block.
- The scoping clause prevents a double page with `GatewayEdgeBlocked` under enforce.
- `AlertRuleMetricsTest` pins the expression, the probe exclusion and the scoping.

### New divergences (recorded as A32.7) — all accepted

| | Divergence | Ruling |
|---|---|---|
| a | A circuit opened by a WS upgrade is cleared by an anonymous upgrade probe against the WS host, counted through `countWsUpgrade`. It falls back to the API probe when no WS probe is bound | **AD-13 oversight, amended.** Cloudflare counts per egress IP and per zone, and on 119 the API and WS hosts are separate zones. An API probe could close a circuit the WS host still enforces |
| b | `group_recovery_skipped_total` is pre-registered under the group MDC, so a run of N skips reads N−1 | accept. This is CLAUDE.md's mandated pattern, and no alert reads the counter |
| c | only circuit refusals are demoted to DEBUG | accept |
| d | a throwing or unserialisable login factory is not counted as a login failure | accept, on the same principle as A4 |
| e | a drift balance read that itself detects the block answers empty instead of throwing | accept. This is AD-10 verbatim |
| f | on the HTTP path, a block requires Cloudflare's page markers in the body. A bodiless `text/html` response (the WS path) still counts. A WS page over 8 KB is not classified | **AD-13 oversight, amended.** The edge headers sit on every proxied response, so IIS's own HTML 403 would have opened a circuit for a whole brand. There are two accepted misses, both of which fail toward today's behaviour: a Cloudflare challenge page, and a WS block page over 8 KB |

Also checked:
- `8771bd6`: a waiter refused at its own timeout instant is no longer sent.
- `ef9d836`: scoped DEBUG now sees the waiter's MDC.
- `60b4c00`: the handshake failure is kept as a suppressed exception.
- `7576d78`: the gauge-staleness javadoc is fixed. This was the smell that the first pass found
  still open.
- `c1505d8`: the alert no longer advises restarting into a live block. **A16.5's restart advice
  is withdrawn** in A32.7.

### V-step updates (A32.5)
- **V5a** now expects three `GatewayEdge*` rules in `/api/v1/rules`: `GatewayEdgeBlocked`,
  `GatewayEdgeBlockObserved` and `GatewayEdgeBlockUncleared`.
- **V5d step 1** now expects:
  - the group WARN;
  - no per-bot ERROR;
  - `lastError` starting with `Started 0/2 bots — Gateway edge block on environment … (cf-ray
    a3c7e4004acc850e-HKG)`.
- **V5d** now notes that its circuit opens on the HTTP login, so it is probed through the API's
  `verifytoken.aspx`. A WS-opened circuit probes the WS host instead (counted stamps, `answered
  HTTP 101`, no `verifytoken.aspx?token=probe`). That case cannot be reproduced on the stub: block
  mode refuses the login first. Unit and loopback tests are its only coverage.

### Still before the deploy (unchanged from A32.4)
- Build from a clean worktree at the tip, after settling the RIK `EXISTED` overlap.
- Run V3g on the laptop.
- Deploy to staging, and pass V5c, before prod.
- `docker compose restart prometheus`, not SIGHUP.
- Brief the Releaser explicitly for prod.

