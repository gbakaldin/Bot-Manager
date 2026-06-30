# ENDGAME_METRICS — Re-seat Phase 4/5 extraction onto `EndGameMessage` subtypes

Owner: gleb · Target: replace the capability-flag value-getters on `BettingMiniGameBot` (shipped in commits `207851f` / `07d6e28`) with marker interfaces on `EndGameMessage` subtypes, and move authoritative bet recording to `onEndGame`.

This is an **infrastructure-only redesign**. After Dev finishes, zero per-game `EndGameMessage` subtypes will implement the new interfaces, so all `bot_winnings_total`, `bot_jackpot*_total`, `bot_bets_placed_total`, `bot_bet_amount_total` counters will remain at zero. The user implements the per-message getters separately.

> **Amendment 2026-06-08 — real-player share / game-aggregate metrics dropped entirely.** The `HasRoundTotals` marker interface (just shipped in Phase A), the matching `BotMetrics.incGameTotal*` methods + constants (shipped in OBSERVABILITY Phase 5), and the Grafana real-player panels are all being removed. Bot-side reconstruction of real-player winnings would require tracking every other player's per-position bets via UpdateBet (2 Hz × 6 positions for Bom) plus inferring payouts from `pR` × outcome × per-player bet history — the wiring cost outweighs the value. The user will instrument the game server directly later, on a separate dashboard. **Phase A.5 (added below) is the cleanup; Phase B and Phase C are unchanged.** `HasBotWinnings`, `HasJackpot`, `HasBetTotals` survive — those have viable implementations.

---

## Goal

Phase 4 and Phase 5 of `docs/plans/OBSERVABILITY.md` shipped Architecture Decision 13: per-game variability is exposed via plain virtual methods on `BettingMiniGameBot` (`getWinnings()`, `getJackpot()`, `canCheckTotalWinnings()`, `getTotalWinnings()`, `getRoundTotalBetAmount()`), defaults of 0/false, subclasses override later. That seam is now judged wrong on two counts:

1. **Capability-flag on the bot is the wrong layer.** Whether a round's payload exposes the bot's own payout, the game-wide totals, or a jackpot is a property of the **message schema**, not of the betting bot. `BettingMiniGameBot` is the single concrete bot for 100+ games across 30 environments; using subclassing as the per-game extraction seam either forces the bot hierarchy to fan out (which the codebase has actively avoided — see `CLAUDE.md` § "Architecture Decisions") or forces field-stashing (`onEndGame` parks the message into instance state so subsequent getter calls can read it).
2. **Plain `getX()` is context-free.** The getters never receive the message they are meant to extract from. They depend on a hidden contract that `BettingMiniGameBot.onEndGame` will mutate per-instance state before calling them.

We replace this with **marker interfaces on `EndGameMessage` subtypes**. `BettingMiniGameBot.onEndGame` does `instanceof`-dispatch and the message owns extraction. As a bonus, moving bet-count + bet-amount increments from `Bot.creditBalance` into `onEndGame`'s `HasBetTotals` branch lets the server's EndGame payload be the source of truth for what bets actually landed, instead of the bets-sent count which doesn't know about server-side rejection.

---

## Findings — Current State

### What shipped under OBSERVABILITY Phases 4+5

Verified at HEAD (`f436223`, branch `feat/observability-metrics`).

| Symbol | Location | Role |
|---|---|---|
| `BettingMiniGameBot.getWinnings()` | `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:229` | default `return 0L`; capability hook for bot's own round payout |
| `BettingMiniGameBot.getJackpot()` | `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:236` | default `return 0L`; capability hook for jackpot won this round |
| `BettingMiniGameBot.canCheckTotalWinnings()` | `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:244` | default `return false`; gate for Phase 5 game-aggregate counters |
| `BettingMiniGameBot.getTotalWinnings()` | `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:251` | default `return 0L`; round-wide sum of winnings across all players |
| `BettingMiniGameBot.getRoundTotalBetAmount()` | `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:265` | default `return 0L`; round-wide sum of bets across all players |
| `onEndGame` capability-flag dispatch | `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:186-211` | calls the five getters, increments `BotMetrics` counters under existing `metrics != null` guard, also writes `lastRoundWinnings` field |
| `Bot.creditBalance(long amount)` | `src/main/java/com/vingame/bot/domain/bot/core/Bot.java:574-579` | called from `BettingMiniGameBot.bet()` at line 297 each time the scenario fires a `Bet` request; increments `totalBetsPlaced`, `totalBetAmount`, and `BotMetrics.incBetPlaced(amount)` |
| `Bot.lastRoundWinnings` | `src/main/java/com/vingame/bot/domain/bot/core/Bot.java:74` | `protected volatile long`, `@Getter`, repurposed by Phase 4 to surface the per-round payout for `BotHealthDTO` |
| `BotMetrics.incBetPlaced(long)` | `src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:180-190` | dual counter: `bot_bets_placed_total` ++1, `bot_bet_amount_total` += amount. **Only caller in main src is `Bot.creditBalance:578`** (verified). Phase B moves that callsite onto a new batch method, leaving this single-bet method with zero production callers → deleted in Phase C. |
| `BotMetrics.incBotWinnings(long)` | `src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:201-206` | `bot_winnings_total` += amount |
| `BotMetrics.incBotJackpot(long)` | `src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:214-224` | dual counter: `bot_jackpots_total` ++1, `bot_jackpot_amount_total` += amount |
| ~~`BotMetrics.incGameTotalWinnings(long)`~~ | ~~`BotMetrics.java:234-239`~~ | **dropped 2026-06-08 — deleted by Phase A.5 below** |
| ~~`BotMetrics.incGameTotalBetAmount(long)`~~ | ~~`BotMetrics.java:245-250`~~ | **dropped 2026-06-08 — deleted by Phase A.5 below** |
| `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES` | `src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java:53-62` | 8-entry allow-list. **Phase A.5 removes the two `game_total_*` entries; reverts to 6.** |

### EndGame message shape (target of the new dispatch)

All three known concrete `EndGameMessage` subclasses are structurally identical (same field set, same `BetInfo` inner class, same `LastJackpotData` inner class):

| Class | Location |
|---|---|
| `EndGameMessage` (abstract) | `src/main/java/com/vingame/bot/domain/bot/message/EndGameMessage.java` |
| `BomEndGameMessage` | `src/main/java/com/vingame/bot/domain/bot/message/g2/bom/BomEndGameMessage.java` |
| `B52EndGameMessage` | `src/main/java/com/vingame/bot/domain/bot/message/g2/b52/B52EndGameMessage.java` |
| `NohuEndGameMessage` | `src/main/java/com/vingame/bot/domain/bot/message/g4/nohu/NohuEndGameMessage.java` |

Each carries `tJpV` (jackpot value), `iJp` (jackpot flag), `bs: List<BetInfo>` (per-entry-id bet aggregates: `eid`, `bc` bet count, `v` value), and `sid` (session id). **None of the current payloads carries a per-username row** — extracting a single bot's winnings or bets from these specific subtypes will require the user to either (a) cross-reference `bs` with the bot's own bet history kept on the bot, or (b) read fields not yet visible in the JSON. That's the user's problem to solve when they implement the interfaces; the architect's job is to make the seam expose the bot identity to the message so any extraction strategy is possible.

### Identifier the bot can pass to message extractors

The task description hypothesised `this.userId`. The codebase has no `userId`. The bot exposes:

- `Bot.userName` (`Bot.java:51`) — `String`, `@Getter`, set from `BotCredentials.getUsername()` at `Bot.java:105`. This is the same value used as `X-TOKEN` lookup and as the WS client name. **Use `userName`** — it's the only stable per-bot identifier and is already in MDC (`BotMdc.BOT_USER_NAME`).

