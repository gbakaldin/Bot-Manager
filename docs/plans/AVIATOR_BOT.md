# Aviator (crash) bot — 119 Avatar

**Status:** architect plan, 2026-10-05. Not started. Replaces the 2026-08/09 draft. AD-1 and
AD-2 of the draft were decided by the user and are kept as AD-1 / AD-2 below. Everything the
2026-10-05 capture disproved has been deleted: the 1800 frame, the polled cash-out, and the
open questions it answered.

Branch `feature/aviator-bot`, cut from `feature/cashout-bot` @ `045670c`, which is what Bot-1
staging runs. File citations use `/Users/gleb/IdeaProjects/Bot/...`. Line numbers are from that
base.

## 1. Goal

Add `GameType.CRASH` and a `CrashBot` that plays 119's **Avatar** crash game (`aviatorPlugin`,
cmds 1700-1709). Two rockets, Jake and Neytiri, fly on shared rounds of about 50 s. The bot bets
during the ~8 s window, then cashes out when its own rocket's multiplier reaches a target it drew
when it placed the bet. If the rocket crashes first, the bot loses. Behaviour matches the legacy
Node bot, with that bot's wrong-runner cash-out bug fixed. Every bet is visible in the existing
bet, winnings and outcome metrics. It reuses the CASHOUT bot's machinery: a pure CAS state
machine, an event-driven send on a captured channel, a reconnect ladder, pre-registered outcome
counters and the same logging tiers. The goal is quick bring-up of crash games on new brands, so
the runner concept stays in the per-brand message layer (AD-2).

---

## 2. Findings — Current State

### Protocol (capture `/Users/gleb/IdeaProjects/Bot/docs/captures/aviator-119-2026-10-05.jsonl`, 119 staging, 218 s, 3 rounds)

CMDs are absolute, and **offset 1700 = code 0**. Zone is `MiniGame`. `Lx` means line x of the
capture.

| cmd | dir | body (relevant keys) | meaning |
|---|---|---|---|
| 1700 | out | `{cmd}` (L2) | subscribe, once per session |
| 1700 | in | `sid, gS, rmT, jOdd, nOdd, jFi, nFi, b, htr[50], cH[50], eI, op, …` (L3) | snapshot. Mid-flight join gave `gS:4, rmT:0`. **No bet-amount list.** |
| 1705 | in | `{sid, iOE, eI}` (L15, L26, L38) | new round, betting opens |
| 1702 | out | `{cmd, b, sid, aid:1, eid}` (L16) | bet. `sid` = the 1705 round |
| 1702 | in | `{eid, b, cmd}` (L17), ~200 ms later | bet ack. **No sid.** |
| 1708 | in | bet board `ps[]`, `tB` (L18-19) | ignore |
| 1706 | in | `{sid, iUC}` (L20) | betting closed, flight starts. 7.87-7.92 s after 1705 in all 3 rounds |
| 1709 | in | `{sid, jOdd, nOdd, jFi, nFi, iJe, ps[]}` every ~500 ms (L4-L12) | flight tick |
| 1703 | out | `{cmd, sid, aid:1, eid}` (L21) | cash-out |
| 1703 | in | `{eid, b, wm, odd, aid}` (L22), ~320 ms later | cash-out ack. `wm` = gross (10000 × 2.4 = 24000; 50000 × 1.82 = 91000). **No sid.** |
| 1707 | in | `{sid, b, jOdd, nOdd}` (L13, L24, L36) | round end. `b` = own stake (0 when no bet), odds = final crash points |
| 1716 | in | same instant as 1707, jackpot info | ignore |

Facts that shape the design:

- **F-1: one shared curve.** `jOdd == nOdd` on every tick until one rocket crashes. After that the
  crashed runner's value freezes and its `*Fi` flag goes true (L12: `nFi:true, nOdd:2.86`, while
  `jOdd` is still climbing). So `multiplierFor(eid)` must read the bot's own runner. A **true flag
  on its own runner means it crashed**: the frozen value can be ≥ the target (crash tick 2.86
  against a 2.85 target, when the previous tick was 2.80), and that must never trigger a cash-out.
- **F-2: values are decimal with two places**, and a value can come as an integer (`jOdd: 6`,
  L12). Parse as double, then scale ×100 to a `long`.
- **F-3: acks carry no sid.** A bot has one bet per round, so the ack binds on `(eid, b)` while a
  bet is pending.
- **F-4: there is no 1800.** The 1700 snapshot covers a mid-round join. `iJe`, `gS`, `iOE`,
  `iUC`, `tFB`, `tFl`, `cCUCO` are not needed and stay unmodelled.
- **F-5: staging crashes every round at the same points.** Neytiri 2.86, Jake 11.59 (L3 `htr`,
  every 1707). Staging RTP means nothing. With targets U(1.1, 5.0), Jake bets always cash out and
  Neytiri bets cash out ~45% of the time.
