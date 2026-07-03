# Code Review — STRATEGY_DECISION_AGGREGATION

Branch: feat/strategy-decision-aggregation
Reviewed diff: `git diff main..feat/strategy-decision-aggregation`

## Verdict

PASS

PASS = no `bug` or `security` findings, smells/styles are advisory.

The load-bearing invariants for this feature all hold:

- **No unbounded state.** `optionHistogram` keys are bounded by option cardinality
  (betting) / `allowedBetValues` cardinality (slots); keys persist but only their
  `LongAdder` cells reset per window via `sumThenReset`, and the whole accumulator
  (histogram included) is dropped on TTL / grace / `evictGroup`. Nothing on the new
  path prevents eviction, so the session map still returns to empty when idle.
- **Snapshot/lost-update consistency.** The histogram drain and min/max
  `getThenReset` are captured in the SAME `captureFlushSnapshot()` pass as the
  bettor/staked/spin snapshots (`SessionAccumulator.java:277-297`), and both
  renderers read only the captured `flushOptionSnapshot()` / `flushMinSnapshot()` /
  `flushMaxSnapshot()` — never a re-read. Baseline advance happens after render.
  A bet arriving mid-flush lands in the next window, matching the parent-feature
  contract.
- **Concurrency.** Feed path is `computeIfAbsent` + `LongAdder.increment()` +
  `LongAccumulator.accumulate()` — all lock-free; drain is single-writer on the
  flush thread. `ConcurrentHashMap` weakly-consistent iteration during a concurrent
  `computeIfAbsent` is safe.
- **Correctness.** `avg = windowStaked / windowBets` from the pre-advance deltas;
  min/max gated on `amount > 0`; empty window renders `-` (no divide-by-zero);
  betting histogram key is the actual `decision.optionId()`.
- **P3 demotions.** Only the 14 enumerated per-bet decision lines went to TRACE;
  balance / status / deposit / race-fallback lines were left untouched. CLAUDE.md
  DEBUG and TRACE bullets updated consistently.

## Findings

### [smell] Dead 2-arg `recordBet` overload
`src/main/java/com/vingame/bot/infrastructure/observability/SessionAccumulator.java:120-122`

`recordBet(String bettor, long amount)` now has zero callers — both
`SessionAggregationService` call sites (`:335`, `:361`) use the 3-arg
`recordBet(bettor, option, amount)`, and no test invokes the 2-arg form directly.
It was the pre-feature signature; this change orphaned it. Fix shape: delete the
overload (and its Javadoc), or if it is deliberately retained as a null-option
convenience for a future caller, add a comment saying so — otherwise it reads as an
alternate entry point that silently skips the histogram.

### [smell] Duplicated histogram + amount renderers across the two strategies
`src/main/java/com/vingame/bot/infrastructure/observability/BettingSessionStrategy.java:63-87`
and `src/main/java/com/vingame/bot/infrastructure/observability/SlotSessionStrategy.java:87-113`

