# Cash-out bot (119 Balloon / Soccer)

**Status:** architect plan, 2026-10-05. Not started.

## 1. Goal

Add one new bot type, `GameType.CASHOUT`, that plays the 119 (WIN79) **Balloon**
(`balloonPlugin`, cmds 1500/1501/1502) and **Soccer** (`soccerPlugin`, cmds 2500/2501/2502)
games. The aim is to retire the legacy prod Node bots (`prd-w79-ballon.js`, 35 bots;
`prd-w79-soccer.js`, 34 at peak) by running 119 **staging** groups first. Each bet is one
player's private game: bet, watch the multiplier climb, cash out at a target or burst. There
is no shared round, no StartGame/EndGame and no betting window. Behaviour defaults to the
legacy loop: pick a stake, pick a target in [1.1, 5.0], cash out on the first progress frame
at or above it, wait 0.5-4.5 s, repeat. Two differences from legacy are deliberate. First, a
bet that gets no reply becomes a **detected, bounded, recoverable** state. The legacy fleet is
mostly wedged in exactly this state; see F-1. Second, the bets show up in the existing metrics.

---

## 2. Findings — Current State

### What the legacy fleet actually does (read this first)

- **F-1: most legacy bots are stalled after one bet.** In `prd-w79-soccer.log`
  (2026-10-04 11:14 to 2026-10-05 15:04), **27 of 34 accounts placed exactly one bet
  and never bet again**. Only 7 bots played: 9,241 / 9,176 / 9,146 / 9,065 / 9,023 /
  6,369 / 1,769 bets. The script only re-bets from an outcome handler (`start_bet()` in the
  1501 `crd==0` branch and the 1502 branch), so an unanswered bet wedges the bot for good.
  `prd-w79-ballon.log` is a ~24 s snapshot after a restart: all 35 bots subscribed and bet,
  and none got anything back. 30 of the 35 accounts had `main_balance < 1000`, below the
  minimum bet. The 4 funded accounts (3.5M-38M) also got nothing back in their 7-11 s.
  **The bot counts in the brief are configured counts, not active ones.** For Soccer, real
  parity is about 7 bots that actually play. For Balloon it is zero today.
- **F-2: the two scripts are not identical.** They also differ in **stake set**: Balloon
  `bettings = [1e3, 1e4, 1e5]` (`prd-w79-ballon.js:56`), Soccer
  `[1000, 10000, 100000, 500000, 1000000]` (`prd-w79-soccer.js:51`). The Soccer log shows
  all five, about 10.7k bets each. Targets (`min_odd 1.1`, `max_odd 5.0`) and the
  inter-bet delay (`random(500, 4500)` ms, `prd-w79-ballon.js:159`) are the same in both.
  The `configs` tiers (1M-5M) are dead code in both.
- **F-3: outcome mix.** Soccer log: 20,198 cash-outs / 33,586 bursts, so **37.6% / 62.4%**,
  which matches the brief's ≈37/63.
- **F-4: bursts arrive on X501, wins on X502.** Every legacy burst was detected in
  the **1501** branch (`crd == 0`, `"Stop inflating by blowing up"`). Every win was logged in the
  **1502** branch (`"Stop inflating success with credit <crd>"`). The user's capture shows a
  burst on **1502** with `iF:true, blS:-1, crd:0.0`, probably a late cash-out that lost the
  race. So **a burst can arrive on either cmd**. Nothing may key on the cmd to decide the outcome.
- **F-5: the winning `crd` is the gross payout.** Logged win credits look like
  `stake × multiplier`, for example `1500896.83` against a 1M stake. This is consistent with `crd`
  in the progress frame (`crd = b × odds`). The rest of the winning terminal frame is still
  **uncaptured**: `blS`, `iF`, whether `b` is present, and extra fields.
- **F-6: a bet lasts a while.** The busiest Soccer bot shows 3-23 s between consecutive bets
  (`test_tx_0012`, 11:14:14 → 11:16:41), including the 0.5-4.5 s pause. Each bet therefore
  streams progress frames for up to ~20 s. The frame **cadence** is not known.
- **F-7: legacy reconnect churn.** The Soccer log has 13,770 `Connection died`/`closed` lines
  in ~28 h. Legacy reconnects on a flat 5 s timer with no bound.

### Code this plan builds on

- **Game types:** `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/game/model/GameType.java:3-9`.
  The enum is `BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN`. `GET /api/v1/game/types` lists
  `GameType.values()`
  (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/game/controller/GameController.java:54-56`),
  so a new constant appears there automatically.
- **Game record:** `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/game/model/Game.java:70-82`
  already has `pluginName` and `offset`. Nothing new is needed on `Game`. `gameId` is nullable,
  and Balloon/Soccer have no gid. `GameMapper.toDTO` already tolerates a game with no option
  affinities (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/game/mapper/GameMapper.java:30-37`).
- **Closest bot precedent:** `SlotMachineBot`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java`).
  It plays per player with no round clock. Its shape: subscribe → server-sourced bet values
  (`onSubscribe` :208-237), a park-and-pop `sendAsync` loop (`spinCondition` :334-383,
  `spin` :392-437), one bet in flight (`spinInFlight` :83), an underfunded-transition log
  (:349-372), `HasBotWinnings`/`HasBetTotals` accounting on the result (:245-298),
  `beforeReconnect` reset (:479-486) and the `OutputPrinter` + scenario install in `onStart`
  (:488-508).
- **Watchdog precedent:** `BettingMiniGameBot.scheduleWatchdog`/`onWatchdogExpired`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:433-465`).
  It uses a per-bot virtual single-thread scheduler (:220-222), `metrics.incBotWatchdogExpired()`,
  `scopedDebugEscalator.onWatchdogExpiry`, and `triggerFullReconnect`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:978-1000`).
  Cleanup is at :652-685.
- **Sending from outside `sendAsync`:** `BettingMiniGameBot.afterBetSent` (:933-950) sends with
  `channel.send(msg.serialize(mapper))` on a client reference captured once in
  `botBehaviorScenario()`. That is the precedent for an event-driven cash-out.
- **Inbound concurrency:** ws-parser 3.0.5 runs `BackpressureConfig.DEFAULT_PROCESSING_THREADS = 4`
  message-processor workers **per client** (`VingameWebSocketClient` ctor, `processingExecutor`).
  `onMessage` handlers for one bot can therefore run **concurrently and out of order**.
  `SlotMachineBot` never had to care because it gets one reply per spin. A progress stream
  followed by a terminal frame does have to care.
- **Message-types registry:** `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/MessageTypesRegistry.java`.
  It keeps `Tables(bettingMini, taiXiu, slot)` (:95-98), and its constructor takes three lists
  (:102-109). The INFO startup line is at :119-123 and the releaser greps it. `indexByProduct`
  (:218-262) rejects an empty `products` for anything other than SLOT. There are 9 files with
  `new MessageTypesRegistry(`. `MessageTypesCoverageTest` keeps per-contract "not yet
  implemented" inventories (:104-153).
- **Provider annotation:** `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/MessageTypesImpl.java:46-63`.
  The existing 119 providers are `Win79GameMessageTypes` (betting-mini) and
  `Win79TaiXiuMessageTypes` (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/taixiu/Win79TaiXiuMessageTypes.java:82-84`).
