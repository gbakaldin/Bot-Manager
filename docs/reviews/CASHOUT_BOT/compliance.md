# Compliance — CASHOUT_BOT

Branch: feature/cashout-bot
Plan reviewed: `docs/plans/CASHOUT_BOT.md` (at commit 5e28aa1, plus the Amendment of 2026-10-05 committed with this file)
Diff reviewed: `git diff feature/bot-provisioning..feature/cashout-bot` (HEAD ed85d86, 9 commits after the plan)
Scope: Phases 1-3. Phase 4 (CashoutSessionStrategy, `CashoutBetsUnanswered` alert, CLAUDE.md subsection) is intentionally not done yet and was not judged.

## Verdict

PLAN_AMENDED

The diff implements Phases 1-3 as planned. Three narrow technical gaps in the plan were filled correctly by Dev and are now recorded in the plan as `## Amendment — 2026-10-05` (A1-A3). Every other declared deviation either implements an AD by a mechanism the plan left open or is harmless. Nothing is sent back.

## Phase-by-phase

### Phase 1 — Protocol layer, game type, registry
Status: implemented
- `GameType.CASHOUT("Cash-out")` (AD-1).
- bot-api contract: `CashoutMessage` (NAME / EXISTING_PROPERTY / `cmd`), `CashoutSubscribeResponse.allowedBets()`, `CashoutBetFrame` (`sid`, `OptionalLong stake`, `multiplier`, `cashoutValue`, `isFinal`, `isBurst`, `winningsFor`, `@JsonAnySetter` `unmapped`), `CashoutRequest`, and `CashoutMessageTypes` with codes 0/1/2 and a default `getTypeRegistrations(offset)` (AD-2, AD-4). `isBurst = isFinal && (blS == -1 || crd <= 0)` is expressed as `isFinal && (burstSignalled() || cashoutValue() <= 0)`, with the 119 `blS` marker in `Win79CashoutFrame` (AD-5). Winnings are gross `round(crd)`.
- 119 provider in `g4/win79/cashout/`: `@Component @MessageTypesImpl(gameType = CASHOUT, products = "119")`. It has two concrete inbound classes over one shared base (AD-5), `Win79CashoutRequest` with `Bet` / `CashOut` bodies, and subscribe through `SubscribeToLobbyMessage`. The bet body has no `sid` and sends `sL`/`aS`/`aSt` as constants (AD-10, OI-4).
- `MessageTypesRegistry`: fourth list, `Tables.cashout`, `cashout(String)` via `lookup(..., "CashoutMessageTypes")`, and `registeredCashoutProducts()`. The startup line is still a single line, with `, CASHOUT n products [...]` appended after the unchanged existing segments (AD-3). All 9 `new MessageTypesRegistry(` sites are updated. `MessageTypesCoverageTest` has a CASHOUT inventory covering every product except 119.
- `Environment.resolveZoneName` treats CASHOUT as a mini game (AD-14). `CashoutConfigValidator` lands in the same commit as the enum constant (AD-15).
- Item 7 (BotFactory throw arm) shipped in 5ec7141 and was replaced in Phase 3, as planned.
- Tests: captured-frame fixtures under `bot-messages/src/test/resources/messages/win79/cashout/`, the synthetic X501/X502 cases, offsets 1500 and 2500, bet and cash-out serialization, registry resolve/throw, validator and factory boot, and zone resolution. All are present.

