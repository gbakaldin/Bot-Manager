# QA — CROWD_AWARE_COORDINATION (WP#6)

**Verdict:** PASS
**Build:** `mvn clean install` → 1461 tests, 0 failures, 0 errors
(baseline was 1459/0; +2 from the `onEndGame` seam cases added below.)

## Scope reviewed

Full diff `git diff main...HEAD` (4 feature commits + plan-doc commit off `main`).
Dev shipped tests alongside production; QA verified they are real, meaningful, and
complete, added the missing `onEndGame` seam coverage, hardened the seam harness'
rng ordering, and ran the flakiness gate.

## Priority-1 — load-bearing invariants (both CONFIRMED)

**AD-C2 crowd-off reduction identity — byte-for-byte internal tier.**
- Verified the production code routes both the internal-tier `computeTargetBudget`
  and the crowd recompute through the *same* `computeCrowdBudget(..., emptyCrowd)`
  with identical integer floor ordering, so `X(o)=0 ∀o ⇒ B_crowd(o)=floor(w·cap/W)=B(o)`
  bit-for-bit (BetCoordinator:180-239).
- `BetCoordinatorCrowdTest.reserveSequenceMatchesDisabled` is real and meaningful:
  it drives a 10-proposal stream (including a cap-breaching 5000 and sub-minBet 90)
  through a shipped 4-arg *disabled* coordinator and a 5-arg *crowd-enabled-but-never-observed*
  coordinator and asserts decision **and** amount match for every proposal, plus
  equal final budget and aggregate. `noCrowdEqualsInternal` / `allZeroCrowdEqualsInternal`
  pin the budget map itself.
- `crowdOffLeavesBudgetUnchanged` (bot seam) drives a heavy-crowd Tip UpdateBet
  through a real `BettingMiniGameBot` with `crowdAware=false` and asserts
  `reserve(0,500)` still APPROVEs (internal 500 untouched).
- ALL shipped `BetCoordinatorTest` / `BetCoordinatorEdgeCasesTest` and the
  seam/composition tests are **unchanged on this branch** (`git diff` shows no edits
  to those files) and pass verbatim in the full suite. The live coordinator's
  behavior is not perturbed.

**Off path never mutates budget (end-to-end).**
- `observeCrowd` returns immediately when `!crowdAware` (BetCoordinator:359), before
  the lock. `Gate.disabledIgnoresObserveCrowd` piles v=100_000 on a disabled
  coordinator and asserts the budget equals the internal split.
- End-to-end: `crowdOffLeavesBudgetUnchanged` (UpdateBet) + new
  `crowdEndGameOffLeavesBudgetUnchanged` (EndGame) both prove a heavy frame through
  the real bot leaves the internal budget intact when the flag is off.

## Priority-2 — flakiness gate (PASS)

`BettingMiniGameBotCrowdSeamTest` builds a live `BettingMiniGameBot` via
`initializeSubclass()` and drives the real `onUpdate` / `onEndGame` handlers.

- **Isolation:** ran 10/10 green before changes and 10/10 green after adding the
  two `onEndGame` cases. Stable.
- **In-suite:** green inside the full `mvn clean install` (surefire: 4/4).
- **Why it is not the jackpot-style race:** the only scheduler the exercised paths
  start is a **120 s one-shot watchdog** (`scheduleWatchdog`, a delayed `schedule`,
  not `scheduleAtFixedRate`) — it cannot fire within the sub-second test and is
  `shutdownNow()`-ed in `tearDown`. The fixed-rate countdown `scheduler`
  (`startRemainingTimeCountDown`) is never started on these paths. `NoopStrategy`
  never consults the RNG, so the decision path is deterministic regardless.
- **Harness fix applied (defensive, matches the de-flaked ramp/jackpot convention):**
  `setRandom(...)` was called **before** `initializeSubclass()`, which overwrites
  `this.rng` with a nanoTime seed (BettingMiniGameBot:170) — the deterministic seed
  was being clobbered. It did not affect this test (NoopStrategy), but I moved
  `setRandom` to **after** `initializeSubclass()` so a future rng-dependent edit
  cannot silently reintroduce a flake.

## Priority-3 — coverage of the diff (all covered)

- **Crowd math (AD-C2):** `CrowdSkew.skewMovesTowardTarget` (over-crowded low-affinity
  option → 0, under-crowded high-affinity option grows), `overfillClampsToZero`,
  `perOptionBounds` — each `B_crowd ∈ [0,C]`. (Note: the plan's "`Σ B_crowd ≤ C`" is
  correctly NOT asserted as a per-option-sum invariant — the aggregate cap in
  `reserve` is the hard ceiling; the test documents this precisely.)
- **Double-count `X(o)=max(0,v−committed)` (AD-C4):** `doubleCountSubtraction`
  (single- and two-option arithmetic showing the fleet's own committed stake is
  subtracted from `v`).
- **committed preserved across mid-round swap (AD-C9):** `committedSurvivesSwap`
  (committed totals + aggregate survive the `withBudget` swap and the cap still binds
  under a 50-reserve storm).
- **Value-only steering (AD-C5):** `countNeverAffectsBudget` (garbage `bc` incl.
  `Integer.MAX_VALUE`/`-999` yields the identical budget).