- **Slot message base (pattern for the cash-out base):** `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/slot/SlotMessage.java`
  (`@JsonTypeInfo(NAME, EXISTING_PROPERTY, "cmd")`). Request body pattern:
  `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/SlotSpin.java`.
- **Bot factory:** `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:160-199`.
  This is an exhaustive `switch (game.getGameType())`. `CARD_GAME, UP_DOWN` throw
  `"Game type not yet implemented"`.
- **Zone resolution:** `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:96-106`.
  Only `BETTING_MINI | SLOT | TAI_XIU` get the mini zone. **Any other type falls to the
  card zone (`Simms`).**
- **Validators:** `GameConfigValidatorFactory` **fails boot** if any `GameType` has no validator
  (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/validation/GameConfigValidatorFactory.java:66-73`).
  The no-op precedent is `SlotConfigValidator`.
- **Group → bot config:** `BotGroupBehaviorService.createSingleBot`
  (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1555-1670`).
  `minBet`/`maxBet` flow into `BotBehaviorConfig`
  (`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/config/bot/BotBehaviorConfig.java`).
  Coordinator, jackpot scaler, ramp and affinity are all gated on `BETTING_MINI || TAI_XIU`
  (:1017-1018, :1049-1050, :1598). They will not attach to CASHOUT, and that is correct.
  `StrategyController` already returns an empty list for unknown types
  (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/bot/strategy/controller/StrategyController.java:48-54`).
- **Balance and budget:** `Bot.checkBalance` (`Bot.java:669`) goes to HTTP only when local drift
  exceeds `BALANCE_SYNC_PERCENT_OF_DEPOSIT` (1%, :781) of `bot.deposit.amount` (1e9, so 10M).
  Drift reads after the first are non-blocking (DEFAULT tier). `depositIsWarranted` (:635) is
  bounded by `sessionBudgetWait()` (:598, watchdog/4). `creditBalance` (:1450) debits locally.
- **Metrics:** `BotMetrics.incBetsPlaced` (:268), `incBotWinnings` (:294),
  `incBotWatchdogExpired` (:224) and `incBotMessage` (:168). Tags come from MDC and are
  group-level, never per bot (`mdcTags` :145-154).
- **Session aggregation:** `SessionAggregationService.recordSpin` / `recordSpinResult`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/SessionAggregationService.java:398-425`)
  take a `SessionAggregationStrategy`. The precedent is `SlotSessionStrategy`
  (round-boundary-less window).
- **Aviator draft** (`/Users/gleb/IdeaProjects/Bot/docs/plans/AVIATOR_BOT.md`) is a different
  game: shared rounds, two runners, 1705/1706/1707. Reused from it: the rule that wire
  vocabulary stops at the message layer (`odds` → multiplier), drawing the target **once per
  bet**, the park-and-pop idiom, and wiring winnings on day one. Not reused: its
  `GameType.CRASH` and round gates.
- **119 staging env:** `d005157f-4dfb-479d-a39a-36a3892439b7` (brand `G4`, product `P_119`),
  per `/Users/gleb/IdeaProjects/Bot/docs/reviews/WIN79_119_ENABLEMENT/release.md:386-395`.

---

## 3. Per-aspect readiness

| Aspect | Status | Notes |
|---|---|---|
| Auth / login on 119 | **ready** | `Win79LoginRequest`. 119 groups already play Tai Xiu and betting-mini |
| WS URL | **ready** | `Environment.webSocketMiniUrl`, the same socket as every 119 mini game |
| Zone | **partial** | `resolveZoneName` must learn `CASHOUT`, or bots auth into `Simms` (AD-14) |
| Subscribe X500 + `bets` | **ready (protocol)** | Captured. Allowed bets `[1000 … 10000000]` |
| Bet X501 OUT | **ready (protocol)** | Captured real-client body, no `sid` |
| Progress X501 IN | **ready (protocol)** | Captured |
| Burst terminal | **ready (protocol)** | Captured on X502. Legacy shows X501 `crd==0` too |
| Win terminal | **partial** | `crd` = gross per legacy log (F-5). Full shape uncaptured, so it is verified in V-8 |
| Bet rejection reply | **blocked (unknown)** | We don't know if the server replies to a rejected bet. Covered by the watchdog (AD-9) |
| Frame cadence | **partial** | Unknown. Watchdog default chosen conservatively (AD-9) |
| `GameType` / factory / validator | **not started** | Phase 1 / 3 |
| Registry contract | **not started** | Phase 1 |
| Bet state machine | **not started** | Phase 2 (pure, unit tested) |
| Metrics | **ready (infra)** | Existing counters + one new outcome counter (AD-12) |
| Auto-deposit | **ready** | Reuses `Bot.depositIsWarranted` / `deposit()` |
| Hour-of-day bot counts | **ready** | Existing activation windows (`TIMED_ACTIVATION`). No new mechanism |
| Per-group stake config | **ready** | Existing `minBet`/`maxBet` (AD-6) |
| Per-group target/delay config | **out of scope** | Code constants with legacy values (AD-7, OI-5) |
| UI | **partial** | Game-type selector is server-driven. A per-type form may be needed (OI-6) |

---

## 4. Architecture Decisions

