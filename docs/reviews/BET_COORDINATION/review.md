# Code Review — BET_COORDINATION

Branch: `feat/bet-coordination`
Reviewed diff: `git diff cd758bb..HEAD` (code commits `fbcc17a..cc4dd7f`; the two leading commits are docs)

## Verdict

PASS

PASS = no `bug` or `security` findings. The findings below are all `smell`/`style` (advisory). The strongest of them (the per-round DEBUG summary firing once per bot instead of once per group) directly contradicts CLAUDE.md's anti-flood logging contract and AD-6, so I'd strongly recommend addressing it before scale runs even though it does not gate the verdict.

## Findings

### [smell] `onRoundComplete` emits the AD-6 DEBUG summary once per bot, not once per group
`src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:181`
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:500`

`onRoundComplete` is called from every bot's `onEndGame`, and — unlike `onRound`, which is made idempotent by only swapping `current` when the sid differs — it has **no first-seen guard**. It reads `current`, builds the histogram, and unconditionally `log.debug(...)`. With N bots in a group, that is N identical DEBUG lines per completed round (the counters are cumulative-since-construction, so every line is byte-identical). At the plan's own stated scale (100 bots) this is a 100× DEBUG flood per round.

This violates two things at once:
- AD-6's explicit contract: "the coordinator emits **one DEBUG line per group per round**."
- CLAUDE.md's logging model, whose whole point is that per-round summaries are first-seen deduplicated (the `SessionAggregationService` "first bot to observe this sid logs, the rest are no-ops" pattern, mirrored right above at `BettingMiniGameBot.onStartGame:386`). Production runs at DEBUG, so this is exactly the per-bot flood the codebase went out of its way to kill.

Fix shape: give `onRoundComplete` a first-seen guard keyed on the sid (e.g. track a `lastCompletedSid` under the lock and early-return if it already equals the round being completed), so only the first bot to finalize a given sid logs. The passed `sessionId` parameter (currently unused — see below) is the natural key.

### [smell] `onRound` swaps on ANY sid difference, so a lagging bot can reset a newer active round
`src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:105`

The swap condition is `sessionId != current.sessionId()`, which advances *or regresses*. Session ids are monotonically increasing and the server serializes `EndGame(N)` before `StartGame(N+1)`, so normally this is fine. But the coordinator is shared across bots that each process their own netty stream independently: if bot A's message processor lags a full round while bot B has already driven `current` to `N+1`, bot A's delayed `onRound(N)` will swap `current` back to a fresh budget for round `N`, discarding B's committed totals for `N+1`. Subsequent `reserve(..., N+1, ...)` then reject on sid mismatch until another bot fires `onRound(N+2)`.

The per-round cap invariant is not breached (each `RoundBudget` is independently capped at `C`), so this is not a correctness bug in the "aggregate exceeds cap within one budget" sense — it's a transient reset/miscount under an unlikely lag. Still, the coordinator should only ever advance. Fix shape: guard with `if (sessionId > current.sessionId())`. Same reasoning applies to `onRoundComplete` finalizing a stale sid.

### [smell] `onRoundComplete(long sessionId)` ignores its argument
`src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:181`

The caller passes `endGameSessionId(msg)`, but the method never reads the parameter — it logs `current.sessionId()` instead. Today they coincide, but if `current` has already advanced (see the finding above) the summary would mislabel the round. Either use the parameter as the finalization key (which also enables the first-seen guard above) or drop it from the signature to avoid implying it is honored.

### [smell] Dead accessors on `RoundBudget`
`src/main/java/com/vingame/bot/domain/bot/coordination/RoundBudget.java:49`, `:84`, `:89`

`cap()`, `budget()`, and `committedSnapshot()` are all unused (`committedSnapshot` allocates a defensive copy for no reader). Trim them, or if they're intended as future Fleet API, note that — as-is they're just surface area to keep in sync.

### [style] FQN and `Collectors.toList()` inconsistency in `BetCoordinator`
`src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:70`, `:196`

Line 70 uses fully-qualified `java.util.Collections.unmodifiableMap(...)` while the file otherwise imports its `java.util` types; and line 196 uses `.collect(Collectors.toList())` where the surrounding new code (e.g. `BotGroupBehaviorService.buildCoordinationState`) uses the Java 16+ `.toList()`. Minor; import `Collections` and prefer `.toList()` for consistency with the rest of the branch.

## Notes

- **Concurrency & math check out.** The single `ReentrantLock` guards every mutation of `current` and both `getCurrentAggregateStake()` and `snapshot()` read under it, so the DTO never sees a torn view. `reserve` computes `allow = min(amount, remainingOption, remainingAggregate)`, floors to grid, and commits `aligned ≤ allow`, so by induction `committedAggregate ≤ cap` and `committedOf(o) ≤ budgetOf(o)` hold at all times — the aggregate cap is the hard ceiling and grid-align-down can never breach it, even summed across many bots. `Σ floor(w/W·cap) ≤ cap`, so per-option budgets never over-subscribe the cap. `betIncrement <= 0` degrades cleanly to "any value ≥ minBet"; `sid == 0` and stale/mismatched sids are rejected in `reserve`. The `log.debug` in `onRoundComplete` is correctly emitted *after* the lock is released. No blocking calls inside the critical section.
- **Off-path identity (AD-9) confirmed.** With `coordinationEnabled=false`: no coordinator is built (guarded in `start()`), `bot.setCoordinator(runtime.getCoordinator())` injects `null`, `applyCoordination` returns `Optional.of(proposed)` verbatim, `betSkipPercentage` is left untouched, and `buildCoordinationState(null)` returns `null` so the DTO block is absent. Zero behavioral change to existing groups.
- **Seam placement is right.** `applyCoordination` runs after `decideBet` in `betCondition()`, so it sees the TaiXiu-remapped option; it parks the gated result and leaves `bet()` unchanged. The `bet()` race-fallback re-derives via `decideBet` only (not `applyCoordination`), so it does not double-reserve — consistent with the documented benign edge (an un-reserved send on an effectively-unreachable `beforeReconnect` path).
- **Wiring is correct.** Coordinator built only for `BETTING_MINI`/`TAI_XIU` (AD-10), injected before `startBot`, `betSkipPercentage` pinned to 0 only under coordination (AD-7), and the two caps (`maxAggregateStakePerRound` group-level vs `maxTotalBetPerRound` per-bot) are kept distinct in model/DTO/mapper/validation with clear javadoc.
- **Logging levels** are otherwise correct: per-proposal outcomes are TRACE, `onRound` swap is TRACE, the group-lifecycle "coordinator created" line is INFO. Only the per-round DEBUG summary's fan-out (first finding) breaks the model.
- **`realizedFraction` formula** (`committedStake / targetBudget`, 0 when budget is 0) is a sensible, documented choice — it reads as "how full is this option against its target," is bounded in `[0,1]` since `committedOf ≤ budgetOf`, and makes the AD-7 under-fill floor visible. Fine as-is.
- The known AD-7 "floor" limitation (trim-only can't force under-filled options to fill) was excluded from review per instructions and is not counted here.