`renderOptionHistogram` and `renderBetHistogram` are byte-for-byte identical, and
the two `renderAmountSummary` methods differ only in a parameter name
(`windowBets` vs `windowSpins`). Both classes even document that they intentionally
mirror each other ("Mirrors the betting option histogram… so both flush lines share
one greppable shape"). This is exactly the shared-shape the plan calls for, but it
is copy-pasted rather than shared, so a future format tweak (e.g. the wide-option
companion-line fallback in the Open Items) has to be made in two places and can
drift. Fix shape: hoist both helpers into a small package-private utility (e.g.
`FlushLineFormat.histogram(Map)` / `.amountSummary(min, max, count, staked)`) that
both strategies call.

### [smell] Narrowing `(int) amount` cast on the slot histogram key
`src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:344`

`recordSpin(..., (int) amount, totalStake)` narrows the per-line bet from `long` to
`int`. `SlotBetContext.allowedBetValues` is `List<Long>` and `chooseBet` returns
`long`, so a per-line bet above `Integer.MAX_VALUE` would silently wrap to a
negative/garbage histogram key (corrupting only the `bets: [...]` log segment, not
money or behavior). In practice slot bet values are small, so this is latent, not
live — but the deliberate `long` typing of the bet-value pipeline makes the cast a
smell. Fix shape: either key the histogram on `long` (widen `optionHistogram` /
`recordSpin` / `perLineBet` to `long`), or add a guarded clamp with a one-time WARN
if a bet value ever exceeds int range, rather than a bare cast.

### [smell] `renderAmountSummary` min/max gate is on bet count, not on a positive-amount bet
`src/main/java/com/vingame/bot/infrastructure/observability/BettingSessionStrategy.java:81-87`
(same in `SlotSessionStrategy.java:107-113`)

The `-` guard fires only when `windowBets <= 0`. If a window has `windowBets > 0`
but every one of those bets had `amount <= 0` (so `amountMin`/`amountMax` were never
accumulated), the render emits the accumulator identities —
`9223372036854775807/0/-9223372036854775808`. `betEventCount` increments
unconditionally (`SessionAccumulator.java:137`) while `amountMin/Max` are gated on
`amount > 0` (`:132-136`), so the two counts can legitimately diverge. This is
unreachable in practice (a real bet always stakes a positive amount), so it is a
defensive gap, not a live bug. Fix shape: gate the min/max render on
`flushMaxSnapshot() != Long.MIN_VALUE` (i.e. "a positive-amount bet was seen this
window") rather than on `windowBets`, keeping `avg` on the count.

### [smell] Slot flush line mixes per-line and total-stake figures under one "amount" label
`src/main/java/com/vingame/bot/infrastructure/observability/SlotSessionStrategy.java:70-77`

On the slot window line, `bets: [100]x2` is keyed on the **per-line** bet while
`amount min/avg/max` is computed over **total stake** (`perLineBet * numLines`). For
any slot with `numLines > 1` the two segments report different magnitudes on the
same line (`bets: [100]…` next to `amount min/avg/max: 500/…`), which can read as a
contradiction to an operator grepping the line. This is called out as intended in
the plan (AD-6), so it is a clarity nit, not a defect. Fix shape (optional): relabel
the slot summary to `stake min/avg/max` to signal it is the total-stake view, or add
`(per-line bet)` to the `bets:` label.

## Notes

- The `flushSpinSnapshot = betEventCount.sum()` read (`SessionAccumulator.java:280`)
  happens *before* the histogram drain (`:287-293`), so a bet arriving between the
  two lines is excluded from `windowBets` but included in the histogram sum — the
  histogram total can transiently exceed the `spins/bettors` delta by one. This is
  the documented `sumThenReset` cross-cell tolerance (Implementation Notes in the
  plan), consistent with how the existing staked/bettor snapshot already behaves,
  and the Micrometer counters remain authoritative. Called out only so it is not
  mistaken for a lost-update regression later.
- Good: the histogram is drained (not baseline-subtracted), so it needs no extra
  baseline field and cannot desync from a stale baseline — a genuinely simpler and
  equally race-free choice than mirroring the bettor baseline. The Javadoc on
  `optionHistogram` (`:54-63`) and `captureFlushSnapshot` (`:281-296`) documents the
  single-writer drain contract clearly.
- Good: `recordBet` autoboxes the primitive `int option` to a stable `Integer` key,
  and the `TreeMap` snapshot keeps the rendered line deterministic/greppable.
- CLAUDE.md DEBUG/TRACE bullets were updated in lockstep with the code demotions and
  correctly preserve the "balance fetch / status transitions stay DEBUG" carve-out.

Review verdict: PASS
Findings: 0 bug, 0 security, 5 smell, 0 style
docs/reviews/STRATEGY_DECISION_AGGREGATION/review.md written.
