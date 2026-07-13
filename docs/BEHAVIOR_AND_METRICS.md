# Bot Manager — Behavioral Settings & Metrics

A quick-start reference for the behavior knobs you set on a bot group and the metrics
the app emits. Aimed at someone picking the app up for the first time.

> **Mental model.** A **bot group** is a fleet of identical bots pointed at one game in
> one environment. You configure *how they bet* (behavior), *how they decide* (strategy),
> and *when they run* (activation). The app then reports *what happened* (metrics).
>
> Settings live on the group and are sent via `POST /api/v1/bot-group` (create) or
> `PATCH /api/v1/bot-group` (update). **PATCH is full-replace per field**: sending a field
> overwrites it; omitting it (`null`) keeps the stored value. Strategy/behavior changes do
> **not** re-configure already-running bots — restart the group to apply them.

---

## 1. Core betting behavior

These shape the *shape and size* of each bot's bets. They apply to round-based games
(`BETTING_MINI`, `TAI_XIU`); SLOT games get their bet amount from the strategy instead
(see §3).

| Field | What it does | Why it exists |
|---|---|---|
| `minBet` / `maxBet` | Lower/upper bound on a single bet amount. | Keeps stakes inside what the game server will accept and what looks human. |
| `betIncrement` | Step size bets snap to (e.g. increment 100 → 100, 200, 300…). | Many games reject arbitrary amounts; increments keep bets on legal values. |
| `minBetsPerRound` / `maxBetsPerRound` | How many separate bets a bot places in one round. | Adds variety — some bots poke one option, others spread across several. |
| `maxTotalBetPerRound` | Ceiling on a **single bot's** summed stake across all its bets in a round. | Caps per-bot burn so one bot can't blow its balance in one round. |
| `betSkipPercentage` | 0–100 chance to skip a given betting opportunity. Higher = fewer bets. | Makes bots look human (real players don't bet every round). ~60 is a sane default. |
| `chatEnabled` | Whether the bot sends chat messages. | Populates the game's chat so a room feels alive. |
| `autoDepositEnabled` | Auto top-up when a bot's balance drops below threshold. | Keeps bots solvent during long runs without manual funding. |

> **Note on `maxTotalBetPerRound` vs `maxAggregateStakePerRound`:** the first is *per bot*;
> the second (§4) is the *whole group's* combined stake ceiling. They are different limits.

---

## 2. Betting strategies (`strategyMix`)

A group's bots don't all have to bet the same way. `strategyMix` is a **weighted list** —
each bot is randomly assigned one strategy at build time according to the weights. Omitting
it defaults to `[(RANDOM, 1.0)]` (every bot random).

```jsonc
"strategyMix": [
  { "strategyId": "RANDOM", "weight": 0.5 },
  { "strategyId": "MARTINGALE_CLASSIC_CAUTIOUS", "weight": 0.3 },
  { "strategyId": "PAROLI_AGGRESSIVE", "weight": 0.2 }
]
```

| Strategy | Behavior |
|---|---|
| `RANDOM` | Pure RNG every tick — ignores history and affinity. The v1 default. |
| `MARTINGALE_CLASSIC_*` | Doubles the bet after a **loss**, resets to min after a win. |
| `PAROLI_*` | Doubles after a **win**, resets after a loss or a short winning streak. |
| `DALEMBERT_*` | Raises one step after a loss, lowers one step after a win. |
| `FIBONACCI_*` | Follows the Fibonacci sequence — one step up on a loss, two steps back on a win. |

Each non-random family comes in a **`_CAUTIOUS`** and **`_AGGRESSIVE`** risk profile:
*cautious* favors the safer entries (lower payout, higher hit rate), *aggressive* chases
the riskier, higher-payout entries. The **why**: mixing strategies and risk profiles makes
the fleet's aggregate betting look like a diverse crowd of real players rather than N copies
of one algorithm. Full catalog is served live at `GET /api/v1/strategy`.

---

## 3. Slot strategy (`slotStrategyId`)

SLOT games are self-paced (no rounds) and pick a bet **amount** from a server-supplied
allowed set. Betting `strategyMix` does not apply. Set one `slotStrategyId` for the group:

| Slot strategy | Behavior |
|---|---|
| `FIXED` | Always stakes the smallest allowed value. The default when unset. |
| `RANDOM` | Picks uniformly at random from the allowed set each spin. |

---

## 4. Advanced betting intelligence (opt-in)

These coordinate bots as a group and make the fleet's collective betting look organic.
All default off; enable per group.

| Field | What it does | Why |
|---|---|---|
| `coordinationEnabled` | Turns on the group-level bet coordinator. | Lets the group act as one crowd instead of N independent RNGs. |
| `maxAggregateStakePerRound` | Ceiling on the **whole group's** combined stake per round. | Bounds total fleet spend per round (distinct from per-bot `maxTotalBetPerRound`). |
| `crowdAwareCoordination` | Bots react to the live per-option crowd (the `bs` array the server sends). Sub-mode of coordination — **requires `coordinationEnabled`**. | Makes bot betting track the real crowd's distribution instead of a flat spread. |
| `affinityWeightedProposal` | Biases each bot's option pick toward the game's affinity weights instead of a uniform draw. Independent of coordination. | Some options are naturally more popular; this mirrors that skew. |
| `rampEnabled` | Per-round bet **ramp-up**: bets accept-probability rises over the round via a power curve. | Builds stake toward the end of a round the way real betting pressure does. |
| `rampShape` | The ramp curve exponent `k` (only meaningful with `rampEnabled`; must be `> 0`). | Controls how sharply the ramp accelerates. |

> Ramp and affinity apply to `BETTING_MINI` / `TAI_XIU` only; SLOT and other types leave
> them off. With everything off, the bet path is the plain flat cadence.

---

## 5. Activation & scheduling

Controls **when** a group runs. Set `activationMode` + `activationWindow`.

| `activationMode` | Meaning |
|---|---|
| `null` (legacy) | Not scheduled — runs purely by `targetStatus` (manual start/stop). |
| `SCHEDULED` | The reconciler starts/stops the group automatically from `activationWindow` (checked every ~60s). Requires a window. |
| `MANUAL_ON` | Operator parked it *up*; the scheduler leaves it alone. |
| `MANUAL_OFF` | Operator parked it *down*; the scheduler leaves it alone. |

> A manual `start`/`stop` on a `SCHEDULED` group parks it as `MANUAL_ON`/`MANUAL_OFF` so
> your action isn't immediately undone by the next reconcile tick.

**`activationWindow`** is a recurring time-of-day window:

```jsonc
"activationWindow": {
  "from": "09:00",
  "to":   "23:00",
  "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"]  // null/empty = every day
}
```

- Windows may cross midnight (`from` > `to`, e.g. `22:00`→`02:00`); the post-midnight half
  is anchored to the day it opened.
- Times are interpreted in the app's configured zone (`bot.activation.zone`, default
  `Asia/Ho_Chi_Minh`).