- **F-6: the server sends no allowed-bet list** (no `bets` in 1700, unlike CASHOUT's X500).

### Legacy reference bot (`/Users/gleb/IdeaProjects/Bot/dev-w79-avatar.js`, local only and git-excluded; never copy it into the repo)

- Stake = `random(min/5000, max/5000) × 5000` (:231): **multiples of 5,000** in tier windows that
  together span [20,000, 200,000]. Captured human bets are 10k / 50k / 100k / 20k (`Aviator.js`).
- Target `odd = U(min, max)` with tiers [1.1, 3] and [1, 5] (:235). `eid = random(1,2)` (:236).
- Bet sent `random(0, 4500)` ms after 1705 (:250, :359).
- Participation gates: a per-hour rate (:223) and tier membership. Our existing activation windows
  replace these (Open Items).
- **Bug (do not port):** at :381 it cashes out when **either** runner crosses, but sends its own
  `eid`. Under F-1 that means it settles its own bet after its own rocket has already crashed.

### Code this builds on (CASHOUT, just shipped)

- `GameType` — `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/game/model/GameType.java:16`
  (`CASHOUT` is last).
- Contracts: `.../bot-api/src/main/java/com/vingame/bot/domain/bot/message/CashoutMessageTypes.java`
  (codes, class accessors, `newRequest`, default `getTypeRegistrations`), `.../message/cashout/CashoutMessage.java`
  (`@JsonTypeInfo(NAME, EXISTING_PROPERTY, "cmd")`), `.../message/request/CashoutRequest.java`.
- 119 provider: `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g4/win79/cashout/Win79CashoutMessageTypes.java`
  (`@MessageTypesImpl(gameType = CASHOUT, products = "119")`).
- Registry: `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/MessageTypesRegistry.java:98-134`
  (`Tables`, ctor, single-line startup INFO with CASHOUT last), `:194` `cashout(String)`, `:216`
  `registeredCashoutProducts()`. Nine test files call `new MessageTypesRegistry(`.
  `MessageTypesCoverageTest:74,122-125,156` keeps per-contract inventories.
- Zone: `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:96-110`.
  Any type that is not listed falls to `Simms`.
- Factory: `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:161-206`
  (exhaustive switch; CASHOUT arm :198-204; throw arm :205), `productKey` :254.
- Validator: `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/validation/CashoutConfigValidator.java`.
  `GameConfigValidatorFactory:68-70` fails boot for any `GameType` that has no validator.
- Group → bot: `BotGroupBehaviorService:1987-1989` passes `minBet/maxBet/betIncrement` to every
  type. Strategy, ramp and affinity are gated on `BETTING_MINI || TAI_XIU` (:2015) and slot on
  `SLOT` (:2053), so CRASH falls through with nothing attached. That is correct and needs no edit.
- `CashoutBot` — `/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/CashoutBot.java`:
  `initializeSubclass` :165-203, `onNewSession` :215-236, `onSubscribe` :241-266 (marks
  authenticated first), `sendCashout` on the captured channel :309-327, watchdog arm/fire/escalate
  :380-436, scenario :520-551, `beforeReconnect` :554-560, `onStart` :572-587.
- `CashoutBetStateMachine` — `.../core/cashout/CashoutBetStateMachine.java`: one `AtomicReference`
  with records and CAS transitions, `isReconnectRung` :445-458, `reset` keeps the ladder memo :477-491.
- Bot lifecycle: `Bot.initialize` sets the MDC and snapshot (:349) **before** `initializeSubclass`.
  `tryReconnectWs` calls `beforeReconnect()` and then `start()` → `onStart()` (:1246-1247, :1466).
  `creditBalance` is at :1450.
- Metrics: `BotMetrics` `BOT_CASHOUT_BETS_TOTAL` / `initCashoutSeries` / `incCashoutOutcome`
  (:100-103, :321-350), `incBotMessage(cmd)` tag `cmd` (:187), `incBotWatchdogExpired`.
  `preRegisterOutcomeCounters` (:405-412) already pre-registers `bot_bets_placed_total`,
  `bot_winnings_total` and `bot_auto_deposits_total`.
- **The CASHOUT release lessons** (`/Users/gleb/IdeaProjects/Bot/docs/reviews/CASHOUT_BOT/release.md:15-31,160-170`):
  1. 119 staging answered the Balloon/Soccer subscribe on the proxy env with
     `[7,2,"…access to this game is not permitted",{"cmd":1500}]`.
  2. The bot sat at `STARTED` forever, with no WARN and no metric, because its only watchdog was
     armed after the subscribe reply.
  3. The outcome series never appeared because pre-registration ran in `onSubscribe`.
  4. `autoDepositEnabled:true` topped every bot up to 1B.
  This plan closes 2 and 3 by design (AD-9, AD-11) and the release closes 4 (Verification).
- websocket-parser 3.0.5: stages after `waitForMessage` keep receiving frames until the scenario
  is shut down, because reaching the last stage does not deactivate the pipeline
  (`Scenario.process`). It runs 4 inbound workers per client, so frames for one bot are handled
  concurrently and out of order.

---

## 3. Readiness

| Aspect | Status | Notes |
|---|---|---|
| Auth / socket on 119 | ready | 119 groups already play on `wss://w79.sgame.club/websocket_mini` (Club env `8ca14218`) |
| Zone | partial | `resolveZoneName` must learn `CRASH` (AD-14) |
| Frames 1700/1702/1703/1705/1706/1707/1709 | ready (protocol) | All captured, including both acks and a crash tick |
| Bet rejection / error replies | out of scope | Absorbed by the round clock (unacked outcome) and the silence watch (AD-9) |
| Allowed bet amounts | partial | Not sent by the server. Legacy 5,000-step used (AD-6, OI-1) |
| GameType / registry / validator / factory | not started | Phase 1 |
| Round state machine | not started | Phase 2 (pure) |
| CrashBot + metrics | not started | Phase 3 |
| Strategy family | deferred | Not in v1 (AD-7) |
| Access gate on Club env for `aviatorPlugin` | unknown | The CASHOUT subscribe was refused on the proxy env. Release step V-5 is where we find out |

---

## 4. Architecture Decisions

**AD-1 (user decision, kept): 119 is ONE `Game` row.** The runners share the `sid`, the betting
window, the 1705/1706/1707 cycle, the subscribe and the 1709 tick. Two rows would count one round
twice and double the sockets.

**AD-2 (user decision, kept, one extension): the runner is protocol metadata.**
- `CrashMessageTypes.runnerCount()` defaults to `1`. The 119 provider returns `2`.
- Not on `Game`, not in the DTO, not in the UI, and no `optionAffinities` reuse.
- `eid` is drawn uniformly over `[1..runnerCount()]` **once per round** when the bet is placed,
  frozen in the plan, and used for both 1702 and 1703.
- A single-runner brand's request class does not serialise `eid`.
- **Extension, forced by F-1:** `HasRunnerMultiplier` has two members.
  - `long multiplierFor(int eid)`: hundredths.
  - `boolean crashedFor(int eid)`: 119 maps eid 1 → `jFi`, eid 2 → `nFi`.
  - The cash-out gate is `!crashedFor(eid) && multiplierFor(eid) >= target`.
  - An eid outside `[1..runnerCount()]` returns `0` / `true`, i.e. never cash out. A frame handler
    must not throw.
- 119 mapping (user-confirmed): **eid 1 = Jake (`jOdd`/`jFi`), eid 2 = Neytiri (`nOdd`/`nFi`)**.
  The wire word "odd" means multiplier (payout ratio) and never probability. It is translated in
  the message class and does not get past it.

**AD-3: `GameType.CRASH("Crash")`.** It is one type for every crash brand, not `AVIATOR`, and
separate from `CASHOUT`, which has no shared rounds (CASHOUT AD-1). Javadoc on the constant says so.

**AD-4: CMD = `offset + code`. Codes are `SUBSCRIBE 0, BET 2, CASHOUT 3, ROUND_START 5,
BETTING_CLOSED 6, ROUND_END 7, TICK 9`.** 119 Avatar: `pluginName aviatorPlugin`,
`offset 1700`. A CRASH game with a null offset fails in `initializeSubclass` with
`IllegalStateException`, like CASHOUT. Codes are interface constants, and `getTypeRegistrations`
is a `default` method. A brand with different codes overrides it and
`default List<Integer> cmds(int offset)` (used by the OutputPrinter). 1708 and 1716 are **not
registered** and are never parsed.

**AD-5: Message layer = product-keyed provider `CrashMessageTypes`, resolved by
`messageTypesRegistry.crash(productKey(env))`.**
- `bot-api/.../message/crash/` holds `CrashMessage` (the abstract `@JsonTypeInfo` base) and these
  abstract frames:
  - `CrashSubscribeResponse` (marker);
  - `CrashRoundStart`: `long sid()`;
  - `CrashBettingClosed`: `long sid()`;
  - `CrashBetAck`: `int eid()`, `long stake()`;
  - `CrashTick implements HasRunnerMultiplier`: `long sid()`;
  - `CrashCashoutAck implements HasBotWinnings`: `int eid()`, `long stake()`, `double multiplier()`,
    `winningsFor(user) = Math.round(wm)`;
  - `CrashRoundEnd`: `long sid()`, `long ownStake()`.
- `HasRunnerMultiplier` lives in `bot-api/.../message/crash/`.
- `bot-api/.../message/request/CrashRequest` has three methods: `subscribe()`,
  `bet(long amount, long sid, int eid)` and `cashOut(long sid, int eid)`.
- `CrashMessageTypes` has class accessors for the 7 inbound types, `runnerCount()`,
  `newRequest(zone, plugin, offset)` and `default getTypeRegistrations(int offset)`.
- 119 concrete classes go in `bot-messages/.../message/g4/win79/crash/`, with
  `@MessageTypesImpl(gameType = CRASH, products = "119")`.
- No `@JsonAnySetter`, because the protocol is fully captured. The ticks carry `ps[]`, which is
  skipped by `FAIL_ON_UNKNOWN_PROPERTIES=false`.
- Registry: a fifth list, `Tables.crash`, `crash(String)` and `registeredCrashProducts()`. The
  startup line gains `, CRASH n products [...]` **appended after the CASHOUT segment**, and it
  stays a single line.

**AD-6: Stake = uniform over the affordable members of the group's step ladder.**
- `step = betIncrement > 0 ? betIncrement : 5_000`.
- `ladder = { k·step : k ≥ 1, minBet ≤ k·step ≤ maxBet }`.
- If `maxBet ≤ 0` (unset), the window is `[20_000, 200_000]`, which is legacy parity (F-6).
- Each bet draws uniformly from `ladder ∩ [.., balance]`.
- Below the cheapest rung the bot does not bet. It logs DEBUG once on entering that state and once
  on leaving it (`CashoutBot:443-470` pattern). An empty ladder means one WARN per bot per
  subscribe and no betting.
- `min/maxBetsPerRound` and `maxTotalBetPerRound` are ignored. The bot places one bet per round.
- Computed by a pure `CrashStakes.ladder(min, max, step)`, which the validator also calls.

**AD-7: No strategy family in v1. Use a `CrashBehavior` record with legacy constants:
`minTarget 1.1`, `maxTarget 5.0` (uniform), `betDelayMinMs 0`, `betDelayMaxMs 4500` (uniform,
after 1705).** `CrashBehavior.LEGACY` is the only instance. No `BotGroup`, DTO or UI field.
Justification:
- For a standard crash curve the target is a variance knob and not an EV knob (the draft's
  `EV = s(1-e)`).
- Legacy behaviour is a single uniform draw.
- A family would add a registry, an annotation, catalogue parity tests and a UI picker with
  nothing to choose in v1.
- `CrashBehavior` makes a later family a wiring change, which is CASHOUT AD-7's reasoning. CROWD,
  FIXED_LOW and MOONSHOT are a follow-up (OI-3).
- The target is drawn once, at placement, as hundredths (`Math.round(t·100)` ∈ [110, 500]), and is
  never re-drawn.

**AD-8: `CrashRoundStateMachine`. A pure class with one `AtomicReference`, every transition a
CAS, an injected `LongSupplier` clock and `Random`.** It lives in `bot-engine/.../core/crash/`, is
public (CASHOUT Amendment A1), and has no I/O and no logging.

```
WAITING --onRoundStart(sid)--------------------------> OPEN(sid, betAt = now + U(0,4500))
OPEN(sid) --tryPlace(sid, ladder, balance)-----------> PLACED(sid, plan{amount, targetH, eid})
OPEN --onBettingClosed(sid)--------------------------> WAITING                  [bet window missed]
PLACED --onBetAck(eid==plan.eid && b==plan.amount)---> LIVE                     [CONFIRMED(plan)]
LIVE --onTick(sid): crashedFor(eid)------------------> SETTLED                  [ENDED(CRASH)]
LIVE --onTick(sid): multiplierFor(eid) >= targetH----> CASHING                  [SEND_CASHOUT(sid, eid)] exactly once
CASHING --onTick-------------------------------------> CASHING                  (crash flag ignored: the server decides)
CASHING --onCashoutAck(eid, b match)-----------------> SETTLED                  [ENDED(CASHOUT, winnings=wm)]
PLACED --onRoundEnd----------------------------------> WAITING                  [ENDED(UNACKED)]
LIVE|CASHING --onRoundEnd----------------------------> WAITING                  [ENDED(CRASH)]
SETTLED|OPEN|WAITING --onRoundEnd--------------------> WAITING                  [ROUND_CLOSED]
PLACED|LIVE|CASHING --onRoundStart(newer sid)--------> OPEN(new)                [ENDED(UNACKED|CRASH) for the old bet first]
any --reset()----------------------------------------> WAITING                  [nothing]
```

- A tick, `onBettingClosed` or `onRoundEnd` whose `sid` is **older** than the state's sid is
  ignored. A tick for another sid is ignored.
- Acks bind only in their single pending state (F-3).
- `SETTLED` ignores ticks, so nothing is ever sent after a crash or an ack.
- Out-of-order ticks are harmless: once the crash tick has moved LIVE to SETTLED, a stale
  pre-crash tick finds SETTLED and does nothing.
- `tryPlace` succeeds only from `OPEN(sid)` with `now ≥ betAt` and a non-empty affordable ladder.
  It draws `amount`, `targetH` and `eid` (AD-2).
- A reset abandons an in-flight bet **without an outcome**. The server settles it, and we have
  already counted it as placed at the ack. Accepted (Concerns).

**AD-9: Silence watch. A bounded reconnect ladder, armed from `onStart`, not from the subscribe
reply.**
- `RoundSilenceWatch` is pure and clock-injected. Every inbound crash frame (1700, 1702, 1703,
  1705, 1706, 1707, 1709) records `lastFrameAt` and resets `silentWindows` to 0. That is one
  volatile write, with no rescheduling per tick.
- Window = the existing `bot.watchdog.timeout.seconds` (`BotConfiguration.watchdogTimeoutSeconds`,
  default 180, which is ~3.5 rounds). **No new property.**
- One task on the bot's single virtual scheduler fires at the deadline:
  - If a frame arrived in the meantime, it re-arms for the remainder (`CashoutBot:417-420`).
  - Otherwise `silentWindows++`, and it re-arms for a full window.
- The silence counter is **not** reset by a reconnect.
- Reconnect at `silentWindows ∈ {1, 2, 4, 8, 16, 32}`, then every 32 windows, via a pure
  `ReconnectLadder.isRung(count, r = 1, topShift = 5)`. That is at most one re-login per bot per
  96 min in steady state.
- Escalation is CASHOUT's `escalate()`: `incBotWatchdogExpired()`,
  `scopedDebugEscalator.onWatchdogExpiry(group)`, then `triggerFullReconnect("watchdog: N silent
  windows")`.
- The first silent window per bot logs one WARN that includes `subscribed=<bool>`. That makes a
  refused subscribe (CASHOUT release finding 2) visible within 3 minutes. It does not hot-loop,
  because of the ladder.
- `ReconnectLadder` is a new shared class in `bot-engine/.../core/support/`. **CASHOUT is not
  migrated to it in this feature.** Its private `isReconnectRung` stays as is (follow-up OI-5).
- No per-bet watchdog. The round clock bounds every bet: by the next 1707, or by the next 1705 if
  that 1707 was lost.

**AD-10: Everything after subscribe is event-driven. No `sendAsync` stage.**
- **Bet.** The 1705 handler calls `machine.onRoundStart(sid)` and schedules a one-shot task at
  `betAt` on the bot's single virtual scheduler (`crash-<user>`, the same executor the silence
  watch uses). The task:
  1. skips if `sessionCheckInProgress`;
  2. calls `machine.tryPlace(sid, ladder, balance)`. That CAS fails harmlessly after 1706, a reset
     or a newer round;
  3. calls `creditBalance(amount)`;
  4. sends `request.bet(amount, sid, eid)` on the channel captured once in `botBehaviorScenario()`.
  There is no park-and-pop and no supplier fallback, so CASHOUT Amendment A3's re-subscribe
  fallback cannot happen here. In this game a re-subscribe would reset a live bet.