**AD-1: One new game type, `GameType.CASHOUT`, display name `"Cash-out"`.** Balloon and Soccer
are two `Game` rows of this one type: `pluginName` `balloonPlugin` / `soccerPlugin`, `offset`
`1500` / `2500`. It is **not** `CRASH`. The Aviator draft's type is round-based with shared
rounds, and this game has neither. If Aviator ever ships, it stays a separate type and bot.

**AD-2: CMDs are `offset + code`, with codes `SUBSCRIBE = 0`, `BET = 1`, `CASHOUT = 2`.**
X500 / X501 / X502. This is the same `CODE + offset` arithmetic betting-mini uses
(`GameMessageTypes.getTypeRegistrations`), with codes that start at zero. A CASHOUT game with
a null `offset` fails loud in `initializeSubclass` (`IllegalStateException` naming the game),
the same way `SlotMachineBot` fails on a null `gameId`.

**AD-3: Message layer is a product-keyed provider: `CashoutMessageTypes`, resolved by
`messageTypesRegistry.cashout(productKey(env))`, with
`@MessageTypesImpl(gameType = CASHOUT, products = "119")`.** Not product-neutral like SLOT.
119 is the only brand we know runs these plugins, and the wire body (`aid`, `sL`, `aS`, `aSt`)
looks brand-specific. A second brand is a new provider class and nothing else. The registry
gets a fourth table, `cashout(String)`, plus `registeredCashoutProducts()`. Its startup INFO
line gains a `CASHOUT n products [...]` segment, appended **after** the existing segments so
existing greps keep matching.

**AD-4: The wire vocabulary stops at the message layer.** `bot-api` declares the contract the
bot reads:
- `CashoutMessage` is the abstract `@JsonTypeInfo(NAME, EXISTING_PROPERTY, "cmd")` base,
  mirroring `SlotMessage`.
- `CashoutSubscribeResponse` (abstract): `List<Long> allowedBets()`.
- `CashoutBetFrame` (abstract, implements `HasBotWinnings`): `long sid()`,
  `OptionalLong stake()` (the `b` field, absent on the burst frame), `double multiplier()`
  (wire `odds`, which is a payout ratio and never a probability), `double cashoutValue()`
  (wire `crd`), `boolean isFinal()` (wire `iF`), `boolean isBurst()`, and
  `Map<String,Object> unmapped()` (filled by `@JsonAnySetter`, used for V-8).
- `CashoutRequest`: `subscribe()`, `bet(long amount)`, `cashOut(long sid)`.
- `CashoutMessageTypes`: `subscribeResponseType()`, `progressType()`, `resultType()`,
  `newRequest(String zoneName, String pluginName, int offset)`, and
  `default NamedType[] getTypeRegistrations(int offset)`.

119 concrete classes live in
`bot-messages/.../message/g4/win79/cashout/`.

**AD-5: Two concrete inbound classes share one base: `Win79CashoutProgressFrame` (X501) and
`Win79CashoutResultFrame` (X502).** The bot registers **one handler** for both. Two classes,
not one class under two type ids, because a burst can arrive on either cmd (F-4). That way
correctness does not depend on how Jackson resolves one class registered under two names.
`isBurst() = isFinal() && (blS == -1 || crd <= 0)`. That rule covers the captured X502 burst
(`blS:-1`) and the legacy X501 `crd==0` burst. `winningsFor(user)` returns
`isFinal() && !isBurst() ? Math.round(crd) : 0`. This is the **gross** payout, per F-5,
credited the same way `SlotMachineBot` credits gross slot winnings.

**AD-6: Stake = a uniform pick from the server's `bets` ∩ `[minBet, maxBet]` of the group.**
This is the existing config model, and it reproduces both legacy scripts exactly:
- Balloon group `minBet=1000, maxBet=100000` gives `{1000, 10000, 100000}`.
- Soccer group `minBet=1000, maxBet=1000000` gives `{1000, …, 1000000}`.

If `maxBet <= 0` (unset), the default window is `[1000, 100000]`, which is Balloon parity.
`betIncrement`, `min/maxBetsPerRound` and `maxTotalBetPerRound` are ignored. An empty
intersection at runtime, or a subscribe with no `bets`, means the bot **does not bet**. It
emits one WARN per bot per subscribe, the same as the degenerate-1300 WARN in
`SlotMachineBot:227`. A bot whose balance cannot cover the **cheapest** eligible stake parks.
It logs at DEBUG on entering and leaving that state, never per tick (copy
`SlotMachineBot:349-372`). The stake drawn on each tick must also be covered by the balance.

**AD-7: Target and delay are code constants in a `CashoutBehavior` record, with legacy
values.** `minTarget=1.1`, `maxTarget=5.0` (uniform), `minDelayMs=500`, `maxDelayMs=4500`
(uniform). `CashoutBehavior.LEGACY` is the only instance in v1. No `BotGroup`/DTO/UI field is
added (OI-5). The record exists so that exposing these later is a wiring change, not a
redesign. No strategy family, no strategy-mix participation: CASHOUT behaves like SLOT in
`StrategyController` and strategy assignment (the strategy id is set but ignored).

**AD-8: Bet state machine, extracted as a pure class and driven by CAS.**
`CashoutBetStateMachine` (package-private, in `bot-engine/.../core/cashout/`) has no I/O and
takes an injected `LongSupplier clock` and `Random`. All state lives in **one**
`AtomicReference<BetState>` and every transition is a `compareAndSet`, because handlers run
concurrently (Findings: inbound concurrency).

```
IDLE --tryPlace(now >= nextBetAt, plan)--> PLACED(plan, sentAt)
PLACED --first frame (stake matches, sid not recently ended)--> LIVE(plan, sid, lastFrameAt)
LIVE --frame, multiplier >= plan.target, !final--> CASHING(plan, sid, lastFrameAt)  [emit SEND_CASHOUT(sid) exactly once]
LIVE|CASHING --frame !final--> same state, lastFrameAt = now
PLACED|LIVE|CASHING --final frame--> IDLE [emit ENDED(outcome = isBurst ? BURST : CASHOUT, stake, winnings); nextBetAt = now + U(minDelay, maxDelay)]
PLACED|LIVE|CASHING --watchdog: now - lastFrameAt(or sentAt) >= frameTimeout--> IDLE [emit TIMED_OUT; nextBetAt per AD-9]
any --reset() (beforeReconnect)--> IDLE (nextBetAt = now + U(minDelay, maxDelay))
```

