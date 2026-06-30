# Compliance — SLOT_MACHINE_BOT

Branch: feat/martingale-strategies
Plan reviewed: `docs/plans/SLOT_MACHINE_BOT.md` (at commit c735f5b)
Diff reviewed: `git diff main..feat/martingale-strategies` (slot scope: commits 475f21f, 0a69c0f, f61b821, 76a8e56, 811ee58, cee3dd5, 46f0f5f, 791dc12, c735f5b)

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Slot message classes + Jackson registration
Status: implemented
- `SlotMessage` is the polymorphic base: `@JsonTypeInfo(use=NAME, include=EXISTING_PROPERTY, property="cmd", visible=true)` extends lib `Body`, `protected SlotMessage(int cmd)` — matches `BettingMiniMessage` exactly (AD-3).
- `SlotSubscribeResponse` (cmd 1300) models `ls` (`List<WinlineDef{lid,poss[]}>`) and `Js` (`List<JackpotTier{b,gid,J,aid}>`), with derived `numLines()` = `ls.size()` and `allowedBetValues()` = `Js[].b` sorted ascending (AD-8/AD-11). `eSC/taxes/as/ae/fss/lss` tolerated as unknowns.
- `SlotSpinResultMessage` (cmd 1302) implements `HasBotWinnings` (`winningsFor = sum(wls[].crd)`) and `HasBetTotals` (`betAmountFor = b`, `betCountFor = 1`) (AD-7). Nested `WinLine` with `crd/lid/sbIds/...`.
- Placeholder package `...message.request.slot` is deleted (absent on HEAD) (AD-3).
- Fixtures `spinResult.json` (`b=500`, wls crd 1000+5000=6000) and `subscribeResponse.json` (25 `ls`, 5-entry `Js` {500,1000,2000,5000,10000}) present and array-framed `[5,{...}]`.
- `SlotMessageDeserializationTest` green: `b==500`, `winningsFor==6000`, `numLines==25`, `allowedBetValues==[500,1000,2000,5000,10000]`.

### Phase 2 — Slot GameMessageTypes provider + resolver split
Status: implemented
- `SlotMessageTypes` interface: fixed `SUBSCRIBE_CMD=1300`, `SPIN_CMD=1302`; accessors `subscribeResponseType()`/`spinResultType()`; default `getTypeRegistrations()` registers against literal `"1300"`/`"1302"` with no offset arithmetic (AD-1/AD-4). Does NOT extend `GameMessageTypes`.
- `SlotMessageTypesImpl` is product-neutral (AD-3/AD-4).
- `GameMessageTypesResolver`: current `resolve(ProductCode)` renamed to `resolveBettingMini(ProductCode)`; new `resolveSlot()` returns `new SlotMessageTypesImpl()` (AD-4). Single call site (BotFactory) updated.
- `SlotMessageTypesTest` / `GameMessageTypesResolverTest` green.

### Phase 3 — Game slot config (gid only)
Status: implemented
- No new Game fields, no DTO/mapper code change (AD-2/AD-8). Only a doc comment added to `Game.gameId` noting it carries the slot `gid` for SLOT games.
- `GameMapperTest.SlotGameTypeTests` confirms `gameType=SLOT` + `gameId=204` round-trips through the existing mapper. Faithful — this phase was a near-no-op by design.

### Phase 4 — SlotMachineBot scenario + accounting
Status: implemented
- Fields match the plan: injected `SlotMessageTypes`, `SlotRequest`, `int gid`, `volatile int numLines`, `volatile List<Long> allowedBetValues`, `AtomicBoolean spinInFlight`, `AtomicReference<Optional<Long>> pendingBet`, `Random rng` (+`setRandom` test seam), injected `SlotStrategyFactory`, `SlotStrategy strategy`.
- `initializeSubclass()`: reads `game.getGameId()` → `gid`, fails loud on null (AD-2); builds `SlotRequest`; seeds RNG `userName.hashCode() ^ nanoTime()`; one INFO init line.
- `botBehaviorScenario()`: `pipeline → waitFor(1000) → send(subscribe) → waitForMessage(cmd(1300).and(typeOf(RECEIVED))) → onMessage(SlotSubscribeResponse, onSubscribe) → sendAsync(spin, INFINITE, 3000ms, condition=spinCondition) → onMessage(SlotSpinResultMessage, onSpinResult)` (AD-11/AD-12). `typeOf(RECEIVED)` prevents matching the bot's own echo on shared cmd 1302.
- `onSubscribe`: captures `numLines`/`allowedBetValues`, WARN+skip on empty (AD-12), then `markConnectionAuthenticated()` + `onNewSession()`.
- `spinCondition()`: AD-12 gate (`numLines==0 || allowedBetValues==null`), AD-6 gate (`spinInFlight`), AD-13 balance gate (`expectedCurrentBalance < chosenBet * numLines`), parks bet. `spin()`: pops bet (re-derive fallback), sets `spinInFlight`, `creditBalance(amount)` debits staked `b`, builds `ls=[0..numLines-1]`.
- `onSpinResult`: defensive gid guard, marker dispatch — winnings gross credited back, `incBetsPlaced(1,b)`, clears `spinInFlight`.
- `beforeReconnect()` resets `spinInFlight`/`pendingBet`. `onStart()` registers OutputPrinter for [1300,1302] then the scenario.
- `SlotRequest`/`SlotSpin`/`SlotSubscribe`: no cmd-prefix arithmetic, fixed cmds, `SlotSpin.Data` has `aid=1, b, gid, ls`; live in `...message.request` (AD-3).
- `SlotMachineBotSubscribeTest` + `SlotMachineBotSpinAccountingTest` green (winnings 6000, debit/credit, in-flight reset, empty-wls path).

