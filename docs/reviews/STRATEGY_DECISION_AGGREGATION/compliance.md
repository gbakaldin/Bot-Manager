# Compliance — STRATEGY_DECISION_AGGREGATION

Branch: `feat/strategy-decision-aggregation`
Plan reviewed: `docs/plans/STRATEGY_DECISION_AGGREGATION.md`
Diff reviewed: `git diff main..feat/strategy-decision-aggregation`

Commits:
- `e7f2488` feat: carry strategy-decision option into the session aggregate
- `e6bff99` feat: render option histogram + amount min/avg/max on the flush line
- `2a15ecb` feat: fold slot per-line bet into the SlotWindow flush (Phase 2)
- `ed7fe0f` test: cover slot bet-size distribution (Phase 2)
- `2cb95cb` refactor: demote per-bet strategy-decision logs to TRACE (Phase 3)
- `59b5aa8` docs: align DEBUG/TRACE norms with strategy-decision aggregation

## Verdict

PASS (COMPLIANT)

Build + full suite green: `Tests run: 1133, Failures: 0, Errors: 0`. Phase-3 grep gate
clean (the only regex hits are javadoc/comments and the demoted TRACE lines themselves —
no surviving `log.debug` decision line).

## Phase-by-phase

### Phase 1 — Betting/Tai Xiu: option histogram + amount summary in the flush
Status: implemented
- `SessionAccumulator`: added `optionHistogram` (`ConcurrentHashMap<Integer,LongAdder>`),
  `amountMin`/`amountMax` (`LongAccumulator` with `Long::min`/`Long::max` identities), and
  captured snapshot fields `flushOptionSnapshot` (TreeMap → sorted/deterministic),
  `flushMinSnapshot`, `flushMaxSnapshot`. `recordBet` grew to `recordBet(String, Integer, long)`;
  the old 2-arg overload is retained as a null-delegating shim (null option not counted) —
  matches the plan's "both remain null-tolerant". Drain done in `captureFlushSnapshot()` via
  `sumThenReset()` per key + `getThenReset()` on min/max — the shipped capture-then-advance seam
  (AD-4), no new baseline fields.
- `SessionAggregationService.recordBet` grew to `(long sid, String bettor, int option, long amount)`,
  forwards to accumulator.
- `BettingMiniGameBot.bet()` (:622) passes `optionId` sourced from `decision.optionId()` at :612 —
  bot-sourced, not frame-parsed. Tai Xiu rides `BettingMiniGameBot.bet()` unchanged.
- `BettingSessionStrategy.renderFlushLine` appends `options: [...]` + `amount min/avg/max: ...`;
  `avg = windowStaked/windowBets` from existing snapshot deltas; zero-bet window renders `-`
  (no divide-by-zero). `total staked` unchanged, not re-emitted.

### Phase 2 — Slots: bet-size histogram in the SlotWindow flush
Status: implemented
- `SessionAggregationService.recordSpin` grew to `(strategy, bettor, int perLineBet, long totalStake)`,
  forwards `perLineBet` as the histogram key and `totalStake` as the amount into the same
  accumulator machinery.
- `SlotMachineBot.spin()` (:344) passes `(int) amount` (the per-line chosen bet, bot-sourced from
  the spin path) as `perLineBet`.
- `SlotSessionStrategy.renderFlushLine` appends `bets: [...]` + `amount min/avg/max: ...`;
  `spins since last` / `total staked` / `total win` / `jackpot hits` untouched. Fixed-bet slot
  correctly collapses to a single bucket.

### Phase 3 — Demote per-bet decision lines to TRACE + CLAUDE.md
Status: implemented
- 16 individual `log.debug` → `log.trace` (the plan table's 14 entries expand to 16 lines because
  #5 and #8 are 2 skip-gate lines each): BettingMiniGameBot :624/:661/:667; RandomBehaviorStrategy
  :75/:83/:103/:111; MartingaleStrategySupport :149/:161/:174/:218/:304; FixedBetStrategy :29;
  RandomBetStrategy :28; SlotMachineBot :299/:352. All present and correct.
- "Do NOT demote" set verified still at DEBUG: BettingMiniGameBot :319 (session balance), :595
  (race fallback); SlotMachineBot :166 (session balance), :255 (spin result / balance), :294
  (below spin cost), :321 (race fallback). Deposit/INFO lines untouched. No silent information loss.
- CLAUDE.md DEBUG bullet updated (aggregate now carries decision distribution; balance/status stay
  DEBUG); TRACE bullet reworded from "Currently unused"/wire-only to include per-bot
  strategy-decision drill-in; cross-references AGGREGATED_SESSION_LOGGING and notes it supersedes
  the interim per-bot strategy DEBUG levels.

## Requested checks

1. **Resolved decisions honored** — YES. One-line format (AD-5), not a companion line; flush stays
   DEBUG (AD-7); Martingale escalation state NOT folded into the aggregate (deferred, Open Item);
   histogram is tumbling per-window via `captureFlushSnapshot` drain (`sumThenReset`/`getThenReset`),
   no new baselines, no unbounded state.
2. **Load-bearing feed** — YES. The option dropped at the old `recordBet(...:622)` is now passed from
   `decision.optionId()` (:612), bot-sourced. Slot per-line bet fed from the spin path (:344),
   bot-sourced.
3. **P3 demotion scope** — YES. Only per-bet strategy-decision lines demoted; the plan's
   Do-NOT-demote set (balance/status/deposit/race-fallback) untouched. Partial-coverage lines
   (skip-gate/onRoundEnd/cap-hit) land at TRACE, not dropped.
4. **Anti-leak preserved** — YES. `optionHistogram` key space is bounded by game option cardinality;
   keys persist, only counters reset per window (drain, not accumulate). Snapshot is a fresh TreeMap
   each window; min/max reset via `getThenReset`. No regression of the parent bounded-map guarantee.
   Feed path stays lock-free (`computeIfAbsent` + `LongAdder.increment`); drain runs only on the
   single flush thread.
5. **CLAUDE.md** — YES. Consistent with the new DEBUG(aggregate)/TRACE(per-bet detail) split.

6. **Open Items resolution** (all resolved to the plan's recommended option):
   - One-line vs companion (AD-5): resolved → one line. Recorded in `BettingSessionStrategy`
     render comment + this file.
   - Martingale escalation state (#9/#10): resolved → deferred, NOT built. Lines :218/:304 still
     demoted to TRACE (partial coverage), escalation signal not added to the aggregate. Correct.
   - Slot histogram format (AD-6): resolved → `bets: [<value>]x<count>`, mirrors the betting shape.
   - Skip-gate #3/#5/#8: resolved → demoted to TRACE (recommended). All skip gates at TRACE.
   - `SlotMachineBot.java:255` spin-result line: resolved → kept DEBUG (balance-classified).

7. **Plan oversight revealed** — none material. No plan amendment warranted.

## Out-of-scope changes

None. The diff touches exactly the files the plan enumerates (2 bots, 4 strategy classes, 4
observability classes, CLAUDE.md) plus their tests.

## Non-blocking observation (for the Reviewer, not a compliance defect)

`MartingaleStrategySupport.java:66` javadoc still reads "conventions: DEBUG per `decide`, DEBUG per
`onRoundEnd`" after those lines were demoted to TRACE — a stale doc comment the demotion introduced.
The plan did not mandate updating it; behavior is correct. Cosmetic only.