- **The target is drawn once, at `tryPlace`, and frozen in the plan.** It is never re-drawn
  per frame or per tick (AVIATOR_BOT "the trap").
- **Binding:** the bet frame carries no sid, so the first non-final or final frame after
  PLACED binds the server `sid`. It binds only if `stake()` is absent or equals
  `plan.amount`, and the sid is not in a 4-entry `recentlyEnded` ring. Without that ring, a
  late frame from the previous bet could bind to the next one.
- A frame whose sid differs from the bound sid, or any frame in IDLE, is **ignored** and
  logged at TRACE.
- A final frame wins every race. A progress frame processed after the terminal finds IDLE and
  does nothing. That is why cash-out-after-end cannot happen.
- A final frame that arrives in PLACED (immediate burst, no progress) is a valid end.
- `CASHING` keeps accepting progress frames (it refreshes `lastFrameAt`) and never emits a
  second cash-out.

**AD-9: Watchdog. A silent bet is detected per bet, and recovery is a bounded ladder, not a
reconnect per timeout.**
- **Detection:** a per-bot virtual single-thread scheduler (the `BettingMiniGameBot`
  pattern). It is armed when a bet is sent, re-armed on every frame of the live bet, and
  cancelled on the terminal frame. Timeout = **time since the last frame of this bet** (or
  since send), not bet duration, because bets legitimately last ~20 s (F-6). Property
  `bot.cashout.frame-timeout-seconds`, default **20**.
- **The watchdog is armed only while a bet is in flight.** An idle bot (paused below its
  stake, or misconfigured) legitimately receives nothing. A connection-level "no message"
  watchdog would reconnect it forever. That is the staging reconnect-hot-loop shape: 156k
  reconnects in 3 days, and it must not be rebuilt here.
- **Ladder:** `consecutiveTimeouts` is reset **only by a received frame for a bet**, never by
  a reconnect.
  - Each timeout: outcome `timeout`, bet state → IDLE, and the next bet waits at least
    `bot.cashout.timeout-backoff-seconds` (default **30**). While
    `consecutiveTimeouts >= 1`, the next bet is a **probe**: it stakes the cheapest eligible
    amount, so a server that silently debits costs as little as possible.
  - A reconnect (`incBotWatchdogExpired()`, `scopedDebugEscalator.onWatchdogExpiry(group)`,
    `triggerFullReconnect("watchdog: N cash-out bets unanswered")`) fires when
    `consecutiveTimeouts` reaches `R·2^k` for `k = 0..5`. `R` is
    `bot.cashout.reconnect-after-timeouts`, default **3**, so reconnects happen at 3, 6, 12, 24,
    48, 96 and then every 96. At ~50 s per timeout, that is at most one re-login per bot per
    ~80 min in steady state. 35 silent bots then cost well under 1% of the 1k/5 min
    Cloudflare budget. Without the doubling it would be ~40 logins/min.
- **Why the bot never goes DEAD for this:** each reconnect succeeds (the socket is fine),
  so `MAX_RECONNECT_CYCLES` never trips. The signal is the outcome metric and its alert
  (AD-12, Phase 4), not bot status. This is the same "reads healthy" gap as FOLLOWUPS P10,
  and it is accepted for v1.
- **Invariant:** `depositIsWarranted` / `checkBalance` run on the message thread only when
  **no bet is in flight**, between terminal and next bet. So `sessionBudgetWait()` (bounded by
  the 180 s connection watchdog, which is unchanged) cannot eat into the 20 s frame timeout.

**AD-10: The bet loop is a polled `sendAsync`. The cash-out is event-driven.**
- **Bet:** one `sendAsync(INFINITE)` stage, `interval` 250 ms, with condition = subscribed
  && stake set non-empty && `machine.tryPlace(now)` succeeds && balance covers the drawn
  stake. Park-and-pop: the condition parks the `Plan`, the supplier pops it, with the
  race-fallback re-derive as in `SlotMachineBot.spin()`. The supplier calls
  `creditBalance(amount)` and arms the watchdog. 250 ms of jitter on a 0.5-4.5 s delay is
  immaterial.
- **Cash-out:** sent **directly from the frame handler** when the machine emits
  `SEND_CASHOUT(sid)`, through `channel.send(request.cashOut(sid).serialize(mapper))` on the
  client captured once in `botBehaviorScenario()` (the `afterBetSent` precedent). It is
  wrapped in try/catch: WARN once per bot, DEBUG afterwards. This adds no second scheduler and
  no poll latency. Late cash-outs (the burst-on-X502 case) cost money, so latency matters
  here.
- The bet body follows the **real client**:
  `{"cmd":X501,"b":<amount>,"aid":1,"sL":2,"aS":false,"aSt":false}`, with **no `sid`**.
  Cash-out: `{"cmd":X502,"sid":<server sid>,"aid":1,"aSt":false}`. `sL`/`aS`/`aSt` are sent
  verbatim as constants. Nothing reads them or depends on them (OI-4).

**AD-11: Accounting.**
- **On send:** `creditBalance(amount)` does a local debit and increments the local
  `totalBetsPlaced`/`totalBetAmount`, as in slot.
- **On terminal frame (server-confirmed):** `metrics.incBetsPlaced(1, plan.amount)`.
  `bot_bets_placed_total` / `bot_bet_amount_total` count **confirmed** bets, in line with
  ENDGAME_METRICS AD-4. Then `winnings = frame.winningsFor(user)`. If `> 0`:
  `expectedCurrentBalance += winnings`, `cumulativeWinnings += winnings`,
  `metrics.incBotWinnings(winnings)`, `lastRoundWinnings = winnings`. Then
  `roundsObserved++`: "rounds" means completed bets, matching slot's definition.
- **Timeouts:** not counted in `bot_bets_placed_total` (unconfirmed), and the local debit
  stands. If it was wrong, the drift re-sync corrects it.
- **Stake source:** `plan.amount` rather than the frame's `b`, because the burst frame has no
  `b`. If the frame carries `b` and it differs from the plan, the frame is not bound (AD-8).
- **No `HasBetTotals`:** the message has no per-round totals to report, so implementing it
  would just echo our own plan. `HasBotWinnings` is implemented (AD-5).

