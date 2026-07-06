# QA — STRATEGY_DECISION_AGGREGATION

**Verdict:** PASS
**Build:** `mvn clean install` → 1145 tests, 0 failures, 0 errors (was 1133 on the branch as delivered; +12 QA-added).

## Tests added / updated

Added (this QA pass):

- `src/test/java/com/vingame/bot/infrastructure/observability/SessionAccumulatorDecisionTest.java` — 7 direct
  `SessionAccumulator` unit tests: histogram counts across multiple bets; tumbling
  drain + reset (a drained key returns to empty when idle — the anti-leak invariant;
  a reused key counts only its own window); amount min/max single-value collapse and
  reset-to-identity; `null` option skips the histogram but still feeds staked/min/max
  (the slot Phase-1 delegation path); `amount=0` gated out of staked and min/max but
  still counted in the histogram + bet count; and the **mid-flush lost-update
  consistency** invariant — a bet arriving after `captureFlushSnapshot` lands wholly in
  the next window and the histogram count agrees with the staked/bet deltas for that
  same window (no loss, no double-count across histogram vs delta counters).
- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotSessionAggregationTest.java` — 1 test:
  the betting `bet()` supplier plumbs the chosen `optionId` (histogram key) plus sid +
  staked amount into `recordBet` (`recordBet(555, "decidebot1", 3, 300)`). The betting
  analogue of the shipped slot `recordSpin` plumbing check.
- `src/test/java/com/vingame/bot/domain/bot/strategy/StrategyDecisionLogLevelTest.java` — 4 tests
  for P3 level correctness: behavioral capture of `FixedBetStrategy`/`RandomBetStrategy`
  (`chooseBet` suppressed at a DEBUG threshold, emitted at TRACE — proving the demotion
  is real level routing, not a text change); a structural guard asserting every
  enumerated per-bet decision phrase across the 6 touched files is on a `log.trace` line;
  and a structural guard asserting the "do not demote" balance/status/race-fallback lines
  stay on `log.debug` (over-demotion regression guard).

Reviewed (delivered on the branch — correct, deterministic, no sleeps, real threads +
latches, `flushOnce(long)` time-injection seam):

- `SessionAggregationDecisionTest.java` (Phase 1 render: histogram + amount min/avg/max,
  tumbling reset, `-` placeholders, concurrent-feed sum, flush stays DEBUG).
- `SessionAggregationSlotTest.java` (Phase 2 slot bet-size histogram, reset, `-`,
  concurrent feed, no lifecycle lines, stays DEBUG).
- `SessionAggregationFlushTest.java`, `SessionAggregationServiceTest.java`,
  `SlotMachineBotSessionAggregationTest.java` (signature migrations to the new
  `recordBet(sid,user,option,amount)` / `recordSpin(strategy,user,perLineBet,total)`).

## Coverage of the diff

- `SessionAccumulator.java` (histogram + `LongAccumulator` min/max, `captureFlushSnapshot`
  drain via `sumThenReset`/`getThenReset`, new snapshot accessors)
  ← `SessionAccumulatorDecisionTest` (drain/reset/idle-empty/null/zero/mid-flush) +
  `SessionAggregationDecisionTest`/`SessionAggregationSlotTest` (through the service).
- `SessionAggregationService.recordBet`/`recordSpin` (grown signatures)
  ← `SessionAggregation{Decision,Slot,Flush,Service}Test` + both bot plumbing tests.
- `BettingSessionStrategy.renderFlushLine` (`options: [...]` + `amount min/avg/max`)
  ← `SessionAggregationDecisionTest` (exact rendered segments, `-` placeholders).
- `SlotSessionStrategy.renderFlushLine` (`bets: [...]` + `amount min/avg/max`)
  ← `SessionAggregationSlotTest`.
- `BettingMiniGameBot.bet()` option plumbing ← `BettingMiniGameBotSessionAggregationTest`.
- `SlotMachineBot.spin()` per-line-bet plumbing ← `SlotMachineBotSessionAggregationTest`.
- P3 demotions across `BettingMiniGameBot`, `SlotMachineBot`, `RandomBehaviorStrategy`,
  `MartingaleStrategySupport`, `FixedBetStrategy`, `RandomBetStrategy`
  ← `StrategyDecisionLogLevelTest` (behavioral for the two slot strategies + structural
  for all six files, both directions: decision→TRACE and balance/status→DEBUG).
- `CLAUDE.md` logging-norm edits — docs only, verified by inspection (DEBUG carve-out for
  per-bet strategy-decision → TRACE; TRACE bullet reworded from "Currently unused").

## Gaps

- **On-server verification (plan `## Verification` 1–6)** is integration-only against a
  live BauCua/Tai Xiu group and a slot group on Bot-1 staging — out of scope for the unit
  suite; deferred to the Releaser. All build/test gates in each phase pass locally.
- **Behavioral P3 capture** is done for the two directly-invocable slot strategies; the
  betting-bot / Martingale / RandomBehavior decision lines are covered structurally (source
  scan) rather than by running each log statement, since driving those paths to the log
  call adds fixture cost without adding signal beyond the structural + "aggregate stays
  DEBUG" assertions already present.
- **Martingale escalation state in the aggregate** and the skip-gate/`SlotMachineBot:255`
  level questions are explicitly Open Items in the plan (deferred); not built, not tested.

## Notes (non-blocking)

- **`amount=0` edge (documented, not a defect):** the pre-existing `amount > 0` gate keeps
  a zero-amount bet out of `totalStaked` and the min/max accumulators, but the bet is still
  counted in `betEventCount` (the avg denominator) and in the option histogram. So a window
  mixing a `0` bet with positive bets renders an `avg` diluted by the zero, and a
  (theoretical) all-zero window would render the accumulator identities
  (`Long.MAX_VALUE/0/Long.MIN_VALUE`) since `windowBets > 0` bypasses the `-` guard. This is
  unreachable in production — bet amounts resolved by every strategy are always `> 0` — and
  is pinned by `SessionAccumulatorDecisionTest.zeroAmount_gatedFromStakedAndMinMax` so any
  future change is caught. Flagged for awareness, not a blocker.

## Failures

None.
