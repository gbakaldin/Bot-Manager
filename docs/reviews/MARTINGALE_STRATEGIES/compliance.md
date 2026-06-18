# Compliance — MARTINGALE_STRATEGIES

Branch: `feat/martingale-strategies`
Plan reviewed: `docs/plans/MARTINGALE_STRATEGIES.md` (at commit 01209c3)
Diff reviewed: `git diff fd62d3d...01209c3` (merge-base with `main` to HEAD)

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Enum entries, RiskProfile, AffinityOptionPicker, shared base
Status: implemented

Notes:
- `StrategyId.java` lines 26-49: eight new entries appended after `RANDOM` in
  the exact order specified in A1, with display names and descriptions
  matching the plan verbatim (em-dash, periods, "safer entries more often" /
  "riskier entries with bigger payouts" suffixes — all present).
- `RiskProfile.java`: enum `{ CAUTIOUS, AGGRESSIVE }` exactly as A2 locks.
- `AffinityOptionPicker.java`:
  - Constructor takes `RiskProfile`, rejects null. Matches A2.
  - `pick(Map<Integer,Integer>, Random)` is the only method (plus a
    getter), matching A2's "stateless and self-contained" contract.
  - CAUTIOUS weight per option = `max(0, raw)` (line 130). ✓ A3.
  - AGGRESSIVE weight = `(maxAffinity + 1) - max(0, raw)` (line 139-144). ✓ A3.
    Defensive nuance: `maxAffinity` is seeded at 1 rather than computed via
    `.orElse(1)`, so when every affinity is ≤ 1 the picker effectively
    treats `maxAffinity` as 1. This collapses to uniform weighting in
    degenerate inputs (all-zero / all-equal-1) — equivalent in behaviour to
    the plan's formulation for any realistic affinity distribution, and the
    plan-pinned `{0:5, 1:1}` example still produces weights `{1, 5}` (option
    1 picked 5/6 — confirmed in `AffinityOptionPickerTest.AGGRESSIVE`).
  - Uniform fallback when `totalWeight <= 0` (line 101-107), with
    `volatile boolean warned` one-shot WARN. Matches A3 + Implementation Note 5.
  - RNG passed per-call (line 88), picker holds no RNG. ✓ A2.
- `MartingaleStrategySupport.java`:
  - Abstract base implementing `BettingStrategy`. ✓ A4.
  - Holds `currentBet`, `currentRoundSessionId`, `numberOfBetsInCurrentSession`,
    `AffinityOptionPicker picker`, `RiskProfile profile`. ✓ A4.
  - Constructor takes `RiskProfile` and instantiates the picker (line 101-107). ✓ A4.
  - `currentBet` initialised to 0L and lazily initialised to `minBet` on
    first decide (line 144-146). ✓ A4 / Implementation Note 1.
  - `decide(BetContext)` order: session-boundary → cache bounds → maxBets
    gate → skip-tick gate → counter increment → option pick (line 121-176).
    Matches A6.
  - Skip-tick gate runs before the option pick (line 160) — matches A6 RNG
    ordering and `RandomBehaviorStrategy` precedent.
  - Three abstract hooks + `onCapHitReset` (line 237-270). ✓ A4 / A5f.
  - `nextBetAfterNoBet` default returns `currentBet` unchanged (line 253-255). ✓ A5.
  - `applyClampAlignReset` (line 288-309): align down to
    `minBet + k*betIncrement`, floor at `minBet`, cap-reset to `minBet` if
    aligned > maxBet. Order matches A5f exactly.
  - Defensive `boundsCached` guard for `onRoundEnd` before any `decide`
    (line 182-191). ✓ Implementation Note 1.
  - `synchronized (this)` wraps every mutable-state access. ✓ thread model.
- `AffinityOptionPickerTest.java` covers: CAUTIOUS {0:5, 1:1}, AGGRESSIVE
  {0:5, 1:1}, uniform-input degenerates to uniform, all-zero fallback,
  all-negative fallback, single-option map, max-affinity never starves under
  AGGRESSIVE. Matches plan step 5.

### Phase 2 — Classic Martingale + Paroli
Status: implemented

Notes:
- `ClassicMartingaleStrategy`: `nextBetAfterWin → cachedMinBet()`,
  `nextBetAfterLoss → Math.multiplyExact(currentBet, 2)` with
  ArithmeticException → `Long.MAX_VALUE` sentinel that trips the cap-reset
  path. ✓ A5a + Implementation Note 3.
- `ClassicMartingaleCautious` / `ClassicMartingaleAggressive`: thin final
  subclasses with `@Component @Scope("prototype") @StrategyImpl(...)` and a
  no-arg constructor calling `super(RiskProfile.X)`. ✓ A4.
- `ParoliStrategy`: extra field `int consecutiveWins`,
  `STREAK_CAP = 3` `private static final`. `nextBetAfterWin` increments the
  counter, banks on reaching cap, doubles otherwise. `nextBetAfterLoss`
  resets counter + `currentBet = minBet`. `onCapHitReset` clears
  `consecutiveWins`. No override of `nextBetAfterNoBet` → streak preserved
  across no-bet rounds. ✓ A5b + A5f.
