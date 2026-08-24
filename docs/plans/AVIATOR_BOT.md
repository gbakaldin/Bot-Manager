# Aviator (crash) bot — draft

**Status: DRAFT sketch, not an architect plan.** Written 2026-08-24 to carry
context into a fresh session. No phases, no verification steps, no ADs yet — if
this turns out to be bigger than it looks, run the architect over it first.

Goal: a crash-game bot so new brands can be brought up quickly. Behaviour is
deliberately primitive: **pick a bet, pick a target multiplier, cash out the
moment the live multiplier reaches it.**

---

## Why this is mostly assembly, not new machinery

`SendAsync` in websocket-parser 3.0.5 already provides the only primitive that
matters. It schedules a runnable at a fixed rate and on each tick evaluates:

```java
if (!(mode.shouldSend(sendingIteration.get()) && conditionSupplier.get() && gateMet)) {
    return;                      // no send; wait for the next tick
}
```

So `buildMessage().condition(...)` **is** "wait until predicate, then send", and
`SlotMachineBot` already uses it in the `INFINITE` + condition + in-flight-guard
form that gives one action per round across many rounds. Aviator is that same
form three times over.

## The three gates

| Gate | Fires | Condition (sketch) | Parks for the supplier |
|---|---|---|---|
| **join** | once per round (or once per session — see Open Questions) | round is in its joinable phase, and we have not joined *this* round | — |
| **bet** | once per round | joined, betting window open, no bet in flight, `expectedCurrentBalance >= amount` | **bet amount + target multiplier** |
| **cash out** | at most once per round | a bet is live, not already cashed out, `currentMultiplier >= target` | — |

Everything else is one `onMessage` that writes the live multiplier into an
atomic, and one that resets state when the round ends.

## State

```java
private final AtomicBoolean  joined        = new AtomicBoolean(false);
private final AtomicBoolean  betInFlight   = new AtomicBoolean(false);
private final AtomicBoolean  betLive       = new AtomicBoolean(false);   // server accepted the bet
private final AtomicBoolean  cashedOut     = new AtomicBoolean(false);
private final AtomicLong     currentMultiplier = new AtomicLong(0);      // scaled int, see below
private final AtomicReference<Optional<RoundPlan>> pending =
        new AtomicReference<>(Optional.empty());

/** Drawn ONCE per round at bet time and frozen. */
private record RoundPlan(long betAmount, long targetMultiplier) {}
```

**Store the multiplier as a scaled long, not a double** (e.g. hundredths:
`2.35x → 235`). The comparison runs on every poll tick of every bot; integer
compare is exact and avoids the `>=` edge cases a float threshold invites.

## Scenario skeleton

Model on `SlotMachineBot.botBehaviorScenario()` (`bot-engine/.../core/SlotMachineBot.java:442`).

```java
return pipeline(buildContext("[Aviator][" + game.getName() + "]", mapper))
        .waitFor(1_000L)
        .send(() -> request.subscribe(gid))
        .waitForMessage(cmd(AviatorMessageTypes.SUBSCRIBE_CMD).and(typeOf(RECEIVED)))
        .onMessage(AviatorSubscribeResponse.class, mdcConsumer(this::onSubscribe))

        // gate 1 — join
        .sendAsync(buildMessage()
                .messageSupplier(mdcSupplier(join()))
                .mode(INFINITE)
                .condition(mdcSupplier(joinCondition()))
                .interval(resolveJoinInterval(), MILLISECONDS)
                .build())

        // gate 2 — bet
        .sendAsync(buildMessage()
                .messageSupplier(mdcSupplier(bet()))
                .mode(INFINITE)
                .condition(mdcSupplier(betCondition()))
                .interval(resolveBetInterval(), MILLISECONDS)
                .build())

        // live multiplier feed — TRACE only, see below
        .onMessage(MultiplierUpdateMessage.class,
                   mdcConsumer(m -> currentMultiplier.set(m.getScaledMultiplier())))

        // gate 3 — cash out
        .sendAsync(buildMessage()
                .messageSupplier(mdcSupplier(cashOut()))
                .mode(INFINITE)
                .condition(mdcSupplier(cashOutCondition()))
                .interval(CASHOUT_POLL_MS, MILLISECONDS)
                .build())

        .onMessage(AviatorRoundEndMessage.class, mdcConsumer(this::onRoundEnd))
        .compile();
```

## The park-and-pop idiom — copy it, it is load-bearing

`condition()` and `messageSupplier()` are **two separate calls on two separate
ticks**. The slot bot resolves this by having the condition *park* its decision
and the supplier *pop* it:

