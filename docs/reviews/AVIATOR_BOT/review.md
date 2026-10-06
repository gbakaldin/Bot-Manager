# Code Review — AVIATOR_BOT

Branch: feature/aviator-bot
Reviewed diff: `git diff 045670c..feature/aviator-bot` (14 commits, b260afc..bd24e8f)

## Verdict

CHANGES_REQUESTED

There are three `bug` findings. None of them can lose real money, because the server settles every bet. What they get wrong is the bot's own picture of events: its local balance, its outcome and winnings metrics, and its watchdog and escalation signals. The core concurrency design holds. One CAS'd `AtomicReference` in `CrashRoundStateMachine` makes "at most one cash-out per bet" and "at most one outcome per bet" true by construction. The crash flag is checked before the multiplier, and only the plan's own `eid` is ever read. The eid 1 = Jake (`jOdd`/`jFi`) and eid 2 = Neytiri (`nOdd`/`nFi`) mapping in `Win79CrashTick` / `Win79CrashRoundEnd` matches the user-confirmed mapping.

Severity order: B1 > B2 > B3 > smells > style.

## Findings

### [bug] B1 — The silence task keeps firing on a DEAD or already-reconnecting bot: it inflates `bot_watchdog_expired_total` and arms group-scoped DEBUG
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/CrashBot.java:497-533`

`onSilenceCheck` re-arms itself unconditionally (`armSilenceWatch(watch.windowMillis())`, line 515). On every ladder rung (silent windows 1, 2, 4, 8, 16, 32, then every 32) it calls `escalate`. That method increments `bot_watchdog_expired_total` and calls `scopedDebugEscalator.onWatchdogExpiry(groupId)` **before** `triggerFullReconnect`. But `triggerFullReconnect` returns at once when the bot is DEAD (`Bot.java:981`) or a reconnect is already running (the `reconnecting` CAS, `Bot.java:982`). The only guard on the task is `isStopped()`, and DEAD is not stopped.

`BettingMiniGameBot`'s watchdog is one-shot: it re-arms only on a frame. `CashoutBot`'s is armed per bet. CrashBot is the first bot whose watchdog re-arms itself, so this gap is new.

Failure scenarios:
- **During a reconnect.** A 119 outage puts a bot into the ~51-minute reconnect loop. The silence task keeps ticking at the 180 s window and hits rungs 2, 4, 8 and 16 while the loop runs. That is 4 extra `bot_watchdog_expired_total` increments and 4 escalator calls per bot, and none of them causes a reconnect. On a 2-3k-bot group, the watchdog-expiry metric stops meaning "watchdog fired a reconnect".
- **Forever after DEAD.** One bot exhausts `MAX_RECONNECT_CYCLES` and goes DEAD in a group that stays below `bot.group.dead.threshold`, so the group is never torn down. The bot's scheduler keeps a task alive for the life of the group. Every 32 windows (~96 min) it increments the counter and calls `onWatchdogExpiry`, which re-arms 15-minute scoped DEBUG for the **whole group**, within the escalator's 25% duty cycle. So one dead bot keeps putting thousands of healthy bots into track 2 at DEBUG, indefinitely.

Fix shape: in `onSilenceCheck`, return without re-arming when `getStatus() == BotStatus.DEAD`. The next `onStart` re-arms it if the bot is ever revived; today nothing revives a DEAD bot short of a group rebuild. Do the metric increment and escalator call only when a reconnect will actually start. Either skip `escalate` while the base class reports a reconnect in progress (this needs a `protected boolean isReconnecting()` on `Bot`), or have `triggerFullReconnect` return whether it fired and count only then. Keep the re-arm-before-escalate order for the non-DEAD case.

### [bug] B2 — A cash-out ack handled after its round end is dropped: a paid bet is recorded as `crash` and its `wm` is never credited
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/crash/CrashRoundStateMachine.java:408-426` (the `Cashing` arm, line 417), with `:387-399`; caller `CrashBot.java:337-360`

ws-parser 3.0.5 runs four inbound workers per client, so one bot's frames are handled concurrently and out of order. The plan states this (§2) and the machine's own javadoc relies on it for ticks. The CASHING → round-end transition does not allow for it. `onRoundEnd` takes `Cashing` straight to `Waiting(sid)` and emits `Ended(CRASH)`. A `1703` handled after that finds `Waiting`, fails the `instanceof Cashing` test and returns `NONE`, and `CrashBot.onCashoutAck` logs it at TRACE as "ignored".

Failure scenario: the bot's runner is the last one still flying. It crosses the bot's target on one of its final ticks, the bot sends `1703`, and the server accepts it, writing `1703 {wm}` and then `1707` shortly after when that runner crashes. Worker A takes `1707` and worker B takes `1703`, and A wins the race. Result:
- `bot_crash_bets_total{outcome="crash"}` is +1, but the bet was really a cash-out.
- `bot_winnings_total`, `cumulativeWinnings` and `expectedCurrentBalance` all miss `wm`. Local balance drifts low until the 1% drift resync, and RTP computed from `bot_winnings_total / bot_bet_amount_total` reads low.
- `onNewSession()` runs (from `onRoundEnd`) against a balance that is missing the payout. It can trigger an unnecessary drift read, or even a deposit decision on a balance that is not really low. `depositIsWarranted` re-reads the server first, which limits the damage.