- **Cash-out.** Sent straight from the 1709 handler when the machine emits `SEND_CASHOUT`, on the
  same captured channel. This copies `CashoutBot.sendCashout` :309-327: try/catch, WARN once per
  bot, DEBUG afterwards. Latency is bounded by the server's ~500 ms tick and not by a poll.
- The draft's "two schedulers per bot" concern is gone. A bot has **one** scheduler thread.
- Wire bodies (119): bet `{"cmd":1702,"b":<amount>,"sid":<sid>,"aid":1,"eid":<eid>}` and cash-out
  `{"cmd":1703,"sid":<sid>,"aid":1,"eid":<eid>}`. `aid` is the constant 1.

**AD-11: Accounting and metrics.**
- **Send:** `creditBalance(amount)` (local debit and local "sent" totals).
- **1702 ack (CONFIRMED):** `metrics.incBetsPlaced(1, amount)`. Confirmed bets, as in
  ENDGAME_METRICS AD-4.
- **1703 ack:** `w = round(wm)`. Then `expectedCurrentBalance += w`, `cumulativeWinnings += w`,
  `metrics.incBotWinnings(w)`, `lastRoundWinnings = w`. `HasBotWinnings` is on the ack and not
  on 1707.
- **Crash / unacked:** `lastRoundWinnings = 0`. An unacked bet's local debit stands, and a drift
  re-sync corrects it (CASHOUT AD-11).