There is no UUID `botId`; `BotMdc.BOT_ID` is the per-group `botIndex` int rendered as string and is not a global identifier.

### Subclass check

```
$ grep -rn "extends BettingMiniGameBot\|extends Bot " src/main/java
src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:43:public class BettingMiniGameBot extends Bot {
```

No subclasses of `BettingMiniGameBot`. No `implementation/` package. The five capability hooks can be removed without ripple to production code. The only override sites are in `BettingMiniGameBotTest` (anonymous subclasses for unit testing).

### Existing tests that exercise the Phase 4/5 shape

| Test | Location | What it asserts | Action |
|---|---|---|---|
| `BettingMiniGameBotTest.OnEndGameMetricsTests.shouldCallWinningsZeroAndSkipJackpotAndGameTotalsByDefault` | `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:518-545` | mocks `BotMetrics`, calls `onEndGame`, asserts `incBotWinnings(0L)` fires, jackpot/game-totals never called | Rewrite to assert that, with a vanilla `EndGameMessage` (mocked, implements none of the marker interfaces), no `incBotWinnings`/`incBotJackpot`/`incGameTotal*` is called — only `incBotMessage("endGame")` |
| `BettingMiniGameBotTest.OnEndGameMetricsTests.shouldEmitWinningsAndJackpotWhenSubclassOverrides` | `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:547-573` | uses `newSubclassWithWinnings(...)` which overrides the five `BettingMiniGameBot` hooks | Rewrite: build a stub `EndGameMessage` that implements `HasBotWinnings`+`HasJackpot` returning the constants, pass via `ActionResponseMessage`, assert `incBotWinnings(750L)` + `incBotJackpot(10_000L)` + `lastRoundWinnings == 750L` |
| `BettingMiniGameBotTest.OnEndGameMetricsTests.shouldEmitGameTotalsWhenCapabilityGateOpen` | `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:575-600` | overrides `canCheckTotalWinnings`+`getTotalWinnings`+`getRoundTotalBetAmount` | Rewrite: stub `EndGameMessage` implements `HasRoundTotals` returning (4750, 5000), assert `incGameTotalWinnings(4750L)` + `incGameTotalBetAmount(5000L)` |
| `BettingMiniGameBotTest.OnEndGameMetricsTests.shouldNoOpWhenMetricsNull` | `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:602-619` | passes `setMetrics(null)`, calls `onEndGame`, asserts no NPE and `gameState == PAYOUT` | Keep mostly intact — the null-guard test still applies. The `instanceof` checks must be inside the `if (metrics != null)` block so the test stays meaningful |
| `BettingMiniGameBotTest.OnEndGameMetricsTests.newSubclassWithWinnings(...)` helper | `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:627-660` | builds an anonymous `BettingMiniGameBot` with the five overrides | Delete the helper; replace with a stub-message factory that builds `EndGameMessage` subclasses implementing the new interfaces |
| `BettingMiniGameBotTest.LifecycleHandlerTests.shouldHandleOnEndGame` | `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:370-386` | passes a vanilla mocked `EndGameMessage`, asserts state transitions to PAYOUT | No change needed (no marker interface implemented = no metric calls, state machine path unchanged) |
| `BotTest.CreditBalanceTests.shouldDecrementAndIncrement` | `src/test/java/com/vingame/bot/domain/bot/core/BotTest.java:90-100` | asserts `creditBalance(500)` increments `totalBetsPlaced` and `totalBetAmount` AtomicLongs by 1 / 500 | **Keep as-is — local balance bookkeeping stays.** Add a new test asserting `creditBalance` no longer calls `BotMetrics.incBetPlaced` (mock and `verify(mocks, never()).incBetPlaced(anyLong())`) once the move lands |
| `BotTest.CreditBalanceTests.shouldAccumulate` | `src/test/java/com/vingame/bot/domain/bot/core/BotTest.java:102-113` | same shape | Same: keep, augment |
| `BotMetricsTest.incBetPlaced_incrementsBothCountAndAmount` | `src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java:111-123` | unit test of `BotMetrics.incBetPlaced` — unaware of caller | **Keep** — the method's contract is unchanged, only the callsite moves |
| `BotMetricsTest.Phase4/5 unit tests for incBotWinnings/incBotJackpot/incGameTotal*` | `src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java:304-389` | unit-tests `BotMetrics` API directly, message-agnostic | **Keep** — `BotMetrics` API is unchanged |

### Fixtures available

`src/test/resources/messages/bom/endGame.json`, `src/test/resources/messages/b52/endGame.json`, `src/test/resources/messages/nohu/endGame.json` exist and deserialize today via the registered `GameMessageTypes`. They cannot exercise the new `instanceof` branches **until the user implements at least one of the marker interfaces on at least one EndGame subclass** — which is explicitly out of scope. Dev tests must therefore stub the interfaces via either an anonymous subclass of an EndGame concrete type or a tiny test-only `EndGameMessage` that implements the marker(s). Recommend the latter (lower coupling to per-game JSON shape).

---

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| Remove `getWinnings()` / `getJackpot()` / `canCheckTotalWinnings()` / `getTotalWinnings()` / `getRoundTotalBetAmount()` from `BettingMiniGameBot` | **ready** | Phase C. No subclasses in main src; only test-side anonymous overrides to update |
| Add 3 marker interfaces under `domain/bot/message/` (`HasBotWinnings`, `HasJackpot`, `HasBetTotals`) | **shipped in Phase A; survive Phase A.5** | Sibling location to `EndGameMessage.java` |
| ~~Add 4th marker interface `HasRoundTotals`~~ | **dropped 2026-06-08** | Phase A.5 deletes the just-shipped `HasRoundTotals.java`, its dispatch branch, and its tests |
| `instanceof`-dispatch in `onEndGame` (three surviving markers) | **ready** | Existing `if (metrics != null)` guard stays as outer wrapper. Phase A.5 deletes the `HasRoundTotals` arm |
| Move bet-counter metric from `Bot.creditBalance` → `onEndGame` via `HasBetTotals` branch (calling new `incBetsPlaced`) | **ready** | Phase B. `creditBalance` keeps `totalBetsPlaced`/`totalBetAmount` local accumulators (read by `BotHealthDTO`); only the **metric** increment moves |
| Reuse `BotMetrics.incBotWinnings` / `incBotJackpot` unchanged | **ready** | Method signatures and meter names stay the same |
| ~~Reuse `BotMetrics.incGameTotalWinnings` / `incGameTotalBetAmount` unchanged~~ | **dropped 2026-06-08** | Phase A.5 deletes both methods + their constants + the gameType-only MeterFilter allow-list entries |
| Add `BotMetrics.incBetsPlaced(int count, long totalAmount)` batch overload (two-counter math, no average) | **shipped in Phase A** | New method; same dual-counter shape and MDC tags as the existing `incBetPlaced(long)` |
| Delete `BotMetrics.incBetPlaced(long)` once its only caller (`Bot.creditBalance`) stops using it | **ready** | Phase C deletion — single-bet API has zero callers after Phase B |
| Pass `userName` to the per-bot interface methods | **ready** | `Bot.userName` is the stable identifier; pass `this.getUserName()` from `onEndGame` |
| `BotMdcTagsMeterFilter` allow-list | **needs trim in Phase A.5** | Two `game_total_*` entries removed; allow-list shrinks from 8 to 6 |
| Per-message implementations of the surviving interfaces | **OUT OF SCOPE** | User does this work after Dev finishes |
| Per-game payload parsing (`bs` arrays, `tJpV` reads) | **OUT OF SCOPE** | User does this work after Dev finishes |

---

## Architecture Decisions

### AD-1. Marker interfaces live in `com.vingame.bot.domain.bot.message`

