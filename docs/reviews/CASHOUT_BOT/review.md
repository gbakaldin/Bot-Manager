# Code Review — CASHOUT_BOT (Phases 1–3)

Branch: feature/cashout-bot
Reviewed diff: `git diff feature/bot-provisioning..feature/cashout-bot` (10 commits, `5e28aa1..ed85d86`)

## Verdict

CHANGES_REQUESTED

One `bug`: `reset()` drops the bound sid of a live bet. A late frame from that abandoned
bet can then bind the next bet. The rest is advisory. The single-CAS state machine is
correct under the four-worker concurrency it was built for.

## Findings

### [bug] `reset()` forgets the bound sid, so a late frame from the abandoned bet binds the next bet
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/cashout/CashoutBetStateMachine.java:447`

`onTimeout()` adds a LIVE/CASHING sid to the `recentlyEnded` ring before it goes to IDLE.
`reset()` does not: it builds `new Idle(current.memo(), …)` from any state. `reset()` runs:

- from `beforeReconnect()`, on every WS-disconnect reconnect that lands mid-bet. Legacy saw
  about 13.8k disconnects in 28 h (F-7), so this is the normal case, not an edge case;
- from `onSubscribe()`, on every subscribe reply. That includes the periodic-logout
  `Bot.restart()` path, which never calls `beforeReconnect()`, and the supplier's
  re-subscribe fallback.

The bet goes on running on the server after the reset. Its frames are ignored while the
machine is IDLE. The next bet is placed 0.5–4.5 s later, and the bet it replaced can run
for up to about 20 s more. In PLACED, the first frame whose sid is not in the ring and
whose stake is absent or equal to the plan binds that sid:

- X502 frames carry no `b`, so any X502 binds.
- An equal stake is likely: few stakes are eligible, and every bet is a probe at the
  cheapest stake once the ladder count is >= 1.

What happens at runtime:

1. The new plan is driven by the old bet's frames. Its target is applied to the old bet,
   and a cash-out for the old sid may be sent.
2. The new bet's real frames, a different sid, are dropped as "another sid". The new bet
   runs to burst with no cash-out.
3. When the old bet ends, the machine goes IDLE and places the next bet. The new bet, now
   unmanaged, is still streaming and can bind that next bet the same way.

So the mis-binding chains from one bet to the next for as long as stakes keep matching.
The accounting attributes the old bet's winnings to the new plan's stake, and the RTP is
distorted, because one bet of each pair is never cashed out.

`CashoutBetStateMachineTest.Reset.fromEveryState` misses this because it only feeds the
old sid while the machine is still IDLE, never after the next `tryPlace`.

Fix shape (a few lines): in `reset()`, remember the sid of a LIVE/CASHING state, the same
way `onTimeout()` does, e.g. `memo = current is Live/Cashing ? memo.remember(sid) : memo`.
Then add a test: bind sid 51, `reset()`, `tryPlace`, feed a frame for sid 51 with the same
stake, and assert `NONE`. A reset from PLACED has no sid to remember. That residual case is
covered by the next smell.

### [smell] The timeout backoff has no jitter, so a silent server keeps a whole group in lockstep and every reconnect rung is a burst of re-logins
`CashoutBetStateMachine.java:413`

`long delay = Math.max(timeoutBackoffMillis, drawDelay())`: with backoff 30 s and
`maxDelayMs` 4.5 s, this is always exactly 30 s. When a server goes silent, every bot in a
group times out within a few hundred ms of the others (20 s frame timeout from roughly
aligned bets). They all wait the same 30 s, re-probe together and time out together. So
rungs 3, 6 and 12 each fire `triggerFullReconnect` for all N bots within about one second,
three times in about 10 minutes. Each one is a PRIORITIZED re-auth plus a PRIORITIZED WS
upgrade.

AD-9's "well under 1% of the budget" figure is an average. The burst is N requests at
once, and at a few hundred bots that is a large share of the 1k/5 min window. The budget
limiter keeps the instance under the Cloudflare cap, but it does so by queueing, which
starves every other group on the environment for the duration.

Fix shape: `timeoutBackoffMillis + drawDelay()` (the plan says "at least" the backoff, so
this complies), or add a jitter proportional to the backoff. Both spread out the timeouts
and the reconnects.

### [smell] `onBetEnded` cancels whatever watchdog is current, which can be the next bet's
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/CashoutBot.java:325`

