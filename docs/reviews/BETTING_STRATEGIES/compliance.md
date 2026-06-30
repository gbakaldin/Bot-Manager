# Compliance — BETTING_STRATEGIES

Branch: `feat/betting-strategies`
Plan reviewed: `docs/plans/BETTING_STRATEGIES.md` (at commit `8f6a965`)
Diff reviewed: `git diff main..feat/betting-strategies`

## Verdict

PASS

## Phase-by-phase

### Phase 1 — `Game` shape change with read-side fallback
Status: implemented

Notes:
- `Game.optionAffinities: Map<Integer, Integer>` is the unified shape
  (`src/main/java/com/vingame/bot/domain/game/model/Game.java:50`).
- Legacy `numberOfOptions` retained as a private package-scoped field with
  `@JsonIgnore` so Mongo still deserializes pre-Phase-1 docs but the field
  is never round-tripped to the wire (`Game.java:65-68`).
- `getEffectiveOptionAffinities()` synthesizes `{0:1, ..., n-1:1}` from the
  legacy field when `optionAffinities` is null/empty, and throws
  `IllegalStateException` when neither is set (`Game.java:82-93`). Matches
  Plan §"Architecture Decision 5" exactly.
- `GameDTO.optionAffinities` is the read-side surface; `numberOfOptions`
  on the DTO is write-only convenience and excluded from responses via the
  class-level `@JsonInclude(NON_NULL)` plus the mapper never populating it
  on `toDTO` (`GameDTO.java:39-47`, `GameMapper.java:38-50`).
- PATCH on `optionAffinities` is full-replace, `numberOfOptions` ignored
  on PATCH (`GameMapper.java:112-114`, doc lines 89-99).
- `BettingMiniGameBot` reads via the affinity-map keyset. Direct call site
  is now in `RandomBehaviorStrategy.decide`:
  `List.copyOf(ctx.game().getEffectiveOptionAffinities().keySet())`
  (`RandomBehaviorStrategy.java:100`). The Phase 1 INFO log line at
  `BettingMiniGameBot.java:165-167` prints `options=<size>` instead of
  the removed `numberOfOptions`.

### Phase 2 — `BotMemory` accumulation + bet→result correlation
Status: implemented

Notes:
- `BotMemory.DEFAULT_CAPACITY = 50` (`BotMemory.java:45`) with bounded
  FIFO eviction in `pushBounded` (lines 201-206). `BotMemoryTest`'s
  `defaultCapacity` test pins this.
- `EndGameMessage.getSessionId()` is `public abstract long`
  (`EndGameMessage.java:24`). Concrete subclasses
  `TipEndGameMessage`, `BomEndGameMessage`, `NohuEndGameMessage`,
  `B52EndGameMessage` all override it returning their private `sid`
  field (each at line 22 of their respective files). `grep "extends
  EndGameMessage"` returns exactly these four classes plus the abstract
  reference sites in BettingMiniGameBot / GameMessageTypes interface —
  no missing subclass.
- Bet→result correlation is sessionId-keyed:
  `BotMemory.recordBetSent` checks
  `currentRound.sessionId == sessionId` and drops mismatches with a WARN
  (`BotMemory.java:110-117`); `BotMemory.completeRound` likewise drops
  the in-flight round on mismatch with a WARN and pushes a result with
  empty `betsByOption` (`BotMemory.java:134-161`).
- `RoundResult.winningOption` is `Optional<Integer>`
  (`RoundResult.java:30`). v1 always passes `Optional.empty()` at the
  call site in `BettingMiniGameBot.onEndGame`
  (`BettingMiniGameBot.java:304-308`) — no `HasWinningOption` interface
  was introduced (verified via `grep -rn HasWinningOption src` — only
  comments). Matches Implementation Note 4 exactly.

### Phase 3 — `BettingStrategy` interface + `RandomBehaviorStrategy` + registry
Status: implemented

Notes:
- `BettingStrategy` interface has exactly two methods: `decide(BetContext)`
  and `onRoundEnd(RoundResult)` (`BettingStrategy.java:28,38`).
