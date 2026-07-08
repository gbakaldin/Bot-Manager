# Compliance — CROWD_AWARE_COORDINATION

Branch: `feat/crowd-aware-coordination`
Plan reviewed: `docs/plans/CROWD_AWARE_COORDINATION.md` (at commit 784b39e)
Diff reviewed: `git diff main...feat/crowd-aware-coordination` (HEAD c6b50be)

## Verdict

PASS

## Re-review (2026-07-08, HEAD c6b50be)

The prior SEND_BACK_TO_DEV (below) flagged two blocking drifts. Fix commit `c6b50be`
resolves both, and does so within scope. Verified:

**Drift §1 (AD-C10 read-side) — RESOLVED.** `OptionSnapshot` + `OptionStateDTO` +
`buildCoordinationState` now surface the full AD-C10 per-option set:
- `crowdAdjustedBudget` (`B_crowd(o)`) — the previously MISSING field — is now read
  from the in-flight `RoundBudget` via `b.budgetOf(optionId)`
  (`BetCoordinator.snapshot():615`). This is the crowd-adjusted budget, not the
  round-independent internal `B(o)`. Verification steps 5–6 (the WP's headline
  counter-balance proof) are now achievable against the diff.
- `observedCrowdStake` — raw `v(o)` (`crowdStake.getOrDefault`, `:605`) — added, AND
  the pre-existing `crowdStake` field (pure `X(o) = max(0, v − committed)`) is kept
  alongside. No information lost; names are sane and match AD-C10's wording (observed
  raw `v` vs fleet-subtracted `X`).
- `observedCrowdCount` (`bc(o)`) — added, semantic-gated: populated only when
  `crowdAware && crowdCountSemantic != UNKNOWN` (`countKnown`, `:588/607`), else `0`.
  Honors AD-C5 (obs-only, never load-bearing) and AD-C10 ("when semantic known").
- Ordered iteration preserved: the loop still iterates `optionAffinities.entrySet()`
  and looks every field up by key — never the crowd/budget map values.

**Drift §2 (AD-C3 EndGame one-round-lag) — RESOLVED.** The carry-forward is real:
- `observeCrowd` now records every accepted distribution into `lastObservedCrowdStake`
  / `lastObservedCrowdCount` (`:442-445`), which survive the round boundary.
- `onRound` no longer clears-and-resets unconditionally: when
  `crowdAware && !lastObservedCrowdStake.isEmpty()` it seeds the new round's opening
  budget via `computeCrowdBudget(optionAffinities, cap, crowdStake)` from the carried
  distribution (`:288-298`). So the BOM/B52/Nohu EndGame `bs` now steers the *next*
  round, realizing the one-round-lag path.
- (a) **Reduction identity still bit-for-bit.** When `crowdAware` is false OR the
  carry-forward is empty, `seededBudget = targetBudget` — the same internal-tier map
  object, byte-for-byte. `ReductionIdentity` (3 tests) and `crowdOffRolloverStaysInternal`
  pass.
- (b) **Tip intra-round overwrite preserved.** `onRound` resets `currentCrowdSum = -1L`
  and does NOT set it from the seed, so the first fresh `observeCrowd` frame (even
  `incomingSum = 0`) satisfies `0 <= -1` false → applies and overwrites the lagged
  seed. `freshIntraRoundOverwritesSeed` + `highWaterResetsPerRound` pin this.
- (c) **Monotonic-by-arrival (newest-Σv-wins) is correct.** A straggler frame whose
  `incomingSum <= currentCrowdSum` is dropped (`:428`), so a slow bot's older/smaller
  snapshot cannot clobber a newer/larger one; the high-water mark resets per round so
  the first fresh frame always beats the lagged seed. `olderSmallerFrameIgnored` pins it.
  This is a sound realization of the AD-C3 "monotonic-by-arrival" note the plan already
  specified (Findings line 114) — not scope creep.

**Reviewer smells (no scope creep).** The only other production changes in `c6b50be`
are: the `computeCrowdBudget` `@param` javadoc rewrite (comment-only), and a
`HasCrowdBets` import reordering in `BettingMiniGameBot` (1-line, alphabetical). Nothing
outside the two drifts + named smells changed — no new config, no math change to the
off/on paths, no new runtime object.

**Tests.** 28 crowd-suite tests pass (new `CarryForward`, `MonotonicByArrival`,
`HealthFields` nested suites + the BOM carry-forward seam tests, plus the preserved
`ReductionIdentity`/`ValueOnly`/`CommittedPreserved`/`Staleness` pins). Build SUCCESS.