The terminal CAS (machine to IDLE) and `cancelWatchdog()` are not atomic. If the handler
thread stalls between them for longer than `nextBetAt − now` (at least 500 ms), the bet loop
can place bet N+1, and its supplier arms a new watchdog. `cancelWatchdog()` then cancels
that new watchdog. Bet N+1 now has no watchdog, and nothing re-arms one: the self-healing
re-check only runs when a watchdog fires. If bet N+1 goes silent, the bot stays "in flight"
forever, with no bets, no timeout and no reconnect.

The window is small, but the consequence is a permanent per-bot wedge. The cancel is not
needed for correctness: a watchdog that fires on IDLE is already a no-op, and one that
fires on a newer bet re-checks against that bet. Either drop the cancel, or cancel only
the task that was armed for the ending plan, e.g. keep `(plan, future)` and compare.

### [smell] A frame with no `sid` binds as sid 0
`CashoutBetStateMachine.java:308`, `Win79CashoutFrame` (`long sid`, primitive)

A missing `sid` deserialises to 0. The subscribe reply shows that 0 is the server's "no
bet" value (`"sid":0`), and the plan lists the shape of a rejected-bet reply as unknown.
If that reply arrives on X501/X502 without a sid (and without `b`), it binds the PLACED
plan to sid 0. If the server did accept the bet, its real frames are then dropped as
"another sid", and the bet runs uncashed until the 20 s timeout.

Refuse to bind `sid <= 0` in PLACED, and trace it like any other ignored frame.

### [smell] `reset()` replaces a timeout backoff with a 0.5–4.5 s pause
`CashoutBetStateMachine.java:447`, called from `CashoutBot.onSubscribe`