- **Every 1707:** `roundsObserved++`, then `onNewSession()` (balance check and deposit decision).
  1707 is between rounds and no bet is in flight.
- **No `HasBetTotals`.** 1707's `b` would only echo our own plan. It appears in a one-shot DEBUG
  line (AD-13) as a cross-check.
- **New counter `bot_crash_bets_total{outcome="cashout"|"crash"|"unacked"}`, with MDC tags.**
  - `BotMetrics.initCrashSeries()` registers all three at 0 and is idempotent.
  - It is **called from `CrashBot.initializeSubclass`**: the MDC is set there (`Bot:349`), and
    that is before any frame or any subscribe. So the series exist even when the subscribe is
    refused (CASHOUT release finding 3). The tags come from the same MDC the increments use.
  - It is a separate metric, not a reuse of `bot_cashout_bets_total`. Different outcome
    vocabulary, and changing CASHOUT's label set would alter its semantics.
- **`incBotMessage` labels:** `crashSubscribe`, `crashRoundStart`, `crashBettingClosed`,
  `crashBetAck`, `crashTick`, `crashCashoutAck`, `crashRoundEnd`.

**AD-12: Join = subscribe once per session. Never bet off the snapshot.**
- The 1700 reply calls `markConnectionAuthenticated()` **first and unconditionally**
  (`CashoutBot:244-247`), then `machine.reset()`, then a stake-ladder compute and log.
- The first bet is on the next 1705. That costs at most one round (~50 s) per (re)connect. It
  avoids modelling `gS`/`rmT`, and it avoids late bets into a closing window.

**AD-13: Logging tiers** (`PerBotInfoLogGuardTest` covers `CrashBot`, `CrashRoundStateMachine` and
`RoundSilenceWatch`).
- **INFO:** none per bot, per round or per bet. Group lifecycle goes through
  `groupLifecycleAggregator.recordInitialized("game=…, type=CRASH, offset=…, runners=…")`.
- **DEBUG:**
  - the init line;
  - the subscribe line (ladder, runner count);
  - balance pause/resume transitions;
  - silent windows after the first;
  - three **one-shots per bot per JVM**:
    - `first crash cash-out ack: eid=, b=, odd=, wm=`;
    - `first crash loss: eid=, targetH=, multiplierH=, crashed=`;
    - `first crash round end: sid=, b=, plan=`.
- **TRACE:** every 1709 line, bet sent, cash-out sent, acks, per-bet outcome, ignored or stale
  frames, the OutputPrinter raw dump over `cmds(offset)`.
- **WARN:** empty ladder (once per subscribe), first silent window per bot, send failure (once per
  bot), and `triggerFullReconnect`'s own line.

**AD-14: `Environment.resolveZoneName` treats `CRASH` as a mini game.**

**AD-15: `CrashConfigValidator` validates only what CRASH reads:**
- `minBet ≥ 0`;
- `betIncrement ≥ 0`;
- `minBet ≤ maxBet` when `maxBet > 0`;
- `CrashStakes.ladder(...)` is non-empty. A 400 names the window and the step.
Other betting fields are ignored. It ships in the same phase as the enum constant
(`GameConfigValidatorFactory:68-70`).

**AD-16: No new HTTP per bet or per round.** HTTP is login, the WS upgrade, the first balance
read, drift re-syncs and auto-deposit (off on the staging group). Reconnects are bounded by AD-9.

---

## 5. Plan