Both fixes were plumbing/logic against a correct plan and were implemented faithfully.
Verdict flips to PASS. The original SEND_BACK_TO_DEV analysis is retained below for the
record.

---

## Original review (HEAD d54d4e4) — SEND_BACK_TO_DEV

The coordinator math, config surface, marker dispatch, off-by-default posture, and
concurrency model are all faithful to the plan and the confirmed v1 scope decisions.
Two Phase-3/Phase-4 drifts block PASS: (1) the AD-C10 read-side crowd view surfaces
only 1 of the 3 specified per-option fields — and the missing `crowdAdjustedBudget`
makes Verification steps 5 and 6 unachievable against the diff; (2) the AD-C3 EndGame
one-round-lag seed is fed to the *finished* round and then discarded by the next
`onRound`, so the BOM/B52/Nohu degradation path is inert. Both were possible to
implement correctly against a correct plan → send back, not amend.

## Phase-by-phase

### Phase 1 — Config (2 fields: 1 on BotGroup, 1 on Game)
Status: implemented
Notes: `BotGroup.crowdAwareCoordination` (primitive boolean, defaults false → legacy
Mongo docs and existing groups unchanged) + `BotGroupDTO.crowdAwareCoordination`
(boxed, PATCH-null keeps) + hand-written mapper across `toDTO`/`toEntity`
(`orElse(false)`)/`updateEntityFromDTO` (replace-if-present) — exactly mirrors
`coordinationEnabled`. `CrowdCountSemantic { BETS, PLAYERS, UNKNOWN }` enum added under
`domain/game/model`; `Game.crowdCountSemantic` (`@Builder.Default UNKNOWN`) with a
null-safe `getEffectiveCrowdCountSemantic()` for legacy hydration; `GameDTO` +
`GameMapper` surface it (obs-only, no validation). Validation added in
`BettingGridRules`: `crowdAwareCoordination && !coordinationEnabled` → violation
"crowdAwareCoordination requires coordinationEnabled to be true" (AD-C6). Two config
fields exactly, no more. Enum default UNKNOWN, obs-only — never referenced by the
budget math. Matches AD-C5/AD-C6/AD-C7. Mapper tests present and pass.

### Phase 2 — Coordinator crowd core
Status: implemented
Notes: The load-bearing math is faithful. `computeCrowdBudget(affinities, cap,
crowdStake)` implements `B_crowd(o) = clamp(P(o)·(X_total+C) − X(o), 0, C)` with the
combined-share term evaluated as `weight * (crowdTotal + cap) / totalWeight` — the
**same integer floor-division ordering** as the internal tier. Crucially,
`computeTargetBudget` is now defined as `computeCrowdBudget(affinities, cap,
Map.of())`, so the crowd-off reduction is not a re-implementation — the internal-tier
budget is literally the `X=0` path through the same function. This is the strict
superset the plan required (AD-C2 special case), and the `ReductionIdentity` unit test
pins it. `X(o) = max(0, v(o) − committed(o))` per AD-C4/D3 (subtract own `committed`,
NOT the Tip-only `b` field — `CrowdOption.ownBet` is carried but unused). `bc`/count
never enters the math (`ValueOnly` test: garbage counts yield identical budgets). Only
`v` steers (AD-C5). `RoundBudget.withBudget(...)` preserves `committed`/
`committedAggregate` across the intra-round swap (`CommittedPreserved` test). Core is
Spring-free / scope-agnostic; single `ReentrantLock` + volatile `current` reused
(AD-C9); `observeCrowd` drops on gate-off and stale sid (`Gate`/`Staleness` tests);
recompute idempotent across N same-frame calls (`Concurrency` test). All 4-arg call
sites preserved via overloads.

### Phase 3 — Wiring + message read
Status: drifted
Notes: `HasCrowdBets { List<CrowdOption> crowdBets(); }` marker added and implemented
on exactly the 9 expected classes — Tip Update+End+Subscribe; BOM/B52/Nohu
End+Subscribe each — mapping `{eid,v,b,bc}`/`{eid,v,bc}` → `CrowdOption` (`b`→0 on the
no-`b` EndGame shape). No Tai-Xiu message implements it (verified: `AD-C7` no-op holds
structurally). BOM/B52/Nohu `UpdateBet` correctly does NOT implement the marker (their
`{gS,rmT}` frame is not faked into a crowd signal). `onUpdate` feeds
`observeCrowd(sidStore.get(), cb.crowdBets())` when `coordinator != null` — the Tip
intra-round live path, correct sid. Coordinator built with the crowd bit and count
semantic only for `coordinationEnabled` BETTING_MINI/TAI_XIU groups; INFO line
"Crowd-aware coordination enabled for group … (countSemantic=…)" gated on `crowdAware`.
Off path provably inert (marker-instanceof + `crowdAware` gate + null coordinator).

