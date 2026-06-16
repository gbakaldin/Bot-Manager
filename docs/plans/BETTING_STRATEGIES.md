# BETTING_STRATEGIES

## Goal

Introduce a pluggable betting-strategy system so each bot in a group can be
assigned a distinct "personality" (cautious, mid, gambler, ...). Today every bot
in a group runs identical hardcoded RNG via
`BettingMiniGameBot.shouldBet()` / `resolveBetAmount()` /
`resolveBetCondition()` (see
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:283-315`).
The end state has the bot collect factual rolling history (last N round
results, current round state, balance, global recent wins, game config) and
delegate every bet decision to a pluggable `BettingStrategy` instance.
`BotGroup` carries a `strategyMix` (weighted list) and bots are deterministically
assigned a strategy at startup with a fill-to-target distribution. v1 ships a
single strategy, `RandomBehaviorStrategy`, that mirrors today's behavior; future
strategies (Martingale, slow-build, gambler, ...) are out of scope.

## Findings — Current State

### Bot decision path
- `BettingMiniGameBot.tick` is `sendAsync`-driven inside `botBehaviorScenario`
  (`.../core/BettingMiniGameBot.java:355-360`). The supplier is
  `bet()` and the condition is `resolveBetCondition()` =
  `() -> canBet() && shouldBet()` (line 299-301).
- `shouldBet()` reads `BotBehaviorConfig.maxBetsPerRound` and
  `betSkipPercentage`, increments a `numberOfBetsInCurrentSession` counter, and
  returns true/false (`.../BettingMiniGameBot.java:283-296`).
- `resolveBetAmount()` returns `minBet + step*betIncrement` chosen uniformly
  (`.../BettingMiniGameBot.java:304-315`).
- `resolveNextEntryToBet()` picks uniformly from
  `configuration.getGame().getEffectiveBettingOptions()`
  (`.../BettingMiniGameBot.java:266-269`).
- **Latent bug noted, not in scope:**
  `BotGroupBehaviorService.createSingleBot()` builds `BotBehaviorConfig` without
  populating `betSkipPercentage`
  (`.../botgroup/service/BotGroupBehaviorService.java:433-442`). The field
  therefore defaults to 0 and `shouldBet()` always returns true (up to
  `maxBetsPerRound`). Replacing this code path with the strategy removes the
  bug by construction.

### Game model
- `Game` carries both `int numberOfOptions` and `List<Integer> bettingOptions`
  with `getEffectiveBettingOptions()` as a fallback
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/model/Game.java:35-50`).
- Single read site: `BettingMiniGameBot.resolveNextEntryToBet()`
  (`.../BettingMiniGameBot.java:267`). `numberOfOptions` is also referenced in
  the INFO log on `.../BettingMiniGameBot.java:102` and in the mapper / DTO
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/dto/GameDTO.java:31-32`,
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/mapper/GameMapper.java:31-32,56-57,79-80`).

### Round messages
- `StartGameMessage.getSessionId()` is abstract at the base
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/StartGameMessage.java:13`)
  and concrete impls expose it.
- `EndGameMessage` is currently empty
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/EndGameMessage.java:1-15`).
  Concrete impls carry `sid` privately
  (e.g. `TipEndGameMessage.sid`, `BomEndGameMessage.sid`, `NohuEndGameMessage.sid`).
  No abstract accessor exists today.
- Per-bot result accessors already exist on `EndGame` via marker interfaces
  (`HasBotWinnings.winningsFor(userName)`, `HasBetTotals.betCountFor/betAmountFor`,
  `HasJackpot.jackpotFor`) — already consumed in `BettingMiniGameBot.onEndGame`
  (`.../BettingMiniGameBot.java:189-227`).
- "Winning option" data: not all products expose a per-round winning option in
  a uniform shape; Tip exposes dice `d1/d2/d3`, Bom/Nohu likewise plus `sD`.
  There is no `HasWinningOption` marker today. This is addressed in
  Implementation Notes.

### Bot state today
- `Bot` carries `lastFetchedBalance`, `expectedCurrentBalance`,
  `totalBetsPlaced`, `totalBetAmount`, `lastRoundWinnings`
  (`.../core/Bot.java:59-75`). All are surfaced via `BotHealthDTO`.
- `BettingMiniGameBot` carries `sidStore` (single long, not a history),
  `gameState`, `remainingTime`, `numberOfBetsInCurrentSession`
  (`.../BettingMiniGameBot.java:55-73`).
- No rolling history of results, no correlation between bets sent and the
  resulting EndGame.

### BotGroup
- `BotGroup` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/model/BotGroup.java`)
  has betting-window fields (`minBet/maxBet/betIncrement/minBetsPerRound/...`).
  No `strategyMix` field.
- `BotGroupDTO` and `BotGroupMapper` mirror the entity 1:1 with
  full-replace PATCH semantics (`updateEntityFromDTO`, no field merging
  required).
- `BotGroupBehaviorService.createSingleBot()` builds the
  `BotConfiguration` per bot (line 420-460); the natural place to wire a
  per-bot strategy.

### Health DTOs
- `BotHealthDTO`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/dto/BotHealthDTO.java`)
  is the per-bot record returned from `/api/v1/bot-group/{id}/health`. Built in
  `BotGroupBehaviorService.getHealth()` (lines 592-603). Adding a
  `strategyId` field there is trivial.

### Existing tests touched / mirrored
- `GameMapperTest`, `GameTest`, `GameServiceTest`, `GameControllerTest` —
  must be updated for the `Game` shape change in Phase 1.
- `BotGroupMapperTest`, `BotGroupServiceTest`, `BotGroupControllerTest`,
  `BotGroupBehaviorServiceTest`, `BotGroupBehaviorServiceRestartTest` —
  updated/extended in Phases 1, 4.
- `BettingMiniGameBotTest`, `BettingMiniGameBotMdcTest`,
  `BettingMiniGameBotTipDispatchTest` — touched in Phases 2 and 5.