Place new interface files as siblings of `EndGameMessage.java`. They are part of the message-protocol vocabulary, not part of the bot or the observability infrastructure. No new package; no annotations; no Spring wiring. Plain Java interfaces with single `default`-free abstract methods.

### AD-2. Three marker interfaces (amended 2026-06-08: was four)

| Interface | Method(s) | Drives which counter |
|---|---|---|
| `HasBotWinnings` | `long winningsFor(String userName)` | `bot_winnings_total` (Phase 4 per-bot) |
| `HasJackpot` | `long jackpotFor(String userName)` | `bot_jackpots_total`, `bot_jackpot_amount_total` (Phase 4 per-bot) |
| `HasBetTotals` | `long betAmountFor(String userName)`, `int betCountFor(String userName)` | `bot_bets_placed_total`, `bot_bet_amount_total` (moved off `creditBalance`) |

**Why three, not fewer:**

- `HasBotWinnings` and `HasJackpot` are separate because jackpot is structurally distinct from regular payout in all three current games (jackpot has its own flag `iJp` and its own value `tJpV`, separate from any `bs[i].v` per-entry payout). A message might expose one and not the other (e.g. a game with no jackpot mechanic at all). Merging them would force `jackpotFor(...) → 0` stubs on every game that has winnings without jackpots.
- `HasBetTotals` is separate from `HasBotWinnings` because the user explicitly called this out in the task description ("some games may report bets but not winnings or vice versa") and because the `bs` payload already carries bet info but not user winnings.

**Why `HasRoundTotals` was dropped (2026-06-08):** computing real-player winnings from the bot side requires tracking every other player's per-position bets via UpdateBet messages (2 Hz × 6 positions for Bom; similar elsewhere) and inferring payouts from `pR` × outcome × per-player bet history. The wiring cost outweighs the value; the user will instrument the game server directly later for a separate real-player dashboard. Phase A.5 deletes the just-shipped `HasRoundTotals.java`, its dispatch branch, and its tests; `BotMetrics.incGameTotal*` + their constants + the `game_total_*` allow-list entries on `BotMdcTagsMeterFilter` are deleted in the same phase.

### AD-3. Identifier passed to per-bot extractors is `String userName`

`Bot.userName` (already a public `@Getter` accessor) is the only stable per-bot identifier and matches the `MDC.BOT_USER_NAME` key. Method signatures: `winningsFor(String userName)`, `jackpotFor(String userName)`, `betAmountFor(String userName)`, `betCountFor(String userName)`. The bot supplies `this.getUserName()` at the `onEndGame` callsite.

If a per-game message subclass's payload uses a different identifier shape (e.g. account id integer mapped via `BotCredentials.accountId`), the message implementation does the translation internally. We do not thread multiple identifier flavours into the interface signature — single `String userName` is the contract.

### AD-4. Bet-counter metrics migrate from `Bot.creditBalance` to `onEndGame`'s `HasBetTotals` branch via a new batch overload (replace, not supplement)

`Bot.creditBalance` keeps local AtomicLong bookkeeping (`totalBetsPlaced.incrementAndGet()`, `totalBetAmount.addAndGet(amount)`, and the `expectedCurrentBalance` debit) — these feed `BotHealthDTO` and are unchanged. **Only the `metrics.incBetPlaced(amount)` line moves.** Authoritative bet counting becomes server-side: bets-sent that the server rejected do not land in `bot_bets_placed_total` / `bot_bet_amount_total` anymore. Bot-side `totalBetsPlaced` (still readable from `BotHealthDTO`) continues to reflect bets-sent.

Add a new method `BotMetrics.incBetsPlaced(int count, long totalAmount)` that does the two independent counter increments directly: `bot_bets_placed_total += count`, `bot_bet_amount_total += totalAmount`. Same MDC tag shape as the existing single-bet method. **No loop, no per-bet average, no granularity loss** — per-bet average is `bot_bet_amount_total / bot_bets_placed_total` in PromQL and that math stays correct as long as both sums are accurate. `bot.HasBetTotals` branch in `onEndGame` calls the new method exactly once per EndGame.

The single-bet `incBetPlaced(long)` is deleted in Phase C because grep confirms `Bot.creditBalance:578` is its only main-src caller; once Phase B moves that callsite to the new batch method, the old method is dead code. Test-only callers in `BotMetricsTest` are retargeted to the new batch method (the underlying two-counter math is what the test asserts).

### AD-5. `onEndGame` dispatch order is: message-counter → bot-side per-bot branches (amended 2026-06-08)

```
metrics.incBotMessage("endGame");                  // unchanged
if (metrics != null) {
    if (msg instanceof HasBotWinnings hw) { ... }
    if (msg instanceof HasJackpot hj)     { ... }
    if (msg instanceof HasBetTotals hb)   { ... }
}
```

Independent `if` checks (not `else if`) — a message can implement multiple interfaces. The pre-amendment fourth arm (`HasRoundTotals`) is removed in Phase A.5.

### AD-6. `lastRoundWinnings` write stays inside the `HasBotWinnings` branch

`Bot.lastRoundWinnings` (`Bot.java:74`) is read by `BotHealthDTO`. Phase 4 introduced the write in `onEndGame`. Under the new dispatch it moves into the `HasBotWinnings` branch:

```java
if (msg instanceof HasBotWinnings hw) {
    long w = hw.winningsFor(getUserName());
    if (w > 0) metrics.incBotWinnings(w);
    lastRoundWinnings = w;
}
```

`lastRoundWinnings` is also written when `w == 0` (so a non-winning round overwrites a previous winning round in the UI). This matches the Phase 4 behaviour. Messages that do **not** implement `HasBotWinnings` leave `lastRoundWinnings` at its previous value — same as the pre-Phase-4 behaviour, and the user has stated this is acceptable for the demo narrative ("no implementer = stays at 0").

### AD-7. Caller-side amount guards stay (`> 0` checks)

`BotMetrics.incBotWinnings(long)` and `incBotJackpot(long)` are called only when the value is `> 0`. This is consistent with the existing dead-seconds guard in `BotMetrics.incBotDeadSeconds` and avoids creating zero-valued series that pollute Prometheus. `incBetPlaced` is called regardless of amount (a 0-amount bet is itself a signal worth counting); rely on `HasBetTotals.betCountFor(...) > 0` to gate.

(`incGameTotal*` guards moot post-2026-06-08 — those methods are deleted in Phase A.5.)

### AD-8. Marker interfaces are empty of implementers at end of this redesign

This is a deliberate, documented end state. The three `BotMetrics.inc*` methods (incBotWinnings, incBotJackpot, incBetsPlaced) stay registered but accumulate no values until the user implements at least one interface on at least one concrete `EndGameMessage` subclass. Grafana panels render exactly the same as today (with the corrected wiring) — "no data" or `0`. The "Verification" section confirms this is the expected post-deploy state.

(Amended 2026-06-08: was "five `BotMetrics.inc*` methods" — `incGameTotalWinnings` / `incGameTotalBetAmount` are removed in Phase A.5 along with the `HasRoundTotals` marker.)

### AD-9. No reflection, no annotations, no dynamic dispatch beyond `instanceof`

Java pattern-matching `instanceof` is the only dispatch mechanism. No `Class.isAssignableFrom`, no annotation scanning, no `Map<Class, Extractor>` lookup. This keeps the seam visible, debuggable in a stacktrace, and JIT-friendly on the netty-ws-message-processor hot path.

### AD-10. `BettingMiniGameBot.onEndGame` is the **only** callsite of these interfaces in this redesign

No other consumer (`Bot`, schedulers, REST controllers) calls `winningsFor` / `betAmountFor` etc. This keeps the contract narrow: a message implementing `HasBotWinnings` only promises to be queried during `onEndGame` of the bot that received it, on the bot's `userName`. If a future feature (e.g. session history persistence) wants to use the same extraction, that's a separate plan.