The window is the cash-out round trip (~320 ms in the capture) against the gap between the last crash and `1707`. That is rare per bet, but the bot places one bet per round per bot across the fleet, and this is the exact path the plan names as the authority for winnings ("winnings = 1703 wm").

Fix shape: keep the at-most-one-outcome guarantee by not emitting from `Cashing` at the round end. Either:
- (a) On `1707`, move `Cashing` to a new `Closing(sid, plan)` state that still accepts `onCashoutAck(eid, b)` and emits `Ended(CASHOUT)`. The next `onRoundStart` resolves a still-`Closing` bet as `abandoned → CRASH`, exactly as it already does for `Cashing`. `onRoundEnd` would then return a `RoundClosed` for the round boundary (rounds counted, session check run) without an outcome. Or:
- (b) Let `Waiting` carry the last `Cashing` plan, and let a matching ack in `Waiting` emit a *winnings-only* action. This is weaker, because the CRASH outcome has already been counted.

Option (a) is the clean one: a single CAS still decides the one outcome. Add a test that delivers `1707` before `1703` while in CASHING.

### [bug] B3 — The bet task outlives client close on the periodic-logout and full-reconnect paths: the local balance is debited for a bet that was never sent, and the next subscribe silently abandons it
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/CrashBot.java:388-428`, `:580-590`; paths `Bot.java:437-457` (`restart`) and `BotGroupBehaviorService.java:3847-3861` (periodic logout)

CASHOUT's bet loop is a `sendAsync` stage on the client, so closing the client kills it. CrashBot moved the bet onto its own per-bot scheduler (AD-10). That is a deliberate change from CASHOUT, but only `beforeReconnect()` cancels the task, and `beforeReconnect` is called from just one place: `tryReconnectWs`, **after** the new socket has connected.
- **Periodic logout.** The path is `bot.logout()` (closes the client) → `Thread.sleep(reconnectDelaySeconds)` → `bot.restart()`. `restart()` never calls `beforeReconnect()`. If the logout lands inside a round's 0-4.5 s bet delay (about 1 round in 10), the scheduled `placeBet(sid)` fires during the sleep. `machine` is still `Open(sid)`, so `tryPlace` succeeds, `creditBalance(amount)` debits `expectedCurrentBalance` and bumps `totalBetsPlaced`, and `send` goes to the closed `sendChannel` (ws-parser drops it with its "not connected" WARN). The machine is now `PLACED`. After `restart()`, the new subscribe reply calls `machine.reset()`, which abandons the bet without an outcome. Net effect: one stake missing from the local balance, and one phantom bet in `/health`'s `totalBetsPlaced`.
- **Full reconnect.** Same shape. `triggerFullReconnect` closes the client, then `performReauth()` and the connect can take seconds or more under the budget. A bet task firing in that window debits locally for a frame sent to a dead channel. `beforeReconnect()` resets the machine only after the new socket is up.

Each event is small, and the drift resync eventually corrects it, but this happens on every periodic-logout cycle that hits a bet window.

Fix shape (either works; the first is the smaller change):
1. In `placeBet`, before `tryPlace`, bail out if the captured channel is not open (`VingameWebSocketClient ch = sendChannel; if (ch == null || !ch.isOpen()) return;`).
2. Override `stop()`, which both `logout()` and `cleanup()` call, to `cancelBetTask()` and `machine.reset()` after `super.stop()`.

### [smell] S1 — `schedule()` has a check-then-act race against `cleanup()`'s `shutdownNow()`
`CrashBot.java:461-466`, `:593-604`

`scheduleBet` / `armSilenceWatch` call `schedule` while holding `taskLock`. `cleanup()` cancels the futures under `taskLock` but calls `scheduler.shutdownNow()` outside it. A round start that arrives on an inbound worker while a group is stopping can pass `isShutdown() == false` and then call `scheduler.schedule(...)` after the shutdown. That throws `RejectedExecutionException` out of the `mdcConsumer` handler into ws-parser's processor during teardown. The effect is only noise, but a frame handler is not supposed to throw (the class's own rule at line 436). Fix: catch `RejectedExecutionException` in `schedule()` and return `null`, or call `shutdownNow()` inside the `taskLock` block. CASHOUT's `armWatchdog` has the same shape; it is worth fixing both together.

### [smell] S2 — The tick path's "allocates nothing" claim is false: every tick builds a Micrometer counter lookup
`CrashBot.java:291-297`

`metrics.incBotMessage("crashTick")` runs `Counter.builder(...).tags(mdcTags())`, which does 6 `MDC.get` calls, an `ArrayList`, `Tags.of` and a registry lookup, on every `1709`. At ~2 ticks/s/bot and 2-3k bots per environment, that is ~5k builder+lookup cycles per second per environment for one message counter. It follows the existing per-message pattern, so it is not a regression in kind. But ticks are by far the highest-rate frame any bot handles, and the javadoc says the opposite. Either correct the javadoc, or resolve the `crashTick` counter once per bot (in `initializeSubclass`, under the same MDC) and call `increment()` on the cached `Counter`.

### [smell] S3 — Silent window 1 with a reconnect logs two per-bot WARNs
`CrashBot.java:505-518`

On the first silent window, `silent.first()` and `silent.reconnect()` are both true. `onSilenceCheck` logs its WARN "silent window 1, …, reconnecting", and `triggerFullReconnect` then logs its own WARN "full reconnect triggered — watchdog: 1 silent windows". When a whole group goes silent, Loki receives 2 × N WARN lines in one burst. Drop the ", reconnecting" WARN when a reconnect follows (log it at DEBUG), or let `triggerFullReconnect`'s WARN carry the `subscribed=` detail.

### [style] Y1 — A generic class hardcodes runners 1 and 2 in its TRACE line
`CrashBot.java:306`

`tick.multiplierFor(1), tick.multiplierFor(2)` is Avatar-specific in a class that is otherwise `runnerCount()`-generic. A single-runner provider would always log `x0` for runner 2. Log `machine.currentPlan()`'s own `eid` value instead, or loop over `machine.runnerCount()`.

## Notes

- **At most one outcome per bet: verified.** Every `Ended` comes from a successful CAS that leaves an in-flight state (`Placed`/`Live`/`Cashing`) for a non-in-flight one: `onRoundStart`'s `abandoned`, `onTick`'s crash, `onCashoutAck`, and `onRoundEnd`. `reset()` leaves without emitting. Two ticks racing produce at most one `SendCashout`. A stale tick from before the crash, handled after the crash tick, meets `Settled` and does nothing. `Cashing` ignores the crash flag, so the server decides. B2 is the one place where that design runs into the out-of-order workers.
- **Cash-out only on the bot's own runner, never after its crash flag: verified** (`CrashRoundStateMachine.java:363-374`). An unknown `eid` reads as crashed with a multiplier of 0 (`Win79CrashTick.java:63-82`), so it can never cash out.
- **Money accounting matches the brief.** The local debit happens at send (`creditBalance`), `bot_bets_placed_total` / `bot_bet_amount_total` are counted on the `1702` ack (`CrashBot.java:272-275`), and winnings are `round(wm)` from `1703` only (`CrashCashoutAck.winningsFor`). B2 and B3 are the exceptions.
- **Metrics pre-registration is correct.** `initCrashSeries()` runs inside `Bot.initialize()`, after `BotMdc.set` and before `BotMdc.clear`. That is the same MDC `mdcSnapshot` captures, and `mdcConsumer`/`mdcWrap` apply it to every later increment, so the zero series and the incremented series carry the same tags. All three `outcome` values are covered, which `CrashBetsUnacked` depends on.
- **Log tiering is clean.** There is no INFO in `CrashBot`, `CrashRoundStateMachine` or `RoundSilenceWatch` (guarded by `PerBotInfoLogGuardTest`). The per-tick line is TRACE behind `isTraceEnabled`. The one-shot DEBUG lines are latched per bot. The group-level line goes through `GroupLifecycleAggregator`. The send-failure WARN is latched once per bot. Apart from B1's escalation side effect and S3, nothing here adds to INFO volume.
- **Resource lifecycle.** `cleanup()` cancels both tasks and calls `shutdownNow()` on the per-bot scheduler, and `ScheduledThreadPoolExecutor` starts no thread until the first `schedule`. A bot whose `initialize()` fails after `initializeSubclass` therefore holds no thread, only an idle executor object. The silence task is the one long-lived task, and B1 covers it.
- **Thread-safety of the fields.** `machine`, `watch`, `request` and `offset` are written once in `initializeSubclass`, before the client connects and the scheduler runs. The executor/ws-parser hand-off publishes them, the same pattern `CashoutBot` uses. `ladder`, `sendChannel` and `sendMapper` are volatile and are read once into locals before use. `java.util.Random` is thread-safe, so `onRoundStart` drawing from it on a worker thread while `tryPlace` draws on the scheduler is fine.
- **Changes from CASHOUT.** The one structural change (scheduler-driven bet instead of `sendAsync`) is justified by AD-10, and it is what makes B3 possible. Everything else follows CASHOUT closely: the `onNewSession` copy, `escalate`, send-channel capture, `beforeReconnect`/`cleanup` overrides and the `markConnectionAuthenticated` order. Arming the silence watch from `onStart` rather than from the subscribe reply is the right fix for CASHOUT release finding 2.
- `onRoundEnd` → `onNewSession()` runs a possibly blocking gateway read and deposit on an inbound worker, as CASHOUT does. `sessionCheckInProgress` makes the next round's bet task skip its round rather than race it. That is acceptable, but it costs one of the four workers for the duration of the read.
