# QA — BET_COORDINATION

**Verdict:** PASS
**Build:** `mvn clean install` → 1325 tests, 0 failures, 0 errors (was 1312 before QA; +13 added here).

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/coordination/BetCoordinatorEdgeCasesTest.java` — 11 tests
  covering the coordinator edge cases the plan calls out but the Dev suite left thin:
  - degenerate grid: `betIncrement == 0` approves a non-grid amount verbatim / trims to the
    exact remaining headroom / rejects below `minBet`; **negative `betIncrement`** degrades to
    no-grid without dividing by zero (this branch was previously unexercised).
  - degenerate budgets: a **tiny cap** floors every per-option budget to 0 → all-REJECT;
    **all-zero affinity weights** → zero budgets → REJECT (no divide-by-zero on `ΣW = 0`);
    a **single option** receives the full cap.
  - lifecycle: `onRound(0)` sentinel never starts a budget; `onRoundComplete` with no active
    round is a silent no-op; `onRoundComplete` does **not** reset committed state (only the next
    `onRound` with a new sid does) — pins the documented "reserve commits, complete does not reset"
    quirk.
  - a **tiny-cap reject-storm** under 64 virtual threads × 300 reserves: cap and every per-option
    budget still hold; aggregate stays grid-aligned; asserts rejects dominate (proves the stress is
    real contention, not a no-op).
- `src/test/java/com/vingame/bot/domain/bot/core/TaiXiuGameBotCoordinationCompositionTest.java` — 2 tests
  pinning AD-2 composition (coordinator runs **after** `decideBet`, so it only sees the TaiXiu
  single-entry-locked option):
  - the coordinator commits to the **locked** entry (Tài) even after the strategy flips to Xỉu —
    Xỉu's committed stake stays 0; approve count = 2.
  - a REJECT on the exhausted **locked** budget skips the tick and does **not** escape the lock by
    approving the strategy's wanted (free-budget) option — reject count = 1, other option 0 committed.

## Coverage of the diff

- `BetCoordinator.java` ← `BetCoordinatorTest` (Dev) + `BetCoordinatorEdgeCasesTest` (QA): budget
  split `floor(w/ΣW·cap)`, trim→clamp→grid-align-down, reject below minBet, aggregate-binds-before-
  option, stale-sid + sid==0 reject, onRound idempotency, 64-thread stress; QA adds the `betIncrement<=0`
  branch, tiny/zero-weight budget degeneracy, single-option, onRound(0), onRoundComplete no-op/preserve,
  and a reject-storm stress.
- `RoundBudget.java` / `ReservationOutcome.java` ← exercised transitively through the coordinator tests
  (remainingOption/remainingAggregate clamping, commit accumulation, APPROVE/TRIM/REJECT mapping).
- `BettingMiniGameBot.java` (applyCoordination seam + onRound/onRoundComplete hooks) ←
  `BettingMiniGameBotCoordinationSeamTest` (Dev: TRIM reduces sent amount, REJECT skips tick, null=identity)
  + `TaiXiuGameBotCoordinationCompositionTest` (QA: seam composes with the TaiXiu lock after decideBet).
- `Bot.setCoordinator` / `BotGroupRuntime.coordinator` ← wired and asserted via the seam and service tests.
- `BotGroupBehaviorService` (build+inject coordinator, force betSkipPercentage=0, buildCoordinationState)
  ← `BotGroupBehaviorServiceTest` (Dev: health surfaces coordination state; null when absent).
- `BotGroupMapper` / `BotGroup` / `BotGroupDTO` (2 write-side fields) ← `BotGroupMapperTest` (Dev:
  toDTO/toEntity/PATCH full-replace + null-keep).
- `BettingGridRules` (AD-1 cap≥minBet, coordination-gated) ← `BettingMiniConfigValidatorTest` (Dev:
  disabled=unconstrained, enabled cap<minBet rejected, == boundary passes, decoupled from per-bot cap).
- `CoordinationStateDTO` / `BotGroupHealthDTO.coordination` ← asserted in the service health test
  (per-option targetBudget/committedStake/realizedFraction, aggregate, approve/trim/reject counts).

## Gaps

- **Live-round steering (Verification #3/#4/#6/#7) is staging-only.** The realized-distribution-tracks-
  target behavior, the one-per-round DEBUG summary line, and the "off path unchanged" end-to-end smoke
  depend on a running group reaching live rounds; these are integration checks for the Releaser on staging,
  not unit-testable here. The counter/summary *logic* is unit-covered (approve/trim/reject counts,
  onRoundComplete no-op), but the emitted DEBUG log string and its once-per-round cadence are asserted only
  by inspection, not by a log-capture test.
- **`betCondition` re-derive fallback does not re-run coordination** (AD-2 documented benign edge): a
  reservation committed in `betCondition` whose park is cleared by `beforeReconnect` is a sub-round
  over-count that resets next `onRound`. Left uncovered by design — matches the plan's explicit
  "do not un-reserve" decision; not a defect.
- **AD-7 `betSkipPercentage=0` forcing** is covered structurally (the service branch is exercised by the
  behavior-service tests via the enabled path) but there is no dedicated assertion that a per-bot
  `BotBehaviorConfig` built under `coordinationEnabled=true` carries `betSkipPercentage==0`. Low risk —
  the field already defaults to 0 in production (plan AD-7), so this is future-proofing, not live behavior.

## Failures

None. `mvn clean install` is green (1325/1325). The stack trace visible in build stdout is a
`RestExceptionHandler`-logged handled exception from an unrelated controller test (expected-path logging),
not a test failure.

## Notes (non-blocking)

- Commit organization is clean and phased (Phase 1→4); no cosmetic concerns worth failing on.
- The Dev-authored 64-thread stress (`BetCoordinatorTest.concurrentStress`) is meaningful: 12,800 reserves
  against a cap that admits ~50k with per-option budgets ~8.3k, asserting both invariants plus
  `approvedPlusTrimmed == currentAggregateStake`. QA added a complementary tiny-cap reject-storm to cover
  the high-contention/high-rejection regime.