- `BotGroupRuntimeTest`, `BotGroupRuntimeDeadSecondsTest` — touched in Phase 4.

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| `Game` model swap (numberOfOptions/bettingOptions → optionAffinities) | Ready | One read site in `BettingMiniGameBot` (`:267`); mapper + DTO straightforward. Fallback on read for legacy docs. |
| Bot factual history (`BotMemory`) | Partial | `lastFetchedBalance` and `lastRoundWinnings` already exist on `Bot`. No rolling per-round buffer; no bet→result correlation. New construct required. |
| Bet→result correlation | Blocked-by-impl | Requires `EndGameMessage.getSessionId()` to become abstract (and concrete subclasses to expose `sid`). Straightforward but touches every concrete EndGame class. |
| `BettingStrategy` interface + `BetContext` | Ready | Pure-code addition under a new package. No external touchpoints. |
| Strategy registry / lookup | Ready | Spring + an enum-keyed `Map<StrategyId, BettingStrategy>` injected at runtime. |
| `BotGroup.strategyMix` (entity, DTO, mapper, PATCH) | Ready | Mirrors existing list-typed fields. Mongo handles `List<WeightedStrategy>` natively. |
| Fill-to-target deterministic assignment | Ready | Pure function: `(strategyMix, bots) → Map<bot, StrategyId>` keyed by hash of bot identity. |
| `BotHealthDTO.strategyId` exposure | Ready | One-line DTO addition; populated in `getHealth()`. |
| Wire strategy into `BettingMiniGameBot.tick` | Ready | `sendAsync` supplier/condition pair maps cleanly to `strategy.decide()`. `onEndGame` → `strategy.onRoundEnd()`. |
| Mongo migration | Ready | Two `updateMany` calls + verification queries. Read-side fallback covers the deploy window. |

## Architecture Decisions

1. **Strategy interface (per-bot, stateful).** Locked-in shape:
   ```java
   public interface BettingStrategy {
       void onRoundEnd(RoundResult result);
       Optional<BetDecision> decide(BetContext ctx);
   }
   ```
   - `Optional.empty()` = skip this tick. Present = `BetDecision(int optionId, long amount)`.
   - Strategies are stateful and per-bot (one instance per bot per restart).

2. **State split.** Bot owns factual data (`BotMemory`); strategy owns
   interpretive data (streaks, internal counters). `BotMemory` lives on the
   `Bot` base class (or a dedicated field), populated by
   `BettingMiniGameBot` from incoming WS messages. Strategy state is opaque to
   `Bot`.

3. **`BotMemory` contents** (factual, objective):
   - `Deque<RoundResult> lastResults` — bounded N=50 (configurable later, hardcoded for v1).
     `RoundResult = (sessionId, winningOption Optional<Integer>, betsByOption Map<Integer,Long>, payout long, balanceDelta long, endedAt Instant)`.
   - `RoundState currentRound` — `(sessionId, phase, remainingTimeMs, betsByOption Map<Integer,Long>)`. Cleared on `StartGame`, accumulated as the bot sends bets, finalized on `EndGame`.
   - `long currentBalance` — mirrors `Bot.expectedCurrentBalance`.
   - `Deque<Integer> globalRecentWins` — bounded N=50; pushed from every EndGame (independent of whether this bot played). For v1 the winning-option extraction is best-effort (see Note 4).
   - Read-only handle to `Game` (option affinity map, allowed bet amounts).
   - `lastResults` and `globalRecentWins` are stored as `ArrayDeque` guarded by `synchronized` blocks. Single-writer (`BettingMiniGameBot.onEndGame`) but read concurrently by the strategy on the `sendAsync` thread, so reads return immutable snapshots (`List.copyOf` on access).

4. **Bet→result correlation = sessionId-keyed.** A bet recorded in
   `currentRound.betsByOption` carries the current `sidStore.get()`. On
   `EndGame` (which also carries `sid`), the bot folds `currentRound` into
   a `RoundResult`, pushes it onto `lastResults`, and resets
   `currentRound`. If `EndGame.sid != currentRound.sessionId`
   (out-of-order / dropped message), the in-flight round is discarded — log
   at WARN, do not crash.
   This requires `EndGameMessage.getSessionId()` to become abstract. Phase 2
   adds the abstract method and implements it in `TipEndGameMessage`,
   `BomEndGameMessage`, `NohuEndGameMessage`, and `B52EndGameMessage` (and
   any other subclass detected by `grep`).

5. **`Game` shape**: replace `numberOfOptions: int` and
   `bettingOptions: List<Integer>` with
   `Map<Integer, Integer> optionAffinities` (option id → affinity, raw int,
   default 1). `Game.getEffectiveOptionAffinities()` synthesizes
   `{0:1, ..., n-1:1}` from a legacy `numberOfOptions` field if
   `optionAffinities` is null and `numberOfOptions` is non-zero. Read-side
   fallback only; the field itself is removed from the model after migration
   (the fallback exists so the read path never breaks during deploy).
   Affinity is a **neutral prior** — strategies decide how to use it; the data
   shape does not embed "high = bet more."

6. **`Game` DTO wire format**:
   - Read: returns the unified `optionAffinities` map only.
   - Write/PATCH: accepts `optionAffinities` (map). For convenience the create
     DTO also accepts a `numberOfOptions: int` shorthand; if both are
     provided, `optionAffinities` wins. If only `numberOfOptions` is provided,
     the mapper expands it to `{0:1, ..., n-1:1}` before persisting.
   - PATCH semantics for `optionAffinities`: **full-replace** of the map (no
     field merge). Matches existing PATCH conventions on `BotGroupMapper`.

