# QA — MARTINGALE_STRATEGIES

**Verdict:** PASS
**Build:** `mvn test` → 716 tests, 0 failures, 0 errors (baseline before QA additions: 711)

## Scope

Reviewed the full `feat/martingale-strategies` branch (8 commits from
`bbb771d` through `01209c3`, merge-base with `main`):

- `bbb771d` scaffold (enum, picker, support base)
- `b3ddad8` Classic Martingale impls
- `ed7bae9` Classic + Paroli progression / factory wiring tests
- `0571ed4` Paroli impls
- `88f4485` D'Alembert impls
- `f13ec99` Fibonacci impls
- `3a80456` D'Alembert + Fibonacci progression / factory wiring tests
- `01209c3` End-to-end deterministic stream + full StrategyId wiring sanity

Production surface: 15 new files under
`com.vingame.bot.domain.bot.strategy.martingale` plus 8 new enum entries
in `StrategyId`.

Test surface before QA additions: 6 test files in
`com.vingame.bot.domain.bot.strategy.martingale`, 1 in the parent
strategy package (`MartingaleStrategyFactoryWiringTest`).

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleStrategySupportTest.java`
  — new file, 5 tests across two nested classes. Plugs the gap called out
  in plan Implementation Note 1 ("write one test that calls `onRoundEnd`
  directly without prior `decide` and asserts no crash + WARN log") plus
  per-round counter coverage that the per-progression tests do not
  exercise directly (they all prime via `decide` first and use
  `maxBetsPerRound = 100`, so the cap path and the session-id reset path
  were untested).
  - `BoundsCacheGuard.onRoundEndBeforeDecideIsSafe` — defensive guard
    (Implementation Note 1).
  - `BoundsCacheGuard.decideRecoversAfterDefensiveSkip` — recovery after
    a defensive skip is observable from the next `decide` call.
  - `PerRoundCounter.perRoundCounterCaps` — `(maxBetsPerRound + 1)`th
    `decide` on the same session returns empty.
  - `PerRoundCounter.sessionBoundaryResetsCounter` — session id rollover
    re-arms the per-round counter.
- `src/test/java/com/vingame/bot/domain/bot/strategy/controller/StrategyControllerTest.java`
  — appended one test (`shouldExposeMartingaleStrategiesWithLockedStrings`)
  that pins each of the 8 new `StrategyId` values surfaces in the
  `/api/v1/strategy/` listing with the exact `displayName` and
  `description` strings from Architecture Decision A1. The pre-existing
  test only pinned `RANDOM` explicitly.

Net delta: +5 tests, all passing.

## Coverage of the diff

### End-to-end harness honesty (the prompt's headline ask)

`MartingaleEndToEndTest.driveScript` exercises the **full**
`BotMemory` lifecycle:

```
mem.beginRound(sid, balance)
strategy.decide(ctx(...))                 // returns BetDecision
mem.recordBetSent(sid, optionId, amount)  // accumulates in-flight RoundState
result = mem.completeRound(sid, ...)      // RoundResult with real betsByOption + balanceDelta
strategy.onRoundEnd(result)
```

Not a fake — the `RoundResult` passed to `onRoundEnd` is the one
`BotMemory.completeRound` produces, with `betsByOption` built from the
in-flight `RoundState` and `balanceDelta = payout - sum(bets)`. This is
the same code path `BettingMiniGameBot` drives in production. The
`PickerIntegration.optionIdsAreValidThroughout` nested test additionally
walks all 8 concrete strategies through the same lifecycle and asserts
the emitted `optionId` is a valid affinity key on every betting round —
catches a future "always returns option 0" regression in the picker.

### Locked-decision coverage matrix

| Locked decision | Pin location |
|---|---|
| A1 — 8 new enum entries with verbatim strings | `StrategyControllerTest.shouldExposeMartingaleStrategiesWithLockedStrings` (new) |
| A2 — `RiskProfile` is a constructor arg, not threaded through `pick` | `MartingaleStrategyFactoryWiringTest.*Resolves` × 8 |
| A3 — CAUTIOUS weighting | `AffinityOptionPickerTest.Cautious.heavyWeightDominates`, `uniformAffinitiesDegenerateToUniform`, `zeroAffinityNeverPicked` |
| A3 — AGGRESSIVE weighting + `+1` floor | `AffinityOptionPickerTest.Aggressive.lowAffinityDominates`, `uniformAffinitiesDegenerateToUniform`, `maxAffinityNeverStarves` |
| A3 — all-zero / all-negative fallback | `AffinityOptionPickerTest.Fallback.allZeroAffinitiesUniformFallback`, `allNegativeAffinitiesUniformFallback` |
| A4 — class layout (abstract base + 4 progression + 8 concrete) | `MartingaleStrategyFactoryWiringTest.everyStrategyIdResolvesEndToEnd` enumerates the 9-id table |
| A5a — Classic Martingale | `ClassicMartingaleStrategyTest.LossProgression.pureLossStreakDoubles`, `WinProgression.winResetsToMinBet` |
| A5b — Paroli streak-cap bank at 3 | `ParoliStrategyTest.WinStreak.streakCapBanks` |
| A5b — Paroli streak preserved across no-bet | `ParoliStrategyTest.NoBetHandling.noBetPreservesStreak` |
| A5c — D'Alembert ±step + floor | `DAlembertStrategyTest.LossProgression.pureLossStreakLinearRamp`, `WinProgression.mixedLossWinSequence`, `winAtMinBetStaysMinBet` |
| A5d — Fibonacci progression | `FibonacciStrategyTest.LossProgression.pureLossStreakAdvancesFibIndex`, `WinProgression.winRetreatsTwoIndices`, `winFloorsAtZero` |
| A5d — Fibonacci overflow guard | `FibonacciStrategyTest.ClampAlignReset.overflowGuardResetsCleanly` |
| A5e — push-as-loss | `ClassicMartingaleStrategyTest.LossProgression.pushTreatedAsLoss`, `ParoliStrategyTest.LossHandling.pushTreatedAsLoss`, `DAlembertStrategyTest.LossProgression.pushTreatedAsLoss`, `FibonacciStrategyTest.LossProgression.pushTreatedAsLoss` |
| A5e — no-bet = unchanged | per-progression `NoBet*` tests × 4 |
| A5f — cap-hit reset to minBet | `ClassicMartingaleStrategyTest.ClampAlignReset.capHitResetsToMinBet`, `ParoliStrategyTest.CapReset.capResetClearsStreak`, `DAlembertStrategyTest.ClampAlignReset.capHitResetsToMinBet`, `FibonacciStrategyTest.ClampAlignReset.capHitClearsFibIndex` |
| A5f — alignment to `betIncrement` | `ClassicMartingaleStrategyTest.ClampAlignReset.alignmentRoundsDownToStep` |
| A5f — Classic doubling overflow | `ClassicMartingaleStrategyTest.ClampAlignReset.doublingOverflowResets` |
| A6 — `decide` RNG ordering + per-round counter | `MartingaleStrategySupportTest.PerRoundCounter.*` (new) |
| A6 — first `decide` lazily inits `currentBet = minBet` | implicit in every progression test's first assertion; explicit in `MartingaleStrategySupportTest.BoundsCacheGuard.decideRecoversAfterDefensiveSkip` (new) |
| RiskProfile does not leak into bet sizing | `MartingaleEndToEndTest.*.aggressiveProgression` × 4 — Cautious and Aggressive variants assert the same trajectory |
| Factory wiring (9 ids → 9 classes, prototype scope) | `MartingaleStrategyFactoryWiringTest.everyStrategyIdResolvesEndToEnd`, `martingaleStrategiesArePrototypeScoped`, `registeredIdsForPhase3` |
| Implementation Note 1 — `onRoundEnd` before any `decide` is safe | `MartingaleStrategySupportTest.BoundsCacheGuard.onRoundEndBeforeDecideIsSafe` (new) |

### AffinityOptionPickerTest distribution defensibility

Plan asks specifically whether the distribution assertions are
statistically defensible. They are.

- N = 10 000 picks per test.
- Tolerance = ±3 percentage points.
- For a Bernoulli observation, σ ≤ 0.5/√N ≈ 0.5 percentage points.
- 3 pp / 0.5 pp ≈ 6 σ — `P(|X - μ| > 6σ) < 2 × 10⁻⁹`.

The headroom is large enough that a deeply broken implementation will
fail (e.g. CAUTIOUS picks at uniform = 50/50 when expected is 5/6 — that's
33 pp off, far beyond tolerance), but RNG noise will not flake the test
within the lifetime of the project. The `5/6 ≈ 0.833` expected rate and
`±0.03` band specifically pin the CAUTIOUS / AGGRESSIVE split — flat-RNG
behavior (≈ 0.5 each) would fail by 33 pp.

`AGGRESSIVE.maxAffinityNeverStarves` is the assertion that does
load-bearing work: it pins the `+1` floor by checking that option 0
with reflected weight 1 appears at rate `1/11 ± 3 pp`, not zero — without
the `+1`, the reflected weight would be 0 and option 0 would never
appear.

### Files covered by the diff vs tests

| Production file | Test coverage |
|---|---|
| `StrategyId.java` (8 new entries) | `StrategyControllerTest`, `MartingaleStrategyFactoryWiringTest.everyStrategyIdResolvesEndToEnd` |
| `martingale/RiskProfile.java` | `AffinityOptionPickerTest`, `MartingaleStrategyFactoryWiringTest.*Resolves` |
| `martingale/AffinityOptionPicker.java` | `AffinityOptionPickerTest` (15 tests across 4 nested classes), `MartingaleEndToEndTest.PickerIntegration` |
| `martingale/MartingaleStrategySupport.java` | `ClassicMartingaleStrategyTest`, `ParoliStrategyTest`, `DAlembertStrategyTest`, `FibonacciStrategyTest`, `MartingaleEndToEndTest`, `MartingaleStrategySupportTest` (new) |
| `martingale/ClassicMartingaleStrategy.java` + 2 concrete | `ClassicMartingaleStrategyTest`, `MartingaleEndToEndTest.ClassicMartingaleDeterministicStream`, factory wiring |
| `martingale/ParoliStrategy.java` + 2 concrete | `ParoliStrategyTest`, `MartingaleEndToEndTest.ParoliDeterministicStream`, factory wiring |
| `martingale/DAlembertStrategy.java` + 2 concrete | `DAlembertStrategyTest`, `MartingaleEndToEndTest.DAlembertDeterministicStream`, factory wiring |
| `martingale/FibonacciStrategy.java` + 2 concrete | `FibonacciStrategyTest`, `MartingaleEndToEndTest.FibonacciDeterministicStream`, factory wiring |

## Gaps

Nothing material; the gaps below are observations the reviewer / dev
should weigh, not blockers.

- **Log-line assertions.** The plan calls for WARN logs on the
  defensive-skip path (Implementation Note 1), the Fibonacci overflow
  guard (A5d), and the all-non-positive affinity fallback (A3 / Note 5).
  None of the tests assert log content (they only assert behavior:
  no-crash, expected state, the `volatile boolean warned` one-shot
  semantics). Adding log capture is awkward with the Lombok `@Slf4j`
  field; behavior-level pinning is the pragmatic choice and matches
  the existing test idiom (`RandomBehaviorStrategyTest`,
  `BettingStrategyFactoryTest`).
- **Thread-safety stress test.** `decide` and `onRoundEnd` run on
  different threads in production (scenario thread vs netty processor
  thread). The diff is correctly `synchronized (this)` throughout, but
  there is no concurrent stress test that pounds both methods from two
  threads against a single instance. The reference `RandomBehaviorStrategy`
  also lacks one — both rely on review of the `synchronized` blocks.
  Deferred to a future concurrency-pass ticket; out of scope for the
  feature.
- **Picker hot-path performance.** The picker allocates a fresh
  `ArrayList<>(affinities.keySet())` and a `int[]` per `pick()` call. With
  ≤ 10 options and 30 bots × 1 tick / 5 s, this is negligible (~ 50 KB / s
  GC pressure), and the plan explicitly notes the allocation pattern.
  Not tested for throughput; not required by the plan.
- **`betSkipPercentage` end-to-end behavior.** Plan Implementation Note 6
  notes the field is not yet wired through `BotGroup` (still the case in
  this diff). The strategies consult it (so when it gets wired they pick
  it up for free), but no test feeds `betSkipPercentage > 0` to a
  Martingale strategy. The existing `RandomBehaviorStrategyTest` already
  pins the skip-gate semantics on the same shared `decide` pattern, and
  the Martingale strategies use the identical gate. Acceptable coverage
  by reference.
- **Staging verification.** Plan Verification sections A, B, C, D, E are
  Releaser concerns (curl checks against staging, group lifecycle, log
  inspection); they are not unit-testable from the bot-manager itself.

## Failures (if any)

None. `mvn test` → 716 tests, 0 failures, 0 errors.

---

QA verdict: PASS
Tests added: 5 | Tests passing: 716 | Tests failing: 0
docs/reviews/MARTINGALE_STRATEGIES/qa.md written.