**AD-12: One new counter, `bot_cashout_bets_total{outcome="cashout"|"burst"|"timeout"}`, with
MDC (group-level) tags.**
- `BotMetrics.initCashoutSeries()` pre-registers all three outcomes at 0. It is called from
  `onSubscribe` under the bot's MDC and is idempotent. Lazy registration would make the first
  `timeout` invisible to `increase()`/`rate()`, the trap CLAUDE.md documents for
  `group_recovery_*`.
- `incBotMessage` gains `cashoutSubscribe` / `cashoutProgress` / `cashoutResult`.
- No per-bot label is added.

**AD-13: Logging tiers.**
- **INFO:** nothing per bet or per bot. Group lifecycle comes from the existing
  `GroupLifecycleAggregator` (`recordInitialized("game=…, type=CASHOUT, offset=…")` in
  `initializeSubclass`).
- **DEBUG:**
  - subscribe captured: allowed bets and the eligible stake set;
  - balance pause / resume transitions;
  - each timeout, with `consecutiveTimeouts` and the next delay;
  - the 5 s `CashoutWindow` aggregate (Phase 4);
  - **one-shot per bot per JVM:** the first terminal frame of each outcome
    (`first cash-out terminal frame: cmd=…, sid=…, crd=…, odds=…, blS=…, iF=…, b=…,
    unmapped={…}` and the same for burst). This is how V-8 reads the uncaptured win shape on
    staging without TRACE. Raw frames are TRACE and are not reachable at staging's DEBUG; see
    WIN79_119_ENABLEMENT release §2.
- **TRACE:** progress frames, the per-bet "sending bet / target", "sending cash-out at x", the
  per-bet outcome line, ignored stale frames, and the `OutputPrinter` raw dump.
- **WARN:** empty stake set; cash-out serialize failure (once per bot); the escalation line
  just before `triggerFullReconnect`, which `triggerFullReconnect` already WARNs.
- `PerBotInfoLogGuardTest` must cover `CashoutBot` (no `log.info(`).

**AD-14: `Environment.resolveZoneName` treats `CASHOUT` as a mini game.** These plugins live
in the `MiniGame` zone (captured `[6,"MiniGame","balloonPlugin",…]`).

**AD-15: `CashoutConfigValidator` validates only what CASHOUT reads.** It checks
`minBet >= 0`, and `minBet <= maxBet` when `maxBet > 0`. Every other betting field is ignored,
not rejected, following the `SlotConfigValidator` reasoning. It must land in the **same phase
as the enum constant**, or boot fails (`GameConfigValidatorFactory:66-73`).

**AD-16: No new HTTP per bet.** The only HTTP a CASHOUT bot makes is what every bot already
makes: login, WS upgrade, the first balance read, drift re-syncs (DEFAULT tier, never waits,
only when drift exceeds 10M at the default deposit) and auto-deposit. `onNewSession()`
(balance check + deposit decision) runs at subscribe and after each terminal frame, and is
local-only unless drift or minimum balance trips. Watchdog reconnects are bounded by AD-9.

---

## 5. Plan

### Phase 1: Protocol layer, game type, registry (no bot yet)

When this ships, a CASHOUT `Game` can be created, the 119 provider is registered, and a
CASHOUT group can be created. Starting one fails each bot with "Game type not yet
implemented", which is the same state CARD_GAME/UP_DOWN are in today.

1. `GameType.java`: add `CASHOUT("Cash-out")`.
2. `bot-api/.../message/cashout/`: `CashoutMessage`, `CashoutSubscribeResponse`,
   `CashoutBetFrame` (AD-4/AD-5, including `isBurst`, `winningsFor`, and `@JsonAnySetter`
   into `unmapped`). `bot-api/.../message/CashoutMessageTypes.java` with code constants
   `SUBSCRIBE_CODE=0, BET_CODE=1, CASHOUT_CODE=2` and `getTypeRegistrations(int offset)`
   registering subscribe@`offset`, progress@`offset+1`, result@`offset+2`.
   `bot-api/.../message/request/CashoutRequest.java` (interface).
3. `bot-messages/.../message/g4/win79/cashout/`: `Win79CashoutMessageTypes`
   (`@Component @MessageTypesImpl(gameType = CASHOUT, products = "119")`),
   `Win79CashoutSubscribeResponse` (`bets`, `sid`; `jackpots` ignored),
   `Win79CashoutProgressFrame`, `Win79CashoutResultFrame` (fields `b` as `Long`, `odds`, `crd`,
   `nextOdds`, `nextCrd`, `blS`, `iF`, `sid`, `sL`, `aS`, `aSt`), and `Win79CashoutRequest`
   with `Win79CashoutBet` / `Win79CashoutCashOut` bodies (pattern: `SlotSpin`). Subscribe
   reuses `SubscribeToLobbyMessage(zone, plugin, new Body(offset))`.
4. `MessageTypesRegistry`: 4th constructor list `List<CashoutMessageTypes>`, `Tables.cashout`,
   `cashout(String)` via `lookup(..., "CashoutMessageTypes")`,
   `registeredCashoutProducts()`, startup line extended (AD-3). Update the 9
   `new MessageTypesRegistry(` call sites (`List.of()` where irrelevant).
   `MessageTypesCoverageTest`: add a CASHOUT inventory with every product except `119`.
5. `Environment.resolveZoneName`: add `CASHOUT` (AD-14).
6. `bot-app/.../botgroup/validation/CashoutConfigValidator.java` (AD-15).
7. `BotFactory` switch: add `CASHOUT` to the `CARD_GAME, UP_DOWN` throw arm. Phase 3
   replaces it.