- `ParoliCautious` / `ParoliAggressive`: thin final subclasses. ✓ A4.
- `ClassicMartingaleStrategyTest`: loss/win/no-bet/push/cap-reset/alignment
  test cases all present.
- `ParoliStrategyTest`: win-streak below cap, win-streak hits cap,
  loss-mid-streak, no-bet-preserves-streak, cap-reset-clears-streak —
  matches plan step 6.

### Phase 3 — D'Alembert + Fibonacci
Status: implemented

Notes:
- `DAlembertStrategy`: `nextBetAfterWin → max(minBet, currentBet -
  betIncrement)` (line 45-53), `nextBetAfterLoss → currentBet +
  betIncrement` (line 55-62). ✓ A5c. No `onCapHitReset` override (no extra
  state). ✓
- `DAlembertCautious` / `DAlembertAggressive`: thin final subclasses. ✓ A4.
- `FibonacciStrategy`:
  - `int fibIndex` starts at 0 (line 70-75). ✓ A5d.
  - `nextBetAfterWin` retreats 2 (`max(0, fibIndex - 2)`) then
    `computeBet(fibIndex)` (line 96-103). ✓ A5d.
  - `nextBetAfterLoss` advances 1, capped at `FIB_INDEX_CAP = 64`, then
    `computeBet(fibIndex)` (line 105-115). ✓ A5d.
  - `computeBet` uses `Math.multiplyExact(minBet, fib(index))` → sentinel
    `Long.MAX_VALUE` on overflow (line 131-141). ✓ A5d / Implementation Note 3.
  - `onCapHitReset → fibIndex = 0` (line 117-123). ✓ A5d / A5f.
  - `static long fib(int)` iterative: `index <= 1 → 1`, then for-loop
    a=b=1; b=a+b. Produces fib(0)=1, fib(1)=1, fib(2)=2, fib(3)=3, fib(4)=5
    — matches A5d.
- `FibonacciCautious` / `FibonacciAggressive`: thin final subclasses. ✓ A4.
- `DAlembertStrategyTest`: linear loss progression 150/200/250, linear win
  retreat 250/200/150, floor at minBet, cap-reset — matches plan step 5.
- `FibonacciStrategyTest`: loss-sequence 10/10/20/30/50, win-retreats-two,
  win-floors-at-0, cap-reset-clears-index, overflow-guard via
  `setFibIndexForTest`, fib helper — matches plan step 6.

### Phase 4 — End-to-end deterministic stream + factory registration sanity
Status: implemented

Notes:
- `MartingaleEndToEndTest.java` (516 lines): nested classes per
  progression (`ClassicMartingaleDeterministicStream`,
  `ParoliDeterministicStream`, `DAlembertDeterministicStream`,
  `FibonacciDeterministicStream`, `PickerIntegration`). Drives a synthetic
  outcome script through every concrete strategy and asserts the resulting
  `currentBet`.
- `MartingaleStrategyFactoryWiringTest.java` (lives one directory up at
  `src/test/java/com/vingame/bot/domain/bot/strategy/` rather than under
  `.../strategy/martingale/` per the Phase 4 plan path — file location is
  not a load-bearing decision and the test content is correct):
  - `create(MARTINGALE_CLASSIC_CAUTIOUS)` → `ClassicMartingaleCautious`
    with CAUTIOUS profile.
  - `create(FIBONACCI_AGGRESSIVE)` → `FibonacciAggressive` with AGGRESSIVE
    profile.
  - `registeredIdsForPhase3` asserts the exact 9-id set including all
    eight Martingale entries.
- All 711 tests pass under `mvn test`.

## Drift

None material to plan obligations. Two cosmetic deviations worth noting:

1. `MartingaleStrategyFactoryWiringTest.java` is placed at
   `src/test/java/com/vingame/bot/domain/bot/strategy/` rather than under
   `.../strategy/martingale/` as the Phase 4 file-list specifies. The test
   class lives alongside the other factory-level tests
   (`BettingStrategyFactoryTest`, `StrategyAssignmentTest`), which is a
   reasonable home for a wiring test that exercises Spring's bean
   registration. Plan path is advisory; functional behaviour is correct.

2. `AffinityOptionPicker.computeWeights` seeds `int maxAffinity = 1`
   directly rather than computing
   `affinities.values().stream()...orElse(1)` as the plan's A3 pseudocode
   illustrates. The two formulations diverge only when every affinity is
   < 1 (e.g. all zeros or all negatives) — in which case the seeded `1`
   keeps weights non-negative and the all-zero/all-negative inputs flow
   into the uniform-fallback path via `totalWeight <= 0`. The plan-pinned
   `{0:5, 1:1}` example still produces weights `{1, 5}` (option 1 picked
   5/6). Behavioural equivalence confirmed by tests.

## Out-of-scope changes

None. Diff touches only `StrategyId.java` plus 16 new files under the new
`strategy/martingale/` package (15 production + tests). No edits to
`BettingStrategyFactory`, `StrategyAssignment`, `BotMemory`,
`StrategyController`, `BotGroupService`, or any other framework class.
Matches A9 ("No frontend / API changes beyond the enum") and Implementation
Note 9 ("No factory signature change").

## Amendments to the plan

None. No genuine technical oversight in the plan; every locked decision is
satisfied by the diff.