**Drift (blocking):** the `onEndGame` branch feeds `observeCrowd(endGameSessionId(msg),
cb.crowdBets())`, but at that point `current.sessionId()` is still the *finished*
round, so the observation updates the finished round's (now irrelevant) budget, and the
subsequent `onRound(nextSid)` unconditionally `crowdStake.clear()`s and resets to the
internal target — **discarding** the EndGame observation. The AD-C3 "seed the *next*
round's budget as a one-round-lagged prior" for BOM/B52/Nohu is therefore not achieved;
that degradation path is inert. Not covered by any test (the seam test exercises only
the Tip intra-round UpdateBet path). See Drift §2.

### Phase 4 — Read-side observability
Status: drifted
Notes: Crowd fields correctly landed on the EXISTING `CoordinationStateDTO`
(`crowdAware`, `crowdCountSemantic`) — no new top-level block (AD-C10 satisfied on that
axis). `snapshot()` extended under the same lock; `buildCoordinationState` iterates the
ORDERED `optionAffinities` key set (not the crowd map's values) — the plan's explicit
ordering caveat is honored. `onRoundComplete` DEBUG line gains the `crowd=[opt=o v=…
adj=…]` segment, crowd-tier only; `observeCrowd` recompute is TRACE-only. All correct.

**Drift (blocking):** AD-C10 specifies THREE per-`OptionStateDTO` crowd fields
(`observedCrowdStake` = latest `v(o)`; `crowdAdjustedBudget` = `B_crowd(o)`;
`observedCrowdCount` = `bc(o)` when semantic known). The diff surfaces only ONE
(`crowdStake`), and it holds pure `X(o) = max(0, v − committed)`, not raw `v(o)`. See
Drift §1. This is the gap Dev flagged; my call is that it is a genuine compliance gap.

## Drift

### §1 — AD-C10 per-option crowd view is 1-of-3 fields, and Verification 5/6 are unachievable
Where: `CoordinationStateDTO.OptionStateDTO`,
`BetCoordinator.OptionSnapshot`/`snapshot()`, `buildCoordinationState`.

AD-C10 (plan line 296) and Verification steps 5–7 require three per-option crowd
fields. The diff has one:

- **`crowdAdjustedBudget` (`B_crowd(o)`) — MISSING.** The DTO's `targetBudget` is the
  round-independent internal `B(o)` (`targetBudget.getOrDefault(...)` in `snapshot()`),
  NOT the crowd-adjusted budget. The crowd-adjusted value exists in the coordinator
  (`current.budget()` / `RoundBudget.budgetOf`) but is never plumbed to the snapshot or
  DTO. **Consequence:** Verification step 5 ("per-option … `crowdAdjustedBudget`") and
  step 6 ("the option the crowd over-fills to show a **lower `crowdAdjustedBudget`**")
  reference a field that does not exist in the diff. Step 6 is the WP's headline proof
  (fleet counter-balances the crowd) and cannot be run. This makes the `## Verification`
  section not achievable against the current diff — squarely a compliance failure.
- **`observedCrowdStake` (latest `v(o)`) — RENAMED and RE-SEMANTIC'd.** Surfaced as
  `crowdStake` holding pure `X(o)`. Raw `v(o)` is not exposed. AD-C10 names the field
  `observedCrowdStake` = "the latest `v(o)`". Exposing `X(o)` is arguably more useful,
  but it is a silent divergence from the AD, and Verification step 6's crowd-over-fill
  reasoning is written against observed crowd. Align to the AD (expose `v(o)` as
  `observedCrowdStake`) or the AD must be amended — but see the call below.
- **`observedCrowdCount` (`bc(o)`) — MISSING.** The coordinator stores `crowdCount`
  under the lock but `snapshot()`/`OptionSnapshot` never read it, so it never reaches
  the DTO. AD-C5/AD-C10 explicitly carry `bc` into the health block "when semantic
  known" for observability. Not surfaced.

**What should happen:** extend `OptionSnapshot` + `OptionStateDTO` with
`observedCrowdStake` (raw `v(o)`), `crowdAdjustedBudget` (`B_crowd(o)`, from
`current.budget()`/`RoundBudget.budgetOf`), and `observedCrowdCount` (`bc(o)`, from the
stored `crowdCount`, populated when `crowdCountSemantic != UNKNOWN`). Keeping the extra
pure-`X(o)` field is fine as an addition, but the three AD-named fields must exist.

**Call on the Dev-flagged AD-C10 gap:** this is a **genuine compliance gap, sent back
to Dev — NOT a plan amendment.** AD-C10 did not over-specify: `crowdAdjustedBudget` is
load-bearing for the feature's own proof (Verification 6), `observedCrowdCount` is the
concrete realization of the AD-C5 "carry `bc` for observability" promise, and all three
quantities are already computed/stored inside the coordinator — surfacing them is
plumbing, and a correct implementation was clearly possible. No falsifiable claim about
the codebase or an external system makes the AD wrong. Per the asymmetric drift policy,
"Dev surfaced fewer fields than the AD listed, and one field is arguably better" is
send-back, not amendment.

### §2 — AD-C3 EndGame one-round-lag seed is discarded (BOM/B52/Nohu path inert)
Where: `BettingMiniGameBot.onEndGame` crowd branch + `BetCoordinator.onRound`
(`crowdStake.clear()` on every new sid).

The EndGame `observeCrowd(finishedSid, …)` matches `current.sessionId()` (the next
round's `onRound` has not fired yet), so it recomputes the *finished* round's budget —
which is never consumed again — and then the next `onRound` clears the crowd map and
resets to the internal target. The AD-C3 intent ("the EndGame `bs` … is used to seed
the *next* round's budget as a one-round-lagged prior when no intra-round signal is
available (the BOM/B52/Nohu case)") is not realized. For BOM/B52/Nohu, which have no
intra-round `bs`, this is their only live-ish crowd signal, so the degradation path is
effectively a no-op.

**What should happen:** carry the last EndGame crowd observation across the round
boundary so the next `onRound` seeds `B_crowd(o)` from it (then let the intra-round
UpdateBet, on Tip, supersede it) — rather than `clear()`-ing unconditionally. Note the
Tip primary path is unaffected and correct; this is the secondary/capture-gated path,
but AD-C3 and the Findings table (BOM one-round-lag) call it out explicitly, and the
plan's own Notes say "The plan is correct without [intra-round BOM] (EndGame-lag
degradation)" — i.e. the EndGame-lag is the load-bearing fallback that is currently
missing. Lower-severity than §1 but still a plan deviation the code could have met.

## Out-of-scope changes

None. The diff touches only crowd-aware surfaces plus their tests. No production code,
build files, or unrelated planning docs outside the four phases' scope were modified.
Open Items (Tip-only `b`-subtraction, count-weighted steering, Subscribe seeding, Tai
Xiu crowd) are correctly NOT implemented — no silently-included out-of-scope work.

## Confirmed faithful (for the record)

- **v-only steering (AD-C5):** `bc` never enters the budget; `ValueOnly` test pins it.
- **Subtract own `committed(o)` (AD-C4/D3):** `X(o)=max(0,v−committed)`, not the
  Tip-only `b` field. `CrowdOption.ownBet` carried but unused.
- **Config split (AD-C6/C7):** `crowdAwareCoordination` on BotGroup (requires
  `coordinationEnabled`, validated) + `crowdCountSemantic` on Game (obs-only, default
  UNKNOWN).
- **Off-by-default posture:** existing groups deserialize `crowdAwareCoordination=false`
  (primitive default), get the internal-tier coordinator, `observeCrowd` inert. Byte-
  for-byte BET_COORDINATION when off. `coordinationEnabled=false` → no coordinator.
- **Tai-Xiu (AD-C7):** no Tai-Xiu message implements `HasCrowdBets`; a crowd-aware
  Tai-Xiu group builds a crowd-capable coordinator that is simply never fed → internal
  tier. Structural no-op confirmed.
- **Tests:** 193 crowd-suite tests pass (`BetCoordinatorCrowdTest`,
  `BettingMiniGameBotCrowdSeamTest`, `HasCrowdBetsTest`, mapper/validator/service).

## Amendments to the plan

None (verdict is SEND_BACK_TO_DEV).