Build: `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn clean install`
in the worktree, using a worktree build for test counts. Each phase ends with a green full build.

### Phase 1: Protocol layer, game type, registry (no bot)

When this ships, a CRASH `Game` and group can be created. Starting a group fails each bot with
"Game type not yet implemented", the same as CASHOUT after its Phase 1.

1. `GameType.java`: add `CRASH("Crash")` after `CASHOUT`, with javadoc (AD-3).
2. `bot-api`: add `message/crash/` (AD-5 classes, plus `HasRunnerMultiplier`),
   `message/CrashMessageTypes.java` (codes from AD-4, `runnerCount()` default 1, `cmds(offset)`,
   `getTypeRegistrations(offset)`) and `message/request/CrashRequest.java`.
3. `bot-messages/.../g4/win79/crash/`:
   - `Win79CrashMessageTypes` (`@Component @MessageTypesImpl(gameType = CRASH, products = "119")`,
     `runnerCount() = 2`);
   - seven concrete inbound classes with wire fields `jOdd/nOdd` (Double), `jFi/nFi`, `sid`, `b`,
     `eid`, `wm`, `odd`. Scaling and `crashedFor` follow AD-2;
   - `Win79CrashRequest` with bet and cash-out bodies (pattern: `Win79CashoutBet`). Subscribe
     reuses `SubscribeToLobbyMessage(zone, plugin, new Body(offset))`.
4. `MessageTypesRegistry`: add the fifth list and table, `crash(String)`,
   `registeredCrashProducts()` and the appended startup segment (AD-5). Update the production
   wiring and the 9 test call sites (`List.of()`). `MessageTypesCoverageTest`: add a CRASH
   inventory of every product except `119`.
5. `Environment.resolveZoneName`: add `CRASH` (AD-14).
6. `CrashStakes` (pure, `bot-api` or `bot-engine`, whichever both the validator and the bot can
   reach) and `bot-app/.../botgroup/validation/CrashConfigValidator.java` (AD-6, AD-15).
7. `BotFactory`: add `CRASH` to the `CARD_GAME, UP_DOWN` throw arm.
8. Tests:
   - `Win79CrashMessageTypesTest`. Fixtures are trimmed copies of capture L3 (with `cH` and `htr`
     emptied), L12, L13, L15, L17, L20, L22 and L24, under
     `bot-messages/src/test/resources/messages/win79/crash/`. Assertions:
     - the tick at L4: `multiplierFor(1) == multiplierFor(2) == 143`, both `!crashedFor`;
     - the crash tick at L12: `crashedFor(2)`, `multiplierFor(2) == 286`, `!crashedFor(1)`,
       `multiplierFor(1) == 600` (integer wire value);
     - eid 3 gives `0` / `true`;
     - bet ack `eid 1, stake 10000`;
     - cash-out ack `winningsFor(any) == 24000`, `multiplier ≈ 2.4`;
     - round end `sid 1638119, ownStake 10000`;
     - round start / betting closed sids;
     - a 1708 frame does not resolve to any registered type.
   - Serialization: the bet equals
     `[6,"MiniGame","aviatorPlugin",{"cmd":1702,"b":10000,"sid":1638119,"aid":1,"eid":1}]` and the
     cash-out equals `{"cmd":1703,"sid":1638119,"aid":1,"eid":1}`. Key order does not matter, but
     every key must be present.
   - Registry: `crash("119")` resolves with `runnerCount() == 2`. `crash("116")` throws the
     standard lookup message. The startup line ends with `CASHOUT 1 products [119], CRASH 1
     products [119]` (`MessageTypesRegistryStartupLogTest`).
   - `CrashStakes`:
     - `ladder(20000, 200000, 5000)` has 37 rungs, first 20000, last 200000;
     - `ladder(0, 0, 0)` falls back to the default window;
     - `ladder(12000, 14000, 5000)` is empty.
     `CrashConfigValidator` gives a 400 for that last window and for `minBet > maxBet`.
     `GameConfigValidatorFactory` boots.
   - `EnvironmentZoneResolutionTest`: CRASH → `MiniGame`.

**Phase 1 verification:** full build green. `grep -c "CRASH 1 products \[119\]"` matches in the
`MessageTypesRegistryStartupLogTest` output. No CASHOUT test changed except the 9 constructor call
sites and the coverage inventory.

### Phase 2: Pure round machine, silence watch, ladder (ships dark)

Nothing calls these classes yet.

1. `bot-engine/.../core/crash/CrashBehavior.java`: a record and `LEGACY` (AD-7).
2. `bot-engine/.../core/crash/CrashRoundStateMachine.java` (AD-8). API sketch:
   - `Opened onRoundStart(long sid)`, carrying `betDelayMs` and an optional `Ended` for an
     abandoned bet;
   - `Optional<Plan> tryPlace(long sid, List<Long> ladder, long balance)`;
   - `Action onBetAck(int eid, long stake)`;
   - `void onBettingClosed(long sid)`;
   - `Action onTick(CrashTick t)`;
   - `Action onCashoutAck(int eid, long stake, long winnings)`;
   - `Action onRoundEnd(long sid)`;
   - `reset()`, `inFlight()`, `currentPlan()`.
   `Plan(long amount, long targetH, int eid)`.
   `Action = None | Confirmed(plan) | SendCashout(plan, sid) | Ended(outcome, plan, winnings) | RoundClosed`.
   `Outcome { CASHOUT("cashout"), CRASH("crash"), UNACKED("unacked") }`. The constructor takes
   `CrashBehavior`, `runnerCount`, the clock and the `Random`.
3. `bot-engine/.../core/crash/RoundSilenceWatch.java` (AD-9): `onFrame()`,
   `Check check()` returning `Remaining(ms) | Silent(count, reconnect)`, and `subscribed` tracking
   for the WARN text.
4. `bot-engine/.../core/support/ReconnectLadder.java`: `static boolean isRung(int count, int r, int topShift)`.
   It has the same semantics as `CashoutBetStateMachine.isReconnectRung`. CASHOUT is untouched.