7. **`BotGroup.strategyMix`**:
   - Type: `List<WeightedStrategy>` where
     `WeightedStrategy = (StrategyId strategyId, double weight)`.
   - `StrategyId` is a Java enum (`RANDOM` for v1; future entries added by
     editing the enum).
   - PATCH semantics for `strategyMix`: **full-replace** of the list.
   - Default on unmigrated docs: read-side fallback returns
     `[(RANDOM, 1.0)]`. Write-side migration is in Phase 6.
   - Single strategy = `[(RANDOM, 1.0)]`, same code path as multi-strategy.

8. **Strategy assignment = fill-to-target by bot ID hash**:
   - Inputs: `botCount`, `strategyMix`, list of bot identifiers (use
     `namePrefix + botIndex`, deterministic and stable per bot).
   - Algorithm: normalize weights to a target count
     `target[i] = round(weight[i] * botCount / sumWeights)` with the
     largest-remainder method to ensure `sum(target) == botCount`. Then sort
     bot identifiers by `Long.hashCode(SipHash(identifier))` or
     `(identifier.hashCode() & 0x7fffffff)` to break ties deterministically,
     and slice the sorted list into contiguous chunks of size `target[i]`.
     Each chunk is assigned strategy `i`.
   - Result: **deterministic** per (botCount, strategyMix, identifiers).
     Re-running on the same group produces the same assignment.
   - With `botCount < |strategyMix|`, strategies with rounded target of 0
     receive no bots (operator-visible via the per-bot strategy field in
     `BotHealthDTO`).

9. **Mid-flight `strategyMix` change**: do NOT re-assign running bots. Their
   existing strategy instances continue. Newly created or restarted bots draw
   from the freshly persisted mix. Document in the API docs comment.

10. **State across bot restart**: in-memory only. A bot crash / reconnect /
    periodic logout resets both `BotMemory` and strategy state to fresh.
    Mirrors current behavior; explicitly out of scope to fix.

11. **v1 strategy = `RandomBehaviorStrategy`**:
    - `decide(ctx)`: with probability `1 - betSkipPercentage/100` (default
      `betSkipPercentage = 0` → always bet, mirrors current latent-bug
      behavior), and only if `ctx.currentRound.betsByOption.values().sum()
      < ctx.behavior.maxBetsPerRound`, returns
      `BetDecision(option, amount)` where:
      - `option = uniform_pick(ctx.game.optionAffinities.keySet())`
      - `amount = minBet + step*betIncrement`, `step ∈ [0, maxSteps]`
    - Else returns `Optional.empty()`.
    - `onRoundEnd(...)`: no-op (Random has no memory).
    - **The current code's behavior** (skip-percentage gate, bet-amount RNG,
      uniform option selection) is reproduced bit-for-bit by this strategy
      with the same `BotBehaviorConfig` + `Random`. Confirmed equivalence is
      a Phase-5 verification step.

12. **Strategy registry**: `BettingStrategyFactory` Spring bean exposing
    `BettingStrategy create(StrategyId id, long seed)`. Strategies are NOT
    singleton Spring beans — each bot needs its own instance with its own
    `Random`. The factory holds a `Map<StrategyId, Supplier<BettingStrategy>>`
    populated by `@PostConstruct` from a list of `BettingStrategy` Spring
    beans annotated with `@StrategyImpl(StrategyId.RANDOM)`. Looking up an
    unknown `StrategyId` throws `IllegalArgumentException` — a strategy in
    Mongo without a corresponding bean is a deploy bug, not a runtime
    fallback case.

13. **Per-bot `Random` seeding**: each bot gets
    `new Random(SipHash(botUserName) ^ System.nanoTime())` at construction.
    Deterministic enough to debug but not bit-identical across restarts;
    matches existing `BettingMiniGameBot`'s `new Random()` behavior.

14. **Logging** (per `/Users/gleb/IdeaProjects/Bot/CLAUDE.md` guidelines):
    - INFO, once per bot at start: `"Bot {userName}: assigned strategy {id}"`
      (group-level scale: 30 bots = 30 INFO lines on start, matches existing
      "Bot starting in virtual thread ..." line at the same scale).
    - DEBUG, per `decide()` call and per `onRoundEnd()`: bot-bounded.
    - WARN, on sessionId mismatch in `BotMemory` correlation, on unknown
      `StrategyId` at startup (then re-throw).
    - MDC: all per-bot lines carry `botGroupId`, `botId`, `gameType` via
      existing `BotMdc` plumbing; new code MUST NOT bypass `mdcConsumer` /
      `mdcSupplier` / `mdcWrap` on threads spawned outside the `Bot`.

15. **`BotMemory` thread-safety**: see Decision 3. Strategy `decide()` is
    invoked on the scenario's `pool-N-thread-1`; `onEndGame` is invoked on
    `netty-ws-message-processor-ws-<userName>`. The accumulators are
    synchronized at the `BotMemory` level (not on `Bot`). `BotMemory` is
    constructed in `BettingMiniGameBot.initializeSubclass()` after `Game` is
    set.

## Plan

Six discrete phases. Each phase compiles independently and is shippable on
its own (Phases 1–4 are no-op for the running bot; Phase 5 activates the
strategy code path; Phase 6 is releaser-only Mongo work).

### Phase 1 — `Game` shape change with read-side fallback

**Goal:** Replace `numberOfOptions` + `bettingOptions` with a unified
`optionAffinities` map on the `Game` entity, DTO, and mapper. Keep the read
path safe for unmigrated Mongo docs.