- `RandomBehaviorStrategy` is the only `BettingStrategy` impl in
  production code. Annotated `@Component`, `@Scope("prototype")`,
  `@StrategyImpl(StrategyId.RANDOM)`
  (`RandomBehaviorStrategy.java:46-50`). The decision body matches the
  canonical reference: skip-check → bet-amount → option, with
  `numberOfBetsInCurrentSession` resetting on `currentRound.sessionId`
  change (lines 60-105).
- Strategy beans are prototype-scoped (Plan Architecture Decision 12 +
  Implementation Note 6); `BettingStrategyFactory.create()` delegates to
  `context.getBean(clazz)` which returns a fresh instance per call
  (`BettingStrategyFactory.java:96`). `BettingStrategyFactoryTest`'s
  `createReturnsFreshInstances` test pins this contract.
- Unknown `StrategyId` lookups throw `IllegalArgumentException`
  (`BettingStrategyFactory.java:91-94`); duplicate `@StrategyImpl` keys
  throw `IllegalStateException` at startup (lines 67-71).

### Phase 4 — `BotGroup.strategyMix` + fill-to-target + `BotHealthDTO`
Status: implemented

Notes:
- `BotGroup.strategyMix: List<WeightedStrategy>` field present
  (`BotGroup.java:70`). Mirrored on `BotGroupDTO`
  (`BotGroupDTO.java:54`) and round-tripped through
  `BotGroupMapper.toDTO` / `toEntity` / `updateEntityFromDTO`.
- PATCH semantics are full-replace; empty `strategyMix` is rejected
  with `BadRequestException("strategyMix must be non-empty")`
  (`BotGroupMapper.java:123-128`) — matches Implementation Note 10.
- `StrategyAssignment.assign` uses largest-remainder apportionment
  (`StrategyAssignment.java:83-129`) and hash-sorts bot identifiers
  by `(identifier.hashCode() & 0x7fffffff)` with the identifier itself
  as a secondary tiebreaker (lines 163-166). The identifier shape is
  `namePrefix + i` (1-based), built in `BotGroupBehaviorService.start`
  at lines 229-232, so the same bot ID across restarts produces the
  same hash key and the same strategy assignment. Hash-pinning
  contract pinned by `StrategyAssignmentTest.DeterminismTests`.
- Single-strategy and multi-strategy use the same code path —
  `effectiveStrategyMix` falls back to `[(RANDOM, 1.0)]` only when the
  persisted mix is null/empty, but both then flow through
  `StrategyAssignment.assign`
  (`BotGroupBehaviorService.java:233-234, 512-518`).
- INFO log at bot start: `log.info("Bot {}: assigned strategy {}",
  username, strategyId)` in `createSingleBot`
  (`BotGroupBehaviorService.java:483`). One line per bot, MDC carries
  group context — matches Architecture Decision 14.
- `BotHealthDTO.strategyId` field present (`BotHealthDTO.java:31`),
  populated in `BotGroupBehaviorService.getHealth` at line 660.
- Mid-flight `strategyMix` changes do NOT re-assign already-running
  bots. The PATCH path only updates the persisted entity; assignment
  runs in `start()` from `BotGroupBehaviorService` and reads the
  configured mix only at group start, not per tick. Decision 9
  honored.
- Multi-bucket test through public `assign()` is impossible in v1
  because only one `StrategyId` value (`RANDOM`) exists — the test
  drives `apportion()` package-private and pins the apportionment-sum
  invariant via a 200-step sweep (`StrategyAssignmentTest.apportion
  mentSumsToBotCount`). The test acknowledges the limitation in its
  Javadoc. Not a drift — the algorithm is exercised; a strict
  end-to-end 30/50/20 split requires a second enum entry not in
  v1 scope.

### Phase 5 — Wire strategy into `BettingMiniGameBot.tick`
Status: implemented

Notes:
- `BettingStrategyFactory` is injected into `BotFactory` constructor
  (`BotFactory.java:65-76`) and passed into `BettingMiniGameBot` via a
  `@Setter`-generated `setStrategyFactory` (line 144).
- Strategy constructed in `initializeSubclass`:
  `strategyFactory.create(effectiveId, rng.nextLong())`
  (`BettingMiniGameBot.java:149-158`).
- `sendAsync` pipeline now uses `betCondition()` (parks decision via
  `pendingDecision.set(decision)` at lines 438-456) and `bet()`
  (reads the parked decision at lines 386-415). The atomic-reference
  parking pattern matches Implementation Note 1 (scenario engine
  cannot tolerate null supplier, so condition computes-and-parks).
