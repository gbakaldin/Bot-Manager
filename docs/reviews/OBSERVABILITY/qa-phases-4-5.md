# QA — OBSERVABILITY Phases 4 & 5

**Verdict:** PASS

**Build:** `mvn test` -> 365 tests, 0 failures, 0 errors, 0 skipped (baseline 355 + 10 new).

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java`
  - New `OnEndGameMetricsTests` nested class, 4 tests:
    - `shouldCallWinningsZeroAndSkipJackpotAndGameTotalsByDefault`
    - `shouldEmitWinningsAndJackpotWhenSubclassOverrides`
    - `shouldEmitGameTotalsWhenCapabilityGateOpen`
    - `shouldNoOpWhenMetricsNull`
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java`
  - `incBotWinnings_attachesMdcTags`
  - `incBotJackpot_incrementsBothCountAndAmount`
  - `incGameTotalWinnings_attachesOnlyGameTypeTag`
  - `incGameTotalBetAmount_attachesOnlyGameTypeTag`
  - `incGameTotalWinnings_emptyMdc_omitsGameTypeTag`
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilterTest.java`
  - Extended `map_aggregateGauge_doesNotAttachMdcTags` to include both new aggregate names.
  - New `map_gameTotalMeter_withGameTypePreTagged_keepsGameTypeButRejectsBotIdentity`.

## Coverage of the diff

- `BettingMiniGameBot.java` (5 hooks + `onEndGame` wiring) <- `BettingMiniGameBotTest$OnEndGameMetricsTests`
- `BotMetrics.java` (4 new methods + `gameTypeTagOnly()`) <- `BotMetricsTest` (5 new tests)
- `BotMdcTagsMeterFilter.java` (2 new allow-list entries) <- `BotMdcTagsMeterFilterTest` (2 updated/new)
- Dashboards (bots.json, game-server.json) <- JSON-validated, metric refs cross-checked against `BotMetrics` constants.

## Capability-gate semantics (verified)

| Hook | Default | Effect on `onEndGame` |
|---|---|---|
| `getWinnings()` | `0L` | `incBotWinnings(0)` fires unconditionally; counter registered, 0-increment is a no-op for series math but ensures the meter exists. |
| `getJackpot()` | `0L` | Caller guards `if (jackpot > 0)` -> `incBotJackpot` does NOT fire by default. |
| `canCheckTotalWinnings()` | `false` | Gates BOTH `incGameTotalWinnings` and `incGameTotalBetAmount`. Default -> 0 emissions and `game_total_*` series does not exist until a subclass opts in. |
| `getTotalWinnings()` | `0L` | Only read when gate is open. |
| `getRoundTotalBetAmount()` | `0L` | Only read when gate is open. |

Order in `onEndGame` (lines 187-221): `incBotMessage("endGame")` -> read `getWinnings()` -> `incBotWinnings(winnings)` -> assign `lastRoundWinnings` -> conditional jackpot -> conditional game-totals -> `gameState = PAYOUT`. Winnings read happens BEFORE state mutation, matching the plan. The whole metrics block is wrapped in `if (metrics != null)` (line 194) and the outer `incBotMessage` guard is also `if (metrics != null)` (line 188). Null-safety confirmed by `shouldNoOpWhenMetricsNull`.

## MDC vs gameType-only tagging (verified)

- `incBotWinnings`, `incBotJackpot` -> `mdcTags()` (full per-bot tag shape: `botGroupId`, `environmentId`, `gameType`). Asserted in `incBotWinnings_attachesMdcTags` / `incBotJackpot_incrementsBothCountAndAmount`.
- `incGameTotalWinnings`, `incGameTotalBetAmount` -> `gameTypeTagOnly()`. The helper reads ONLY `MDC.get("gameType")` and returns either `Tags.empty()` or `Tags.of("gameType", value)`. Asserted in `incGameTotalWinnings_attachesOnlyGameTypeTag` (full MDC populated, only `gameType` survives) and `..._emptyMdc_omitsGameTypeTag`.
- `BotMdcTagsMeterFilter` defense-in-depth: name-prefix check (`startsWith("bot_")`) already excludes `game_*` from MDC stamping. The allow-list (lines 53-62) explicitly lists `game_total_winnings_total` and `game_total_bet_amount_total` as belt-and-braces. Asserted in `map_gameTotalMeter_withGameTypePreTagged_keepsGameTypeButRejectsBotIdentity` and `map_aggregateGauge_doesNotAttachMdcTags`.

## Dashboards (verified)

JSON validates with `python3 -c "import json,sys; [json.load(open(f)) for f in sys.argv[1:]]"`.

**bots.json** (6 new panels):
- 13 "Bot RTP overall (5m)" -> `bot_winnings_total`, `bot_bet_amount_total`. Has `or vector(0)` guard.
- 14 "Bot RTP per game (5m)" -> `bot_winnings_total`, `bot_bet_amount_total`. NO `or vector(0)` / `clamp_min` guard (minor; see Concerns).
- 15 "Bot winnings rate per game (5m)" -> `bot_winnings_total` (pure rate, no division).
- 16 "Bot money drain per game (5m)" -> `bot_bet_amount_total`, `bot_winnings_total` (subtraction, no division).
- 17 "Bot jackpot wins rate (5m)" -> `bot_jackpots_total` (pure rate).
- 18 "Bot jackpot amount total per game (5m)" -> `bot_jackpot_amount_total` (pure rate).

**game-server.json** (3 new panels):
- 8 "Bot bet share per game (5m)" -> `bot_bet_amount_total` / `game_total_bet_amount_total`. Uses `clamp_min(..., 1)`.
- 9 "Real-player bet share per game (5m)" -> `1 - (bot_bet_amount / clamp_min(game_total_bet_amount, 1))`.
- 10 "Real-player RTP per game (5m)" -> `(game_total_winnings - bot_winnings) / clamp_min(game_total_bet_amount - bot_bet_amount, 1)`.

All metric references resolve to constants in `BotMetrics.java`.

## Cardinality (AD 5) — verified

No `username` / `botId` tags on any new meter. `game_total_*` carry ONLY `gameType` (test-asserted). `bot_winnings_total` / `bot_jackpots_total` / `bot_jackpot_amount_total` carry the same `botGroupId, environmentId, gameType` shape as the existing `bot_bet_amount_total` (test-asserted by tag-key assertion).

## Naming deviation — `getRoundTotalBetAmount()`

Dev renamed plan's `getTotalBetAmount()` -> `getRoundTotalBetAmount()` because Lombok generates a public `Bot.getTotalBetAmount()` returning `AtomicLong` (per-bot lifetime accumulator) that the `protected long` override cannot shadow. Verified consistent rename:

- Caller in `BettingMiniGameBot.onEndGame` line 210 -> `getRoundTotalBetAmount()`.
- Definition at line 266 with Javadoc explaining the rename.
- Test subclass in `BettingMiniGameBotTest` line 635 overrides `getRoundTotalBetAmount`.
- No reference to a plain `getTotalBetAmount()` exists in new code; the existing `bot.getTotalBetAmount().get()` call in `BotGroupBehaviorService:487` continues to refer to the unchanged Lombok-generated AtomicLong accessor.

QA confirms the rename is internally consistent. Plan amendment is Architect-2's call.

## Health DTO compatibility — verified

- `Bot.lastRoundWinnings` field (line 74) still declared; type unchanged (`long`).
- `BotGroupBehaviorService.java:488` reads via `bot.getLastRoundWinnings()` — unaffected.
- `BotHealthDTO.lastRoundWinnings` (line 22) unchanged.
- `BotGroupControllerTest.java:640,651` continue to use the field on test DTOs (existing tests still green).
- New behavior: `onEndGame` writes `lastRoundWinnings = getWinnings()` (line 197). Default sets it to 0, subclasses set it to the round payout. Asserted in `shouldCallWinningsZeroAndSkipJackpotAndGameTotalsByDefault` (0L) and `shouldEmitWinningsAndJackpotWhenSubclassOverrides` (750L).

No regression in any existing health-DTO test (12 `BotGroupControllerTest` tests still pass).

## Concerns

1. **Minor (cosmetic):** bots.json panel 14 ("Bot RTP per game (5m)") lacks the `or vector(0)` / `clamp_min` cold-start guard that panel 13 and the game-server.json division panels use. Will display "No data" before any bot has placed a bet rather than `0`. Not a correctness issue; the data point will still render once `bot_bet_amount_total` has any value. Releaser may want to add `or vector(0)` for consistency before demo.
2. **None blocking.** All capability-gate semantics, MDC tag shapes, allow-list membership, and health-DTO compatibility verified.

## Failures

None.