**Files to touch:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/model/Game.java`
  - Remove `int numberOfOptions` and `List<Integer> bettingOptions`.
  - Add `Map<Integer, Integer> optionAffinities`.
  - Keep a transient/legacy field `Integer numberOfOptionsLegacy` deserialized
    from Mongo via `@Field("numberOfOptions")` (Spring Data MongoDB supports
    this without keeping a write-side field — verify with the team if
    `@Transient` + custom reader is preferred). Simpler alternative: keep
    `numberOfOptions` as a private field with no getter, used only as the
    fallback source.
  - Add `getEffectiveOptionAffinities()` returning `optionAffinities` if
    non-null/non-empty, else `{0:1, ..., numberOfOptions-1}` if
    `numberOfOptions > 0`, else throw `IllegalStateException` (a Game with
    neither field set is misconfigured).
  - Remove `getEffectiveBettingOptions()`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/dto/GameDTO.java`
  - Remove `numberOfOptions` and `bettingOptions` fields from the read path.
  - Add `Map<Integer, Integer> optionAffinities`.
  - For write-compatibility, accept `numberOfOptions` as an `Integer`
    write-only convenience field (the mapper expands it).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/mapper/GameMapper.java`
  - `toDTO`: emit `optionAffinities = entity.getEffectiveOptionAffinities()`.
  - `toEntity`: if DTO has `optionAffinities`, use it; else if DTO has
    `numberOfOptions`, expand to `{0:1, ..., n-1:1}`; else leave null.
  - `updateEntityFromDTO`: full-replace of `optionAffinities` when present in
    DTO; ignore `numberOfOptions` on PATCH (the field is for create
    convenience only).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
  - Line 267: replace `configuration.getGame().getEffectiveBettingOptions()`
    with `configuration.getGame().getEffectiveOptionAffinities().keySet()`
    (collected to a `List<Integer>` for the existing `random.nextInt` index
    pick — Phase 1 keeps the call-site behavior identical to today;
    affinity-aware option selection is a future strategy's concern).
  - Line 102: update the INFO log to print the affinity-map size rather
    than `numberOfOptions`.
- Test updates: `GameMapperTest`, `GameTest`, `GameServiceTest`,
  `GameControllerTest`, `BettingMiniGameBotTest` (the parts that build a
  `Game` with `numberOfOptions`).

**Step-by-step:**
1. Update the `Game` entity, remove old fields/method, add new field +
   `getEffectiveOptionAffinities()`. Mongo `@Document` annotation untouched.
2. Add the legacy-read shim (keep a non-public `numberOfOptions` field that
   Mongo can read).
3. Update `GameDTO`, mapper, and all read sites.
4. Update `BettingMiniGameBot.resolveNextEntryToBet()` and the INFO log.
5. Update tests.

**Verifiable, this phase only:**
- `mvn -pl . test` passes.
- Unit test: a `Game` loaded with only legacy `numberOfOptions=4` exposes
  `getEffectiveOptionAffinities() == {0:1, 1:1, 2:1, 3:1}`.
- Unit test: a `Game` with `optionAffinities={0:1,1:3}` returns the map as-is.
- Unit test: a `Game` with neither set throws `IllegalStateException`.
- Manual: create a `Game` via `POST /api/v1/game` with body containing
  `numberOfOptions=4` — `GET /api/v1/game/{id}` returns
  `optionAffinities: {"0":1,"1":1,"2":1,"3":1}` and no `numberOfOptions` in
  the response.
- Manual: `PATCH /api/v1/game` with `optionAffinities: {"0":2,"1":1}` — read
  back returns exactly that map.

### Phase 2 — `BotMemory` accumulation + bet→result correlation

**Goal:** Bot accumulates rolling factual history. No strategy is invoked yet
— this phase is data plumbing only.

**Files to touch:**
- New: `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BotMemory.java`
- New: `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/RoundResult.java`
- New: `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/RoundState.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/EndGameMessage.java`
  - Add `public abstract long getSessionId();`
- Concrete EndGame impls — add `@Override public long getSessionId() { return sid; }`:
  - `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/g3/tip/TipEndGameMessage.java`
  - `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/g2/bom/BomEndGameMessage.java`
  - `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/g4/nohu/NohuEndGameMessage.java`
  - `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/g2/b52/B52EndGameMessage.java`
  - Any other found via `grep -rn "extends EndGameMessage" src/main/java`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
  - Construct `BotMemory` in `initializeSubclass()`.
  - In `bet()` (line 256-264): after `creditBalance(amount)`, call
    `memory.recordBetSent(sidStore.get(), entryToBet, amount)`.
  - In `onStartGame()` (line 164-177): call
    `memory.beginRound(msg.getSessionId())`. Snapshot `currentBalance` from
    `expectedCurrentBalance.get()`.
  - In `onEndGame()` (line 189-227): extract `winningOption` (best effort —
    see Note 4), build `RoundResult`, push via
    `memory.completeRound(sessionId, winningOption, payout)`. `payout` is
    derived from the existing `HasBotWinnings.winningsFor(userName)`. If the
    bot did not bet this round, `currentRound.betsByOption` is empty but the
    round is still recorded (Random ignores it; future strategies care).
  - In `onEndGame()`: also call
    `memory.recordGlobalWin(winningOption)` whenever `winningOption.isPresent()`.

**Step-by-step:**
1. Create `RoundResult`, `RoundState`, `BotMemory` value/holder classes.
   `BotMemory` constructor takes the bot's `Game` and capacity (50).
2. Add abstract `EndGameMessage.getSessionId()`; implement in concrete
   subclasses.
3. Wire `BotMemory` calls into `BettingMiniGameBot`. **Do not** call any
   strategy yet — the strategy package does not exist in this phase.
4. Add unit tests for `BotMemory`: bounded buffer eviction, sessionId mismatch
   logged at WARN and round discarded, `recordBetSent` accumulates by option,
   `completeRound` produces the correct `RoundResult`.
5. Add an integration-style test on `BettingMiniGameBot` that feeds synthetic
   `StartGame`/`EndGame` messages and asserts `BotMemory.lastResults` reflects
   the sequence. Mirror `BettingMiniGameBotTipDispatchTest` style.

**Verifiable, this phase only:**
- `mvn test` green.
- Unit test: `BotMemory.lastResults` capacity = 50; pushing a 51st evicts the
  oldest (FIFO).
- Unit test: `onEndGame` with mismatched sessionId triggers a WARN log line
  (matching `^.*sessionId mismatch.*$`) and discards the in-flight round.
- No behavioral change in the running bot — `decide()` not yet called.

### Phase 3 — `BettingStrategy` interface + `RandomBehaviorStrategy` + registry

**Goal:** Pure code. Define the interface, build the registry, ship a single
strategy. Nothing in the bot lifecycle yet calls it.

**Files to touch:**
- New package: `com.vingame.bot.domain.bot.strategy`
- New: `BettingStrategy.java` (interface).
- New: `BetContext.java` (record):
  `BetContext(BotMemory memory, BotBehaviorConfig behavior, Game game,
   long currentBalance, RoundState currentRound, Random rng)`.
- New: `BetDecision.java` (record): `(int optionId, long amount)`.
- New: `StrategyId.java` (enum): `RANDOM`.
- New: `WeightedStrategy.java` (record): `(StrategyId strategyId, double weight)`.
- New: `@StrategyImpl(StrategyId)` annotation.
- New: `RandomBehaviorStrategy.java` — Spring bean annotated
  `@StrategyImpl(StrategyId.RANDOM)`.
- New: `BettingStrategyFactory.java` — Spring `@Component`. Holds
  `Map<StrategyId, Class<? extends BettingStrategy>>` populated at
  `@PostConstruct` from all `BettingStrategy` beans. `create(StrategyId,
   long seed)` reflectively constructs a new instance (or, simpler, uses
  `ObjectProvider<BettingStrategy>` with `@Lookup`-style indirection — pick
  the simpler one when writing).

**`RandomBehaviorStrategy` body (canonical reference for Dev):**
```
decide(ctx):
  betsThisRound = sum(ctx.currentRound.betsByOption.values())
  if betsThisRound >= ctx.behavior.maxBetsPerRound: return empty
  if ctx.rng.nextInt(100) < ctx.behavior.betSkipPercentage: return empty
  options = ctx.game.getEffectiveOptionAffinities().keySet() as List
  option = options.get(ctx.rng.nextInt(options.size()))
  maxSteps = (ctx.behavior.maxBet - ctx.behavior.minBet) / ctx.behavior.betIncrement
  steps = ctx.rng.nextInt(maxSteps + 1)
  amount = ctx.behavior.minBet + steps * ctx.behavior.betIncrement
  return BetDecision(option, amount)