- `onEndGame` routes through `strategy.onRoundEnd(roundResult)`
  (`BettingMiniGameBot.java:303-318`) wrapped via `mdcConsumer`
  registered on the pipeline (line 502).
- Old abstract methods on `Bot` are removed —
  `grep -rn "shouldBet\|resolveBetAmount\|resolveBetCondition\|
  resolveNextEntryToBet" src/main` returns only comment / Javadoc
  references in strategy classes. The `Bot` class declares no such
  abstract methods (`Bot.java` reviewed end-to-end).
- Equivalence test: `BettingMiniGameBotStrategyEquivalenceTest`
  drives the bot's `betCondition()` + `bet()` reflection-style and
  asserts the parked `BetDecision` sequence is bit-identical to a
  `legacyReplay` function that mirrors the pre-Phase-5 RNG-consumption
  order (skip-check → bet-amount → option) exactly. The legacyReplay
  helper is correct: it consumes `nextInt(100)`, `nextInt(maxSteps+1)`,
  and `nextInt(options.size())` in the same order
  `RandomBehaviorStrategy.decide` does, and the test runs across a
  fresh round boundary so the strategy's per-round counter reset is
  also pinned. This is a real equivalence proof — same seed, two
  independent code paths, identical output. Plan §"Phase 5 Verifiable"
  satisfied.

### Phase 6 — Releaser-side Mongo migrations
Status: implemented

Notes:
- `docs/reviews/BETTING_STRATEGIES/release.md` contains:
  - Exact `mongosh` migration block for `games` (lines 117-136)
    with `$arrayToObject` / `$range` / `$toString` pipeline to
    synthesize `optionAffinities` and `$unset` of `numberOfOptions` +
    `bettingOptions`.
  - Exact `mongosh` migration block for `botGroups` (lines 174-178)
    defaulting `strategyMix` to `[{strategyId:"RANDOM", weight:1.0}]`.
  - Pre-migration count queries (1.1, 2.1), sanity-check query (1.2 —
    no doc with both numberOfOptions and optionAffinities), and
    post-migration verification queries (1.4–1.7, 2.3–2.5).
  - Operator quick-reference copy-paste block (lines 314-356) with
    `print` lines that should all read `0`.
  - Rollback section with forward-reverse migration query for
    Game.numberOfOptions reconstruction (lines 282-302).
- The release runbook matches the queries Plan §"Verification B/C"
  specified verbatim, with additional sanity checks added.

## Drift

None. The diff faithfully implements the plan across all six phases.

A few observations that are NOT drift:
- The `BetContext.rng` field is sourced from the bot, not from a
  strategy-held RNG — matches Plan Implementation Note 6 ("Dev picks
  the simpler one"). The factory's `seed` parameter is reserved for
  future strategies that hold their own RNG, documented in
  `BettingStrategyFactory.create` Javadoc.
- The bot ships a defensive `new RandomBehaviorStrategy()` fallback
  when `strategyFactory` is null (`BettingMiniGameBot.java:152-158`)
  — used only by test fixtures that bypass `BotFactory`. Production
  always wires the factory. Documented inline.
- `StrategyAssignment` rejects non-positive weights with
  `IllegalArgumentException` (`StrategyAssignment.java:89-93`) — an
  extra safety check beyond the plan but consistent with
  Implementation Note 10's "treat malformed input loudly" philosophy.
- The `apportion()` method is exposed package-private specifically
  so `StrategyAssignmentTest` can drive the math directly given v1's
  single-enum limitation — explicitly called out in both the
  production Javadoc (lines 56-67) and the test Javadoc.

## Out-of-scope changes

None observed. Every touched file maps to a phase. The five `Bot*Test`
files with 3-4 line deletions each are removing references to the
now-deleted abstract methods (`shouldBet` / `resolveBetAmount` /
`resolveBetCondition`) on the `TestBot` inner class — required by
Phase 5's `Bot` API change. `BotFactoryFailLoudTest` updated to the
new `BotFactory` constructor signature with `BettingStrategyFactory`
mock — required by Phase 5's `BotFactory` constructor change.

## Amendments to the plan

None.
