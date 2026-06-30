# QA — TIP_ENDGAME

**Verdict:** PASS
**Build:** `mvn test` → 443 tests, 0 failures, 0 errors (baseline before QA: 438; QA added 5)

Branch: `feat/tip-endgame`. Commits reviewed: `b5a8d52`, `4180bc0`, `0a029b9`, `17c8d50`.

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTipDispatchTest.java` (new, 5 tests) — integration-style coverage of `BettingMiniGameBot.onEndGame(TipEndGameMessage)` exercising the marker-interface dispatch path end-to-end with both constructor-built and Jackson-deserialized `TipEndGameMessage` instances. Closes the gap between the per-message unit tests in `TipGameMessageTypesTest` (direct interface calls on Tip) and the per-bot dispatch tests in `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests` (which use a generic `StubEndGameMessage`).

## Coverage of the diff

| Production change | Test that covers it |
|---|---|
| `TipEndGameMessage implements HasBotWinnings/HasJackpot/HasBetTotals` (`4180bc0`) | `TipGameMessageTypesTest.hasBotWinnings/hasJackpot/hasBetTotals/hasBetTotalsEmpty/capabilityInterfacesFromFixture` + new `BettingMiniGameBotTipDispatchTest` (all 5) |
| `TipEndGameMessage.winningsFor(userName) → wm` | `TipGameMessageTypesTest.hasBotWinnings`, `capabilityInterfacesFromFixture`; `BettingMiniGameBotTipDispatchTest.constructorBuilt_dispatchesAllThreeCounters`, `fixtureDeserialized_dispatchesAllThreeCounters` |
| `TipEndGameMessage.jackpotFor(userName) → iJp ? jpV : 0L` (jpV pinned, NOT tJpV) | `TipGameMessageTypesTest.hasJackpot` (direct, with tJpV=200_000 + iJp=false → 0), `endGame` (asserts both jpV=1_603_000 and tJpV=200_000 are deserialized to distinct fields), `capabilityInterfacesFromFixture` (asserts 1_603_000 not 200_000); `BettingMiniGameBotTipDispatchTest.fixtureDeserialized_dispatchesAllThreeCounters` (`verify(metrics, never()).incBotJackpot(200_000L)`), `noJackpotWhenIJpFalse_doesNotFallBackToTJpV` |
| `TipEndGameMessage.betAmountFor / betCountFor` reading `sum(bs[].b)` and `sum(bs[].bc)` (server-authoritative, not Bot-local) | `TipGameMessageTypesTest.hasBetTotals` (3-element bs summing to 2600/6), `hasBetTotalsEmpty` (null + empty), `capabilityInterfacesFromFixture` (from fixture); `BettingMiniGameBotTipDispatchTest.fixtureDeserialized_dispatchesAllThreeCounters` (asserts `incBetsPlaced(3, 2500L)` from `bs[]` only) |
| `Bot.creditBalance` no longer calls `BotMetrics.incBetPlaced` (`0a029b9`) | `BotTest.CreditBalanceTests.shouldDecrementAndIncrement` + `shouldAccumulate` + `shouldNotEmitMetricsCounter` (the last uses `verifyNoInteractions(metrics)` — strict) |
| `BotMetrics.incBetPlaced(long)` deleted (`0a029b9`); only `incBetsPlaced(int,long)` survives | `BotMetricsTest.incBetsPlaced_batchIncrementsCountAndAmountSums`, `_zeroOrNegativeCountIsNoOpAndCreatesNoCounter`, `_multipleCallsSumIntoTheSameTimeSeries` |
| Capability hooks (`getWinnings`/`getJackpot`/`canCheckTotalWinnings`/`getTotalWinnings`/`getRoundTotalBetAmount`) deleted from `BettingMiniGameBot` (`17c8d50`) | Phase C deletion verified by grep (see Focus 4 below) — no test or production caller remains |
| Tip Jackson fixtures (`b5a8d52`) | `TipGameMessageTypesTest.subscribe/startGame/startGameMd5/updateBet/endGame` round-trip all 5 fixtures through the polymorphic mapper |

## Focus-area findings

**1. Tip fixture validity.** All 5 fixtures (`subscribe.json`, `startGame.json`, `startGameMd5.json`, `updateBet.json`, `endGame.json`) round-trip cleanly through `TipGameMessageTypes` via `mapper.registerSubtypes(...)`. The mapper sets `FAIL_ON_UNKNOWN_PROPERTIES=false` (matches `BettingMiniGameBot.botBehaviorScenario()` config), so the fixtures don't need to be exhaustive — they only need to populate every field the test reads. Field shape against `ROUND_DATA_COLLECTION_FINDINGS.md`:
- `endGame.json` carries `wm=1500`, `iJp=true`, `jpV=1_603_000`, `tJpV=200_000`, and `bs=[{eid:0,bc:2,b:2000,v:8000},{eid:1,bc:1,b:500,v:3500}]` — exercises every new capability surface. The shape matches the documented "personalized payload" interpretation (root `wm` = recipient, `bs[].b` = recipient's per-position bet, `bs[].v` = round-wide aggregate).
- `subscribe.json` has all 25 constructor properties from `TipSubscribeMessage.@JsonCreator`. No missing required fields.

**2. Capability dispatch.** Pre-QA, only direct calls on the interface methods were tested (`((HasBotWinnings) tip).winningsFor(...)` etc. in `TipGameMessageTypesTest`). The `BettingMiniGameBot.onEndGame` dispatch was tested with a generic `StubEndGameMessage` in `BettingMiniGameBotTest`. No test put the two halves together. **Gap closed** by `BettingMiniGameBotTipDispatchTest` (5 new tests):
   - `constructorBuilt_dispatchesAllThreeCounters` — feeds a `TipEndGameMessage` built via constructor into `onEndGame` and verifies `incBotWinnings(1500)` + `incBotJackpot(10_000)` + `incBetsPlaced(3, 2500)` fire in order, plus `lastRoundWinnings == 1500L`.
   - `fixtureDeserialized_dispatchesAllThreeCounters` — same, but the `TipEndGameMessage` is produced by Jackson from `/messages/tip/endGame.json` — the most realistic path. Asserts `incBotJackpot(1_603_000L)` AND `verify(metrics, never()).incBotJackpot(200_000L)` to pin `jpV`-not-`tJpV` through the dispatch, not just the direct interface call.
   - `noJackpotWhenIJpFalse_doesNotFallBackToTJpV` — `iJp=false, jpV=0, tJpV=200_000`; asserts dispatch never calls `incBotJackpot(anyLong())`.
   - `emptyBetTotals_callsBatchWithZeros` — `bs=null`; asserts `incBetsPlaced(0, 0L)` IS called (the gating is inside `BotMetrics`, not the dispatch).
   - `nullMetrics_realTipMessage_doesNotCrash` — null-metrics smoke test with the real Tip type; asserts `gameState == PAYOUT`.

**3. Phase B regression / vacuous-pass risk.** `Bot.creditBalance` keeps local AtomicLong accumulators (`totalBetsPlaced`, `totalBetAmount`) which feed `BotHealthDTO`. Two risks:
   (a) "Both tests assert on the local accumulator, never proving the metric was removed." — Mitigated. `BotTest.CreditBalanceTests` includes `shouldNotEmitMetricsCounter` which calls `verifyNoInteractions(metrics)` after `creditBalance(500)` — a strict contract that fails if any new metric call leaks back into `creditBalance`. The two pre-existing tests (`shouldDecrementAndIncrement`, `shouldAccumulate`) were also updated to add `verify(metrics, never()).incBetsPlaced(anyInt(), anyLong())`.
   (b) "Dispatch test verifies `incBetsPlaced` is actually called from `onEndGame`." — Covered by `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldExtractFromHasBetTotals` (with the stub) and by the new `BettingMiniGameBotTipDispatchTest` (with the real Tip message).

**4. Phase C scope.** Grep `getWinnings|getJackpot|canCheckTotalWinnings|getTotalWinnings|getRoundTotalBetAmount|\bincBetPlaced\b` against `src/main` and `src/test`:
   - No production-code references to the deleted `BettingMiniGameBot` methods (only a Javadoc comment at `BettingMiniGameBot.java:197-198` lists the deleted method names for context).
   - No test-code references to the deleted methods, except one **stale Javadoc comment** at `TipGameMessageTypesTest.java:151` that says "per Javadoc on TipEndGameMessage.getJackpot()" — `TipEndGameMessage` has no `getJackpot()` method; it has `jackpotFor(String)`. **Cosmetic only**; doesn't affect compilation or test behaviour. Flagging as a nit, not a failure.
   - The matches in `SessionHistoryMapper.java` / `SessionHistoryMapperTest.java` (`getJackpot`, `getTotalWinningsSinceLastDeposit`) refer to `SessionHistoryDTO` / `SessionHistory` entity getters — different scope, unrelated to the ENDGAME_METRICS capability hooks. Confirmed by reading the surrounding code.
   - `BotMetrics.incBetPlaced(long)` (the deleted single-bet method): 0 references in `src/main` or `src/test`. Clean.

**5. `jpV` decision pinning.** Asserted at three layers:
   - Direct interface call: `TipGameMessageTypesTest.hasJackpot` — `jackpotFor("any-bot") == 1_603_000L` with `iJp=true, jpV=1_603_000, tJpV=200_000`. The `iJp=false, jpV=0, tJpV=200_000` branch returns 0 (not 200_000), pinning the choice.
   - Fixture-deserialized direct call: `capabilityInterfacesFromFixture` — `jackpotFor("bot1") == 1_603_000L` from the JSON fixture (which has tJpV=200_000 distinct).
   - Dispatched through `BettingMiniGameBot.onEndGame` (added by QA): `fixtureDeserialized_dispatchesAllThreeCounters` — `verify(metrics).incBotJackpot(1_603_000L)` AND `verify(metrics, never()).incBotJackpot(200_000L)`. The "never tJpV" assertion is the load-bearing one.

**6. `HasBetTotals` semantics (server-authoritative).** Both `TipEndGameMessage.betAmountFor()` and `betCountFor()` iterate `this.bs` (the deserialized `List<BetInfoWithTotal>` from the `EndGame` JSON payload) and sum the per-user fields (`info.getB()`, `info.getBc()`). They do not read any local field on the `Bot` (no `totalBetsPlaced`, no `totalBetAmount`). Confirmed by source read. Tests pin this:
   - `TipGameMessageTypesTest.hasBetTotals` with 3 distinct bs entries summing to 2600/6.
   - `capabilityInterfacesFromFixture` — from JSON fixture, bs sums to 2500/3.
   - `BettingMiniGameBotTipDispatchTest.fixtureDeserialized_dispatchesAllThreeCounters` — through the dispatch path, asserts `incBetsPlaced(3, 2500L)` with comment explaining that a fallback onto `Bot.totalBetsPlaced`/`totalBetAmount` would still surface (since no bets were sent on the test bot, both are 0).
   - `BettingMiniGameBotTipDispatchTest.emptyBetTotals_callsBatchWithZeros` — null bs[] → `incBetsPlaced(0, 0L)`. If the implementation ever fell back to a local field, this would surface a non-zero value.

## Gaps

1. **No assertion that `b52` / `bom` / `nohu` EndGame messages still do NOT implement the marker interfaces** post-Phase-C. The plan's AD-8 says "marker interfaces are empty of implementers at end of this redesign" (Tip is the first; B52/Bom/Nohu are not yet). A static grep verifies this for the current diff, but no test pins it as a contract — a future commit could silently add a `HasBotWinnings` impl on `BomEndGameMessage` with a buggy extractor, and only the staging Grafana dashboard would notice. Not worth a test today; flagging for future Phase-B-on-Bom work.

2. **`TipUpdateBetMessage.getGameState()` returns hardcoded 0** because the JSON sample lacked `gS`/`rmT`. Asserted in `TipGameMessageTypesTest.updateBet` but not exercised through `BettingMiniGameBot.onUpdate` (which gates phase transitions on `gameState > 0`). This is a known item in the CLAUDE.md backlog ("Verify TipUpdateBetMessage.getGameState() behavior in staging"), so deferred — the bot will simply never transition phase from a Tip UpdateBet until that's resolved upstream.

3. **Fixture realism caveat.** Per the user's note, no real wire captures were available; `bET`/`bST` in the `jpCD` fixture are ISO-8601 strings but the production server may send Unix epoch millis or a different format. `JackpotCountdown` declares them as `String`, so any string round-trips, but a numeric source would deserialize to `null`/`"123456"` depending on Jackson config. **Not testable without a real frame.** Same caveat for `htr` shape and `sDi`. The tests pass on the fixtures provided; real-world deserialization could surface field-type mismatches in staging.

4. **`tJpv2` typo (lowercase v) was preserved verbatim.** `TipEndGameMessage` declares `private long tJpv2` (not `tJpV2`) and the `@JsonCreator` uses `@JsonProperty("tJpv2")`. The endGame fixture matches with `"tJpv2": 0`. If the wire actually sends `tJpV2`, the fixture deserializes fine but production would silently drop the value. Cosmetic — the field isn't read by the capability methods.

5. **Cosmetic doc nit** at `TipGameMessageTypesTest.java:151` (comment references nonexistent `TipEndGameMessage.getJackpot()` instead of `jackpotFor(String)`). Not a failure; flagging for the next sweep.

## Failures (if any)

None. Full `mvn test` clean: 443 tests, 0 failures, 0 errors.
