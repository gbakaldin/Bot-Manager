# Compliance — BET_COORDINATION

Branch: feat/bet-coordination
Plan reviewed: `docs/plans/BET_COORDINATION.md` (at commit 4378011)
Diff reviewed: `git diff cd758bb..cc4dd7f` (code commits fbcc17a..cc4dd7f)

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Write-side config (2 fields)
Status: implemented
Notes: Exactly two write fields added. `BotGroup` gains `boolean coordinationEnabled`
and `long maxAggregateStakePerRound`; `BotGroupDTO` gains the boxed `Boolean`/`Long`
counterparts with correct PATCH-null-keeps semantics. All three `BotGroupMapper`
methods handle both fields — `toDTO` reads them, `toEntity` uses
`Optional.ofNullable(...).orElse(false/0L)`, `updateEntityFromDTO` full-replaces-if-present
mirroring the existing scalars. Validation added in `BettingGridRules`: when
`coordinationEnabled`, requires `maxAggregateStakePerRound >= minBet`, guarded by
`if (group.isCoordinationEnabled())` and using its own local — decoupled from the
per-bot `maxTotalBetPerRound` block as required. `BotGroupMapperTest` updated (+52).

### Phase 2 — Coordinator core (scope-agnostic, unit-tested)
Status: implemented
Notes: `com.vingame.bot.domain.bot.coordination` package with `BetCoordinator`,
`RoundBudget`, `ReservationOutcome`. Constructor signature is exactly
`(Map<Integer,Integer> optionAffinities, long maxAggregateStakePerRound, long minBet,
long betIncrement)`. Concurrency uses a single `ReentrantLock` — no `synchronized`
keyword anywhere in the package. Budget = `weight * cap / totalWeight` (floor via
long math) per AD-5. `reserve` clamps to `min(amount, remainingOption,
remainingAggregate)`, grid-aligns DOWN (`minBet + steps*betIncrement`, never up),
rejects below `minBet`, returns APPROVE/TRIM/REJECT. `onRound` is idempotent
(swaps only on `sessionId != current.sessionId()`, `0` sentinel is a no-op).
`onRoundComplete` emits a single DEBUG summary line. Read accessors + coherent
`snapshot()` (single lock acquisition) for the DTO. No Spring, no `BotGroup`/
`BotGroupRuntime` import (reuse seam intact). `BetCoordinatorTest` (+279) present.

### Phase 3 — Runtime wiring + bot seam
Status: implemented
Notes: `BotGroupRuntime` gains a nullable `BetCoordinator coordinator` field with
Lombok class-level `@Getter/@Setter`. `Bot` gains a `protected BetCoordinator
coordinator` field and a null-tolerant fluent `setCoordinator(...)`. `BettingMiniGameBot`
adds `protected Optional<BetDecision> applyCoordination(BetContext, BetDecision)` that
is identity when `coordinator == null`, else maps reserve → APPROVE/TRIM/REJECT with
TRACE-only per-proposal logging. The seam is placed AFTER `decideBet` in
`betCondition()` and parks the gated result, matching the AD-2 snippet exactly.
`onStartGame` calls `coordinator.onRound(sid)` after `memory.beginRound` (guarded);
`onEndGame` calls `coordinator.onRoundComplete(...)` after `memory.completeRound`
(guarded). `start()` builds a coordinator ONLY when `coordinationEnabled` AND game
type is `BETTING_MINI`/`TAI_XIU` (SLOT excluded, AD-10), then injects it in the
startBot loop via `bot.setCoordinator(runtime.getCoordinator())` BEFORE `startBot`.
`createSingleBot` pins `betSkipPercentage(0)` on the builder only when
`coordinationEnabled` (AD-7).

### Phase 4 — Read-side observability
Status: implemented
Notes: New `CoordinationStateDTO` with the exact field set from the plan, plus nested
`OptionStateDTO { optionId, targetWeight, targetBudget, committedStake,
realizedFraction }`. Nullable `coordination` added to `BotGroupHealthDTO`. `getHealth`
populates it via `buildCoordinationState(runtime.getCoordinator())` — returns `null`
when no coordinator, else maps the coherent `snapshot()`. Read-only; no coordinator
mutation from the read path. Write DTOs and `BotGroupStatsDTO` untouched.

## Load-bearing invariants

1. **Trim/grid-align never breaches cap or per-option budget** — HOLDS.
   `allow = min(amount, remainingOption, remainingAggregate)` where both remainders
   are `max(0, budget/cap − committed)`. `aligned = gridAlignDown(allow) <= allow`,
   so every commit stays within both the per-option budget and the aggregate cap.
   `gridAlignDown` floors (`minBet + steps*betIncrement`), never rounding up.

2. **Off-path is byte-for-byte unchanged** — HOLDS.
   With `coordinator == null`: `applyCoordination` returns `Optional.of(proposed)`,
   so `betCondition` parks a semantically identical decision (same `BetDecision`
   record, TRACE log values unchanged). `onRound`/`onRoundComplete` are `if
   (coordinator != null)`-guarded no-ops. `bet()` supplier and the race-fallback
   re-derive path are untouched (not in the diff). `createSingleBot` only sets
   `betSkipPercentage(0)` under `coordinationEnabled` — the off-path builder is
   identical to before (field was already default-0).

3. **Coordinator core has no Spring/BotGroup dependency** — HOLDS.
   The only `synchronized`/`BotGroup` occurrences in the package are in javadoc
   prose. No `org.springframework` import, no `BotGroup`/`BotGroupRuntime` import.
   Fleet reuse seam intact.

4. **Coordinator excluded from slots** — HOLDS.
   `start()` builds a coordinator only for `GameType.BETTING_MINI`/`TAI_XIU`; SLOT
   groups leave `runtime.getCoordinator()` null, so slot bots receive a null
   coordinator. `applyCoordination` lives in `BettingMiniGameBot` only; `SlotMachineBot`
   never references it.

## Drift

None.

## Out-of-scope changes

None in production code. The diff also includes `docs/reviews/TIMED_ACTIVATION/release.md`
(+120) and a `BETTING_INTELLIGENCE_ROADMAP.md` (+5, WP#7) — both are docs from the two
pre-code commits in the range and unrelated to this feature's source; harmless.

## Notes (non-blocking)

- `realizedFraction` is implemented as `committedStake / targetBudget` (fill ratio
  against the option's target). The plan named the field but did not fix a formula;
  the chosen interpretation is coherent, documented in the DTO javadoc, and matches
  the AD-7 floor-caveat framing ("below 1.0 means under-filled"). Acceptable.
- `RoundBudget#cap()`, `budget()`, and `committedSnapshot()` are currently unused
  helpers — dead-code observation for the Reviewer, not a compliance issue.