5. Tests (JUnit, no Spring, fake clock, seeded `Random`):
   - Full replay of capture rounds 1638119 and 1638120 (start → place → ack → ticks → cash-out →
     ack → end). The machine emits exactly one `SendCashout`, and only on the first tick with
     `multiplierFor(eid) ≥ targetH`.
   - **The F-1 rule:** an eid-2 plan with target 2.85 against the ticks 2.80 then `{nFi:true,
     nOdd:2.86}` gives `Ended(CRASH)` and **no** `SendCashout`.
   - An eid-1 plan reads `jOdd` only. An eid-2 plan with target 3.0 gets `Ended(CRASH)` on the
     `nFi` tick even while `jOdd` keeps climbing past 3.0. This pins the legacy bug as
     unrepresentable.
   - Target frozen: 100 ticks below target emit nothing, and `currentPlan()` is identical before
     and after.
   - 4-thread `CountDownLatch` hammer on the crossing tick gives exactly one `SendCashout`.
     Crossing tick vs crash tick concurrently gives exactly one of {SendCashout, Ended(CRASH)}.
   - A crash tick processed before a stale pre-crash crossing tick gives `Ended(CRASH)` and then
     `None`.
   - An ack with the wrong `eid` or `b`, or arriving in the wrong state, does not bind. A
     `CASHING` bet with no ack gets `Ended(CRASH)` on 1707. `PLACED` at 1707 gets
     `Ended(UNACKED)`. `SETTLED` at 1707 gets `RoundClosed` with no second outcome.
   - `tryPlace` before `betAt`, after `onBettingClosed`, for an old sid, or after `reset()` returns
     empty. `betAt − roundStart ∈ [0, 4500]`.
   - Missed 1707: the next `onRoundStart` emits the old bet's outcome first.
   - Draws:
     - eid frequency over 10k plans is 50% ± 2% with `runnerCount 2`, and always 1 with
       `runnerCount 1`;
     - `targetH ∈ [110, 500]` with mean ≈ 305 ± 3;
     - amount only from the affordable ladder, uniform.
   - `RoundSilenceWatch` + `ReconnectLadder`: reconnect at windows 1, 2, 4, 8, 16, 32, 64, 96 and
     at no other count ≤ 100. A frame resets the count, a reconnect does not. `Remaining` is exact
     after a frame mid-window.

**Phase 2 verification:** full build green. New tests run in `bot-engine`. Coverage of every
transition row in AD-8 is asserted.

### Phase 3: `CrashBot`, factory wiring, metrics

When this ships, CRASH groups play.

1. `bot-engine/.../core/CrashBot.java` extends `Bot`, modelled on `CashoutBot`:
   - `initializeSubclass`:
     - require `offset` (AD-4);
     - build `request` and the per-bot RNG (the `CashoutBot:176-178` rule), the machine, the
       watch and one virtual scheduler `crash-<user>`;
     - `metrics.initCrashSeries()` (AD-11);
     - the aggregator line (AD-13).
   - `botBehaviorScenario`:
     - capture `client` once and bind the channel and mapper (`registerSubtypes(getTypeRegistrations(offset))`);
     - `waitFor(1000)` → `send(request::subscribe)` → `waitForMessage(cmd(offset).and(typeOf(RECEIVED)))`;
     - then `onMessage` for each of the seven types, each `mdcConsumer`-wrapped. **No `sendAsync`.**
   - Handlers:
     - every handler first calls `watch.onFrame()` and `incBotMessage(...)`;
     - `onSubscribe` (AD-12);
     - `onRoundStart` (schedule the bet task, AD-10; account any `Ended` it returns);
     - `onBetAck` (`Confirmed` → `incBetsPlaced`);
     - `onBettingClosed`;
     - `onTick` (TRACE only; `SendCashout` → send; `Ended(CRASH)` → account);
     - `onCashoutAck` (account winnings);
     - `onRoundEnd` (account any `Ended`, `roundsObserved++`, `onNewSession()`).
     - Accounting for `Ended` is one private method: outcome counter, winnings, the one-shot DEBUG
       and the TRACE outcome line.
   - `onNewSession`: copy `CashoutBot:215-236`, including the `sessionCheckInProgress` gate.
   - Silence task (AD-9): `armSilenceWatch(delay)` cancels and reschedules under a lock. On fire:
     `Remaining` re-arms; `Silent` logs WARN for the first window or DEBUG after that, re-arms a
     full window, and on `reconnect` calls `escalate`.
   - `beforeReconnect`: cancel the pending bet task, `machine.reset()`. Leave the silence count.
     `onStart` re-arms the watch.
   - `cleanup`: shut down the scheduler.
   - `onStart`: `onNewSession()`, the OutputPrinter over `cmds(offset)`, `addScenario(...)`, and
     `armSilenceWatch(window)`. Arming happens here, so a refused subscribe is caught.
2. `BotFactory`: `case CRASH -> { CrashBot b = new CrashBot();
   b.setMessageTypes(messageTypesRegistry.crash(productKey(env))); yield b; }`.
3. `BotMetrics`: add `BOT_CRASH_BETS_TOTAL`, `CRASH_OUTCOMES`, `initCrashSeries()` and
   `incCrashOutcome(String)` (copy :321-350). Add the new `incBotMessage` labels to its javadoc.
4. Tests:
   - `CrashBotDispatchTest` (fixture pattern `CashoutBotDispatchTest`). Replaying capture rounds
     gives:
     - one bet sent with the round sid and the frozen eid;
     - one cash-out with matching sid and eid;
     - `bot_bets_placed_total += 1` on ack;
     - `bot_winnings_total += 24000`;
     - `bot_crash_bets_total{outcome=cashout} += 1`.
     An eid-2 bet replayed through the L12 crash tick gives `crash += 1` and no 1703 sent. A bet
     with no ack before 1707 gives `unacked += 1`.
   - **Series pre-registration:** right after `initializeSubclass`, with no frame processed, all
     three `bot_crash_bets_total` series exist at 0 with the group tag.
   - The bet task, fired after `onBettingClosed`, sends nothing. `beforeReconnect` mid-flight sends
     nothing further, and a later tick for that sid sends no cash-out.
   - Silence: a fake clock with no frames triggers `triggerFullReconnect` at windows 1 and 2 and
     not at 3. One WARN is logged at window 1 with `subscribed=false`.
   - `BotFactoryCrashWiringTest` (pattern `BotFactoryCashoutWiringTest`).
   - `PerBotInfoLogGuardTest` lists the three new classes.

**Phase 3 verification:** full build green. Then a local smoke against 119 staging Club is
optional. The release verification below is the gate.

### Phase 4: Alert and docs (can follow Phase 3)

1. `prometheus/alerts.yml`: add `CrashBetsUnacked`, severity `warning`:
   ```
   sum by (environmentId, botGroupId) (increase(bot_crash_bets_total{outcome="unacked"}[15m]))
     / clamp_min(sum by (environmentId, botGroupId) (increase(bot_crash_bets_total[15m])), 1) > 0.5
   ```
   with `for: 15m`. It needs no Alertmanager route. It must satisfy `AlertRuleMetricsTest` and
   `AlertRulesAudienceTest`: the metric has to exist in `BotMetrics`, and the rule needs an
   audience label.
2. `CLAUDE.md`: add a short "Crash bot" subsection covering five points:
   - runner = protocol metadata;
   - the F-1 crashed-flag rule;
   - the AD-9 silence ladder armed from `onStart`;
   - the outcome metric;
   - "never bet off the snapshot".
   Correct the draft-era claim that the CASHOUT watchdog pattern catches a refused subscribe. It
   does not, and this bot does.