8. Tests (small, isolated):
   - `Win79CashoutMessageTypesTest` deserializes the three captured frames from
     `balloon-frames.txt`, stored as fixtures under
     `bot-messages/src/test/resources/messages/win79/cashout/` (subscribe, progress, burst),
     through a mapper with `getTypeRegistrations(1500)`. Asserts: `allowedBets`
     = the 7 values; progress `isFinal()==false`, `multiplier()≈2.609`, `sid=1801744`,
     `stake()=100000`; burst `isFinal()`, `isBurst()`, `winningsFor()==0`, `stake()` empty.
     A synthetic X501 `{iF:true, crd:0.0}` gives `isBurst()`. A synthetic X502
     `{iF:true, blS:0, crd:250000.4}` gives `winningsFor()==250000`. Offset 2500 maps to 2501/2502.
   - Serialization: the bet body equals
     `[6,"MiniGame","balloonPlugin",{"cmd":1501,"b":100000,"aid":1,"sL":2,"aS":false,"aSt":false}]`
     with no `sid` key. The cash-out body equals `{"cmd":1502,"sid":…,"aid":1,"aSt":false}`.
   - Registry: `cashout("119")` resolves, and `cashout("116")` throws the AD-20-style message.
   - `GameConfigValidatorFactory` boots with CASHOUT. `CashoutConfigValidator` 400s on
     `minBet > maxBet`.
   - `Environment.resolveZoneName` returns `MiniGame` for a CASHOUT game.

### Phase 2: `CashoutBetStateMachine` (pure, ships dark)

Nothing calls it yet. This phase can be reviewed on its own.

1. `bot-engine/.../core/cashout/CashoutBehavior.java`: record plus `LEGACY` (AD-7).
2. `CashoutBetStateMachine` (AD-8, AD-9 ladder arithmetic). API sketch:
   `Optional<Plan> tryPlace(List<Long> eligibleStakes, long balance)` (honours `nextBetAt`
   and probe mode); `FrameAction onFrame(CashoutBetFrame f)` returning
   `NONE | SEND_CASHOUT(sid) | ENDED(outcome, stake, winnings)`;
   `TimeoutAction onTimeout()` returning `NONE | TIMED_OUT | TIMED_OUT_AND_RECONNECT`;
   `void reset()`; `boolean inFlight()`; `long millisUntilTimeout()`. Inputs: clock and
   `Random`.
3. `CashoutBetStateMachineTest` (JUnit, no Spring, fake clock, seeded `Random`):
   - target frozen: 50 progress frames below target send no cash-out. The target is identical
     before and after.
   - exactly one `SEND_CASHOUT`, for the first frame with `multiplier >= target`, even when 4
     threads deliver the same crossing frames concurrently (`CountDownLatch` hammer).
   - final frame → `ENDED(BURST)` from PLACED, LIVE and CASHING; final win → `ENDED(CASHOUT)`
     with winnings.
   - a progress frame after the terminal → `NONE`, and no second cash-out.
   - a stale frame (sid in `recentlyEnded`, or `b != plan.amount`) does not bind.
   - `nextBetAt` lies in `[now+500, now+4500]` after an end. `tryPlace` before it → empty.
   - stake drawn only from eligible stakes and only if `balance >= stake`. Empty stakes →
     empty.
   - target uniform in `[1.1, 5.0]` (10k draws: min ≥ 1.1, max ≤ 5.0, mean ≈ 3.05 ± 0.05).
   - timeout ladder: reconnect fires at consecutive timeouts 3, 6, 12, 24, 48, 96, 192 and at
     no other count up to 200. The counter does **not** reset on `reset()` but **does** reset
     on a bound frame. A probe stakes `min(eligible)`.
   - `reset()` from every state → IDLE, with no emission.

### Phase 3: `CashoutBot` + factory wiring + metrics

When this ships, CASHOUT groups play.

1. `bot-engine/.../core/CashoutBot.java` extends `Bot`. Model it on `SlotMachineBot`.
   - `initializeSubclass`: requires `offset` (AD-2). Builds `request =
     messageTypes.newRequest(zone, game.getPluginName(), offset)`, the per-bot RNG, the
     machine, and the watchdog scheduler. Records the group aggregator line.
   - `botBehaviorScenario`: `waitFor(1000)` → `send(request.subscribe())` →
     `waitForMessage(cmd(offset).and(typeOf(RECEIVED)))` → `onMessage(subscribeType,
     onSubscribe)` → `sendAsync(bet loop, AD-10)` → `onMessage(progressType, onFrame)` →
     `onMessage(resultType, onFrame)`. Every callback is wrapped in `mdcConsumer` /
     `mdcSupplier`. Capture `client` once for the cash-out channel (AD-10).
   - `onSubscribe`: `markConnectionAuthenticated()` **first, unconditionally** (the
     `SlotMachineBot:211-218` reasoning), `metrics.initCashoutSeries()`, compute eligible
     stakes (AD-6), `machine.reset()`, `onNewSession()`.
   - `onFrame`: `machine.onFrame` → on `SEND_CASHOUT` send and re-arm the watchdog; on any
     non-terminal bound frame re-arm the watchdog; on `ENDED` cancel the watchdog, account
     (AD-11), count the outcome (AD-12), feed the session aggregator (Phase 4 hook, null-safe),
     run the one-shot first-frame DEBUG (AD-13), then `onNewSession()`.
   - Watchdog expiry → `machine.onTimeout()` → count `timeout`, DEBUG, and on
     `TIMED_OUT_AND_RECONNECT` the escalation sequence (AD-9).
   - `beforeReconnect`: cancel the watchdog, `machine.reset()`, clear the parked plan.
     `cleanup`: shut down the watchdog scheduler (`BettingMiniGameBot:675-685` pattern).
   - `onStart`: `onNewSession()`, `OutputPrinter.debugOutputPrinter(List.of(offset,
     offset+1, offset+2), …)`, `addScenario(botBehaviorScenario())`.
2. `BotFactory`: `case CASHOUT -> { CashoutBot b = new CashoutBot();
   b.setMessageTypes(messageTypesRegistry.cashout(productKey(env))); yield b; }`.
3. Properties in `application.properties` with comments:
   `bot.cashout.frame-timeout-seconds=20`, `bot.cashout.timeout-backoff-seconds=30`,
   `bot.cashout.reconnect-after-timeouts=3`. Thread them through `BotConfiguration` (three
   fields, set in `BotGroupBehaviorService.createSingleBot` next to `watchdogTimeoutSeconds`).
4. `BotMetrics`: `BOT_CASHOUT_BETS_TOTAL`, `initCashoutSeries()`,
   `incCashoutOutcome(String)` (AD-12).