- **Stale-sid / sentinel / new-round clear:** `staleSidDropped`, `unknownEidsIgnored`,
  `newRoundClearsCrowd`.
- **Concurrency (AD-C9):** `concurrentReserveAndObserve` — 48 reserve + 16 observe
  virtual threads; aggregate ≤ cap, grid-aligned, every budget ∈ [0,C].
- **Marker deserialization per product (Phase 3):** `HasCrowdBetsTest` parses real
  fixtures — Tip UpdateBet/EndGame/Subscribe = `HasCrowdBets`; BOM/B52/Nohu
  EndGame+Subscribe = `HasCrowdBets` but their UpdateBet is NOT; **Tai-Xiu
  Subscribe+EndGame explicitly NOT `HasCrowdBets`** (AD-C7 negative). Fixtures
  verified present with matching `bs` presence.
- **Health block crowd fields + ordered-iteration caveat (AD-C10):**
  `healthSurfacesCrowdAwareCoordinationState` builds affinities in insertion order
  `(2,1)` and asserts the option list follows `optionAffinities` order (not the
  crowd/budget map `values()`), with per-option `crowdStake` = v(o).
  `healthSurfacesCoordinationState` proves the crowd fields are present-but-inert
  (`crowdAware=false`, semantic echoed, per-option `crowdStake=0`) for an internal-tier
  coordinator.
- **start() build-gating (AD-C6):** `CrowdAwareCoordinatorBuildTests` — both flags →
  crowd-aware coordinator carrying the game semantic; flag off → non-crowd coordinator
  (semantic still carried); coordination off → no coordinator.
- **Config surface (Phase 1):** `BettingMiniConfigValidatorTest` (crowdAware requires
  coordinationEnabled, 400 otherwise), `BotGroupMapperTest` (toDTO/toEntity/PATCH
  replace-if-present), `GameMapperTest` (crowdCountSemantic round-trip + legacy-null →
  UNKNOWN via `getEffectiveCrowdCountSemantic`, JSON round-trip).

## Tests added / updated by QA

- `src/test/java/.../bot/core/BettingMiniGameBotCrowdSeamTest.java`
  - Added `crowdEndGameShiftsBudget` — drives the real `onEndGame` with a
    `bs`-bearing Tip EndGame and proves the one-round-lag `observeCrowd` path fires
    against `endGameSessionId` (option 0 → REJECT, option 1 → APPROVE). This branch
    (BettingMiniGameBot:549) was previously covered only by inspection; the UpdateBet
    seam did not exercise it. Required injecting a mocked `VingameWebSocketClient`
    (onEndGame → onNewSession → checkBalance reads `getClient().getAuthToken()`; the
    mocked `ApiGatewayClient.getBalance` returns 0 → no network).
  - Added `crowdEndGameOffLeavesBudgetUnchanged` — same frame, `crowdAware=false`,
    budget untouched (end-to-end off-path proof on the EndGame branch).
  - Moved `setRandom(...)` to after `initializeSubclass()` (harness hardening, above).

## Coverage of the diff

- `BetCoordinator.java` (crowd core, observeCrowd, computeCrowdBudget, snapshot) ←
  `BetCoordinatorCrowdTest` (math, gate, double-count, value-only, staleness,
  concurrency) + `BotGroupBehaviorServiceTest` (health snapshot).
- `RoundBudget.withBudget` ← `committedSurvivesSwap`.
- `CrowdOption` / `HasCrowdBets` + per-product message impls ← `HasCrowdBetsTest`.
- `BettingMiniGameBot` onUpdate/onEndGame crowd hooks ← `BettingMiniGameBotCrowdSeamTest`.
- `BotGroupBehaviorService` (build-gating + INFO line + health population) ←
  `CrowdAwareCoordinatorBuildTests` + `healthSurfacesCrowdAwareCoordinationState`.
- `CoordinationStateDTO` crowd fields ← health tests.
- `BettingGridRules` crowdAware→coordinationEnabled ← `BettingMiniConfigValidatorTest`.
- Game/BotGroup/DTO/mappers + `CrowdCountSemantic` ← `GameMapperTest`,
  `BotGroupMapperTest`.

## Gaps (accepted, not blocking)

- The **INFO line** "Crowd-aware coordination enabled for group … (countSemantic=…)"
  (AD-C10) is asserted-by-construction (the build-gating test proves the crowd
  coordinator is built with the right flag/semantic) but the literal log line is not
  asserted — consistent with the existing "Bet coordinator created" line, which is
  also unasserted. Log-only, verified in staging step 2.
- **`bc` count in the health snapshot** (`observedCrowdCount`) is mentioned in the
  plan (AD-C10) but the shipped `OptionSnapshot`/`OptionStateDTO` surface only
  `crowdStake` (v), not the count — the count remains TRACE/observability-only. This
  matches AD-C5 (count is never load-bearing) and the DTO diff; no functional gap,
  only a slightly narrower health surface than the plan's prose. Flagged for the
  releaser as an intentional narrowing, not a bug.
- **Staging live-crowd verification** (plan Verification §3/§6/§7) is capture-/DNS-gated
  (Tip WS DNS block per MEMORY) and out of scope for unit QA — deferred to staging.

## Failures

None.