There's also `scheduledRestartTime` for a one-off restart at a specific time.

---

## 6. Operational knobs (application.properties)

App-wide, not per group — mostly you leave these alone, but good to know they exist:

| Property | Default | Purpose |
|---|---|---|
| `bot.periodic-logout.enabled` | `true` | One bot per group logs out & reconnects each interval (round-robin) to refresh stale connections. |
| `bot.periodic-logout.interval-minutes` | `60` | How often that cycle fires. |
| `bot.watchdog.timeout.seconds` | `180` | If a bot receives no game message for this long, it force-reconnects (recovers silent "zombie" bots). |
| `bot.group.dead.threshold` | `0.80` | Fraction of dead bots at which the whole group is marked DEAD. |
| `bot.activation.tick-seconds` | `60` | How often the activation reconciler runs. |

---

## 7. Metrics

All bot metrics are Prometheus counters, exported at `/actuator/prometheus`. Every `bot_*`
series carries these identity tags so you can slice by group / environment / game:
`botGroupId`, `environmentId`, `gameType`, `gameId`, `gameName`.

### Counter catalog

| Metric | Tags (beyond identity) | What it counts |
|---|---|---|
| `bot_bets_placed_total` | — | Bets confirmed by the server's EndGame. |
| `bot_bet_amount_total` | — | Total staked (sum of confirmed bet amounts). |
| `bot_winnings_total` | — | Gross winnings paid back. |
| `bot_jackpots_total` | — | Count of jackpot-winning rounds. |
| `bot_jackpot_amount_total` | — | Sum of jackpot value won. |
| `bot_money_drained_total` | — | Observed real-balance depletion — a **burn gauge**, see below. |
| `bot_messages_total` | `cmd` (subscribe/startGame/updateBet/endGame) | Protocol messages received. |
| `bot_ws_connections_total` | `event` (connected/authenticating/disconnected) | WebSocket lifecycle events. |
| `bot_reconnects_total` | `reason` (watchdog/ws-disconnect/reauth-cycle) | Reconnect attempts. |
| `bot_watchdog_expired_total` | — | Watchdog timeouts (silent-eviction detector fired). |
| `bot_failures_total` | — | Bot transitions into DEAD. |
| `bot_creation_failures_total` | `reason` (validation/auth/unknown) | Per-bot failures during group start. |
| `bot_login_total` | `outcome` (success/failure) | Login attempts. |
| `bot_verify_token_total` | `outcome` | verifytoken calls. |
| `bot_auto_deposits_total` | `outcome` | Auto-deposit top-ups. |
| `bot_dead_seconds_total` | — | Accumulated per-bot DEAD downtime (seconds). |
| `group_dead_seconds_total` | — | Accumulated per-group DEAD downtime (seconds). |

### Derived metrics you'll actually read

- **RTP (return to player)** = `bot_winnings_total / bot_bet_amount_total` over a window.
  A healthy game sits near the configured payout (≈`0.99` in our tests). **RTP > 1.0
  sustained is a red flag** — it means bots are net-winning, i.e. a game-logic problem.
- **Money drain / day** — from `bot_money_drained_total`. This is a **burn gauge, not net
  P&L**: net-gain windows contribute 0 rather than offsetting prior losses, so it
  intentionally over-states true depletion. It answers "how fast are these bots bleeding
  balance," which is independent ground truth from bet-minus-winnings.

### Where to look

- **Grafana** — two templated dashboards, `per-game` and `per-environment`, with
  `$game` / `$environment` dropdowns. Panels for bot counts/statuses, RTP,
  bets/winnings/jackpots, and failures/dead-time.
- **Metrics API** — `GET /api/v1/metrics/...` (game/env × summary/timeseries) if you want
  the numbers in an app/UI without Grafana.
- **Per-group health** — `GET /api/v1/bot-group/{id}/health` returns live per-bot state
  (this is a product-facing health view, distinct from the infra metrics above).
- **Raw** — `/actuator/prometheus` (internal port only).

> **Known caveat:** BOM games don't yet report winnings, so their RTP/payout metrics read
> 0 regardless of real settlement — don't read `payout=0` on BOM as proof of anything.
```