**Phase 4 verification:** full build green (alert-rule tests). `promtool check rules
prometheus/alerts.yml` returns `SUCCESS` if `promtool` is available.

---

## 6. Implementation Notes / Concerns

- **The crashed flag is the correctness crux** (F-1). Check `crashedFor(eid)` **before**
  comparing the multiplier on every tick. The frozen crash value is a real number that can exceed
  the target.
- **One atomic.** Every state change goes through the machine's CAS. Do not add `betLive` /
  `cashedOut` flags beside it (CASHOUT concern #1). The bet task, the 4 inbound workers and the
  silence task all touch the machine.
- **The decision is atomic, the send is not.** A cash-out decided on the last tick before the
  crash can reach the server after it. The server refuses it, there is no ack, and 1707 records
  `crash`. That is correct, and it is not an error to alert on.
- **A reset abandons a live bet without an outcome.** So `sum(outcomes) ≤ bets confirmed +
  unacked`. The gap equals bets interrupted by reconnects. Do not "fix" this by counting a fake
  outcome.
- **Ignore 1708 and 1716** by not registering them. Do not add catch-all classes. Do not
  deserialize `ps[]`. At prod it can carry every player's cash-out on every tick.
- **Ticks are 2/s/bot.** The tick handler does no allocation beyond the parsed frame, logs nothing
  above TRACE, and makes one volatile write for the watch. `incBotMessage("crashTick")` is a
  counter increment only.
- **Registry constructor change** touches 9 test files plus production wiring. The startup line
  must stay one line with the existing segments unchanged. Releasers diff it.
- **Exhaustive switch and validator boot check:** the enum constant, the factory throw arm and
  `CrashConfigValidator` land together in Phase 1.
- **`sessionCheckInProgress` skips a bet task.** That costs one round, and only when a deposit or
  re-sync is still running 0-4.5 s after 1705. With auto-deposit off this is a non-blocking drift
  read, so it is rare.
- **Staging RTP is meaningless** (F-5). The outcome *mix* is still a useful runner-reading sanity
  check while staging crash points stay fixed (Verification V-7, informational).
- Never delete game implementations. This plan only adds. Do not copy `dev-w79-avatar.js` or any
  credentials into the repo. Java 21. `bot-api` stays free of Spring.

---

## 7. Open Items

- **OI-1: real minimum and step for bets.** The server sends no list. Legacy always sent multiples
  of 5,000 in [20k, 200k], and humans sent 10k / 20k / 50k / 100k. If staging returns no ack for
  some rungs (`unacked` > 0 concentrated on small or odd amounts), tighten the group's
  `minBet`/`betIncrement`. Do not change code.
- **OI-2: access gate.** If 119 staging refuses `aviatorPlugin` on the Club env the way it refused
  Balloon/Soccer on the proxy env, only the back office can lift it. The bot will show
  `STARTED`, a WARN `subscribed=false`, and ladder reconnects.
- **OI-3: strategy family** (FIXED_LOW / CROWD / MOONSHOT, plus a per-runner `runner` metric label
  for the control-arm RTP check). This is a follow-up. It needs `CrashBehavior` exposed per group,
  or a `CrashStrategy` registry.
- **OI-4: participation rate.** Legacy skips rounds by an hour-of-day rate. Activation windows
  already cover hour-of-day bot counts. A per-round skip probability is a follow-up if the board
  looks too uniform.
- **OI-5: migrate `CashoutBetStateMachine.isReconnectRung` to `ReconnectLadder`.** This is a
  separate, test-pinned refactor and is out of scope here.
- **OI-6: fleet rollup.** `rounds=` / `staked=` in the 5-min rollup read 0 for CRASH groups, as
  for CASHOUT, because there is no `SessionAggregationService` feed. A cosmetic follow-up.
- **OI-7: prod.** Needs 119-prod whitelisting of the bot host and a `bot.ip` check (CLAUDE.md
  "bets never settle", BOT_IP trap). This is a separate release decision.
- **Out of scope:** jackpot and payout logic, error-frame modelling, dual bet panels,
  coordination, chat, UI changes. The game-type selector is server-driven, so `CRASH` appears
  automatically.

---

## Verification

Run on Bot-1 staging after deploy. Logs are in `/home/sgame/bot-java/logs/` and the container is
`bot-java-bot-manager-1`. Staging runs `BOT_LOG_LEVEL=DEBUG`, so DEBUG lines land in
`logs/detail/detail.log`. Phase split: V-1..V-4 apply from Phase 1, and V-5..V-11 need Phase 3.

```
BASE=http://localhost:8080
ENV=8ca14218-c98e-4734-a79e-c53732626337      # 119 Club env — NOT the proxy env d005157f
```

**V-0: the target env is the Club env.**
`curl -s $BASE/api/v1/environment/$ENV | jq -r '.productCode, .webSocketMiniUrl'`
Expect `P_119` and `wss://w79.sgame.club/websocket_mini`.

**V-1: the app is up.** `curl -s $BASE/actuator/health | jq -r .status`. Expect `UP` (HTTP 200).

**V-2: registry line.**
`docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized" | tail -1`
Expect exactly one line per boot. Every segment up to and including `CASHOUT 1 products [119]`
must be identical to the pre-deploy baseline, and the line must end `, CRASH 1 products [119]`.

**V-3: type listed.** `curl -s $BASE/api/v1/game/types | jq -r '.[].code' | grep -cx CRASH`.
Expect `1`.

**V-4: game record.** First check that one does not already exist:
`curl -s $BASE/api/v1/game/G4/P_119/$ENV | jq -r '.[] | select(.pluginName=="aviatorPlugin") | .id'`.
If that is empty, create it:
```
curl -s -X POST "$BASE/api/v1/game/G4/P_119/$ENV" -H 'Content-Type: application/json' \
  -d '{"name":"Aviator","gameType":"CRASH","pluginName":"aviatorPlugin","offset":1700,"md5":false}'
```
Expect HTTP 200 with `gameType:"CRASH"`, `offset:1700`. Record the id as `$GAME`.

**V-5: group registers, funds without auto-deposit, starts and subscribes.**
```
curl -s -X POST $BASE/api/v1/bot-group/ -H 'Content-Type: application/json' -d '{
  "name":"Aviator 119 Club","environmentId":"'$ENV'","gameId":"'$GAME'","botCount":3,
  "namePrefix":"w79avi","password":"<releaser-chosen>","autoDepositEnabled":false,
  "initialDeposit":20000000,"minBet":20000,"maxBet":200000,"betIncrement":5000}'
```
- Expect 200 with `autoDepositEnabled:false` and `targetStatus:"REGISTRATION_PENDING"`. Record
  `$G`.
- Poll `GET $BASE/api/v1/bot-group/$G` until `registeredCount == 3`.
- Then `POST $BASE/api/v1/bot-group/$G/start`, expect 200, and wait 90 s.
- `curl -s $BASE/api/v1/bot-group/$G/health | jq '{c:.connectedBots,d:.deadBots,s:[.bots[].status]}'`
  should show **3 connected, 0 dead, 3 × `CONNECTION_AUTHENTICATED`**. That status is set only in
  `onSubscribe`, so it proves 1700 was answered.
- If the bots stay `STARTED`: grep detail.log for `subscribed=false`. Then capture the raw frames
  for ≤ 30 s by raising `com.vingame.websocketparser.VingameWebSocketClient` to DEBUG and setting
  it back to INFO afterwards, the way the CASHOUT release did. Report a
  `[7,…,"…not permitted",{"cmd":1700}]` as OI-2 and stop there.

**V-6: no auto-deposit happened.** After 15 minutes:
`curl -s "$BASE/actuator/metrics/bot_auto_deposits_total?tag=botGroupId:$G&tag=outcome:success" | jq '.measurements[0].value'`
Expect `0.0`. The series is pre-registered, so a 404 is a FAIL.

**V-7: bets acked, cash-outs acked, losses counted** (15 minutes ≈ 18 rounds).
```
for M in bot_bets_placed_total bot_bet_amount_total bot_winnings_total; do
  echo "$M $(curl -s "$BASE/actuator/metrics/$M?tag=botGroupId:$G" | jq '.measurements[0].value')"; done
for O in cashout crash unacked; do
  echo "$O $(curl -s "$BASE/actuator/metrics/bot_crash_bets_total?tag=botGroupId:$G&tag=outcome:$O" | jq '.measurements[0].value')"; done
```
Expect:
- `bot_bets_placed_total ≥ 30`, `bot_bet_amount_total ≥ 600000`, `bot_winnings_total > 0`.
- All three outcome series return HTTP 200, which proves pre-registration.
- `cashout > 0`.
- `unacked / (cashout + crash + unacked) < 0.05`.
- Informational, not a gate (F-5): with staging crash points fixed at 2.86 / 11.59,
  `crash / (cashout + crash)` should be ≈ 0.27. A value in [0.10, 0.45] confirms the bot reads its
  own runner. Record it.

**V-8: cash-out payouts are gross and match the ack.**
`grep -h "first crash cash-out ack" /home/sgame/bot-java/logs/detail/detail*.log | head -3`
Expect at least 1 line, and on each line `wm ≈ b × odd` within 1%. Also run
`grep -h "first crash loss" …/detail*.log | head -3`. If `crash > 0` in V-7, expect at least 1
line showing `crashed=true`.

**V-9: rounds reset.**
`curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$G&tag=cmd:crashRoundEnd" | jq '.measurements[0].value'`
Expect `≥ 45` after 15 minutes (3 bots × ≥ 15 rounds). A second reading 5 minutes later should
have grown by `≥ 12`.

**V-10: no WARN/ERROR storm, no per-bet INFO.**
```
grep "\"botGroupId\":\"$G\"" /home/sgame/bot-java/logs/console.log | wc -l
grep "\"botGroupId\":\"$G\"" /home/sgame/bot-java/logs/console.log | grep -cE '"(WARN|ERROR)"'
```
Expect the first count `< 20` (lifecycle lines only, flat across a second reading 5 minutes later)
and the second `≤ 3`.

**V-11: no reconnect churn, no extra HTTP.**
```
curl -s "$BASE/actuator/metrics/bot_reconnects_total?tag=botGroupId:$G" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_watchdog_expired_total?tag=botGroupId:$G" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/gateway_budget_window_requests?tag=environmentId:$ENV" | jq '.measurements[0].value'
```
Expect reconnects `≤ 3` (a 404 also passes) and watchdog expiries absent or `0`. Expect the
budget window `< 100` and flat (±20) between two readings 5 minutes apart, taken after the login
burst.

**State to leave:** the group stays ACTIVE as the 119 staging Aviator group. Report `$GAME`, `$G`
and the money moved (3 × 20,000,000 registration funding, and no auto-deposit).

---

## Amendment — 2026-10-06 (Compliance Architect)

Two technical oversights in the plan, both found during compliance review of
`045670c..bd24e8f`. The text above is unchanged; where this section disagrees with it, this
section is authoritative.

**AM-1: V-5's "registered" poll did not prove the group was startable.** V-5 said to poll until
`registeredCount == 3` and then `POST /start`. That gate is wrong. `RegistrationWorker` writes
`registeredCount = k` (`persistProgress`) **before** it names and funds index `k`. It clears
`registrationState` only in `recordCompletion`, once `depositedCount` also meets the target,
because this group sets `initialDeposit` and AD-5 makes funding part of completion.
`BotGroupBehaviorService` refuses `/start` with a 400 ("still registering") while
`registrationState` is pending, and `BotGroupMapper.renderedStatus` shows that state as
`targetStatus: "REGISTRATION_PENDING"`. So `registeredCount == 3` can be read while index 3 is
still being named or funded, and a `/start` sent then fails. Replace that V-5 bullet with:

- Poll `curl -s $BASE/api/v1/bot-group/$G | jq '{t:.targetStatus,r:.registeredCount,d:.depositedCount,e:.registrationError}'`
  until `targetStatus` is **no longer** `REGISTRATION_PENDING` **and** `depositedCount == 3`. If
  `targetStatus` becomes `REGISTRATION_FAILED`, report `registrationError` and stop: do not
  retry blindly, because a deposit with an unknown outcome is money.

The rest of V-5 is unchanged.

**AM-2: AD-11 "every 1707 runs `roundsObserved++` and `onNewSession()`" assumed every 1707 lies
between rounds.** §2 of this plan notes that ws-parser runs 4 inbound workers per client, so a
bot's frames are handled out of order. A 1707 whose `sid` is older than the machine's
(`onRoundEnd` → `None`) has, by definition, been overtaken by a later round start. Running the
session check on it could set `sessionCheckInProgress` inside the next round's bet window and
skip that round's bet (AD-10 step 1). That window is exactly what AD-11's "no bet is in flight"
reasoning relies on not happening. Corrected rule: **a 1707 that the machine ignores as stale
counts no round and runs no balance check.** It still records the frame for the silence watch
and still increments `bot_messages_total{cmd="crashRoundEnd"}`, so V-9 is unaffected. The old
bet's outcome is not lost, because the newer round start already reported it as `abandoned`
(AD-8, missed-1707 row). Implemented in `CrashBot.onRoundEnd` and pinned by
`CrashBotDispatchTest` "a stale 1707 counts no round".
