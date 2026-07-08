# Jackpot-Based Scale + Bet Ramp-Up — Per-Round Betting Shaping (Internal Tier)

## Why one combined plan (not two)

These are work packages **#3 (Jackpot-based scale)** and **#2 (Bet ramp-up)** of
`docs/plans/BETTING_INTELLIGENCE_ROADMAP.md`, both greenlit 2026-07-08. They share
enough load-bearing seam that splitting them would duplicate the same wiring twice
and risk drift: **identical `BETTING_MINI`/`TAI_XIU` game-type gating** (mirroring
BET_COORDINATION AD-10), **the same bot bet-path seam** (`betCondition()` in
`BettingMiniGameBot`, the same method the coordinator already hooks), **the same
per-round group-scoped ownership pattern** (a value computed once per round and read
by N bots, mirroring `BotGroupRuntime.coordinator`), and **the same nullable
health-block precedent** on `BotGroupHealthDTO`. They differ only in the *quantity*
being shaped: jackpot-scale modulates bet **magnitude/volume** from an external
meter observed at round end; ramp-up modulates bet **timing** within the window.
Both reduce to "a scalar in `[floor, 1.0]` that gates/attenuates proposals in
`betCondition()`." One plan, two independent phase blocks (Phases J* and R*), one
shared wiring phase. Either block can ship without the other; neither depends on
BET_COORDINATION being on.

## Goal

Add two independent, opt-in, per-round betting-shaping levers for
`BETTING_MINI`/`TAI_XIU` groups, both degrading to today's exact behavior when off:

1. **Jackpot-based scale (WP#3):** read the game's **live jackpot pool meter**
   (already parsed at round end, currently unused) and derive a group-scoped
   **volume scale factor** in `[floor, 1.0]` that rises ~linearly from the seed
   floor (~500k after a discharge) to an operator-configured per-game ceiling
   (low tens of millions). Higher pool → bots bet at fuller intensity, mimicking
   real players piling in as a jackpot grows. Configured **on the Game** (jackpot
   presence is a game-intrinsic trait).

2. **Bet ramp-up (WP#2):** shape *when* within the bet window a bot's ticks land —
   ramp acceptance from low at window-open toward high at window-close, so aggregate
   fleet volume builds toward round close like a real-player pile-in, instead of the
   current flat every-second cadence. Configured **on the BotGroup** (ramp is a
   behavior preference).

## Findings — Current State

### The current bet-cadence path (the ramp-up seam)

- The betting loop is a single `sendAsync` stage built in
  `BettingMiniGameBot.botBehaviorScenario()`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:800-805`):
  ```
  .sendAsync(buildMessage()
      .messageSupplier(mdcSupplier(bet()))
      .mode(INFINITE)
      .condition(mdcSupplier(betCondition()))
      .interval(resolveIntervalBetweenBets(), MILLISECONDS)
      .build())
  ```
- **`resolveIntervalBetweenBets()` (`:745`) returns a flat `1_000L`** and is read
  **once at scenario-build time**. `SendAsync` schedules with
  `scheduleAtFixedRate(runnable, delay, interval, timeUnit)`
  (`/Users/gleb/IdeaProjects/WebSocket Parser/websocket-parser-core/src/main/java/com/vingame/websocketparser/scenario/processors/SendAsync.java:158`)
  — **the interval is immutable for the life of the scenario.** *You cannot vary the
  tick interval per-round or intra-round.* This is the central constraint for ramp-up.
- What **is** re-evaluated every tick is the **`condition` supplier**
  (`betCondition()`, `:717`): `SendAsync.processInternal`'s runnable calls
  `conditionSupplier.get()` on every fixed-rate fire
  (`SendAsync.java:124`) and only invokes the message supplier when it returns
  `true`. So the tick fires every 1s; whether it *emits a bet* is the condition's
  decision. **The ramp lever is therefore a per-tick probabilistic accept gate
  inside `betCondition()`, keyed on how far through the window we are** — not an
  interval change.
- **Where in the round timeline bets currently land:** `betCondition()` gates on
  `canBet()` (`:698`): `sidStore.get() != 0`, `gameState == BET`, and
  `doesEnoughTimeRemain()` (`:694`, `remainingTime.get() >= blockBetTime`).
  `remainingTime` is an `AtomicLong` seeded to `timeForBetting` at StartGame
  (`startRemainingTimeCountDown`, `:325`, decremented 1000ms/tick) and
  `blockBetTime`/`timeForBetting` come off the Subscribe frame
  (`onSubscribe`, `:367-368`). So today: from StartGame until
  `remainingTime < blockBetTime`, **every 1s tick that the strategy doesn't skip
  places a bet** — a flat cadence, capped per round by the strategy's
  `maxBetsPerRound` gate (`RandomBehaviorStrategy.decide:74`).
- **The window fraction is directly computable in the bot:** `timeForBetting`
  (window length) and `remainingTime.get()` (time left) are both bot fields, so
  `elapsedFraction = 1 - remainingTime/timeForBetting ∈ [0,1]` is available in
  `betCondition()` with no new plumbing.
- **Per-bot bet ceiling stays authoritative:** `maxBetsPerRound` is enforced inside
  the strategy (`RandomBehaviorStrategy.decide:74`, `MartingaleStrategySupport`),
  independent of the ramp. Ramp shifts *when* a bot's allotted bets land, not *how
  many* — the strategy's round cap is untouched.

### The jackpot-pool signal (the jackpot-scale input)

Confirmed by reading each message class. **The live pool meter and the per-bot
jackpot payout (`HasJackpot.jackpotFor`) are DISTINCT.** `HasJackpot` drives only
`bot_jackpots_total` (`onEndGame:457`, `BettingMiniGameBot.java`); we need the meter.

| Product / game type | Live pool meter field | On EndGame | On Subscribe | Reliable? |
|---|---|---|---|---|
| Tip (`P_116` TAI_XIU-family via Tip plugin) | **`tJpV`** = `totalJackpotValue`, "total winnable jackpot displayed in game UI" | `TipEndGameMessage:103` | `TipSubscribeMessage:18` | **Yes** — always present, is the UI meter |
| BOM (`B52EndGameMessage`, `NohuEndGameMessage`, `BomEndGameMessage`) | **`tJpV`** | `BomEndGameMessage:33` | `BomSubscribeMessage:22` | **Yes** — always present |
| B52 / Nohu | **`tJpV`** | `B52EndGameMessage:35`, `NohuEndGameMessage:33` | their Subscribe | **Yes** |
| Tai Xiu jackpot variant (`taixiuJackpotPlugin`, **P_114/RIK**) | **`tJpV`** = live pool meter | on EndGame cmd **1104** (`TaiXiuEndGameMessage`, currently unparsed — see below) | on Subscribe cmd **1105** (`TaiXiuSubscribeMessage`, currently ignored) | **Yes — once `tJpV` is parsed** |
| Tai Xiu plain variant (`taixiuPlugin`, P_116) | none (no live meter) | `jpV` is a per-win value only | none | Non-jackpot: meter `0` → **degrades to neutral** (AD-J5) |

- **`lJp` (`LastJackpotData`) is a RED HERRING — NOT the pool.** On
  Bom/B52/Nohu EndGame + Subscribe, `lJp` decodes to `{ int d1; long sid; }`
  (`BomEndGameMessage.LastJackpotData:93`) — the dice result and session id of the
  *last discharge*, no monetary value. The user flagged this correctly. **Do not
  read `lJp` for scaling.** The pool for these products is `tJpV`.
- **`tJpv2`** exists alongside `tJpV` on all three G2 products
  (`BomEndGameMessage:32`) and on Tip (`tJpv2`, `TipEndGameMessage:112`). Its
  semantics are not documented in the codebase (likely a secondary/mini jackpot
  tier). **v1 reads `tJpV` only**; `tJpv2` is out of scope (Open Item).
- **Tai Xiu IS supported — the P_114 jackpot variant carries a live `tJpV` meter
  that is simply not yet deserialized.** `TAI_XIU_114_JACKPOT.md:33` shows `tJpV`
  on the Subscribe response (cmd 1105) and `:36` shows a **live `tJpV`** on EndGame
  (cmd 1104). The 114 jackpot product **reuses the shared `TaiXiuEndGameMessage`**
  (`JackpotTaiXiuMessageTypes.endGameType()` returns `TaiXiuEndGameMessage.class`,
  `JackpotTaiXiuMessageTypes.java:100-102`). That class's `@JsonCreator` reads
  `…iJp, jpV` with **no `tJpV`** (`TaiXiuEndGameMessage:85-115`), so the live meter
  is currently dropped by the scenario mapper's `FAIL_ON_UNKNOWN_PROPERTIES=false`
  (`TAI_XIU_114_JACKPOT.md:105,107,211`). The fix is to **parse `tJpV`** — a few
  lines (Phase J1). `jpV`/`iJp` (the per-win value) stay as-is and keep driving
  `HasJackpot` / `bot_jackpots_total`; `tJpV` is the separate running meter.
- **Where `tJpV` parsing lands (decision):** add an optional
  `@JsonProperty("tJpV") long tJpV` to the **shared `TaiXiuEndGameMessage`**
  constructor + a `tJpV` field/getter — **no jackpot-specific subclass.**
  Justification: (a) the 114 provider already registers `TaiXiuEndGameMessage.class`
  for endGame (`JackpotTaiXiuMessageTypes.java:100`), so a subclass would force a
  new `TaiXiuMessageTypes.endGameType()` return + registration path for zero
  behavioral gain; (b) the field is **optional** — the plain P_116
  `taixiuPlugin` End frame carries no `tJpV`, so Jackson defaults it to `0`, which
  AD-J5 maps to neutral ("`0` = not observed"). Adding the field to the
  `@JsonCreator` is exactly what makes it stop being silently ignored; the test
  harness mapper carries the same `FAIL_ON_UNKNOWN_PROPERTIES=false`
  (`TaiXiuMessageDeserializationTest.java:38`), so parsing behavior matches runtime.
  Net effect: 114 gets a live meter, 116 stays neutral, no other Tai Xiu code moves.
- **Subscribe meter — read only at EndGame (decision, AD-J7).** Tai Xiu (like
  BOM/etc.) also exposes `tJpV` on its Subscribe response (cmd 1105). We
  deliberately read the meter **only at EndGame**, not Subscribe: it keeps the
  one-round-lag consistent with every betting-mini product (AD-J7), avoids a second
  `HasJackpotPool`-on-Subscribe seam and a scaler "seed" hook, and the only cost is
  one extra warm-up round (the very first round of a run bets at neutral until the
  first EndGame meter lands — negligible). Not worth the added surface.
- **Presence-reliability:** for all supported products `tJpV` is a primitive
  `long` (`@JsonProperty("tJpV") long tJpV`), so a missing field Jackson-defaults
  to `0`. A `0` meter is indistinguishable from "not yet observed" — both map to
  the neutral factor (AD-J5). The meter is only trustworthy once we've observed a
  **non-zero** value at least once this run. This is precisely what lets a plain
  (non-jackpot) Tai Xiu game with the flag on degrade to neutral rather than
  throttle to the floor.

### Marker-interface dispatch precedent (how to expose the meter)

- `onEndGame` (`:426-467`) already does per-message marker dispatch:
  `if (msg instanceof HasBotWinnings hw)`, `if (msg instanceof HasJackpot hj)`,
  `if (msg instanceof HasBetTotals bt)`. Adding a **new marker
  `HasJackpotPool`** implemented on `TipEndGameMessage`/`BomEndGameMessage`/
  `B52EndGameMessage`/`NohuEndGameMessage` and read in `onEndGame` is the
  established pattern (mirrors `HasJackpot`, `HasBetTotals` — ENDGAME_METRICS).

### Round lifecycle hooks (where the per-round scale is (re)computed)

- `onStartGame` (`:372`) and `onEndGame` (`:426`) already carry coordinator hooks
  (`coordinator.onRound` `:403`, `coordinator.onRoundComplete` `:500`) — the same
  first-seen-idempotent, group-scoped, once-per-round pattern the jackpot module
  will reuse. The meter arrives at **EndGame of round N** and is applied to the
  scale used **during round N+1** (a one-round lag — the pool value we react to is
  always the last completed round's, which matches "scale *next* round's volume"
  in the roadmap, `BETTING_INTELLIGENCE_ROADMAP.md:102`).

### Group-scoped per-round ownership precedent

- `BotGroupRuntime.coordinator` (`BotGroupRuntime.java:81`) is the one-per-group
  holder for a value computed once and read by N bots. The jackpot scale is
  identical in shape — a group/round-level scalar derived from a game-level meter —
  so it gets its own nullable runtime field (`JackpotScaler`, AD-J8) mirroring the
  coordinator: built in `start()` for eligible groups, injected into each bot
  before `startBot` (`BotGroupBehaviorService.start:303-308`).

### Config surfaces

- **Game** (`Game.java:66-94`) + **GameDTO** (`GameDTO.java`) +
  **GameMapper** (hand-written `toDTO:24`/`toEntity:70`/`updateEntityFromDTO:103`)
  + **GameController** (`GameController.java`, create `:118`, PATCH `:136`) cleanly
  accept new scalar fields — the mapper is hand-written and env-scoping is already
  in place (BOTGROUP_GAME_MANAGEMENT). Two new Game fields fit the existing shape.
- **BotGroup** (`BotGroup.java:36-62`) already carries `minBet`/`maxBet`/
  `betIncrement`/`maxBetsPerRound` and the BET_COORDINATION pair
  (`coordinationEnabled:51`, `maxAggregateStakePerRound:62`). **BotGroupDTO** +
  hand-written **BotGroupMapper** are the write surface. Ramp fields fit here.
- **BotBehaviorConfig** is the per-bot immutable snapshot built in
  `createSingleBot` (`BotGroupBehaviorService.java:522-538`); ramp params flow
  through it to the bot (mirroring `betSkipPercentage`).

### Read-side observability precedent

- `BotGroupHealthDTO` (`BotGroupHealthDTO.java:39,46`) carries a nullable
  `CoordinationStateDTO coordination` block, populated in `getHealth` (`:836`) via
  `buildCoordinationState(runtime.getCoordinator())` returning `null` when off.
  Two more nullable blocks (`jackpotScale`, `ramp`) follow this pattern exactly.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Ramp seam (per-tick accept gate) | ready | `betCondition()` re-evaluated per tick; window fraction from `remainingTime`/`timeForBetting`. Interval is immutable — ramp is a probabilistic gate, not an interval change (AD-R1). |
| Ramp config (2 fields on BotGroup) | ready | `rampEnabled`, `rampShape` → BotGroup/DTO/Mapper/BotBehaviorConfig (Phase R1). |
| Jackpot meter input | ready | `tJpV` reliable for Tip/BOM/B52/Nohu; **Tai Xiu P_114 supported** once `tJpV` is parsed (`TaiXiuEndGameMessage`, Phase J1). New `HasJackpotPool` marker. |
| Jackpot config (2 fields on Game) | ready | `jackpotScaleEnabled`, `jackpotCeiling` → Game/DTO/Mapper/Controller (Phase J2). |
| Jackpot scaler (group-scoped, per-round) | ready | `JackpotScaler` domain object on runtime, mirroring coordinator (AD-J8). |
| Jackpot volume lever | ready (confirmed) | Modulate per-bot `maxBetsPerRound` (AD-J4), `minMultiplier=0.25` — user-confirmed. |
| Degrade-to-neutral | ready | Both default to scale=1.0 / flat when off, meter absent (`tJpV==0`), or type ineligible (AD-J5, AD-R5). |
| Game-type gating | ready | Jackpot: `{BETTING_MINI, TAI_XIU}`; ramp: `{BETTING_MINI, TAI_XIU}`; mirrors BET_COORDINATION AD-10. |
| Compose with BET_COORDINATION | ready | Independent; jackpot factor scales coordinator's aggregate cap when both on (AD-J9). |
| Health blocks | ready | Nullable `jackpotScale` + `ramp` on `BotGroupHealthDTO` (Phase J4/R3). |
| `tJpv2` / secondary jackpot | out of scope | v1 reads `tJpV` only. |

## Architecture Decisions

### Shared

**AD-S1 — Game-type gating mirrors BET_COORDINATION AD-10.** Both features build
their group-scoped helper in `BotGroupBehaviorService.start()` **only** for
`GameType.BETTING_MINI` / `GameType.TAI_XIU`, with the same
`(game.getGameType() == BETTING_MINI || == TAI_XIU)` guard already at
`BotGroupBehaviorService.java:284`. All other types (SLOT, UP_DOWN, CARD_GAME) build
no helper; every bot's reference is `null`; the bet path is byte-for-byte today's.
The eligible set is **`{BETTING_MINI, TAI_XIU}` for BOTH features** — jackpot-scale
and ramp share the same guard. (Tai Xiu is fully supported for jackpot-scale via the
P_114 `tJpV` meter, AD-J3; a Tai Xiu game with no live meter degrades to neutral via
AD-J5, it is not gated out.)

**AD-S2 — Scope-agnostic cores (Fleet reuse, mirrors BET_COORDINATION AD-8).** Both
helpers are plain domain objects in `com.vingame.bot.domain.bot.coordination` (reuse
the existing package) — no Spring, no `BotGroup`/`BotGroupRuntime` import.
`JackpotScaler` is constructed from `(long ceiling, long seedFloor, double
minMultiplier)`; the ramp needs no group-scoped object at all (it is a pure per-bot
function of `(rampShape, elapsedFraction)` — AD-R2). A future Fleet builds the
`JackpotScaler` identically from fleet-level fields.

**AD-S3 — Off = today, everywhere.** When a feature is disabled (its flag false),
type-ineligible, or its input is unavailable, the effective scalar is exactly the
neutral value (jackpot factor `1.0`, ramp accept-probability `1.0` on every tick).
No helper is built, no health block is emitted, and the bet path is unchanged.

**AD-S4 — Logging per CLAUDE.md.** Group-lifecycle at INFO (one line per group at
start: "Jackpot scaler created …" / "Bet ramp enabled …"). One **DEBUG** aggregate
line per group per round (jackpot: pool + computed factor at `onRoundComplete`;
ramp: no per-round line needed — the ramp state is per-bot and stateless, its effect
is visible in the existing 5s UpdateBet aggregate). Per-tick / per-proposal ramp and
scale decisions are **TRACE** only. No per-frame or per-bet flood; no new INFO on the
hot path.

### Jackpot-based scale (WP#3)

**AD-J1 — Two write-side fields, on the Game.** Add exactly `boolean
jackpotScaleEnabled` and `long jackpotCeiling` to `Game`, `GameDTO`, and all three
`GameMapper` methods, surfaced through the existing `GameController` create/PATCH.
Jackpot presence is a game-intrinsic trait (not every Tai Xiu / betting-mini has a
jackpot), so it belongs on the Game, not the BotGroup. **The seed floor is NOT a
new field** — it is the known reset value `≈500000` (roadmap-confirmed server
behavior), held as a constant `JackpotScaler.DEFAULT_SEED_FLOOR = 500_000L` (AD-J6
discusses making it overridable — deferred).

**AD-J2 — Input = the live pool meter via a new `HasJackpotPool` marker.** Add
`interface HasJackpotPool { long jackpotPool(); }` to
`com.vingame.bot.domain.bot.message`. Implement it on `TipEndGameMessage`
(`return tJpV;`), `BomEndGameMessage`, `B52EndGameMessage`, `NohuEndGameMessage`
(all `return tJpV;`), **and on `TaiXiuEndGameMessage`** (`return tJpV;`) once its
constructor parses `tJpV` (Phase J1). The shared `TaiXiuEndGameMessage` serves both
the plain P_116 `taixiuPlugin` (no `tJpV` on the wire → defaults `0` → neutral) and
the P_114 `taixiuJackpotPlugin` (live `tJpV`), so one implementation covers both.
`HasJackpotPool.jackpotPool()` is DISTINCT from `HasJackpot.jackpotFor()` — the
former is the running meter (`tJpV`), the latter the per-bot payout (`jpV`/`iJp`),
which stays untouched. In `onEndGame`, after the existing marker dispatch, read the
meter:
```
if (jackpotScaler != null && msg instanceof HasJackpotPool hp) {
    jackpotScaler.observePool(hp.jackpotPool());   // group-scoped, first-seen idempotent
}
```
This is DISTINCT from the `HasJackpot hj` block at `:457` (that stays as-is, driving
`bot_jackpots_total`).

**AD-J3 — Tai Xiu IS supported for jackpot-scale via the `tJpV` meter.**
`start()` builds a `JackpotScaler` for `GameType.BETTING_MINI` **or**
`GameType.TAI_XIU` groups with `jackpotScaleEnabled` true (same guard as ramp — no
type is skip-logged). Support hinges on parsing `tJpV` off `TaiXiuEndGameMessage`
(Phase J1) and implementing `HasJackpotPool` there (AD-J2). Two cases collapse into
the same fail-safe:
- **P_114 / RIK `taixiuJackpotPlugin`** carries a live `tJpV` on every EndGame
  (`TAI_XIU_114_JACKPOT.md:36`) → the scaler observes a rising meter and the factor
  tracks it exactly as for BOM/Tip.
- **P_116 `taixiuPlugin` (plain, non-jackpot)** carries no `tJpV` → Jackson defaults
  it to `0` → AD-J5 treats "no non-zero pool observed yet" as **neutral** (factor
  `1.0`, today's volume). So enabling the flag on a meterless Tai Xiu game is a
  harmless no-op, not a throttle-to-floor — it **degrades to neutral, it is not
  gated out**.

No skip-log, no per-type branch: the `start()` guard is simply
`jackpotScaleEnabled && (BETTING_MINI || TAI_XIU)`.

**AD-J4 — Volume lever = modulate per-bot `maxBetsPerRound`, floored at a minimum
multiplier.** *(Open Decision — see below; this is the recommended resolution.)*
The scale factor `f ∈ [minMultiplier, 1.0]` multiplies each bot's effective
per-round bet ceiling for the upcoming round:
`effectiveMaxBetsPerRound = max(1, round(configuredMaxBetsPerRound × f))`.
- **Why this lever:** "bots at 100%" reads most naturally as *full configured
  participation intensity* — every bot placing its full allotment of bets per
  round. `maxBetsPerRound` is the exact knob that governs per-bot round intensity
  (`RandomBehaviorStrategy.decide:74`), it is already per-bot config
  (`BotBehaviorConfig.maxBetsPerRound`), and attenuating it scales aggregate volume
  smoothly and monotonically without touching option choice, amount grid, or the
  coordinator. Rejected alternatives: `betSkipPercentage` (BET_COORDINATION AD-7
  pins it to 0 under coordination — collides; and it shapes *within* a round, which
  is ramp-up's job); an active-bot participation fraction (coarser, needs a
  group-scoped "which bots sit out this round" decision — more state for no gain);
  scaling the coordinator aggregate cap (only exists when coordination is on — fails
  the "must work independently" requirement; see AD-J9 for the compose case).
- **Low end is a baseline presence, NOT zero.** `minMultiplier` (default `0.25`,
  Open Decision) means at the seed floor a group still runs at 25% intensity —
  a plausible quiet-table presence, never a dead table. `max(1, …)` guarantees a
  bot never drops below one bet per round while betting is enabled.
- **The bot reads the factor per round, not per tick.** At `onStartGame` (round N+1)
  the bot snapshots the current group factor into a `volatile double
  currentJackpotFactor` and the strategy reads `effectiveMaxBetsPerRound` for the
  round. (Implementation: pass the factor via `BetContext`, or have the bot compute
  and stash an effective cap the strategy consults — see Phase J3.)

**AD-J5 — Transfer function: clamped linear seed→ceiling, fail-safe to 1.0.**
For observed pool `p`, seed floor `s = 500_000`, ceiling `c = jackpotCeiling`,
minimum multiplier `m = minMultiplier`:
```
if scaler disabled / no non-zero pool observed yet / c <= s:  f = 1.0   (neutral)
else:  t = clamp((p - s) / (c - s), 0.0, 1.0);  f = m + (1.0 - m) * t
```
So `f = m` at/below the seed floor, `f = 1.0` at/above the ceiling, linear between.
**Neutral (`f = 1.0`) whenever the signal is untrustworthy:** disabled for the
game, no non-zero `tJpV` observed yet this run (a raw `0` is "not-yet-observed", not
"empty pool" — AD-J5 fail-safe), game type ineligible, or a misconfigured
`ceiling <= seedFloor`. Fail safe = run at full volume, never silently throttle to
the floor on missing data.

**AD-J6 — Seed floor is a constant, ceiling is operator config.** `seedFloor` is the
known ≈500k reset (constant). `jackpotCeiling` is the per-game operator field
(AD-J1). Making `seedFloor` a third Game field is deferred (Open Item) — no observed
product deviates from the ≈500k reset, and adding a field the operator must get
right per-game is UI/validation cost for no current benefit.

**AD-J7 — One-round lag is intended.** The meter for round N arrives at N's EndGame;
the derived factor applies to round N+1's `maxBetsPerRound`. This matches the
roadmap's "scale *next* round's bot volume" (`:102`) and needs no mid-round
recompute. The factor is recomputed once per round in `observePool` (first-seen
idempotent across the group's N bots, mirroring `coordinator.onRoundComplete`).

**AD-J8 — Group-scoped `JackpotScaler` on the runtime.** A new nullable
`JackpotScaler` field on `BotGroupRuntime` (mirroring `coordinator:81`), built in
`start()` for eligible groups, injected into each bot before `startBot`. The scaler
holds `volatile long lastObservedPool` + `volatile double currentFactor` + a
`ReentrantLock` for first-seen idempotency and coherent snapshot reads (mirroring
the coordinator's concurrency model, AD-3 of BET_COORDINATION). `observePool(long)`
updates both under the lock; `getCurrentFactor()` reads `currentFactor` (volatile,
lock-free single read). N bots calling `observePool` for the same round converge on
the same factor (idempotent — same input, same output; the lock guards only the
first-seen histogram/log emission and the paired update).

**AD-J9 — Composition with BET_COORDINATION (both on).** Jackpot-scale's primary
lever (`maxBetsPerRound` attenuation, AD-J4) works with coordination **off** — the
hard requirement. When coordination is **also** on, the two compose naturally with
no coupling: bots propose fewer times per round (jackpot lever), and the coordinator
still trims/rejects against its aggregate cap. **Additionally**, when both are on,
the jackpot factor scales the coordinator's effective aggregate cap for the round:
`effectiveCap = round(maxAggregateStakePerRound × f)` — so a low jackpot both thins
proposals *and* lowers the ceiling, and a high jackpot opens both. This is a small,
optional enhancement (the coordinator gains an `onRound(sessionId, factor)` overload
or reads the scaler); it is **not required** for either feature to function and is
gated behind "both helpers non-null." If deferred, jackpot-scale still fully works
via the per-bot lever alone.

**AD-J10 — Observability.** Nullable `JackpotScaleStateDTO jackpotScale` on
`BotGroupHealthDTO` (`{ boolean enabled; long jackpotCeiling; long seedFloor; long
lastObservedPool; double currentFactor; double minMultiplier; }`), populated in
`getHealth` from `runtime.getJackpotScaler()` (null when off/ineligible/not-running).
One DEBUG line per group per round in `observePool` (first-seen):
`"JackpotScale sid=… pool=… factor=… ceiling=…"`.

### Bet ramp-up (WP#2)

**AD-R1 — Ramp lever = per-tick probabilistic accept gate in `betCondition()`,
keyed on window elapsed-fraction.** Since `SendAsync`'s interval is immutable
(Findings), the tick continues to fire every `resolveIntervalBetweenBets()` (1000ms,
unchanged). Ramp shapes *which ticks emit a bet*: after `canBet()` passes and before
`decideBet`, compute `elapsedFraction = 1 - remainingTime/timeForBetting` and an
**accept probability** `pAccept = rampWeight(shape, elapsedFraction)`; draw
`rng.nextDouble()`; if `>= pAccept`, skip this tick (return `false`) — a *deferral*,
not a lost bet: the bot's `maxBetsPerRound` allotment is unchanged, its bets simply
concentrate later in the window. This makes aggregate fleet volume ramp toward round
close. **The gate runs BEFORE `decideBet`/`applyCoordination`** so a deferred tick
never touches the strategy counter or reserves coordinator budget.

**AD-R2 — Ramp is a pure stateless per-bot function — no group-scoped object.**
Unlike jackpot-scale, ramp needs no meter and no per-round shared value: every bot
computes its own accept probability from `(rampShape, elapsedFraction, rng)`. So
there is **no `RampController` on the runtime** — the ramp params flow through
`BotBehaviorConfig` into the bot, and the gate is a private method
`rampAccepts(elapsedFraction)` on `BettingMiniGameBot`. This keeps the seam minimal
and inherently Fleet-safe (nothing group-scoped to own).

**AD-R3 — Shape model: a single `rampShape` exponent (power curve).** *(Open
Decision — see below; this is the recommended resolution.)* Model the accept
probability as `pAccept(x) = pMin + (1 - pMin) · x^k` where `x = elapsedFraction ∈
[0,1]` and `k = rampShape`:
- `k = 1.0` → linear ramp (probability rises evenly from `pMin` at open to `1.0` at
  close).
- `k > 1.0` → back-loaded (bets pile in hard near close — the real-player pattern;
  e.g. `k = 3.0`).
- `k = 0.0` (or ramp disabled) → flat `1.0` everywhere = **today's cadence exactly**
  (AD-R5 degrade path).
- `pMin` (accept probability at window open, default `0.15`) prevents a dead early
  window (some early bettors are realistic) and is a `JackpotScaler`-style constant,
  not a per-group field (Open Decision: expose it or keep constant).
A single exponent is the minimum expressive knob that captures "flat vs
back-loaded"; a full piecewise curve is UI/validation bloat for no demonstrated
need (mirrors BET_COORDINATION's "one AND, no rule engine" restraint).

**AD-R4 — Ramp config = two fields, on the BotGroup.** Add `boolean rampEnabled`
and `double rampShape` to `BotGroup`, `BotGroupDTO`, all three `BotGroupMapper`
methods, and thread `rampShape`/`rampEnabled` through `BotBehaviorConfig` in
`createSingleBot`. **Placement rationale (reconciled against jackpot-on-Game):** ramp
is a *behavior preference* — how aggressively *this fleet of bots* piles in — which
historically lives on the BotGroup (like `betSkipPercentage`, `maxBetsPerRound`,
`strategyMix`). It is not a game-intrinsic trait (unlike jackpot presence): the same
game can host a flat group and a back-loaded group. So the two features land on
different entities *by their nature*, not inconsistently — game-intrinsic → Game;
behavior-preference → BotGroup. This is the same split the codebase already draws
(`optionAffinities` on Game; `strategyMix` on BotGroup).

**AD-R5 — Off = today's flat cadence.** When `rampEnabled` is false (or `rampShape
<= 0`), `rampAccepts` returns `true` unconditionally — every eligible tick proceeds,
byte-for-byte the current behavior. No RNG is drawn on the off path (preserves the
strategy's RNG-consumption order that `RandomBehaviorStrategyTest` pins — the ramp
draw happens *before* and *outside* the strategy, and only when ramp is on).

**AD-R6 — Game-type gating (AD-S1).** Ramp applies to `BETTING_MINI` and `TAI_XIU`
(both extend `BettingMiniGameBot`, share `betCondition`). SLOT
(`SlotMachineBot`) has no shared bet-window model and is excluded — the ramp params
are simply never set on non-eligible bots (guarded in `createSingleBot`, mirroring
the coordination-enabled guard). The window fraction relies on
`timeForBetting`/`remainingTime`, which only the betting-mini/TaiXiu path populates.

**AD-R7 — Observability.** Nullable `RampStateDTO ramp` on `BotGroupHealthDTO`
(`{ boolean enabled; double rampShape; }`) — thin, since ramp is stateless per-bot;
its runtime effect is already visible in the existing 5s UpdateBet aggregate (bet
timing distribution). No new per-round DEBUG line (would duplicate the aggregate);
per-tick defer/accept is TRACE only.

## Plan

### Phase J1 — Jackpot pool marker (message layer)
- New `com.vingame.bot.domain.bot.message.HasJackpotPool` with
  `long jackpotPool();` (javadoc: DISTINCT from `HasJackpot` — this is the live
  running meter (`tJpV`), not a per-bot payout (`jpV`/`iJp`)).
- Implement on `TipEndGameMessage` (`return tJpV;`), `BomEndGameMessage`,
  `B52EndGameMessage`, `NohuEndGameMessage` (each `return tJpV;`).
- **Parse `tJpV` on the shared `TaiXiuEndGameMessage`** (Findings decision): add
  `@JsonProperty("tJpV") long tJpV` as the final constructor param + the `tJpV`
  field (Lombok `@Getter`/`@Setter` already on the class), and implement
  `HasJackpotPool` with `return tJpV;`. This is what makes the P_114 live meter stop
  being dropped by `FAIL_ON_UNKNOWN_PROPERTIES=false`; the plain P_116 frame omits
  `tJpV` so it defaults to `0` (neutral). Keep `jpV`/`iJp` and the existing
  `HasJackpot`/`HasBotWinnings`/`HasBetTotals` behavior untouched. No jackpot-specific
  subclass; `JackpotTaiXiuMessageTypes.endGameType()` continues to return
  `TaiXiuEndGameMessage.class` unchanged.
- Unit tests:
  - Positive deserialization for each of the four G2/Tip products: assert
    `jackpotPool()` returns the fixture's `tJpV`.
  - **P_114 Tai Xiu positive test:** deserialize a 114 End frame (cmd 1104) with a
    live `tJpV` and assert `TaiXiuEndGameMessage` `instanceof HasJackpotPool` and
    `jackpotPool() == tJpV`. (Extend `TaiXiuMessageDeserializationTest`, which already
    uses `FAIL_ON_UNKNOWN_PROPERTIES=false`.)
  - **P_116 plain Tai Xiu neutral test:** deserialize a 116 End frame with no `tJpV`
    and assert `jackpotPool() == 0` (so AD-J5 maps it to neutral).
  - Assert the existing `jpV`/`iJp`-driven `jackpotFor()` (`HasJackpot`) is unchanged.

### Phase J2 — Jackpot Game config (2 fields)
- `Game` (`Game.java`): add `boolean jackpotScaleEnabled;` and
  `long jackpotCeiling;`.
- `GameDTO`: add `Boolean jackpotScaleEnabled;` and `Long jackpotCeiling;` (boxed,
  PATCH-null = keep).
- `GameMapper`: add both to `toDTO`, `toEntity`
  (`Optional.ofNullable(...).orElse(false/0L)`), and `updateEntityFromDTO`
  (replace-if-present, mirroring the existing scalar handling `:112-123`).
- Validation: when `jackpotScaleEnabled`, require `jackpotCeiling > 500000`
  (ceiling must exceed the seed floor, else the transfer function is degenerate —
  AD-J5 makes it neutral, but a soft 400 is friendlier). Add to `GameService.save`
  or the existing game validation path.
- `GameMapper` test + a `GameController` round-trip test for the two fields.

### Phase J3 — Jackpot scaler core + wiring
- New `com.vingame.bot.domain.bot.coordination.JackpotScaler` (scope-agnostic,
  AD-S2/AD-J8): constructor `(long ceiling, long seedFloor, double minMultiplier)`;
  `static final long DEFAULT_SEED_FLOOR = 500_000L`;
  `void observePool(long sessionId, long pool)` (first-seen idempotent per sid,
  under lock; recomputes `currentFactor` via AD-J5; emits the AD-J10 DEBUG line);
  `double getCurrentFactor()` (neutral `1.0` until a non-zero pool seen);
  `Snapshot snapshot()` for the DTO. Unit tests: factor = minMultiplier at/below
  seed; = 1.0 at/above ceiling; linear midpoint; neutral before first non-zero
  observation; raw `0` treated as not-observed; degenerate `ceiling <= seed` →
  neutral; idempotent `observePool` across repeated sid.
- `BotGroupRuntime`: add nullable `@Getter/@Setter JackpotScaler jackpotScaler`.
- `Bot`/`BettingMiniGameBot`: add null-tolerant `setJackpotScaler(...)` (fluent,
  mirroring `setCoordinator`) + `protected JackpotScaler jackpotScaler`.
- `BettingMiniGameBot`:
  - In `onEndGame`, after existing marker dispatch: `if (jackpotScaler != null &&
    msg instanceof HasJackpotPool hp) jackpotScaler.observePool(endGameSessionId(msg),
    hp.jackpotPool());`.
  - In `onStartGame`, snapshot the factor for the upcoming round into a `volatile
    double currentJackpotFactor` (`= jackpotScaler != null ?
    jackpotScaler.getCurrentFactor() : 1.0`).
  - Apply the factor to the per-round cap: in `buildBetContext()` pass an
    `effectiveMaxBetsPerRound = max(1, round(behavior.getMaxBetsPerRound() ×
    currentJackpotFactor))` (via a new `BetContext` field or a bot-computed value
    the strategy reads). **Preferred:** add `int effectiveMaxBetsPerRound` to
    `BetContext` and have strategies read `ctx.effectiveMaxBetsPerRound()` instead
    of `behavior.getMaxBetsPerRound()` — a one-line change in
    `RandomBehaviorStrategy.decide:74` and `MartingaleStrategySupport`, defaulting
    to `behavior.getMaxBetsPerRound()` when the factor is 1.0. (Dev: confirm both
    strategy call sites; keep the default path identical.)
- `BotGroupBehaviorService.start()` (near the coordinator build, `:283-294`): if
  `game.jackpotScaleEnabled` **and** `(game.getGameType() == BETTING_MINI ||
  game.getGameType() == TAI_XIU)`, build `new JackpotScaler(game.getJackpotCeiling(),
  JackpotScaler.DEFAULT_SEED_FLOOR, minMultiplier)` and
  `runtime.setJackpotScaler(...)` — same eligibility guard as the coordinator/ramp,
  no per-type branch or skip-log. A Tai Xiu game with no live `tJpV` on the wire
  simply never observes a non-zero pool and stays neutral (AD-J3/AD-J5).
- Inject into each bot in the `startBot` loop (`:303-308`):
  `bot.setJackpotScaler(runtime.getJackpotScaler());` before `runtime.startBot(bot)`.
- (Optional, AD-J9) if both `coordinator` and `jackpotScaler` are non-null, wire the
  factor into the coordinator's per-round cap. Defer if it complicates Phase 3;
  jackpot-scale is complete without it.

### Phase J4 — Jackpot read-side observability
- New `JackpotScaleStateDTO` (`{ boolean enabled; long jackpotCeiling; long
  seedFloor; long lastObservedPool; double currentFactor; double minMultiplier; }`).
- Add nullable `JackpotScaleStateDTO jackpotScale` to `BotGroupHealthDTO`.
- In `getHealth` (`:836`), populate from `runtime.getJackpotScaler()` via a
  `buildJackpotScaleState(scaler)` helper returning `null` when the scaler is null
  (mirroring `buildCoordinationState:848`).

### Phase R1 — Ramp BotGroup config (2 fields)
- `BotGroup`: add `boolean rampEnabled;` and `double rampShape;`.
- `BotGroupDTO`: add `Boolean rampEnabled;` and `Double rampShape;`.
- `BotGroupMapper`: add both to `toDTO`, `toEntity` (default `false`/`0.0`), and
  `updateEntityFromDTO`.
- `BotBehaviorConfig`: add `boolean rampEnabled;` `double rampShape;`.
- `createSingleBot` (`:522-538`): set `.rampEnabled(...)` / `.rampShape(...)` from
  the group **only** for `BETTING_MINI`/`TAI_XIU` (guard; leave defaults for SLOT).
- Validation: when `rampEnabled`, require `rampShape > 0` (a soft 400).
- `BotGroupMapper` test for the two fields.

### Phase R2 — Ramp bet-path seam
- `BettingMiniGameBot`: add `private boolean rampAccepts()`:
  ```
  BotBehaviorConfig b = configuration.getBehaviorConfig();
  if (!b.isRampEnabled() || b.getRampShape() <= 0) return true;      // AD-R5: off = today
  long window = timeForBetting;
  if (window <= 0) return true;                                       // fail-safe
  double x = 1.0 - Math.max(0, remainingTime.get()) / (double) window; // elapsedFraction
  double pMin = RAMP_P_MIN;                                           // constant, e.g. 0.15
  double pAccept = pMin + (1 - pMin) * Math.pow(clamp01(x), b.getRampShape());
  return rng.nextDouble() < pAccept;                                  // draw only when ramp on
  ```
- In `betCondition()` (`:717`), after `canBet()`/`strategy == null` guards and
  **before** `decideBet(ctx)`: `if (!rampAccepts()) { log.trace("Bot {}: ramp
  deferred tick", getUserName()); return false; }`. This ensures a deferred tick
  touches neither the strategy counter nor the coordinator.
- Unit tests (`BettingMiniGameBotTest` or a focused test): ramp off → every tick
  accepted, RNG untouched (assert `rng` not consumed on the off path); linear shape
  → accept rate rises with elapsed fraction; `k > 1` back-loads more than `k = 1`;
  `window <= 0` → accept.

### Phase R3 — Ramp read-side observability
- New `RampStateDTO` (`{ boolean enabled; double rampShape; }`).
- Add nullable `RampStateDTO ramp` to `BotGroupHealthDTO`.
- In `getHealth`, populate from the group's config when the group is running and
  `rampEnabled` (read off any bot's `BotBehaviorConfig`, or off the loaded
  `BotGroup` — prefer the group entity, single source), else `null`.

## Implementation Notes / Concerns

- **Interval is immutable — do not try to vary it.** The ramp MUST be a per-tick
  accept gate; changing `resolveIntervalBetweenBets()` to a per-round value has no
  effect after scenario build (`SendAsync.java:158` captures it once). Any Dev
  attempt to "speed up the tick near close" is a dead end.
- **RNG-order regression.** `RandomBehaviorStrategyTest` pins the exact RNG
  consumption order of the strategy. The ramp draw (`rng.nextDouble()`) happens in
  `betCondition()` **before and outside** `decideBet`, and **only when ramp is on**.
  The off path must draw nothing (AD-R5). Verify the equivalence test still passes
  with ramp off.
- **`0` pool is ambiguous.** A raw `tJpV == 0` is Jackson's default for a missing
  field AND a legitimately-empty pool. AD-J5 treats "no non-zero observed yet" as
  neutral (`f = 1.0`). Do not map a `0` reading to `f = minMultiplier` — that would
  throttle a group to the floor the instant a frame omits the field.
- **Tai Xiu jackpot trap.** `TaiXiuEndGameMessage` implements `HasJackpot` but must
  **NOT** implement `HasJackpotPool`. Its `jpV` is a per-win value, not a meter
  (Findings). Do not be tempted to read `jpV` as the pool — it is `0` on every
  non-discharge round and would collapse the scale.
- **`tJpv2` is not the pool.** Read `tJpV` only. `tJpv2` (present on all products)
  is an undocumented secondary field — out of scope (Open Item).
- **One-round lag is correct, not a bug.** The factor reacts to the *previous*
  round's meter (AD-J7) — this is the roadmap's "scale next round's volume."
- **Factor applied to `maxBetsPerRound`, not amount.** Do not scale the bet
  *amount* — that would fight the grid/coordinator and change stake distribution.
  Scale *how many* bets a bot places (AD-J4).
- **Both features composable and independent.** A group may enable ramp, jackpot,
  both, or neither, with/without coordination. Verify the four corners on the off
  paths (AD-S3) — that is the only regression-sensitive surface.
- **Concurrency mirror.** `JackpotScaler` reuses the coordinator's lock+volatile
  model (BET_COORDINATION AD-3): `observePool` under lock, `getCurrentFactor` a
  lock-free volatile read. `ReentrantLock` (not `synchronized`) to avoid pinning
  virtual threads.

## Open Items

- **`seedFloor` as a per-Game field — deferred (AD-J6).** Constant ≈500k for v1; no
  product deviates. Add a Game field only if a product with a different reset shows
  up.
- **`tJpv2` / secondary jackpot tier — out of scope.** v1 reads `tJpV`. Revisit if a
  product's primary meter turns out to be `tJpv2`.
- **AD-J9 coordinator-cap composition — optional.** May ship in Phase J3 or defer;
  jackpot-scale is complete via the per-bot lever alone.
- **Tai Xiu Subscribe-meter seeding — deferred (AD-J7).** Tai Xiu (and BOM/etc.)
  also carry `tJpV` on the Subscribe response; v1 reads the meter only at EndGame for
  a uniform one-round lag. Seeding from Subscribe would shave one warm-up round —
  revisit only if the first-round warm-up proves visible.
- **Fleet ownership — future.** `JackpotScaler` is scope-agnostic (AD-S2); a Fleet
  can own one spanning groups. Fleet entity itself is out of scope (roadmap #1).

## Open Decisions — RESOLVED (user-confirmed 2026-07-08)

All decisions below are **confirmed as recommended**; no open items remain for Dev.
Recorded here for traceability:

1. **Jackpot volume lever (AD-J4) — CONFIRMED:** modulate per-bot `maxBetsPerRound`
   by the factor. (`betSkipPercentage` / participation-fraction / coordinator-cap-only
   rejected as documented in AD-J4.)
2. **Jackpot minimum multiplier (AD-J4/AD-J5) — CONFIRMED:** `minMultiplier = 0.25`,
   held as a **constant** (not a per-group/per-game field). A quiet-but-alive 25%
   floor at seed — baseline presence, never zero.
3. **Jackpot ceiling (AD-J1) — CONFIRMED:** **operator-set per Game, no baked
   default**; validation requires `jackpotCeiling > 500000`.
4. **Ramp shape/curve model (AD-R3) — CONFIRMED:** power curve
   `pAccept = 0.15 + 0.85·x^k` (`pMin = 0.15` constant, `k = rampShape`; `k=1`
   linear, `k>1` back-loaded, `k<=0`/off = flat = today).
5. **Ramp config placement (AD-R4) — CONFIRMED:** on the **BotGroup** (behavior
   preference); jackpot-scale on the **Game** (game-intrinsic).

**Jackpot Tai Xiu support (post-review course-correction, user-confirmed):** Tai Xiu
IS supported for jackpot-scale via the P_114 `tJpV` meter (AD-J2/AD-J3/Phase J1); it
is not gated out. A meterless Tai Xiu game degrades to neutral.

## Verification

Run on staging after deploy. Prerequisites: for jackpot-scale, a group on a
jackpot-bearing product whose environment reaches live rounds with a non-zero
`tJpV` — **the P_114 / RIK `taixiuJackpotPlugin` Tai Xiu group is the natural
vehicle** (its EndGame carries a live meter and P_114 is already verified end-to-end
at scale on staging per MEMORY), with BOM/B52/Nohu as BETTING_MINI alternatives; for
ramp, any BETTING_MINI or TAI_XIU group that reaches live rounds. Substitute the
staging base URL, a real group id `$GID`, and a real game id `$GAMEID`. Note (per
MEMORY): staging **TIP** WS-connect may be DNS-blocked (`tipclubgw-sock.stgame.win`)
— avoid Tip for the live-round jackpot checks; prefer P_114 Tai Xiu or a BOM/B52/Nohu
group.

**1. Jackpot Game config round-trips (Phase J2).**
```
curl -s -X PATCH $BASE/api/v1/game/$GAMEID -H 'Content-Type: application/json' \
  -d '{"jackpotScaleEnabled":true,"jackpotCeiling":20000000}'
curl -s $BASE/api/v1/game/$GAMEID | jq '{jackpotScaleEnabled, jackpotCeiling}'
```
Expect `{"jackpotScaleEnabled": true, "jackpotCeiling": 20000000}`.

**2. Ramp BotGroup config round-trips (Phase R1).**
```
curl -s -X PATCH $BASE/api/v1/bot-group -H 'Content-Type: application/json' \
  -d '{"id":"'$GID'","rampEnabled":true,"rampShape":3.0}'
curl -s $BASE/api/v1/bot-group/$GID | jq '{rampEnabled, rampShape}'
```
Expect `{"rampEnabled": true, "rampShape": 3.0}`.

**3. Scaler builds on start for BETTING_MINI and TAI_XIU (Phase J3, AD-J3).** Start
the group (P_114 Tai Xiu or a BETTING_MINI group) and grep the app log.
```
curl -s -X POST $BASE/api/v1/bot-group/$GID/start
grep -E "Jackpot scaler created" <app-log> | grep "$GID"
```
Expect one startup line with the ceiling for any `jackpotScaleEnabled` group of type
BETTING_MINI **or** TAI_XIU (no type is skipped). Expect **no** scaler line for a
group with `jackpotScaleEnabled=false` or an ineligible type (SLOT/UP_DOWN).

**4. Per-round jackpot DEBUG summary (Phase J3, AD-J10).** Set DEBUG, observe one
line per round with pool + factor.
```
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
grep -E "JackpotScale sid=.* pool=.* factor=.* ceiling=" <app-log> | tail
```
Expect roughly one line per group per completed round; `pool` non-zero once the
meter is observed; `factor` in `[minMultiplier, 1.0]`.

**5. Jackpot health block + factor tracks pool (Phase J4, AD-J5).**
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.jackpotScale'
```
Expect a non-null object with `enabled=true`, `jackpotCeiling`, `seedFloor=500000`,
`lastObservedPool`, `currentFactor` (in `[minMultiplier,1.0]`), `minMultiplier=0.25`.
On the **P_114 Tai Xiu** group, confirm `lastObservedPool > 0` within a couple of
rounds (proof the newly-parsed `tJpV` reaches the scaler). Poll across several rounds:
as `lastObservedPool` rises toward `jackpotCeiling`, `currentFactor` rises toward
`1.0`; at/below `500000` it sits at `0.25`. For a group with
`jackpotScaleEnabled=false` (any type) or an ineligible type, expect
`.jackpotScale == null`. For a **plain P_116 Tai Xiu** group with the flag on but no
`tJpV` on the wire, expect the block present but `lastObservedPool=0` and
`currentFactor=1.0` (neutral — AD-J3/AD-J5).

**6. Jackpot volume actually scales (AD-J4, best-effort over ≥20 rounds).** With the
factor well below 1.0 (low pool), per-round bet counts per bot should sit below the
configured `maxBetsPerRound`; as the factor approaches 1.0, they approach the
configured max. Cross-check via the 5s UpdateBet aggregate DEBUG line's bet
histogram, or:
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.stats'
```
Expect aggregate bet volume to track `currentFactor` — lower when the pool is near
seed, higher as it grows. (Best-effort; noisy at low round counts.)

**7. Ramp health block (Phase R3).**
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.ramp'
```
Expect `{enabled:true, rampShape:3.0}` for a ramp-on group; `.ramp == null` when
`rampEnabled=false`.

**8. Ramp shifts bet timing toward window close (AD-R1, best-effort).** With ramp on
(`rampShape=3.0`) and TRACE enabled, per-tick defer/accept lines show early ticks
deferred and late ticks accepted:
```
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"TRACE"}'
grep -E "ramp deferred tick" <app-log> | tail
```
Expect deferrals concentrated early in the window; near window-close, ticks proceed
to bet. Compare against a ramp-off group (no `ramp deferred tick` lines, flat
cadence).

**9. Both-off path is unchanged (AD-S3).** Start a group with
`jackpotScaleEnabled=false` and `rampEnabled=false`; confirm `.jackpotScale == null`
and `.ramp == null` on its health, no `JackpotScale`/`ramp deferred` log lines for
its id, and bets flow every second as before. This is the only regression-sensitive
path; the universal smoke test otherwise applies.

Plan written: docs/plans/JACKPOT_SCALE_AND_RAMP.md
Ready for user approval before Dev begins.
