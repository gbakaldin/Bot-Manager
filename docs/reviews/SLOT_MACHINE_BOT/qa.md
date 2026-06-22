# QA — SLOT_MACHINE_BOT

**Verdict:** PASS
**Build:** mvn test → 748 tests, 0 failures, 0 errors (was 740 before QA additions; +8 new)

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/core/SlotMachineBotGateEdgeCasesTest.java` (NEW, 4 tests) — covers the spin-gate edge cases the dev tests left implicit:
  - AD-13 balance gate: `expectedCurrentBalance < chosenBet * numLines` closes the gate and parks nothing; `>= cost` opens it (boundary pinned at 12_499 vs 12_500 for 500×25).
  - AD-11/AD-12 server-sourced winline count: a 10-line subscribe response drives the cost gate to 500×10=5_000 (not the fixture's 25), proving the gate uses the runtime count.
  - AD-6/reconnect: `beforeReconnect()` clears both the in-flight gate and the parked bet.
  - Implementation-Notes gid guard: a spin result for a foreign `gid` is ignored (no `incBotWinnings`, no `incBetsPlaced`, balance untouched) but still clears `spinInFlight` so the bot is not wedged.
- `src/test/java/com/vingame/bot/domain/bot/message/slot/SlotMessageDeserializationTest.java` (UPDATED, +2 tests) — partial/malformed JSON: a 1302 body with only `cmd` present deserializes with scalar defaults and null `wls` → zero winnings, no NPE; the captured 1300 fixture's un-modeled fields (`eSC`, `taxes`, `as`, `ae`, `fss`, `lss`) deserialize without failing.
- `src/test/java/com/vingame/bot/domain/bot/strategy/slot/RandomBetStrategyTest.java` (UPDATED, +2 tests) — single-element allowed set always returns that element (`nextInt(1)==0`); over 500 draws the RANDOM picks span the entire allowed `Js[].b` set (no element starved).

## Coverage of the diff

- `SlotMachineBot.java` ← `SlotMachineBotSubscribeTest` (onSubscribe capture + AD-12 gate), `SlotMachineBotSpinAccountingTest` (onSpinResult winning/losing accounting + gate clear), `SlotMachineBotSpinStreamTest` (full condition→supplier→result loop, multi-spin balance invariant, RANDOM over Js), `SlotMachineBotGateEdgeCasesTest` (balance gate boundary, variable numLines, beforeReconnect, foreign-gid guard).
- `SlotSpinResultMessage.java` / `SlotSubscribeResponse.java` ← `SlotMessageDeserializationTest` (round-trip from captured `[5,{...}]` fixtures, derived accessors, null/empty/partial defaults).
- `SlotMessageTypesImpl.java` / `SlotMessageTypes` / `GameMessageTypesResolver` ← `SlotMessageTypesTest` (cmd constants 1300/1302, registrations keyed on literal strings, `resolveSlot()`), `GameMessageTypesResolverTest`.
- `SlotRequest.java` / `SlotSpin.java` / `SlotSubscribe.java` ← `SlotMachineBotSpinStreamTest` (asserts emitted `SlotSpin` carries server-sourced `b`, `gid`, and `ls == [0..numLines-1]`).
- `FixedBetStrategy.java` / `RandomBetStrategy.java` / `SlotStrategyFactory.java` / `SlotBetContext` ← `FixedBetStrategyTest`, `RandomBetStrategyTest`, `SlotStrategyFactoryTest`.
- `BotFactory` `case SLOT` wiring ← `BotFactorySlotWiringTest`, `BotFactoryFailLoudTest`.
- `Game` slot config (gameId=gid) ← `GameMapperTest` slot round-trip.

## Gaps

- **Scenario-engine wiring (`botBehaviorScenario`, `onStart`, `buildContext`)** is not exercised by a live scenario engine / WebSocket — all tests drive the `onSubscribe`/`spinCondition`/`spin`/`onSpinResult` handlers directly. This is consistent with the betting-mini test idiom (deterministic, no threads/network). The actual pipeline assembly, `waitForMessage(cmd 1300)`, `typeOf(RECEIVED)` echo-suppression, and the 3s `sendAsync` cadence are integration-only and are covered by the plan's on-server Verification steps 3–6.
- **Free-spin / bonus-round handling** — explicitly out of v1 scope (AD-14); the result model carries `hFS`/`fss` defensively but no state machine exists, so nothing to test.
- **Silent-unanswered-spin watchdog** — deferred Open Item (AD-5/AD-6); no production code, no test.
- **`onNewSession` deposit path** inside `onSubscribe` is reached with `autoDepositEnabled=false` in tests; the deposit branch itself is inherited `Bot` behavior covered elsewhere.

## Failures (if any)

None. The `log4j` "Could not create directory /app/logs" ERROR lines in the test output are environment noise (the container log path is absent on the dev box); they do not affect test outcomes. The `RestExceptionHandler`/`ResourceNotFoundException` stack trace seen in verbose output is a handled-exception assertion in an unrelated session-history controller test, not a failure.