```java
// in betCondition()
pending.set(Optional.of(new RoundPlan(amount, target)));
return true;

// in bet()
Optional<RoundPlan> popped = pending.getAndSet(Optional.empty());
long amount = popped.map(RoundPlan::betAmount).orElseGet(this::chooseBet);   // race fallback
```

The fallback is not defensive padding — `beforeReconnect()` can clear the parked
value between the two calls, and **the engine forbids a null supplier result**.
See `SlotMachineBot.spin()` for the precedent, including its `log.debug` on the
fallback path.

### The trap this creates for aviator specifically

`SlotMachineBot.spinCondition()` calls `chooseBet()` **on every tick**, which is
fine there because the value is only used if the gate passes.

**Do not do that with the target multiplier.** If the target is re-drawn on each
poll, the cash-out threshold jitters every tick and the bot effectively cashes
out at the *minimum* of all draws — RTP collapses toward 1.0x and the configured
distribution means nothing. Draw the target **once**, at bet time, into
`RoundPlan`, and read only that for the rest of the round.

## Reset — the correctness crux

`onRoundEnd` (crash or settle) must clear `joined`, `betLive`, `cashedOut`,
`currentMultiplier`, and `pending`. If `cashedOut` or the multiplier survives
into the next round, the next bet cashes out instantly against a stale value.

**Also reset in `beforeReconnect()`** — `SlotMachineBot:469` does, and
reconnects are frequent enough on these boxes that this is a real path, not a
corner case.

## Four things that will bite

1. **The condition is polled, not event-driven.** Cash-out latency is bounded by
   `interval`. A late cash-out in a crash game is not "slightly worse" — if the
   plane crashed in the gap the bot gets **zero**. Start `CASHOUT_POLL_MS` at
   ~50 ms and treat it as the parameter that decides whether realised RTP matches
   the intended target distribution. The join/bet gates can be lazy (seconds).

2. **`MultiplierUpdateMessage` must log at TRACE, nothing higher.** A crash game
   broadcasts the multiplier many times per second per bot. That is textbook
   "rate is a function of message rate" under the CLAUDE.md logging rule, and at
   DEBUG it would rebuild the exact log-volume problem LOG_VOLUME_TIERING just
   removed.

3. **Wire the metrics on day one.** The round-result message should implement
   `HasBetTotals` and `HasBotWinnings`, or the game is invisible in Grafana and
   its RTP cannot be verified. BOM has been silently reporting `payout=0` for
   months for exactly this reason. Retrofitting is far more annoying than doing
   it up front.

4. **Three `sendAsync` calls = three schedulers per bot**, versus one for the
   other bot types. Virtual threads, so cheap — but watch `jvm_threads_live`
   when this scales past a few hundred bots.

## Per-brand surface

Everything below is per-product, in `bot-messages` alongside the existing
`g2/bom`, `g4/nohu` message sets:

- `AviatorSubscribeResponse`
- `JoinRequest` / `BetRequest` / `CashOutRequest`
- `MultiplierUpdateMessage` (scaled multiplier accessor)
- `AviatorRoundEndMessage` (`HasBetTotals`, `HasBotWinnings`)

Plus, once, in core:

- `GameType.AVIATOR` — the enum currently has `BETTING_MINI`, `SLOT`, `TAI_XIU`,
  `CARD_GAME`, `UP_DOWN` and no crash type.
- `AviatorBot extends Bot` — implements `initializeSubclass()`,
  `botBehaviorScenario()`, `onStart()`.
- A target-multiplier chooser. Simplest v1: uniform draw between configured
  bounds on `Game` / `BotBehaviorConfig`. Bet sizing can reuse the existing
  betting strategies unchanged.

## Open questions — resolve before coding

1. **Which brand first**, and do we have real frames for it, or do we capture
   them off staging?
2. **Is "join" per-round or once per session?** Decides whether gate 1 is a real
   gate or folds into subscribe. Depends on the protocol.
3. **Does the server confirm a bet before the round launches?** Determines
   whether `betLive` is set optimistically on send or on an ack — and therefore
   whether a cash-out can be sent for a bet the server never accepted.
4. **What does cash-out return**, and is the payout in that response or only in
   the round-end frame? Decides where `HasBotWinnings` lives.
5. **Multiplier scale and precision** on the wire (hundredths? thousandths?).

## Not in scope

Crowd-aware behaviour, coordination, jackpot scaling, auto cash-out configured
server-side. Keep v1 to the loop above.

## Related

`docs/plans/SLOT_MACHINE_BOT.md` (closest existing precedent),
`docs/plans/BETTING_STRATEGIES.md` (bet sizing), CLAUDE.md logging rule.