onRoundEnd(_): no-op
```

**Step-by-step:**
1. Create the package and all new files. No edits to `Bot` or
   `BettingMiniGameBot` in this phase.
2. Implement `BettingStrategyFactory` + `@StrategyImpl` discovery.
3. Unit tests:
   - `RandomBehaviorStrategyTest` with a seeded `Random`: assert that for a
     fixed `BotMemory` + `BotBehaviorConfig` + `Game`, the sequence of
     `decide(ctx)` outputs is byte-identical to running the current
     `BettingMiniGameBot.shouldBet()` + `resolveBetAmount()` +
     `resolveNextEntryToBet()` with the same seed. This is the equivalence
     proof for Phase 5.
   - `BettingStrategyFactoryTest`: registers `RANDOM` at startup; unknown
     `StrategyId` throws.

**Verifiable, this phase only:**
- `mvn test` green; tests above pass.
- No runtime change for any deployed bot.

### Phase 4 — `BotGroup.strategyMix` + fill-to-target + `BotHealthDTO` exposure

**Goal:** Persist and edit `strategyMix`; assign strategies to bots at start;
expose the assignment via the health DTO. The strategy is still not called
during `tick`.

**Files to touch:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/model/BotGroup.java`
  - Add `List<WeightedStrategy> strategyMix`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/dto/BotGroupDTO.java`
  - Add `List<WeightedStrategy> strategyMix`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/mapper/BotGroupMapper.java`
  - Add `strategyMix` to `toDTO`, `toEntity`, and `updateEntityFromDTO`.
    Full-replace semantics on PATCH.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/dto/BotHealthDTO.java`
  - Add `StrategyId strategyId`.
- New: `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/StrategyAssignment.java`
  - Pure function: `static Map<String, StrategyId> assign(List<WeightedStrategy> mix, List<String> botIdentifiers)`.
  - Largest-remainder method for `target[i] = round(weight[i] / sumWeights * n)`.
  - Sort bot identifiers by hash; slice contiguously.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/config/bot/BotConfiguration.java`
  - Add `StrategyId strategyId`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
  - In `start()` after `Game game = gameService.findById(group.getGameId())`:
    compute `Map<String, StrategyId> assignment = StrategyAssignment.assign(effectiveStrategyMix(group), botIdentifiers)`.
  - `effectiveStrategyMix(group)`: returns `group.getStrategyMix()` if
    non-null-and-non-empty, else `[(RANDOM, 1.0)]` (read-side fallback for
    unmigrated docs).
  - In `createSingleBot()`: look up `assignment.get(username)` and set on
    `BotConfiguration.builder().strategyId(...)`.
  - INFO log per bot at assignment time:
    `log.info("Bot {}: assigned strategy {}", username, strategyId);` — emitted
    once per bot in the create path, so 30 bots = 30 INFO lines on start.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`
  - Add a `@Getter StrategyId strategyId` field. `setConfiguration` reads
    from `BotConfiguration` and stores it for the health DTO.
- `BotGroupBehaviorService.getHealth()` (line 592-603): populate
  `BotHealthDTO.strategyId(bot.getStrategyId())`.

**Step-by-step:**
1. Add `strategyMix` end-to-end (model → DTO → mapper). Default-fallback in
   `BotGroupBehaviorService.effectiveStrategyMix()`.
2. Implement `StrategyAssignment.assign` as a pure function with unit tests.
3. Wire assignment into `start()` and propagate the chosen `StrategyId` into
   `BotConfiguration` and `Bot`.
4. Surface in `BotHealthDTO`.

**Verifiable, this phase only:**
- `mvn test` green.
- Unit test (`StrategyAssignmentTest`): with `mix = [(A,0.3),(B,0.5),(C,0.2)]`
  and 100 deterministic bot identifiers, the assignment is exactly
  30 / 50 / 20 (largest-remainder removes the rounding drift).
- Unit test: with `botCount = 5` and `mix = [(A,0.3),(B,0.5),(C,0.2)]`, the
  rounded targets sum to 5 (e.g. 2 / 2 / 1 — exact values pinned by the
  remainder rule).
- Unit test: re-running the assignment with the same inputs produces
  bit-identical output.
- Manual: `POST /api/v1/bot-group` with
  `strategyMix: [{"strategyId":"RANDOM","weight":1.0}]` → read back returns
  the same shape.
- Manual: `PATCH /api/v1/bot-group` with new `strategyMix` → read back returns
  the new value (full-replace).
- Manual: `GET /api/v1/bot-group/{id}/health` shows each bot's `strategyId`.
- Smoke-deploy on staging: start a group of 30 bots with `[(RANDOM,1.0)]` —
  every bot's `strategyId == RANDOM`. Behavior identical to today (strategy
  not yet wired into `tick`).

### Phase 5 — Wire strategy into `BettingMiniGameBot.tick`

**Goal:** Replace `shouldBet` / `resolveBetAmount` / `resolveBetCondition`
with calls to the assigned strategy. Route `onEndGame` into
`strategy.onRoundEnd(roundResult)`.

**Files to touch:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
  - Inject `BettingStrategyFactory` (passed in via a new setter on the
    parent, similar to `setMetrics(BotMetrics)`; `BotFactory` wires it).
  - In `initializeSubclass()`: construct strategy:
    `this.strategy = factory.create(configuration.getStrategyId(), seed)`.
  - Replace the `sendAsync` pipeline (line 355-360) so that:
    - The supplier calls `strategy.decide(buildBetContext())` and, if
      present, sends the bet; if empty, returns null (existing supplier
      signature returns `ActionRequestMessage` — confirm the contract
      tolerates null; if not, lift the gating into `condition` and have
      `condition = () -> latestDecision.isPresent()` evaluated alongside
      `canBet()`).
    - Concretely: `decide()` is called inside `bet()` (today's place); the
      returned decision determines option + amount; if `empty`, skip by
      returning null and letting the scheduled-send treat it as a no-op
      (this matches the current "skip tick" behavior implemented via
      `resolveBetCondition()` returning false).
  - `creditBalance` continues to update `expectedCurrentBalance`; in
    addition, `BotMemory.recordBetSent(...)` is called as in Phase 2.
  - In `onEndGame()`: after the existing per-bot extraction, build a
    `RoundResult` and call `strategy.onRoundEnd(roundResult)`. Wrap with
    `mdcConsumer` (the call happens on `netty-ws-message-processor-...` which
    has no MDC by default).
  - Remove `shouldBet()`, `resolveBetAmount()`, `resolveBetCondition()`,
    `resolveNextEntryToBet()`, and the `numberOfBetsInCurrentSession` /
    `random` fields (the strategy owns these now).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`
  - Remove abstract `shouldBet()`, `resolveBetAmount()`,
    `resolveBetCondition()` (no other subclass exists).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java`
  - Inject `BettingStrategyFactory` and pass to `BettingMiniGameBot` via the
    new setter.
- Tests:
  - Update `BettingMiniGameBotTest` to inject a `RandomBehaviorStrategy` (or
    a fake) and assert end-to-end that `decide()` is called per tick,
    `onRoundEnd` is called per EndGame, and the resulting bet messages match
    the strategy output.
  - Add an equivalence test: configure `[(RANDOM, 1.0)]` with a fixed seed,
    feed a deterministic StartGame/UpdateBet/EndGame stream, and assert the
    sequence of bet messages matches the pre-refactor sequence captured by
    Phase 3's equivalence harness.

**Step-by-step:**
1. Wire `BettingStrategyFactory` through `BotFactory` → `Bot` setter →
   `BettingMiniGameBot.initializeSubclass()`.
2. Replace `bet()` body to call `strategy.decide(buildBetContext())`.
3. Replace `resolveBetCondition()` callsite — `canBet()` stays (it's a phase
   / time / session-id check, not a strategy concern); `shouldBet()` and
   amount calculation are gone.
4. Hook `onEndGame` into `strategy.onRoundEnd`.
5. Delete dead code from `Bot` and `BettingMiniGameBot`.
6. Run the equivalence test.

**Verifiable, this phase only:**
- `mvn test` green.
- Equivalence test: with `[(RANDOM,1.0)]` + fixed seed, the captured bet
  stream is byte-identical to a pre-refactor baseline (recorded in Phase 3
  and committed as a golden file under `src/test/resources/`).
- Staging smoke: start a 5-bot group with `[(RANDOM,1.0)]`, let it run for
  60s, confirm via logs that bots emit bets at the same approximate rate as
  before, and `GET /health` shows `connectedBots == 5`.
- Logging audit: grep one bot's logs over a round; expect one INFO line at
  start ("assigned strategy RANDOM"), several DEBUG lines per round
  (decide, onRoundEnd), no new WARNs.

### Phase 6 — Releaser-side Mongo migrations

**Goal:** Migrate the two collections so the read-side fallbacks become
inert. Code deploys first (so the running app handles unmigrated docs);
migration runs after.

**Files to touch:**
- `/Users/gleb/IdeaProjects/Bot/docs/plans/BETTING_STRATEGIES.md` — this file
  (queries listed in `## Verification`).