5. Tests:
   - `CashoutBotDispatchTest`, mirroring `SlotMachineBotSpinStreamTest` fixtures: a
     subscribe → bet → progress×N → win sequence sends exactly one cash-out with the bound
     sid, credits winnings, increments `bot_bets_placed_total` by 1 and `bot_winnings_total`
     by the win, and `bot_cashout_bets_total{outcome=cashout}` by 1.
   - A burst on X501 (`crd 0, iF true`) and a burst on X502 each count `burst`, with no
     winnings.
   - A watchdog expiry with a fake/short timeout counts `timeout` and does not call
     `triggerFullReconnect` until the 3rd.
   - `beforeReconnect` mid-bet → IDLE, no cash-out sent afterwards.
   - `BotFactory` resolves a `CashoutBot` for a CASHOUT game on 119 (the
     `BotFactorySlotWiringTest` pattern).
   - `PerBotInfoLogGuardTest` includes `CashoutBot`.

### Phase 4: Observability polish + docs

This can ship after Phase 3. It is not required for the first staging run, but V-6..V-8 read
better with it.

1. `bot-engine/.../observability/CashoutSessionStrategy.java` (pattern: `SlotSessionStrategy`):
   a DEBUG 5 s line `CashoutWindow <game>/<group> #n | bets since last | total staked | total
   win | amount min/avg/max`. Fed by `recordSpin(CashoutSessionStrategy.INSTANCE, user,
   (int) amount, amount)` on send, and `recordSpinResult(winnings, false)` on end.
2. `prometheus/alerts.yml`: `CashoutBetsUnanswered`, `warning`:
   `sum by (environmentId, botGroupId) (increase(bot_cashout_bets_total{outcome="timeout"}[15m]))
   / clamp_min(sum by (environmentId, botGroupId) (increase(bot_cashout_bets_total[15m])), 1) > 0.5`,
   `for: 15m`. It needs no Alertmanager route (it falls through to `viptalk`, like
   `EnvironmentGroupRecoveryFlapping`). Add it to any alert-rule test inventory that
   enumerates rules.
3. `CLAUDE.md`: a short "Cash-out bot" subsection covering the AD-9 ladder, AD-6 stake rule,
   the metric, and the fact that an all-timeout group reads healthy in `/health`.

---

## 6. Implementation Notes / Concerns

- **Concurrency is the main risk.** Four inbound workers per client means a progress frame
  and the terminal frame for one bet can be handled at the same instant, in either order.
  Every state change goes through the single CAS in the machine. Do not add a second atomic
  (`cashedOut`, `betLive`, …) beside it. Two flags updated separately are exactly the race
  Phase 2's hammer test exists to catch.
- **Do not key outcome on cmd.** Key it on `iF` (AD-5, F-4). The legacy script's X501
  `crd==0` check and the captured X502 `blS:-1` are two faces of one rule.
- **Zone fallback is silent.** If `resolveZoneName` is missed (AD-14), bots authenticate into
  `Simms`. The subscribe is never answered, and with AD-9 the watchdog is not even armed,
  because no bet is placed before subscribe. The group sits in
  `AUTHENTICATING_CONNECTION`. The Phase 1 test pins it.
- **`MessageTypesRegistry` constructor change** touches 9 files. This is mechanical and
  belongs in Phase 1. The startup INFO line must stay a **single** line, with existing
  segments unchanged and in order: releasers diff it.
- **`GameConfigValidatorFactory` boot check:** the enum constant and its validator ship
  together (Phase 1), or the app does not start.
- **Exhaustive `switch` in `BotFactory`:** adding the enum constant is a compile error until
  the arm exists. That is why Phase 1 adds it to the throw arm.
- **Probe bets still cost money** if the server silently debits. That is the reason for the
  minimum stake and the 30 s backoff. Do not "optimise" probes back to the normal stake mix.
- **`crd` is a double** (`260908.608…`). `Math.round` is used for winnings. A ±1 discrepancy
  per bet against the wallet is absorbed by the drift re-sync. Do not add tolerance logic.
- **Funding:** F-1 suggests the legacy accounts were mostly unfunded. Staging groups should
  run `autoDepositEnabled=true`. The stake/balance gate (AD-6) is what stops our bots from
  reproducing the "bet into silence" pattern from an empty wallet.
- **`roundsObserved`** becomes "completed bets" for CASHOUT, like slot. The `/health`
  `roundsSinceRestart` therefore climbs ~10x faster than a round game's. That is expected.
- **Never delete game impls** (memory rule): this plan only adds.
- **Java 21**, and `bot-api` stays free of Spring.

---

## 7. Open Items

- **OI-1: shape of the winning terminal frame.** Expected: X502, `iF:true`, `blS != -1`,
  `crd` = gross > 0. V-8 checks it. If `crd` turns out to be **net** (payout − stake), the bot adds
  `plan.amount` to the parsed winnings for a non-burst end. The burst frame has no `b`, so
  the stake has to come from the plan. This is a one-line change in `CashoutBot` accounting,
  pinned by a test.
- **OI-2: does the server reply to a rejected bet** (insufficient balance, invalid amount,
  bet while a bet is live)? F-1 suggests silence. If there is an error frame, add it as a
  terminal "rejected" outcome later. Until then, the watchdog covers it.
- **OI-3: progress-frame cadence and maximum bet duration.** Measure from V-8's one-shots
  plus the `CashoutWindow` line. If frames can be > 20 s apart in a legitimate bet, raise
  `bot.cashout.frame-timeout-seconds`.
- **OI-4: `sL` / `aS` / `aSt` semantics.** These are probably server-side auto-stop at a
  multiplier. If confirmed, a future option is to send the target in the bet and drop the
  client-side cash-out. Out of scope. Nothing depends on them now.
- **OI-5: per-group target / delay config.** Not in v1 (AD-7). Exposing it means a nullable
  `cashoutBehavior` sub-document on `BotGroup` + `BotGroupDTO` + mapper + validator, with
  null meaning `LEGACY`. It is a separate small plan when someone needs it.
- **OI-6: UI.** The game-type selector is server-driven, so CASHOUT appears in it. Whether the
  bot-group form needs a CASHOUT-specific layout (it only needs `minBet`/`maxBet`, botCount,
  autoDeposit and activation) is a frontend question outside this repo.
- **OI-7: why the 4 funded legacy Balloon bots got no reply** (F-1). This could be prod
  Balloon being off, or something in the legacy bet with `sid`. It is irrelevant on staging,
  but check it before any **prod** cutover.
