# QA — TAI_XIU_BOT

**Verdict:** PASS
**Build:** `mvn test` → 875 tests, 0 failures, 0 errors

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/core/TaiXiuGameBotSessionCorrelationTest.java` (NEW, 3 tests) —
  hardens focus area #3 (EndGame session correlation). The captured Tai Xiu EndGame frame
  carries no `sid` (`TaiXiuEndGameMessage.getSessionId()` returns `0`), so the bot must
  correlate the finished round to the session tracked from StartGame via the
  `endGameSessionId()` seam reading `sidStore`. The pre-existing dispatch/stream tests
  pin balance + metrics (which flow through `balanceCreditFor`/marker interfaces and would
  still pass even if correlation were broken); this gap was the only genuinely
  under-pinned focus area. The new tests assert the correlation through the finalized
  `RoundResult` that `BotMemory.completeRound` produces:
  1. `endGameSessionId()` seam returns the tracked session (`2670572`), not `msg.getSessionId()=0`.
  2. A no-sid EndGame correlates the placed bet to the tracked round — `RoundResult.sessionId`
     is the tracked sid, `betsByOption` is non-empty (non-discarded), `payout = G`,
     `balanceDelta = payout − staked`.
  3. Control/contrapositive: when the tracked session advances past the round the bet was
     placed in, `BotMemory` discards the in-flight attribution (empty `betsByOption`) —
     proving the assertions in (2) are load-bearing, not vacuously true.

  Verified RED against a deliberately-broken seam (`return msg.getSessionId()` instead of
  `getSidStore().get()`): all 3 tests fail; production restored to passing.

The pre-existing Tai Xiu test suite (already on the branch from Dev) was reviewed and found
genuinely strong; no rewrites were needed. Files reviewed:
- `TaiXiuGameBotDispatchTest.java`, `TaiXiuGameBotStreamTest.java`,
  `TaiXiuMessageDeserializationTest.java`, `TaiXiuMessageTypesTest.java`,
  `TaiXiuRequestTest.java`, `BotFactoryTaiXiuWiringTest.java`,
  `TaiXiuConfigValidatorTest.java`, `StrategyControllerTest.java`,
  `EnvironmentZoneResolutionTest.java`, `BotGroupConfigValidationIT.java`,
  `PermissiveConfigValidatorsTest.java`.

## Coverage of the diff

- `TaiXiuEndGameMessage` (refund-aware accounting, AD-11) ← `TaiXiuMessageDeserializationTest` +
  `TaiXiuGameBotDispatchTest` + `TaiXiuGameBotStreamTest`. **The `G`-vs-`GX−gB` regression is
  genuinely pinned**: the partial/zero-refund fixtures use `G ≠ GX−gB`
  (partial: `G=120k` while `GX−gB=−180k`; zero: `G=80k` while `GX−gB=−420k`), and the tests
  assert `winnings == G` AND `winnings != Math.max(0, GX−gB)`. A reversion to `GX−gB` fails.
  Effective wagered = `gB−gR` (full-refund → 0, partial → 300k, zero → 500k) is asserted on
  the `bot_bet_amount_total` path (`incBetsPlaced(count, amount)`), distinct from gross `gB`.
  Balance net per round = `−b + gR + G` asserted in isolation (dispatch) and as a closed-form
  running identity across a mixed 5-round stream (stream test). Edge cases full / partial /
  zero refund all covered, plus defensive `G<0` floor and jackpot gating.
- `TaiXiuGameBot` (fixed CMD seams, request, balance credit, session correlation) ←
  `TaiXiuGameBotDispatchTest` + `TaiXiuGameBotStreamTest` + `TaiXiuGameBotSessionCorrelationTest` (NEW).
  Fixed CMDs 1005/1002/1004 and bet 1000 are asserted with a **poison offset (`999_999`) +
  `verify(gameSpy, never()).getOffset()`** — AD-9 (no offset leak) is genuinely enforced.
- `TaiXiuSubscribeMessage` (subscribe RESPONSE timing, OI-5) ← deserialization + dispatch:
  `tFB=50000 → timeForBetting`, `tFBB=3000 → blockBetTime/timeForDecision`, `sid`/`gS`/`rmT`
  all pinned from the real inbound subscribe-response fixture (not the bare request).
- `TaiXiuMessageTypes` / `MiniGameTaiXiuMessageTypes` / `resolveTaiXiu` ← `TaiXiuMessageTypesTest`
  (no-offset registrations against `"1005"/"1002"/"1004"`, unimplemented product throws).
- `TaiXiuRequest` (bare fixed cmd 1000 + `eid`/`aid`) ← `TaiXiuRequestTest` + dispatch bet path.
- `BettingMiniGameBot` Phase-1 seam refactor (behavior preservation) ← the **entire existing
  betting-mini suite stays green** — the contract for AD-2. No betting-mini regression.
- `BotFactory` `case TAI_XIU` ← `BotFactoryTaiXiuWiringTest` (builds a `TaiXiuGameBot`, never
  throws "not yet implemented"; `CARD_GAME`/`UP_DOWN` still throw).
- `Environment.resolveZoneName` (TAI_XIU → mini, AD-7) ← `EnvironmentZoneResolutionTest`
  (TAI_XIU moved from card/Simms to mini case).
- `StrategyController.list` (TAI_XIU lists betting strategies, AD-6) ← `StrategyControllerTest`.
- `TaiXiuConfigValidator` / `BettingGridRules` (grid parity, AD-8) ← `TaiXiuConfigValidatorTest`
  (mirrors BettingMini boundary cases + asserts identical accept/reject/message parity);
  both validators delegate to the same `BettingGridRules.validate`, so parity is structural.
  `GameConfigValidatorFactory` boot-completeness invariant still holds (`PermissiveConfigValidatorsTest`,
  `BotGroupConfigValidationIT`).

## Gaps

- **`aid` semantics (OI-6)** — `aid` is hardcoded `1`; tests assert it is `1` but cannot verify
  correctness because the meaning is unconfirmed upstream. Deferred (non-blocking, plan OI-6).
- **`updateBet` (OI-5)** — omitted in v1 (no captured frame); `updateBetType()` is null and the
  scenario skips it. Not a defect; correctly not registered (the inbound matcher must not carry
  3002). Covered by the TaiXiu provider test (only 1005/1002/1004 registered).
- **md5 StartGame variant (AD-10)** — no md5 Tai Xiu payload captured; `startGameMd5Type()`
  delegates to the provider. Not exercised by a real md5 fixture (none exists). Acceptable for v1.
- **On-server / staging verification (plan `## Verification` items 1–7)** — boot health, live
  strategy listing curl, REST game creation, real bot reaching CONNECTION_AUTHENTICATED,
  live metrics/RTP, and BettingMini non-regression on the server are integration/staging checks
  outside the unit-test scope. These are the Releaser's post-deploy responsibility; the unit
  suite gives high confidence the corresponding code paths are correct.

## Failures (if any)

None. 875 tests, 0 failures, 0 errors, BUILD SUCCESS.