- No production code changes.

**Releaser procedure:**

1. **Deploy the new bot-manager image.** Read-side fallback covers
   unmigrated docs (`games` without `optionAffinities` synthesize from
   `numberOfOptions`; `bot_groups` without `strategyMix` default to
   `[(RANDOM, 1.0)]`).
2. **Sanity-check the deploy:** `GET /actuator/health` → 200; pick one
   group, `GET /api/v1/bot-group/{id}/health` → expect bots showing
   `strategyId: "RANDOM"`.
3. **Run the `games` migration** (exact `mongosh` script in `## Verification`).
4. **Run the `bot_groups` migration** (exact `mongosh` script in
   `## Verification`).
5. **Verify both migrations** with the count queries in `## Verification`.
6. **Restart no bot group** — the migration is transparent to running bots
   (they read `Game` and `BotGroup` at start, not at every tick).

## Implementation Notes / Concerns

1. **`sendAsync` supplier returning null.** The current contract on
   `OutboundMessage.messageSupplier(...)` is "supplier of an
   `ActionRequestMessage`." Today it never returns null because
   `resolveBetCondition()` gates the call. With strategies, the supplier is
   called whenever `canBet()` is true and the strategy decides per call.
   **Action for Dev**: verify whether the websocket-parser scenario engine
   tolerates a null return from the supplier. If not, keep the
   `condition` gate populated by a lazily-evaluated decision —
   pre-compute `decide()` into an `AtomicReference<Optional<BetDecision>>`
   at condition-time, return `true` only if present, and have the supplier
   read it back. The plan does not pre-commit to one shape; either is
   acceptable as long as the equivalence test passes.