### Phase 5 — BotFactory wiring
Status: implemented
- `case SLOT` builds `SlotMachineBot`, sets `messageTypes = resolveSlot()`, sets `slotStrategyFactory`. Betting-mini resolution moved inside its own branch so a SLOT game on a product lacking a betting-mini provider does not throw (matches plan rationale).
- `SlotStrategyFactory` injected into the `BotFactory` constructor. `TAI_XIU/CARD_GAME/UP_DOWN` still throw.
- `BotFactorySlotWiringTest` green: stops deterministically at the auth boundary and asserts no "not yet implemented". See Deviations — this boundary is the faithful unit-test stopping point.

### Phase 6 — Slot strategy family
Status: implemented
- New package `...strategy.slot`: `SlotStrategyId{FIXED,RANDOM}` (with displayName/description), `SlotStrategy.chooseBet(SlotBetContext)`, `SlotBetContext(allowedBetValues, numLines, currentBalance, rng)`, `@SlotStrategyImpl`, `SlotStrategyFactory` (prototype-bean discovery mirroring `BettingStrategyFactory`, logs "SlotStrategyFactory initialized" with [FIXED, RANDOM]), `FixedBetStrategy` (smallest = `get(0)`), `RandomBetStrategy` (uniform over allowed set) — all `@Scope("prototype")` (AD-9).
- `BotConfiguration.slotStrategyId` added (nullable). `SlotMachineBot.initializeSubclass` defaults null → `FIXED`, and falls back to inline `new FixedBetStrategy()` when the factory is null (test seam). See Deviations.
- Slot strategies kept entirely out of the betting `StrategyId` enum and out of `StrategyController` (lists only betting `StrategyId.values()`) (AD-9/AD-10).
- `SlotStrategyFactoryTest`/`FixedBetStrategyTest`/`RandomBetStrategyTest` green.

### Phase 7 — Deterministic spin-stream test
Status: implemented
- `SlotMachineBotSpinStreamTest` drives condition→supplier→result by hand with a seeded Random: asserts AD-12 pre-subscribe gate, parked bet, `SlotSpin` with expected `b` and `ls==[0..24]`, AD-6 one-in-flight gate, balance debit-then-credit, `incBetsPlaced(1,b)` once, and a multi-spin running-balance variant. Green.

## Drift

None requiring action. All deviations below are faithful to the plan as written.

### Deviations reviewed and accepted

1. **`b` debit vs `b * numLines` gate** (Dev-flagged). The balance gate uses `chosenBet * numLines` (SlotMachineBot.java:264) while the per-spin debit is just `creditBalance(amount)` = `b` (line 299). This is **exactly** what AD-13 + AD-14 specify: "the per-spin debit `creditBalance(b)` debits the staked amount `b`" while "`canSpin()` requires `expectedCurrentBalance >= chosenBet * numLines`". Winnings are credited gross from `sum(wls[].crd)`, matching AD-7. Faithful. (Note: this means local balance accounting trusts the server's reported `b` rather than the full `b*numLines` stake; the plan explicitly chose this in AD-13/AD-14 as the v1 accounting model, deferring full free-spin/stake reconciliation. Not a compliance gap — it is the planned behavior.)

2. **Factory-null inline `FixedBetStrategy` fallback** (Dev-flagged). `initializeSubclass` uses `slotStrategyFactory != null ? factory.create(id) : new FixedBetStrategy()`. AD-9/Phase 6 explicitly prescribe this: "if factory null (test seam), default to an inline `FixedBetStrategy` instance (mirror betting bot's RandomBehaviorStrategy fallback)." Faithful.

3. **BotFactory test stops at the auth boundary** (Dev-flagged). `BotFactorySlotWiringTest` lets `createBot` proceed until `authenticate()` throws a sentinel, asserting the SLOT branch was selected (no "not yet implemented"). `createBot` ends in `Bot.initialize()` which opens a real Netty WS connection — undriveable in a unit test. Stopping at auth is the correct seam and still proves the one thing Phase 5 owns (branch selection + setter wiring). Phase 5's other verification (Spring context resolvability of `SlotStrategyFactory`/`SlotMachineBot`) is covered by `SlotStrategyFactoryTest` and the full-suite green. Faithful.

## Out-of-scope changes

None within slot scope. The branch also carries the MARTINGALE_STRATEGIES feature (`...strategy.martingale`, `StrategyId` additions, related tests) — that is a separate feature with its own plan/review and is outside this compliance review.

## Amendments to the plan

None.

---

Verification section achievability: the on-staging steps (1–6) all reference artifacts that now exist — `SlotStrategyFactory initialized` log line ([FIXED, RANDOM]), SLOT game REST shape (`gameType:SLOT, gameId:204`, no winline/bet-value config fields), `CONNECTION_AUTHENTICATED` after cmd 1300, `bot_bets_placed_total`/`bot_bet_amount_total`/`bot_winnings_total` fed via the inherited `HasBetTotals`/`HasBotWinnings` paths. All 49 slot-related unit tests pass locally (`mvn -o test -Dtest='Slot*,SlotMachineBot*,GameMessageTypesResolverTest,GameMapperTest,BotFactorySlotWiringTest,BotFactoryFailLoudTest'` → BUILD SUCCESS).