---

## Plan

Three phases. Each ends with a green `mvn test` and a residual-reference grep. The bot project compiles green at every phase boundary because (a) the four marker interfaces have zero implementers by design, (b) `Bot.creditBalance`'s local bookkeeping is preserved, (c) `BotMetrics` public methods are unchanged.

### Phase A — Introduce marker interfaces + new `instanceof` dispatch alongside the existing capability hooks

**Status (2026-06-08):** SHIPPED on branch `feat/observability-metrics` — see commits prior to this amendment. **Phase A.5 below reverts the `HasRoundTotals` parts of Phase A; everything else stays.** The Phase A step list is preserved as a historical record.

**Goal:** new dispatch wired and tested, old capability hooks still present and still called. Dev session is small enough to review in one sitting; deferring removal to Phase C lets Phase B's bet-counter move be reviewed independently.

**Steps:**

1. Create `src/main/java/com/vingame/bot/domain/bot/message/HasRoundTotals.java`:
   ```java
   /** Marker for EndGameMessage subtypes exposing round-wide aggregates
    *  (sum of all players' bets and winnings for the just-completed round). */
   public interface HasRoundTotals {
       long totalBetAmount();
       long totalWinnings();
   }
   ```
2. Create `src/main/java/com/vingame/bot/domain/bot/message/HasBotWinnings.java`:
   ```java
   /** Marker for EndGameMessage subtypes exposing a single bot's gross payout
    *  for the just-completed round. */
   public interface HasBotWinnings {
       long winningsFor(String userName);
   }
   ```
3. Create `src/main/java/com/vingame/bot/domain/bot/message/HasJackpot.java`:
   ```java
   /** Marker for EndGameMessage subtypes exposing a single bot's jackpot
    *  payout for the just-completed round. Returns 0 if the bot won no jackpot. */
   public interface HasJackpot {
       long jackpotFor(String userName);
   }
   ```
4. Create `src/main/java/com/vingame/bot/domain/bot/message/HasBetTotals.java`:
   ```java
   /** Marker for EndGameMessage subtypes exposing the authoritative server-side
    *  tally of how many bets a given user placed in the just-completed round and
    *  the total amount staked. */
   public interface HasBetTotals {
       long betAmountFor(String userName);
       int betCountFor(String userName);
   }
   ```
5. Add a new method to `BotMetrics` (`src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`) that accepts the server-side aggregate counts from a single `HasBetTotals` extraction and does the two independent counter increments directly. **No loop. No average.** Place it immediately after the existing `incBetPlaced(long)`:
   ```java
   /**
    * Record a batch of bets confirmed by the server's EndGame payload:
    * increments {@code bot_bets_placed_total} by {@code count} and
    * {@code bot_bet_amount_total} by {@code totalAmount}. Same per-bot tag
    * shape as {@link #incBetPlaced(long)}. Per-bet average is
    * {@code bot_bet_amount_total / bot_bets_placed_total} in PromQL and is
    * accurate as long as both sums are accurate — this method preserves
    * both sums exactly without making any per-bet-amount assumption.
    * <p>
    * Caller guards on {@code count > 0}; a zero-count call is a no-op but
    * still creates the counter on first invocation (mirrors the dead-seconds
    * silent-drop pattern).
    */
   public void incBetsPlaced(int count, long totalAmount) {
       if (count <= 0) return;
       Tags tags = mdcTags();
       Counter.builder(BOT_BETS_PLACED_TOTAL)
               .tags(tags)
               .register(registry)
               .increment(count);
       Counter.builder(BOT_BET_AMOUNT_TOTAL)
               .tags(tags)
               .register(registry)
               .increment(totalAmount);
   }
   ```
   Keep the existing `incBetPlaced(long)` in place for Phase A — its sole caller (`Bot.creditBalance:578`) is still using it. Phase B moves the caller; Phase C deletes the method.

6. In `BettingMiniGameBot.onEndGame` (`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:186-211`), **add the new `instanceof` dispatch alongside the existing capability-hook calls** — both run in this phase. New dispatch goes inside the existing `if (metrics != null)` block, immediately after `metrics.incBotMessage("endGame")` and before the capability-hook block. The message is `data.getData()` — unwrap once. Exact insertion:
   ```java
   private void onEndGame(ActionResponseMessage<? extends EndGameMessage> data) {
       if (metrics != null) metrics.incBotMessage("endGame");

       EndGameMessage msg = data.getData();
       if (metrics != null) {
           if (msg instanceof HasBotWinnings hw) {
               long w = hw.winningsFor(getUserName());
               if (w > 0) metrics.incBotWinnings(w);
               lastRoundWinnings = w;
           }
           if (msg instanceof HasJackpot hj) {
               long j = hj.jackpotFor(getUserName());
               if (j > 0) metrics.incBotJackpot(j);
           }
           if (msg instanceof HasBetTotals bt) {
               // Batch increment: bot_bets_placed_total += count,
               // bot_bet_amount_total += amount. Two-counter math, no average.
               metrics.incBetsPlaced(bt.betCountFor(getUserName()), bt.betAmountFor(getUserName()));
           }
           if (msg instanceof HasRoundTotals hr) {
               long tBet = hr.totalBetAmount();
               long tWin = hr.totalWinnings();
               if (tBet > 0) metrics.incGameTotalBetAmount(tBet);
               if (tWin > 0) metrics.incGameTotalWinnings(tWin);
           }

           // === Legacy capability-hook dispatch — REMOVED IN PHASE C ===
           long winnings = getWinnings();
           metrics.incBotWinnings(winnings);
           lastRoundWinnings = winnings;
           long jackpot = getJackpot();
           if (jackpot > 0) metrics.incBotJackpot(jackpot);
           if (canCheckTotalWinnings()) {
               metrics.incGameTotalWinnings(getTotalWinnings());
               metrics.incGameTotalBetAmount(getRoundTotalBetAmount());
           }
       }
       // ... rest of method unchanged ...
   }
   ```
   **The legacy block stays.** It still uses the (zero-by-default) capability hooks. In production, with zero implementers of either the old hooks or the new interfaces, both blocks emit nothing. The double-write only matters when a test simultaneously overrides both; tests in this phase exercise only the new path.

7. **Tests (new, additive — do not touch the existing capability-hook tests in this phase):**
   - `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldEmitNothingForVanillaEndGameMessage()` — pass a mocked `EndGameMessage` (implements no marker); assert only `incBotMessage("endGame")` is called on the mocked `BotMetrics`, no `incBotWinnings` / `incBotJackpot` / `incBetsPlaced` / `incGameTotal*`.
   - `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldExtractFromHasBotWinnings()` — build a test-only `EndGameMessage` subclass implementing `HasBotWinnings` returning 750L for the bot's userName; assert `metrics.incBotWinnings(750L)` and `bot.getLastRoundWinnings() == 750L`.
   - `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldExtractFromHasJackpot()` — symmetric, asserts `incBotJackpot(10_000L)` only when amount > 0; second case returns 0, asserts NO `incBotJackpot` call.
   - `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldExtractFromHasRoundTotals()` — implements `HasRoundTotals`, assert both `incGameTotalWinnings` and `incGameTotalBetAmount` fired with the returned values.
   - `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldExtractFromHasBetTotals()` — implements `HasBetTotals` returning count=3, amount=500; assert `metrics.incBetsPlaced(3, 500L)` called exactly once. Edge: `count=0` → still calls `incBetsPlaced(0, ...)` and the method itself silently no-ops (verified via separate `BotMetricsTest`); the bot side does not gate.
   - `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldDispatchAllInterfacesIfImplemented()` — single test message implements all four; assert all four counter paths fire in order: winnings → jackpot → bets → round-totals.
   - `BettingMiniGameBotTest.OnEndGameMarkerDispatchTests.shouldNoOpOnAllInterfacesWhenMetricsNull()` — `bot.setMetrics(null)`, message implements all four interfaces; assert no NPE, `gameState == PAYOUT`.
   - **New `BotMetricsTest.incBetsPlaced_*` tests** asserting the new batch method's two-counter math: `incBetsPlaced(3, 500)` → `bot_bets_placed_total += 3`, `bot_bet_amount_total += 500`; `incBetsPlaced(0, 0)` → no-op (both counters unchanged).