2. **`betSkipPercentage` field is not populated today** —
   `BotGroupBehaviorService.createSingleBot()` builds `BotBehaviorConfig`
   without setting it (line 433-442), so `Random` always bets. Phase 5 does
   NOT change this behavior; the field defaults to 0 and `Random` still
   always bets. If the operator wants the field actually wired through
   `BotGroup`, that's a separate ticket — out of scope for this plan.

3. **Affinity is `Map<Integer, Integer>`.** Mongo stores integer keys as
   strings in BSON document keys. Spring Data Mongo handles this transparently
   for `Map<Integer, V>` (key conversion). Verify with a round-trip test in
   Phase 1 to catch any surprise — particularly that JSON read returns
   `{"0":1}` (string keys in JSON, which is the wire-level reality and
   matches the Swagger expectations downstream).

4. **`winningOption` extraction in Phase 2 is best-effort.** No
   `HasWinningOption` marker exists today. For v1:
   - If the EndGame implements a yet-to-be-added `HasWinningOption`
     interface, extract; otherwise pass `Optional.empty()`.
   - Phase 2 introduces the interface and implements it on
     `TipEndGameMessage` (winningOption derived from `d1` / `d2` / `d3`
     mapping per the existing game logic — confirm shape with the team; if
     uncertain, leave it `Optional.empty()` for now).
   - `RandomBehaviorStrategy` ignores `winningOption`, so v1 ships fine
     either way. Future strategies that care will fail loudly on
     `Optional.empty()` and force the proper extraction.

5. **`Map<Integer, Integer>` PATCH semantics.** PATCH replaces the map
   wholesale (Decision 6). A "delete affinity for option 3" intent must be
   expressed by `PATCH` with the full map minus that key. Field-merge would
   make this op impossible without a sentinel value.

6. **Spring bean lifecycle for strategies.** `RandomBehaviorStrategy` is NOT
   a singleton — each bot gets its own instance (state-carrying). Mark with
   `@Scope(SCOPE_PROTOTYPE)` if using `@Component`, or simpler: don't make
   it a Spring bean at all; the `@StrategyImpl` annotation is read off the
   class, and `BettingStrategyFactory.create(...)` does
   `clazz.getDeclaredConstructor(Random.class).newInstance(rng)`. Either
   pattern is acceptable; Dev picks the simpler one. The factory test pins
   the contract: `create(RANDOM, seed1)` and `create(RANDOM, seed1)` produce
   instances with identical decision sequences; `create(RANDOM, seed2)`
   diverges.

7. **`BotMemory` GC pressure at scale.** 2 000 bots × 50 rounds × ~200 bytes
   per `RoundResult` ≈ 20 MB resident. Not a concern. 100 000 bots × 50
   rounds ≈ 1 GB — acceptable on the load-test target node but worth a heap
   check in Phase 2 verification.

8. **`Game` field removal — Mongo data shape.** Even after the Phase 6
   migration `$unset`s `numberOfOptions` and `bettingOptions`, any Mongo doc
   that wasn't re-saved between deploy and migration will still have those
   fields on disk. Spring Data Mongo ignores unknown fields by default, so
   leaving them as orphaned data is harmless. Do not add `@TypeAlias` or
   strict deserialization.

9. **Backwards-compatible API surface.** External callers (UI) currently
   read `Game.numberOfOptions` on the read DTO. After Phase 1 that field
   disappears from the read DTO. Coordinate with the UI team before merging
   Phase 1, or keep `numberOfOptions` as a derived field on the DTO
   (`optionAffinities.size()`) for one release as a compat shim. Architect
   decision: drop the field — derived `optionAffinities.size()` is a UI
   concern and the UI can compute it client-side.

10. **PATCH for `strategyMix` rejecting an empty list.** An empty
    `strategyMix` would leave the group with no assignable strategies.
    Reject in the mapper: if the DTO supplies `strategyMix` and it's empty,
    throw `BadRequestException("strategyMix must be non-empty")`. If the DTO
    omits the field, retain existing value (standard PATCH).

## Open Items

- **`HasWinningOption` per-product extraction.** Phase 2 introduces the
  interface; Tip / Bom / Nohu / B52 implementations are best-effort for v1.
  Verify with the dev team which root field of each EndGame is the canonical
  winning option (likely `d1` for Tip / Bom / Nohu Bau Cua family). If
  uncertain at Phase 2 time, the interface ships unimplemented and
  `BotMemory.globalRecentWins` is empty — no v1 strategy cares.
- **Per-bot `Random` seed determinism.** Decision 13 uses
  `System.nanoTime()`, which means the equivalence test in Phase 5 needs an
  explicit test-only constructor that takes a deterministic seed.
- **UI changes** for the new fields are out of scope (UI is a separate
  repo). The plan only specifies the API contract.

## Verification

