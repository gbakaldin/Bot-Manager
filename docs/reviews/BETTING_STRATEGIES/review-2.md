# Code Review (Round 2) — BETTING_STRATEGIES

Branch: `feat/betting-strategies`
Reviewed delta: last 4 fix commits (`ff7dcb7`, `92c010c`, `11a27bd`, `a6a03f5`)

## Verdict

PASS

All three `bug` findings from the prior review are addressed cleanly. Smells flagged
in the previous round are also resolved as a bonus (dead `memory != null` /
`strategy != null` guards removed, stale Javadoc refreshed, `BettingStrategyFactory`
seed parameter dropped, `BotMemory(game)` null-checked, fully-qualified
`RandomBehaviorStrategy` reference replaced with import). Ship it.

## Findings

None.

## Verification of prior bugs

### Bug 1 — `bettingOptions` silently dropped on fallback + migration (ff7dcb7)
- `Game.getEffectiveOptionAffinities()` now checks `bettingOptions` between the
  explicit `optionAffinities` branch and the `numberOfOptions` range branch.
  `bettingOptions = [1, 3, 5]` correctly produces `{1:1, 3:1, 5:1}`, preserving
  the operator-chosen option ids. `GameTest` pins all three precedence cases
  (`shouldSynthesizeFromLegacyBettingOptions`,
  `shouldPreferBettingOptionsOverNumberOfOptions`,
  `shouldPreferAffinitiesOverBettingOptions`).
- Field is `@JsonIgnore` with package-private `@Getter`/`@Setter` — no REST API
  regression; only Mongo and same-package code can see it. The Mapper layer is
  unaffected.
- `release.md` migration adds a Phase A pass before the existing range fallback
  using `$arrayToObject` + `$toString` to build proper string-keyed affinity
  maps. Both passes `$unset ["numberOfOptions", "bettingOptions"]` so the
  fields are gone post-migration. Expected-counts line updated accordingly.

### Bug 2 — `pendingDecision` race between condition and supplier (11a27bd)
- `onStartGame` no longer clears `pendingDecision`. Confirmed by inspection:
  the only remaining clear site is `beforeReconnect` (line 340). Comment at
  the previous clear site explains why the line is gone and how the strategy
  recovers via `RoundState.sessionId`.
- `bet()` supplier re-derives via `strategy.decide(buildBetContext())` when the
  parked decision is empty, and only throws `IllegalStateException` if the
  strategy *also* declines. Sound: the engine's null-guard
  (`SendAsync.processInternal:135`) leaves no sane way to skip the send.
- Phase 5 bit-for-bit equivalence is preserved. The re-derive path is reachable
  only after a netty-thread `beforeReconnect` clear, which (a) requires a WS
  reconnect mid-tick and (b) is not exercised by
  `BettingMiniGameBotStrategyEquivalenceTest` (which only drives the steady
  StartGame → condition → supplier → EndGame loop). In steady state the
  supplier pops the parked decision without calling `decide` a second time, so
  the RNG consumption order is identical to the legacy bot. The new
  `BettingMiniGameBotPendingDecisionRaceTest` pins all four properties:
  pendingDecision survives mid-tick StartGame, the supplier doesn't throw on
  race-and-recover, the supplier still throws on strategy-also-declines, and
  the engine null-guard contract is upheld.

### Bug 3 — `RoundState` cross-thread reads (92c010c)
- `sessionId`, `phase`, and `remainingTimeMs` are now `volatile`. These are the
  only primitive scalar fields. The fourth field, `betsByOption`, is
  intentionally not volatile — the only cross-thread consumer is
  `BotMemory.snapshotCurrentRoundBets()` which holds the monitor and returns a
  defensive copy. The bot's hot-path `RandomBehaviorStrategy.decide` reads only
  `getSessionId()` from `RoundState` (line 68); all other field accesses on
  the live RoundState are inside `BotMemory` methods that hold the monitor.
- `BotMemory.getCurrentRound()` drops the misleading `synchronized` modifier
  and the Javadoc now states explicitly that the returned reference exposes
  volatile scalars but a non-volatile bets map; strategies must go through
  `snapshotCurrentRoundBets()` for a coherent view. Accurate.
- `BotMemory` constructor now does `Objects.requireNonNull(game, "game")` —
  addresses the prior smell.

## Notes

- The supplier re-derive on the `beforeReconnect` race path consumes RNG that
  the condition already consumed for the same tick. This is a one-tick RNG
  drift in an already-degraded code path (WS reconnect) and is not asserted by
  any equivalence pin. Not a finding; flagging only because the JavaDoc on
  `bet()` calls out the recovery path as "stale-tick decision is strictly less
  safe than a fresh one", which is correct but worth understanding as a
  conscious trade-off.
- `StrategyAssignment` botCount-resize caveat from the prior review remains
  unaddressed in code (no Javadoc change), but it was filed as a `smell`, not
  a `bug`, and is purely an operator-documentation concern. Not a blocker.
- The seed-parameter cleanup in `BettingStrategyFactory.create(...)` propagates
  through the two test call sites correctly. Phase 5 wiring at
  `BettingMiniGameBot:150` is updated.
- Volatile-publish thread-safety is also exercised by the new
  `RoundStateThreadSafetyTest`.