### Phase 2 — `CashoutBetStateMachine`
Status: implemented. The plan is amended for visibility (A1) and API shape (A2).
- `CashoutBehavior` record with `LEGACY` = 1.1 / 5.0 / 500 / 4500 (AD-7).
- One `AtomicReference<BetState>` with immutable records. The ladder count and the 4-entry `recentlyEnded` ring are inside the same reference (`Memo`), and every transition is a CAS (AD-8). The target is drawn once in `tryPlace`. Binding requires a matching or absent stake and a sid not in the ring. A final frame ends the bet from PLACED, LIVE or CASHING. CASHING never emits a second cash-out.
- Ladder (AD-9): reconnect at R·2^k for k = 0..5, then every R·32. Probes stake `min(eligible)`. The wait after a timeout is `max(backoff, U(delay))`. `reset()` keeps the count, and a bound frame clears it. A timed-out LIVE/CASHING sid is also added to the ring, which is stricter than the plan and correct.
- Tests cover every Phase 2 bullet, including the 4-thread `SEND_CASHOUT` hammer, the ladder at 3..192 with no other count up to 200, the target mean 3.05 ± 0.05, and `reset()` from every state.

### Phase 3 — `CashoutBot` + factory wiring + metrics
Status: implemented
- `initializeSubclass`: fails loudly on a null offset (AD-2). Builds the request, the per-bot RNG, the machine and the virtual watchdog scheduler, and records the aggregator line `game=…, type=CASHOUT, offset=…` (AD-13).
- Scenario: `waitFor(1000)` → subscribe → `waitForMessage(cmd(offset) & RECEIVED)` → `onMessage(subscribe)` → `sendAsync(INFINITE, 250 ms)` → one handler for the progress class and the result class. The callbacks are MDC-wrapped and the client is captured once for the cash-out channel (AD-10).
- `onSubscribe`: calls `markConnectionAuthenticated()` first, then `initCashoutSeries()`, then computes the eligible stakes (∩ `[minBet, maxBet]`, defaulting to `[1000, 100000]` when `maxBet <= 0`, with one WARN on an empty set), then `machine.reset()`, then `onNewSession()` (AD-6, AD-12).
- `onFrame` → `machine.onFrame`. The cash-out is sent directly on the captured channel, inside try/catch, with WARN once and DEBUG afterwards. On `Ended`, the bot cancels the watchdog, increments `incBetsPlaced(1, plan.amount)`, credits gross winnings, increments `roundsObserved`, counts the outcome, writes the one-shot first-terminal-frame DEBUG, then calls `onNewSession()` (AD-11, AD-13).
- Watchdog expiry: counts `timeout`, logs DEBUG, and on a rung runs `incBotWatchdogExpired` + `scopedDebugEscalator.onWatchdogExpiry` + `triggerFullReconnect("watchdog: N cash-out bets unanswered")`. `beforeReconnect` cancels the watchdog, resets the machine and clears the parked plan. `cleanup` shuts the scheduler down. `onStart` installs the `OutputPrinter` for the three cmds and then the scenario.
- `BotFactory` `case CASHOUT` resolves through `messageTypesRegistry.cashout(productKey(env))`. The three `bot.cashout.*` properties (20 / 30 / 3) are in `application.properties` with comments, `@Value` in `BotGroupBehaviorService`, and `BotConfiguration` fields set next to `watchdogTimeoutSeconds`. `BotMetrics` has `BOT_CASHOUT_BETS_TOTAL`, `initCashoutSeries()` and `incCashoutOutcome()`. `incBotMessage` uses `cashoutSubscribe/Progress/Result`.
- Tests: `CashoutBotDispatchTest` covers the win stream (one cash-out with the bound sid, winnings, all four counters), a burst on X501, a burst on X502, the ladder reconnecting only on the 3rd timeout, `beforeReconnect` mid-bet, the stake window and the broke-bot park. `BotFactoryCashoutWiringTest` covers the factory wiring, and `PerBotInfoLogGuardTest` includes `CashoutBot` and the machine.

## Drift

Each declared deviation, judged:

1. **The state machine is public, not package-private.** Plan error, and the plan is amended (A1). The plan put the class in `core.cashout` and its caller in `core`. Package-private access does not reach a parent package, so the plan's two requirements contradicted each other.
2. **`FrameAction.Progress` and `TimedOut(…, reconnect)`.** Plan gap, recorded (A2). The sketch's `NONE | SEND_CASHOUT | ENDED` could not tell a bound non-terminal frame from an ignored one, and Phase 3 needs that distinction. The `reconnect` flag means the same as `TIMED_OUT_AND_RECONNECT`.
3. **`placeIgnoringDelay` plus the re-subscribe fallback.** Plan gap, recorded (A3). The slot precedent's re-derive cannot fail, but this one can when the machine refuses. ws-parser forbids an empty supplier, and a bet the machine did not record as PLACED could never be bound. In that path no bet is in flight, so the subscribe reply's `reset()` is harmless.
4. **The watchdog is armed once per bet and re-armed for the remaining time.** Accepted, not amended. AD-9's decision is the timeout definition: time since the last frame of this bet, armed only while a bet is in flight. Dev's mechanism meets that exactly. `onTimeout()` measures from `lastFrameAt` and re-arms for the remainder, and `earlyWatchdogIsHarmless` pins it. This is a mechanism the plan did not need to fix, not a change to a decision.
5. **Extra gate that blocks bets during the balance/deposit check.** Conforms. It is how the AD-9 invariant (deposit only between bets) holds when the `sendAsync` thread and the frame-handler thread are different. Without it the invariant would be false.
6. **Session aggregator hook left for Phase 4.** Conforms. Phase 3 asked only for a null-safe hook point, and there is no `CashoutSessionStrategy` to feed until Phase 4. **Carry-forward:** Phase 4 must wire `recordSpin` on send and `recordSpinResult` at the marked point in `onBetEnded`, or V-9's `CashoutWindow` check fails.
7. **`bot.cashout.*` values <= 0 fall back to the defaults.** Harmless. The plan does not cover this, and the property defaults equal the plan's values. One side effect: the machine's "R <= 0 disables reconnects" cannot be reached through configuration. No requirement asks for that.
8. **Test deletion in an earlier commit.** `BotFactoryCashoutNotYetImplementedTest` pinned the Phase 1 throw arm and is correctly superseded by `BotFactoryCashoutWiringTest`. It was deleted in c92b9b4, one commit before the factory change in ed85d86, so that one intermediate commit has the throw arm without a test. This is commit hygiene, not compliance, and the final tree is correct.

Also checked and found consistent with the plan:
- `lastRoundWinnings` is set on every terminal frame, including to 0 on a burst. AD-11 lists it under "if > 0", but this matches the `SlotMachineBot:264` precedent the plan says to copy, and `burstOnResultCmd` asserts it.
- The one-shot line reads `first {cash-out|burst} terminal frame: cmd=…, sid=…, crd=…, odds=…, iF=…, b=…, unmapped=…, frame=…`. It has no separate `blS=` field, but `blS` is printed through `Win79CashoutFrame`'s `@ToString` in `frame=`, so V-8 can still read it.

## Out-of-scope changes

None. The only edits outside CASHOUT code are the mechanical test updates (registry constructor sites, validator lists in WebMvc tests, the coverage inventory and the log guard), all of which the plan called for. Phase 4 items are absent, as expected.

## Verification achievability

- V-1..V-4 can be run from Phase 1. The registry line ends with `CASHOUT 1 products [119]` and `GET /types` lists CASHOUT.
- V-5..V-8 and V-10 can be run now. All the metrics, tags and greps they use exist.
- V-9's console.log check can be run now. Its `CashoutWindow` half needs Phase 4, as the plan's own phase split says.

## Amendments to the plan

Appended `## Amendment — 2026-10-05` to `docs/plans/CASHOUT_BOT.md`:
- **A1:** `CashoutBetStateMachine` is public. The original "package-private" contradicted the plan's own class placement.
- **A2:** the shipped `FrameAction` / `TimeoutAction` shapes. The sketch could not express the "bound progress" signal Phase 3 depends on.
- **A3:** the supplier fallback when the machine refuses to re-derive (`placeIgnoringDelay`, otherwise `request.subscribe()`). The slot precedent named in AD-10 has no failure case, and ws-parser forbids an empty supplier.

The amendment also lists items 4-6 above as following the plan and explicitly not amended.