The Releaser runs these on staging after deploying the new bot-manager image
and before running the Mongo migration.

### A. Pre-migration sanity (immediately after deploy)

```bash
# 1. App health
curl -fsS https://staging.bot-manager/actuator/health
# expect: HTTP 200 and body containing "status":"UP"

# 2. Existing groups still readable (read-side fallback)
curl -fsS https://staging.bot-manager/api/v1/bot-group/ | jq '.[0] | {id, name, strategyMix}'
# expect: strategyMix is either present from a previous deploy or null/absent
#         (the application code synthesizes [(RANDOM, 1.0)] on read for assignment).

# 3. Existing games still readable
curl -fsS https://staging.bot-manager/api/v1/game/ | jq '.[0] | {id, name, optionAffinities}'
# expect: HTTP 200; optionAffinities present (synthesized from numberOfOptions
#         if the Mongo doc has not been migrated yet).

# 4. Start a small test group (or pick a running one) and verify per-bot strategy
GROUP_ID=<staging-test-group-id>
curl -fsS -X POST https://staging.bot-manager/api/v1/bot-group/$GROUP_ID/start
sleep 10
curl -fsS https://staging.bot-manager/api/v1/bot-group/$GROUP_ID/health \
  | jq '.bots | map(.strategyId) | group_by(.) | map({key: .[0], count: length})'
# expect: every bot has strategyId "RANDOM". For [(RANDOM, 1.0)] a group of N
#         shows {"key":"RANDOM","count":N}.

# 5. Logs: one INFO line per bot at start
# Expect log lines matching ^.*Bot .*: assigned strategy RANDOM$ in count N.
```

### B. Mongo migration — `games` collection

Connect with `mongosh` to the staging Mongo instance, target the bot-manager
database (default `botmanager`).

```javascript
// 1. Pre-migration count
db.games.countDocuments({ optionAffinities: { $exists: false } })
// record the value as N_BEFORE

// 2. Migration: for each game with numberOfOptions and no optionAffinities,
//    synthesize {0:1, 1:1, ..., n-1:1} and unset legacy fields.
db.games.updateMany(
  { optionAffinities: { $exists: false }, numberOfOptions: { $gt: 0 } },
  [
    {
      $set: {
        optionAffinities: {
          $arrayToObject: {
            $map: {
              input: { $range: [0, "$numberOfOptions"] },
              as: "i",
              in: { k: { $toString: "$$i" }, v: 1 }
            }
          }
        }
      }
    },
    { $unset: ["numberOfOptions", "bettingOptions"] }
  ]
)
// expect: matchedCount == N_BEFORE, modifiedCount == N_BEFORE

// 3. Post-migration verification
db.games.countDocuments({ optionAffinities: { $exists: false } })
// expect: 0

db.games.countDocuments({ numberOfOptions: { $exists: true } })
// expect: 0

db.games.countDocuments({ bettingOptions: { $exists: true } })
// expect: 0

// 4. Spot-check one doc
db.games.findOne({}, { name: 1, optionAffinities: 1 })
// expect: optionAffinities is a non-empty object with integer-string keys
```

### C. Mongo migration — `bot_groups` collection

```javascript
// 1. Pre-migration count
db.botGroups.countDocuments({ strategyMix: { $exists: false } })
// record the value as M_BEFORE

// 2. Migration: default unmigrated groups to [(RANDOM, 1.0)]
db.botGroups.updateMany(
  { strategyMix: { $exists: false } },
  { $set: { strategyMix: [{ strategyId: "RANDOM", weight: 1.0 }] } }
)
// expect: matchedCount == M_BEFORE, modifiedCount == M_BEFORE

// 3. Post-migration verification
db.botGroups.countDocuments({ strategyMix: { $exists: false } })
// expect: 0

db.botGroups.countDocuments({
  strategyMix: { $exists: true, $not: { $size: 0 } }
})
// expect: total count of botGroups
```

### D. Post-migration smoke

```bash
# 1. Same health check, after migration
curl -fsS https://staging.bot-manager/api/v1/bot-group/$GROUP_ID/health \
  | jq '.bots[0].strategyId'
# expect: "RANDOM"

# 2. Read back a game — verify only the new shape on the wire
curl -fsS https://staging.bot-manager/api/v1/game/ | jq '.[0] | keys'
# expect: keys include "optionAffinities" and do NOT include
#         "numberOfOptions" or "bettingOptions"

# 3. PATCH-update a bot group strategyMix end-to-end
curl -fsS -X PATCH https://staging.bot-manager/api/v1/bot-group \
  -H 'Content-Type: application/json' \
  -d "{\"id\":\"$GROUP_ID\",\"strategyMix\":[{\"strategyId\":\"RANDOM\",\"weight\":1.0}]}"
# expect: HTTP 200

curl -fsS https://staging.bot-manager/api/v1/bot-group/$GROUP_ID | jq '.strategyMix'
# expect: [{"strategyId":"RANDOM","weight":1.0}]

# 4. Run-time behavior — bots are still placing bets
# Grep app logs for the last 2 minutes for bet messages from a known bot:
# Expect non-zero count of "bot_bets_placed_total" increments OR a non-zero
# delta in /actuator/prometheus when scraping the bot_bets_placed_total
# counter for this group.
curl -fsS https://staging.bot-manager/actuator/prometheus \
  | grep -E '^bot_bets_placed_total' | head -3
# expect: at least one line, with non-zero value matching a recently-active group.
```

### E. Rollback

If the migration produces a non-zero residual count in any of B.3, B.4, C.3:
- Do NOT re-run the migration. Re-running is idempotent on the `$exists` guard
  but a non-zero residual means something is off — investigate first.
- Code rollback is safe: the read-side fallback in `Game` /
  `effectiveStrategyMix()` continues to work for unmigrated docs as well as
  migrated ones (migrated docs use the explicit field; unmigrated docs hit
  the fallback path that the migration was meant to retire).