- **OI-8: prod.** This plan targets 119 **staging**. A prod cutover needs the bot host
  whitelisted for 119 prod (CLAUDE.md "bets never settle") and `bot.ip` checked
  (BOT_IP config trap). It is a separate release decision.
- **Out of scope:** coordination, jackpot, ramp, strategies, Aviator.

---

## Verification

Run on staging (Bot-1) after deploy. `BASE=http://localhost:8080`,
`ENV=d005157f-4dfb-479d-a39a-36a3892439b7` (119 Staging). Logs are at
`/home/sgame/bot-java/logs/`. Staging runs `BOT_LOG_LEVEL=DEBUG`, so DEBUG lands in
`logs/detail/detail.log`. Container `bot-java-bot-manager-1`.

**Phase split:** V-1..V-4 apply from Phase 1. V-5..V-10 need Phase 3. The `CashoutWindow`
part of V-9 needs Phase 4.

**V-1: the app is up.**
```
curl -s -o /dev/null -w '%{http_code}\n' $BASE/actuator/health
```
Expect **200**, and `jq -r .status` gives **`UP`**.

**V-2: registry line.**
```
docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized" | tail -1
```
Expect **exactly one** line per boot. The BETTING_MINI and TAI_XIU segments must be
**identical to the pre-deploy baseline**, and a trailing segment must read
**`CASHOUT 1 products [119]`**.

**V-3: game type listed.**
```
curl -s $BASE/api/v1/game/types | jq -r '.[].code' | grep -cx CASHOUT
```
Expect **1**.

**V-4: games created.**
```
curl -s -X POST "$BASE/api/v1/game/G4/P_119/$ENV" -H 'Content-Type: application/json' \
  -d '{"name":"Balloon","gameType":"CASHOUT","pluginName":"balloonPlugin","offset":1500,"md5":false}'
curl -s -X POST "$BASE/api/v1/game/G4/P_119/$ENV" -H 'Content-Type: application/json' \
  -d '{"name":"Soccer","gameType":"CASHOUT","pluginName":"soccerPlugin","offset":2500,"md5":false}'
```
Expect HTTP **200** for each, with `gameType:"CASHOUT"` and the given offset/plugin. Record
the ids as `$BALLOON` and `$SOCCER`.

**V-5: groups start, authenticate and subscribe.** Create two 3-bot groups with
`POST $BASE/api/v1/bot-group/` (trailing slash). Use body fields `name`,
`environmentId:$ENV`, `gameId`, `botCount:3`, `namePrefix` (≤ 8 chars, unique), `password`,
`autoDepositEnabled:true`, and:
- Balloon: `minBet:1000, maxBet:100000`
- Soccer: `minBet:1000, maxBet:1000000`

Poll `GET /{id}` until `registeredCount == 3`, then:
```
curl -s -X POST $BASE/api/v1/bot-group/$G/start
sleep 90
curl -s $BASE/api/v1/bot-group/$G/health | jq '{connected:.connectedBots, dead:.deadBots, s:[.bots[].status]}'
```
Expect **200** on start and, within 90 s, **3/3 `CONNECTION_AUTHENTICATED`, 0 dead**, for both
groups. `markConnectionAuthenticated()` runs in `onSubscribe`, so this proves X500 was
answered and parsed.

**V-6: bets are placed and confirmed.** After **15 minutes**, for each group:
```
for M in bot_bets_placed_total bot_bet_amount_total bot_winnings_total; do
  echo "$M $(curl -s "$BASE/actuator/metrics/$M?tag=botGroupId:$G" | jq '.measurements[0].value')"; done
```
Expect **`bot_bets_placed_total > 50`** (3 bots, ~1 bet per 5-25 s each), **`bot_bet_amount_total > 0`**,
and **`bot_winnings_total > 0`**.

**V-7: both outcomes occur in a plausible mix; timeouts are rare.**
```
for O in cashout burst timeout; do
  echo "$O $(curl -s "$BASE/actuator/metrics/bot_cashout_bets_total?tag=botGroupId:$G&tag=outcome:$O" | jq '.measurements[0].value')"; done
```
Expect, per group:
- **`cashout > 0`** and **`burst > 0`**;
- `cashout / (cashout + burst)` in **[0.20, 0.55]** (legacy 0.376, F-3);
- **`timeout / total < 0.05`**;
- the `timeout` series **exists** (value may be 0.0). That proves pre-registration (AD-12).

**V-8: the winning terminal frame shape (closes OI-1).**
```
grep -h "first cash-out terminal frame" /home/sgame/bot-java/logs/detail/detail*.log | head -6
grep -h "first burst terminal frame"    /home/sgame/bot-java/logs/detail/detail*.log | head -6
```
Expect at least **one line of each**. For the cash-out lines, expect:
- `iF=true`;
- `blS` **≠ -1**;
- `crd` **≈ b × odds within 1%**, which confirms gross (AD-5). If `crd ≈ b × (odds − 1)`,
  that is the net case: file OI-1's fix.

For the burst lines, expect `iF=true` and either `blS=-1` or `crd=0.0`. **Record the cmd,
`blS` value and full `unmapped={…}` map from both lines in the release notes.** This is the
capture the plan is missing.

**V-9: no per-bet INFO; aggregate DEBUG present.**
```
grep -c "\"botGroupId\":\"$G\"" /home/sgame/bot-java/logs/console.log
grep -h "\[$G/" /home/sgame/bot-java/logs/detail/detail.log | grep -c "CashoutWindow"
```
Expect the console.log (track 1, INFO+) count to be **< 20** after 15 minutes: lifecycle lines
only, not growing with bets. Expect the `CashoutWindow` count to be **> 0** (Phase 4 only).

**V-10: request budget and reconnect churn are unaffected.**
```
curl -s "$BASE/actuator/metrics/gateway_budget_window_requests?tag=environmentId:$ENV" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_reconnects_total?tag=botGroupId:$G" | jq '.measurements[0].value'
```
Take the first reading 15 minutes after start, outside the login burst. Expect it **< 100**,
and to stay flat across a second reading 5 minutes later (±20). That shows bets cause no HTTP.
For reconnects, expect **≤ 3** per group (`null`/absent also passes) over the 15 minutes.

**Cleanup:** leave the groups running only if the user asks. Otherwise
`POST $BASE/api/v1/bot-group/$G/stop` for both, and expect **200**.