After a ladder reconnect the machine is IDLE with `nextBetAt` at least 30 s away.
The subscribe reply's `reset()` replaces that with `now + U(0.5, 4.5) s`, so the first
probe after every reconnect skips the backoff. The ladder count survives, so this is not a
hot loop, but it is not what the property comment promises ("the next bet waits at least
this long"). Keep `max(current nextBetAt, now + drawDelay())` when resetting from IDLE.

### [smell] `bot.cashout.reconnect-after-timeouts=0` silently means 3, while the machine documents `<= 0` as "disabled"
`CashoutBot.java:182-184`; `CashoutBetStateMachine` constructor javadoc; `application.properties`

Dev's deviation, "0 means default", is reasonable for the two durations: a 0 s frame
timeout is meaningless, and the machine rejects it. For `R` it hides a capability the
machine has. An operator who sets 0 to stop a reconnect storm gets the default ladder
with no log line. Pick one of these:

- document in `application.properties` that `<= 0` means default (3) and that reconnects
  cannot be disabled, and drop "disables" from the machine's javadoc;
- or pass `R` through unchanged and let 0 disable reconnects.

Either is fine. Today the two layers contradict each other.

### [smell] The re-subscribe fallback in `bet()` is reachable on a live socket, and its reply resets the machine
`CashoutBot.java:475-479`

Judged acceptable as a deviation: the engine forbids a null supplier, and a subscribe is
the least harmful frame to send. But the "harmless to send twice" claim depends on
`onSubscribe`, which runs on every subscribe reply (`OnMessage` is a persistent pipeline
processor). Each reply runs `machine.reset()` and `onNewSession()`.

The realistic trigger is not the reconnect race. It is the fallback's `placeIgnoringDelay`
re-drawing a stake that the balance does not cover: the condition only checked the floor.
If a bet gets placed before the reply arrives, the reply abandons it. The first finding's
fix makes that abandonment safe. With that fix in, this is fine. Optionally, the fallback
could pick the cheapest stake so that it does not fail on balance.

### [smell] The "no cash-out after the end" guarantee holds in the machine, not on the wire
`CashoutBot.java:299` / `CashoutBetStateMachine.java` class javadoc

Thread A's CAS LIVE→CASHING and thread B's CAS CASHING→IDLE (final frame) can both succeed
before thread A runs `sendCashout`. The cash-out for an already-ended sid then goes out
after the bet has ended. The machine still guarantees at most one cash-out per bet, and the
server will just reject the stale one, so this is harmless. But the plan's sentence "That is
why cash-out-after-end cannot happen" overstates it. Reword the javadoc, so that no one
later builds on the stronger claim (e.g. by treating a cash-out error reply as a fault).

### [style] `allowedBets()` sorts the raw list, so a `null` element throws an NPE
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g4/win79/cashout/Win79CashoutSubscribeResponse.java:45`

`CashoutBot.eligibleStakes` filters out `null` stakes, but the list was already sorted
before it gets there. `[1000, null]` would throw inside the frame handler. That handler
runs before `machine.reset()`/`onNewSession()`, but after `markConnectionAuthenticated()`,
so the bot would be authenticated and never bet. Filter nulls before sorting.

## Notes

- **The concurrency core is sound.** It uses one `AtomicReference` over immutable
  records, and every transition is a retried CAS. SEND_CASHOUT is emitted only on the LIVE
  or PLACED → CASHING edge, and CASHING never emits it again. I checked the four-thread
  interleavings of progress, crossing and final frames, and of the watchdog against a
  frame: none produces two cash-outs or two ENDED results. Draws inside a retried CAS loop
  are harmless, and `java.util.Random` is thread-safe.
- **Deviation: the watchdog is armed once per bet and re-checked at expiry.** Plan AD-9
  says it is re-armed on every frame. This deviation is better than the plan: it gives the
  same "time since last frame" semantics without a cancel and schedule per progress tick.
  It also makes a stale watchdog harmless, which is what makes the cancel in the third
  finding unnecessary.
- **Hot loop: not recreated.** The watchdog is never armed for an idle bot. The ladder count
  is reset only by a bound frame, and the rung arithmetic (`isReconnectRung`) is correct for
  `R·2^k`, `k ≤ 5`, then multiples of `R·32`. `triggerFullReconnect`'s `reconnecting`
  CAS and its DEAD guard stop a rung from stacking onto a reconnect that is already in
  progress. The remaining budget concern is the burst shape in the second finding, not the
  rate.
- **The bot can stall silently after a reconnect.** If the subscribe goes unanswered (the
  zone fallback, or a server that accepts the socket and ignores the subscribe), the bot
  never bets, so it never arms a watchdog and never reconnects. It stays in
  `AUTHENTICATING_CONNECTION`. This is a choice the plan accepts (AD-9, the "zone fallback
  is silent" risk), and it is the opposite failure to a hot loop. I am noting it so that
  the Phase 4 alerting covers it.
- **Metrics pre-registration is correct.** `initCashoutSeries()` runs under the same MDC
  (`mdcConsumer`) as every `incCashoutOutcome` site; the watchdog task is `mdcWrap`ped. One
  advisory: it is called only on subscribe. Calling it from `initializeSubclass()` as well
  (MDC is set there too) would make the three series visible for a group stuck before
  subscribe.
- **Logging tiers are respected.** `CashoutBot` has no INFO call, and both new classes are
  pinned in `PerBotInfoLogGuardTest`. The only per-bot WARNs are "no eligible stake" (once
  per subscribe), "cash-out frame not sent" (latched to once per bot) and the existing
  `triggerFullReconnect` WARN (bounded by the ladder). Per-bet lines are TRACE, and the two
  one-shot terminal-frame lines are DEBUG.
- **No regressions found** in the registry (new table, `requireGameType` enforced, startup
  line extended at the end only), zone resolution (CASHOUT joins the mini branch, with a
  test), the validators (CASHOUT validator shipped with the enum; a boot test uses the real
  `@Component` set), or the `BotFactory` exhaustive switch. The other `GameType` branches
  in `BotGroupBehaviorService` (strategy assignment at :1133/:1165/:2015/:2053) treat
  CASHOUT as "neither", which is right because `CashoutBot` reads no strategy.
- **Resources.** The watchdog scheduler is shut down in `cleanup()`. `beforeReconnect()`
  cancels the watchdog and clears the parked plan. `Bot.restart()` (periodic logout) does
  not call `beforeReconnect()`. Today the subscribe reply's `reset()` covers that path,
  which is a second reason to land the first finding's fix.