8. **Add a helper to the test class** to build a small `EndGameMessage` subclass implementing any combination of markers. Suggested shape:
   ```java
   private static EndGameMessage stubEnd(...flags...) {
       return new EndGameMessage(0) implements HasBotWinnings, ... { ... };
   }
   ```
   (Dev: anonymous inner subclasses can't add interfaces; use a static test-package class like `StubEndGameMessage` with constructor flags or named inner classes per combination. Pick whichever is least verbose.)

**Verification at end of Phase A:**
```bash
# Build
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn -q clean test

# New interfaces exist
find src/main/java/com/vingame/bot/domain/bot/message -maxdepth 1 -name "Has*.java"
# Expect: HasRoundTotals.java HasBotWinnings.java HasJackpot.java HasBetTotals.java

# No production implementer yet
grep -rn "implements.*HasRoundTotals\|implements.*HasBotWinnings\|implements.*HasJackpot\|implements.*HasBetTotals" src/main/java
# Expect: 0 matches

# Old capability hooks still present
grep -n "protected long getWinnings\|protected boolean canCheckTotalWinnings" src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java
# Expect: lines 229 / 244 still present
```

**Independence:** depends on nothing. Unblocks Phase A.5, Phase B and Phase C.

---

### Phase A.5 — Cleanup: drop `HasRoundTotals` + game-aggregate counters + Grafana real-player panels (added 2026-06-08)

**Status:** NEW phase, NOT YET SHIPPED. Reverts the `HasRoundTotals` slice of Phase A and the `game_total_*` slice of OBSERVABILITY Phase 5. The remaining three marker interfaces (`HasBotWinnings`, `HasJackpot`, `HasBetTotals`) and their dispatch survive untouched.

**Goal:** delete every reference to the bot-side real-player share / game-aggregate metrics — the marker interface, the `BotMetrics` methods + constants, the `BotMdcTagsMeterFilter` allow-list entries, the tests, and the Grafana panels. Leave room for the user's later game-server-side instrumentation (separate dashboard, separate codebase concern).

**Scope guard:** the `grafana/provisioning/dashboards/game-server.json` dashboard itself stays. Only the three real-player panels go (panels 8, 9, 10). The other panels on that dashboard (login success rate, verify-token success rate, group DEAD seconds, message rate by `cmd`, watchdog firings, WS event rate, login/verify attempts by outcome) are unrelated and must survive.

**Steps:**

1. **Delete the marker interface file.**
   ```
   src/main/java/com/vingame/bot/domain/bot/message/HasRoundTotals.java
   ```
   (just shipped in Phase A; no production implementer in main src — see step 2 for the one B52 callsite).

2. **Remove the one production implementer.** `src/main/java/com/vingame/bot/domain/bot/message/g2/b52/B52EndGameMessage.java` currently declares `implements HasRoundTotals` (at the class declaration, line 15) and overrides `totalBetAmount()` + `totalWinnings()` returning 0 (lines 64-72). Both the `implements` clause and the two `@Override` methods are deleted; the corresponding `import com.vingame.bot.domain.bot.message.HasRoundTotals;` (line 7) is also removed. The class otherwise stays exactly as-is — its other fields/getters (`bs`, `tJpV`, etc.) are still useful for `HasBotWinnings` / `HasJackpot` extraction the user will write later.

3. **Remove the `instanceof HasRoundTotals` dispatch branch from `BettingMiniGameBot.onEndGame`** (`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`). At HEAD this is lines 214-219:
   ```java
   if (msg instanceof HasRoundTotals hr) {
       long tBet = hr.totalBetAmount();
       long tWin = hr.totalWinnings();
       if (tBet > 0) metrics.incGameTotalBetAmount(tBet);
       if (tWin > 0) metrics.incGameTotalWinnings(tWin);
   }
   ```
   Delete the entire arm. Also delete `import com.vingame.bot.domain.bot.message.HasRoundTotals;` at line 16. The three surviving branches (`HasBotWinnings`, `HasJackpot`, `HasBetTotals`) above it stay verbatim. The legacy capability-hook block below (lines 226-242 today, scheduled for deletion in Phase C) also stays untouched in this phase.

4. **Delete from `BotMetrics`** (`src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`):
   - The two constants at lines 67-68:
     ```java
     public static final String GAME_TOTAL_WINNINGS_TOTAL = "game_total_winnings_total";
     public static final String GAME_TOTAL_BET_AMOUNT_TOTAL = "game_total_bet_amount_total";
     ```
   - The `gameTypeTagOnly()` private helper at lines 104-110 (only callers are the two `incGameTotal*` methods being deleted in the next bullet).
   - `incGameTotalWinnings(long)` at lines 260-273 (including its javadoc).
   - `incGameTotalBetAmount(long)` at lines 275-284 (including its javadoc).
   - Update the class-level javadoc at lines 64-66 — drop the "Phase 5 — real-player share aggregates" comment block that currently introduces the deleted constants.

5. **Trim `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES`** (`src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java`, lines 53-62). Remove these two entries:
   ```java
   "game_total_winnings_total",
   "game_total_bet_amount_total"
   ```
   Allow-list shrinks from 8 entries to 6 (`bots_managed`, `bot_groups_running`, `ws_connections_open`, `bots_by_status`, `bots_dead_currently`, `groups_dead_currently`). Update the comment block at lines 47-52 — drop the "Phase 5 amendment" paragraph that documents the `game_total_*` allow-list rationale.

6. **Delete tests from `BotMetricsTest`** (`src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java`):
   - `incGameTotalWinnings_attachesOnlyGameTypeTag()` at lines 403-420.
   - `incGameTotalBetAmount_attachesOnlyGameTypeTag()` at lines 422-437.
   - `incGameTotalWinnings_emptyMdc_omitsGameTypeTag()` at lines 439-451.
   - The `/* ----- Phase 5 — game-aggregate counters with gameType-only tags ----- */` separator comment at line 401.

7. **Delete tests + stub-class plumbing from `BettingMiniGameBotTest`** (`src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java`):
   - `import com.vingame.bot.domain.bot.message.HasRoundTotals;` at line 10.
   - `shouldExtractFromHasRoundTotals()` test method at lines 726-744 (including `@Test` + `@DisplayName` annotations).
   - From the `StubEndGameMessage` static inner class at lines 833-907:
     - Remove `HasRoundTotals` from the `implements` clause (line 834).
     - Delete fields `roundTotalBetAmount` and `roundTotalWinnings` (lines 846-847).
     - Delete the `withRoundTotals(long, long)` builder method (lines 872-876).
     - Delete the `@Override public long totalBetAmount()` method (lines 898-901).
     - Delete the `@Override public long totalWinnings()` method (lines 903-906).
   - From `shouldDispatchAllInterfacesIfImplemented()` at lines 766-792:
     - Remove the `.withRoundTotals(5_000L, 4_750L)` call (line 778).
     - Remove the two `order.verify(metrics).incGameTotalBetAmount(...)` / `incGameTotalWinnings(...)` lines (lines 790-791).
   - From `shouldNoOpOnAllInterfacesWhenMetricsNull()` at lines 796-814:
     - Remove the `.withRoundTotals(1_000L, 900L)` call (line 806).
   - From the existing `OnEndGameMetricsTests` legacy block (the pre-Phase-A capability-hook tests still present per the table in Findings; the whole nested class is deleted in Phase C, but for A.5 we just keep it compiling):
     - Lines 546-547 (inside `shouldCallWinningsZeroAndSkipJackpotAndGameTotalsByDefault`) contain `verify(metrics, never()).incGameTotalWinnings(...)` / `incGameTotalBetAmount(...)`. Delete those two lines.
     - Lines 572-573 (inside `shouldEmitWinningsAndJackpotWhenSubclassOverrides`) — same two `never()` assertions. Delete.
     - Lines 660-661 (inside the Phase-A `shouldEmitNothingForVanillaEndGameMessage`) — same two `never()` assertions on `incGameTotal*`. Delete.
     - The entire `shouldEmitGameTotalsWhenCapabilityGateOpen` test at lines 581-606 must be deleted — it asserts `incGameTotalWinnings(4750L)` and `incGameTotalBetAmount(5000L)` fire, and the methods being called are gone after step 4. Its sibling tests (`shouldCallWinningsZeroAndSkipJackpotAndGameTotalsByDefault`, `shouldEmitWinningsAndJackpotWhenSubclassOverrides`, `shouldNoOpWhenMetricsNull`) survive — they still validate the legacy capability-hook branch that Phase C deletes later.
     - The `newSubclassWithWinnings(...)` helper at lines 939+ still has `canCheckTotalWinnings`, `getTotalWinnings`, `getRoundTotalBetAmount` overrides. These compile (the capability hooks are still on `BettingMiniGameBot` until Phase C), so the helper stays untouched in A.5. **No edit to the helper in A.5.** Phase C deletes the helper alongside the capability hooks.
   - The TODO comment at line 817 mentions `HasRoundTotals` in the future-fixture list — strike that one interface name from the comment.

8. **Delete real-player panels from `grafana/provisioning/dashboards/game-server.json`.** The file holds 10 panels; the three to delete are:
   - Panel `"id": 8`, title `"Bot bet share per game (5m)"`, JSON object spans lines 304-342.
   - Panel `"id": 9`, title `"Real-player bet share per game (5m)"`, spans lines 343-381.
   - Panel `"id": 10`, title `"Real-player RTP per game (5m)"`, spans lines 382-419.

   The objects appear consecutively in the `"panels": [...]` array. Delete all three (including the trailing comma between panel 7 and panel 8). Panels 1-7 (login success, verify-token success, group DEAD seconds, message rate by cmd, watchdog firings, WS event rate, login/verify attempts) survive. Dashboard JSON is otherwise unchanged — no `id` renumbering needed; the missing ids don't break Grafana.

9. **No other files touched.** `bots.json` is unaffected (its Phase 4 RTP / winnings panels reference `bot_winnings_total` / `bot_bet_amount_total` only — none of the `game_total_*` series). `application.properties`, `docker-compose.yml`, `prometheus.yml` need no edits.

**Verification at end of Phase A.5:**

```bash
# Build + tests green
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn -q clean test

# Zero references to the dropped surface across main + test sources
grep -rn "HasRoundTotals\|incGameTotal\|GAME_TOTAL_" src/main/java src/test/java
# Expect: 0 lines

# The marker file is gone
ls src/main/java/com/vingame/bot/domain/bot/message/Has*.java
# Expect: HasBetTotals.java HasBotWinnings.java HasJackpot.java   (three files, no HasRoundTotals.java)

# B52EndGameMessage no longer mentions HasRoundTotals
grep -n "HasRoundTotals" src/main/java/com/vingame/bot/domain/bot/message/g2/b52/B52EndGameMessage.java
# Expect: 0 lines

# Allow-list is back to 6 entries
grep -c '"' src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java | head -1
# (informational — the canonical check is reading the file and confirming six string literals
#  in AGGREGATE_METER_NAMES)

# game-server.json has 7 panels left (Phase 5 panels gone)
grep -c '"title"' grafana/provisioning/dashboards/game-server.json
# Expect: 8 (the dashboard's own top-level "title" + 7 panel titles)
```

**Independence:** depends on Phase A (the things being deleted shipped in Phase A and OBSERVABILITY Phase 5). Independent of Phase B and Phase C — Phase A.5 deletes a disjoint slice of code from what those phases touch.

---

### Phase B — Move bet-counter metric increment from `Bot.creditBalance` to `onEndGame`'s `HasBetTotals` branch

**Goal:** authoritative bet recording via server payload. Local bookkeeping unchanged.

**Steps:**

1. In `Bot.creditBalance` (`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:574-579`), delete only the metric line. The new body:
   ```java
   protected void creditBalance(long amount) {
       this.expectedCurrentBalance.addAndGet(-amount);
       this.totalBetsPlaced.incrementAndGet();
       this.totalBetAmount.addAndGet(amount);
       // Bet counters (bot_bets_placed_total, bot_bet_amount_total) moved to
       // BettingMiniGameBot.onEndGame HasBetTotals branch — authoritative
       // server-side recording (see docs/plans/ENDGAME_METRICS.md AD-4).
   }
   ```
   The `HasBetTotals` branch added in Phase A already calls `metrics.incBetsPlaced(count, amount)`. No new code in `onEndGame`.

2. **Tests to update:**
   - `BotTest.CreditBalanceTests.shouldDecrementAndIncrement` (`src/test/java/com/vingame/bot/domain/bot/core/BotTest.java:90-100`) — add a `verify(metrics, never()).incBetPlaced(anyLong())` AND `verify(metrics, never()).incBetsPlaced(anyInt(), anyLong())` assertion. Local AtomicLong assertions stay.
   - `BotTest.CreditBalanceTests.shouldAccumulate` — same additions.
   - Add `BotTest.CreditBalanceTests.shouldNotEmitMetricsCounter` — explicitly assert `verifyNoInteractions(metrics)` after `creditBalance(500)`.

3. **Test to leave alone:**
   - `BotMetricsTest.incBetPlaced_incrementsBothCountAndAmount` (`src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java:111-123`) — unaffected for now. The single-bet method's API is unchanged in Phase B; this test gets removed in Phase C alongside the method itself. The new `incBetsPlaced` batch tests added in Phase A cover the moved semantics.

**Verification at end of Phase B:**
```bash
JAVA_HOME=... mvn -q clean test

# creditBalance no longer mentions BotMetrics
grep -n "metrics" src/main/java/com/vingame/bot/domain/bot/core/Bot.java | grep -i "credit\|incBet\|BotMetrics"
# Expect: only comments/field declarations, no inc call

# incBetPlaced (single-bet) is now unused in main src — only the definition remains
grep -rn "incBetPlaced" src/main/java
# Expect: BotMetrics.java (definition) only. No BettingMiniGameBot.java match.
#         No Bot.java match.

# incBetsPlaced (batch) has exactly one caller in main src — the onEndGame HasBetTotals branch
grep -rn "incBetsPlaced" src/main/java
# Expect: BotMetrics.java (definition) + BettingMiniGameBot.java (one call site)
```

**Independence:** depends on Phase A. Independent of Phase C.

---

### Phase C — Remove the obsolete capability hooks + obsolete test paths

**Goal:** delete dead code. Make the new seam the only seam.

**Steps:**

1. Delete from `BettingMiniGameBot.java`:
   - `getWinnings()` at line 229
   - `getJackpot()` at line 236
   - `canCheckTotalWinnings()` at line 244
   - `getTotalWinnings()` at line 251
   - `getRoundTotalBetAmount()` at line 265
   - The "legacy capability-hook dispatch" block inside `onEndGame` (added under Phase A as the second half of the `if (metrics != null)` body).

2. Delete from `BotMetrics.java`:
   - `incBetPlaced(long)` at line 180-190 — now has zero main-src callers (Phase B moved its only caller, and Phase A wired the new `HasBetTotals` branch onto `incBetsPlaced(int, long)` instead). Verify with `grep -rn "incBetPlaced" src/main/java` before deleting; expect only the definition line.

3. The final `onEndGame` body (in full, post-Phase-C; amended 2026-06-08 — `HasRoundTotals` arm already removed by Phase A.5):
   ```java
   private void onEndGame(ActionResponseMessage<? extends EndGameMessage> data) {
       if (metrics != null) metrics.incBotMessage("endGame");

       EndGameMessage msg = data.getData();
       if (metrics != null) {
           if (msg instanceof HasBotWinnings hw) {
               long w = hw.winningsFor(getUserName());
               if (w > 0) metrics.incBotWinnings(w);
               lastRoundWinnings = w;
           }
           if (msg instanceof HasJackpot hj) {
               long j = hj.jackpotFor(getUserName());
               if (j > 0) metrics.incBotJackpot(j);
           }
           if (msg instanceof HasBetTotals bt) {
               metrics.incBetsPlaced(bt.betCountFor(getUserName()), bt.betAmountFor(getUserName()));
           }
       }

       gameState = BettingMiniGameState.PAYOUT;
       if (scheduler != null) {
           scheduler.shutdownNow();
           scheduler = null;
       }
       scheduleWatchdog();
       onNewSession();
   }
   ```

4. Delete from `BettingMiniGameBotTest`:
   - The entire `OnEndGameMetricsTests` nested class (`src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:512-620`) — replaced by Phase A's `OnEndGameMarkerDispatchTests`.
   - The `newSubclassWithWinnings(...)` helper (`src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java:627-660`) — no longer has callers.
   - The `seedBalance(...)` helper at line 662 — was only used by `newSubclassWithWinnings`. **Verify** with grep before deleting; if `seedBalance` ends up unused elsewhere, drop it too.
   - **Keep** `LifecycleHandlerTests.shouldHandleOnEndGame` — vanilla state-machine test, message implements no markers, still valid.

5. Delete from `BotMetricsTest`:
   - `incBetPlaced_incrementsBothCountAndAmount` (`src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java:111-123`) — the single-bet method is gone, the batch method's tests added in Phase A cover the surviving contract.

6. Update any javadoc / comments in `BettingMiniGameBot` that reference the capability-flag pattern. The `BotMetrics.incBotWinnings` javadoc at `BotMetrics.java:192-200` mentions "Called unconditionally from `BettingMiniGameBot.onEndGame`" — amend to "Called from `BettingMiniGameBot.onEndGame` when the EndGame message implements `HasBotWinnings`". Same for `incBotJackpot` and the surviving `incBetsPlaced` javadocs. (Note 2026-06-08: `incGameTotalWinnings` / `incGameTotalBetAmount` were already deleted by Phase A.5, so they have no javadoc to update here.)

**Verification at end of Phase C:**
```bash
JAVA_HOME=... mvn -q clean test

# Hooks fully removed
grep -n "getWinnings\|getJackpot\|canCheckTotalWinnings\|getTotalWinnings\|getRoundTotalBetAmount" src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java
# Expect: 0 matches

# No test-side references to the old hooks
grep -rn "getWinnings\|canCheckTotalWinnings\|getRoundTotalBetAmount" src/test
# Expect: 0 matches

# Surviving marker interfaces still present, still zero production implementers
# (HasRoundTotals removed in Phase A.5)
grep -rn "implements.*HasBotWinnings\|implements.*HasJackpot\|implements.*HasBetTotals" src/main/java
# Expect: 0 matches

# Single-bet incBetPlaced fully removed from main src and tests
grep -rn "incBetPlaced" src/main/java src/test/java
# Expect: 0 matches

# Batch incBetsPlaced has exactly one production caller — the HasBetTotals branch
grep -rn "incBetsPlaced" src/main/java
# Expect: BotMetrics.java (definition) + BettingMiniGameBot.java (one call site)
```

**Independence:** depends on Phases A and B.

---

## Implementation Notes / Concerns

1. **`Bot.totalBetsPlaced` / `Bot.totalBetAmount` divergence.** After Phase B, the AtomicLong accumulators in `Bot` (read by `BotHealthDTO.getTotalBetsPlaced()` / `getTotalBetAmount()`) count **bets sent**, while `bot_bet_amount_total` / `bot_bets_placed_total` Prometheus counters count **bets confirmed by EndGame payload** (only once any subclass implements `HasBetTotals` — until then, the counter stays at 0 forever). For the demo this is fine because both happen to be 0 today; once a `HasBetTotals` implementer ships, the two values can diverge if the game server rejects bets. Document this in the new `BotMetrics.incBetsPlaced` javadoc as part of Phase A.

2. **`lastRoundWinnings` is written every time, even at 0.** Same as current Phase 4 behaviour. A message implementing `HasBotWinnings` and returning 0 for `winningsFor(...)` will overwrite a previous winning round's `lastRoundWinnings` with 0. This is the Phase 4 contract; preserve it.

3. **`HasBotWinnings.winningsFor(userName)` returning a sentinel for "not found" vs "0 winnings".** The interface returns `long`. A bot whose userName isn't in the payload is indistinguishable from a bot that bet and won nothing. Both are legitimate "0" cases; the user's implementations should treat them identically. Documented as part of the `HasBotWinnings` javadoc (see AD-2).

4. **MDC threading on the `onEndGame` callback is already in place.** `BettingMiniGameBot.botBehaviorScenario()` wraps the handler with `mdcConsumer(this::onEndGame)` at line 399. Per-callsite MDC reads inside `BotMetrics.inc*` (already implemented) pick up `botGroupId`, `environmentId`, `gameType`, `botUserName`. No new wiring needed.

5. **`creditBalance` is `protected`, currently called only from `BettingMiniGameBot.bet()` at line 297.** Confirmed via grep. No other caller's behaviour changes when we remove the metric increment.

6. **`BotFactory.java:109` is the single production constructor site for `BettingMiniGameBot`.** No factory changes needed — `BotMetrics` injection (line 110 `setMetrics(...)`) is unchanged.

7. **`Bot.userName` field visibility.** `userName` is `protected` on `Bot` and exposed via Lombok `@Getter` at `Bot.java:51`. From inside `BettingMiniGameBot.onEndGame` use `getUserName()` (consistent with other call sites in the same file).

8. **Stub-message test helper shape.** Anonymous classes in Java cannot add interfaces to a class instantiation; they can only override methods of the named type. Dev must create one or more **named** test-only subclasses of `EndGameMessage` (e.g. as static inner classes of `BettingMiniGameBotTest`, one per marker combination tested, or one parametric class with constructor flags). The latter is less code and easier to read.

9. **`BotMetrics` javadoc reorganisation.** Several javadoc blocks reference the capability-flag pattern (e.g. `BotMetrics.java:193-200`). Phase C must amend these. Architect recommends Dev grep `BotMetrics.java` for "capability" / "subclass" / "getWinnings" and update each occurrence. Also amend the new `incBetsPlaced` javadoc to reference `HasBetTotals` as the dispatch source.

10. **`BotMdcTagsMeterFilter` change is Phase A.5 only.** The allow-list at lines 53-62 used to list `game_total_winnings_total` and `game_total_bet_amount_total` (8 entries total); Phase A.5 trims it back to 6 (the four aggregate gauges plus the two Phase 3 downtime gauges). No new aggregate meters are introduced in this redesign; Phase B and Phase C make no allow-list changes.

11. **Compile cycle for Phase A's coexistence.** During Phase A the `onEndGame` body calls both `getWinnings()` (default 0) and the `HasBotWinnings` branch. If a test simultaneously implements `HasBotWinnings` returning N AND overrides `getWinnings()` returning M, **both** would fire and the counter would receive M+N. Tests in Phase A only exercise the new path (vanilla message + marker interface); old-path tests should be deleted in Phase C. If Dev wants belt-and-braces in Phase A: explicitly assert in the new tests that `getWinnings()` returns the default 0 on the test bot (or skip — it always does).

---

## Open Items

1. **`HasBotWinnings.winningsFor` identifier flavour.** The interface takes `String userName`. If the user's per-game message implementations need a numeric account id (e.g. from `BotCredentials.accountId` — verify if that field exists; not checked here), the message subtype does the mapping internally. We will not widen the interface signature.
2. **Implementation of the interfaces on `BomEndGameMessage` / `B52EndGameMessage` / `NohuEndGameMessage`.** Explicitly OUT OF SCOPE. The user said they will write these themselves after Dev finishes. The architect did not investigate whether the current JSON payloads carry enough information to extract per-username winnings (suspected answer: no, would need protocol extension or out-of-band correlation with the bot's own bet history).
3. **Should `HasJackpot` be merged into `HasBotWinnings` returning a record `(payout, jackpot)`?** Considered and rejected (AD-2). Jackpot is structurally distinct in all current games.
4. ~~**Per-game test fixture exercising `HasRoundTotals` against a real `BomEndGameMessage`.**~~ (Closed 2026-06-08: `HasRoundTotals` deleted in Phase A.5.) The analogous TODO survives for `HasBotWinnings` / `HasJackpot` / `HasBetTotals` — once the user implements any of those on a concrete EndGameMessage subtype, the per-game-fixture test points naturally at `src/test/resources/messages/bom/endGame.json`.

---

## Verification

Releaser checklist for staging deploy. After Phase A.5 (and B, C), the surviving Phase-4-flavoured meter set is **three names** — `bot_winnings_total`, `bot_jackpots_total`, `bot_jackpot_amount_total` — plus the bet counters `bot_bets_placed_total` / `bot_bet_amount_total` (whose increment moves off `creditBalance` and onto the `HasBetTotals` branch in Phase B). The two dropped `game_total_*_total` series MUST be absent from the scrape after Phase A.5. The surviving meters remain at 0 (or absent from the scrape) until the user implements the surviving marker interfaces. `bot_bets_placed_total` and `bot_bet_amount_total` will read 0 in production (where today they grow with every bet) because no `EndGameMessage` subtype implements `HasBetTotals` yet. This is **expected** and the user has acknowledged it.

Run all checks from a host with `curl` and `jq`. Assume host port `8080` reaches the Spring Actuator at `bot-manager:8085/actuator/prometheus` (per `docker-compose.yml`). Replace `$BOT_GROUP_ID` with a running group's ID.

1. **Build green.**
   ```bash
   JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
     mvn -q clean test
   ```
   Expect: BUILD SUCCESS, 0 failures.

2. **Prometheus scrape endpoint reachable.**
   ```bash
   curl -fsS http://localhost:8080/actuator/prometheus | head -3
   ```
   Expect: lines starting with `# HELP` and `# TYPE`.

3. **The three surviving Phase 4 meter names register or remain absent (both acceptable).**
   ```bash
   curl -fsS http://localhost:8080/actuator/prometheus \
     | grep -E '^(bot_winnings_total|bot_jackpots_total|bot_jackpot_amount_total) '
   ```
   Expect: 0 lines (no implementer means no counter creation) OR lines with value `0.0`. Either is correct; a positive value here means a per-game subtype has secretly shipped an implementation between this plan and deploy — flag for review.

3a. **`game_total_*` meters MUST be absent (Phase A.5 deletion).**
    ```bash
    curl -fsS http://localhost:8080/actuator/prometheus \
      | grep -E '^(game_total_winnings_total|game_total_bet_amount_total) '
    ```
    Expect: 0 lines. **Any output here means Phase A.5 was not applied** — escalate.

4. **`bot_bets_placed_total` and `bot_bet_amount_total` are 0 or absent (the deliberate regression vs pre-redesign).**
   ```bash
   curl -fsS http://localhost:8080/actuator/prometheus \
     | grep -E '^(bot_bets_placed_total|bot_bet_amount_total) '
   ```
   Expect: 0 lines, OR lines with value `0.0`. **A positive value here means `creditBalance` is still incrementing — Phase B was not applied.**

5. **Bots are still sending bets (verify the local accumulator is unaffected).** Pick any running bot group, hit the health API:
   ```bash
   curl -fsS http://localhost:8080/api/v1/bot-group/$BOT_GROUP_ID/health \
     | jq '.bots[] | {userName, totalBetsPlaced, totalBetAmount, lastRoundWinnings}'
   ```
   Expect: `totalBetsPlaced > 0` and `totalBetAmount > 0` for bots that have been running for at least one round (proves `creditBalance` local bookkeeping survived). `lastRoundWinnings` will be `0` until a marker interface is implemented.

6. **No residual capability-flag methods on the deployed jar.** Use the running container to confirm the class shape (sanity check that the deployed artifact is the redesigned one):
   ```bash
   docker exec bot-manager-1 jar tf /app/app.jar | grep BettingMiniGameBot
   ```
   Expect: only the class file, no `getWinnings`, `getJackpot`, etc. — those are private members and won't appear in jar listing, so the real check is:
   ```bash
   docker exec bot-manager-1 javap -p /app/BOOT-INF/classes/com/vingame/bot/domain/bot/core/BettingMiniGameBot.class \
     | grep -E "getWinnings|getJackpot|canCheckTotalWinnings|getTotalWinnings|getRoundTotalBetAmount"
   ```
   Expect: 0 lines (methods removed in Phase C).

7. **`bot_messages_total{cmd="endGame"}` still increments.** Confirms `onEndGame` is still being called.
   ```bash
   A=$(curl -fsS http://localhost:8080/actuator/prometheus | grep '^bot_messages_total{[^}]*cmd="endGame"' | head -1 | awk '{print $2}')
   sleep 60
   B=$(curl -fsS http://localhost:8080/actuator/prometheus | grep '^bot_messages_total{[^}]*cmd="endGame"' | head -1 | awk '{print $2}')
   echo "before=$A after=$B"
   ```
   Expect: `B > A` (at least one endGame round completed in 60s). If not, the bots aren't receiving EndGame at all and something earlier is broken — escalate.

8. **No new ERROR-level log lines from the redesigned code path.**
   ```bash
   docker compose logs --since=5m bot-manager \
     | grep -E "ERROR.*(onEndGame|HasBotWinnings|HasJackpot|HasBetTotals|HasRoundTotals)"
   ```
   Expect: 0 lines.

9. **No NPE from `instanceof`-dispatch on a vanilla EndGameMessage.** Search for unexpected NPEs in the netty processor pool:
   ```bash
   docker compose logs --since=5m bot-manager \
     | grep -E "NullPointerException|ClassCastException" \
     | grep -E "BettingMiniGameBot|onEndGame|instanceof"
   ```
   Expect: 0 lines.

**No on-server verification needed beyond the universal smoke test if all of the above pass.** The redesign is observable as "three meters present at 0" (`bot_winnings_total`, `bot_jackpots_total`, `bot_jackpot_amount_total`), plus "bet counters dropped to 0", plus "`game_total_*` series absent from the scrape" — all expected. Once the user lands the first per-message interface implementation, `bot_winnings_total{gameType="..."}` will start moving, and that's the verification point for *that* follow-up work, not this plan.
