# Code Review — BETTING_STRATEGIES

Branch: `feat/betting-strategies`
Reviewed diff: `git diff main..feat/betting-strategies`

## Verdict

CHANGES_REQUESTED

Two real concurrency / correctness problems sit in the strategy hot path; one
silent migration data-loss bug breaks the "bit-for-bit equivalence" claim for
any production `Game` document that customised `bettingOptions`.

## Findings

### [bug] `bettingOptions` is silently dropped on read-fallback and on Mongo migration — equivalence claim is broken for any non-default option set
`src/main/java/com/vingame/bot/domain/game/model/Game.java:82-93`
`docs/reviews/BETTING_STRATEGIES/release.md:115-137`

The pre-branch `Game` carried two fields used to pick an option to bet on:
`numberOfOptions` (size of the implicit `[0..n-1]` range) and `bettingOptions`
(an explicit `List<Integer>` of allowed option ids — e.g. `[1, 10, 100]`).
`Game.getEffectiveBettingOptions()` preferred the explicit list when set.

After this branch:

- The read-side fallback `Game.getEffectiveOptionAffinities()` only consults
  `numberOfOptions` and synthesises `{0:1, 1:1, ..., n-1:1}` —
  `bettingOptions` is never read. Any unmigrated Mongo doc that had
  `bettingOptions=[1,10,100]` will silently start placing bets on options
  `[0,1,2,3]` after this branch ships, with no warning.
- The Phase 6 migration in `release.md` literally `$unset`s
  `bettingOptions` (line 133) without copying its contents into
  `optionAffinities`. Once the migration runs, the original option set is
  irrecoverable from the live document.

The plan acknowledges affinity-aware option selection is a future strategy's
concern (Architecture Decision 5), but the `Game` legacy field
`bettingOptions` is not about affinity values — it changes the **set of
option ids** strategies bet on. The bit-for-bit equivalence claim in the
plan's Phase 5 spec and in `RandomBehaviorStrategyTest`'s Javadoc is
therefore false for any production game that ever had `bettingOptions` set.

Fix shape: either (a) extend the fallback to read `bettingOptions` and
synthesise `{k:1 for k in bettingOptions}`, and have the migration build
`optionAffinities` from `bettingOptions` when present (falling back to the
`numberOfOptions` range only when `bettingOptions` is null/empty), or
(b) verify against the live Mongo and explicitly state in the release runbook
that "no game in this dataset has bettingOptions set" so the data loss is
intentional. Today neither is done.

### [bug] Race between condition and supplier — `bet()` can crash the scenario for a bot when StartGame fires mid-tick
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:248-249,329-348,386-415`

`betCondition()` parks a `BetDecision` in `pendingDecision`; `bet()` reads
it via `getAndSet(Optional.empty())` and throws `IllegalStateException` if
empty. The two run on the **scenario thread**. But `onStartGame` (netty
message-processor thread) also calls `pendingDecision.set(Optional.empty())`
at line 249, and `beforeReconnect` does the same at line 347.

The sequence the comment claims "should not happen" is in fact reachable:

1. Scenario thread executes `betCondition()` — strategy returns a decision,
   `pendingDecision.set(Optional.of(d))`, condition returns `true`.
2. Before the scenario engine invokes the supplier, the netty thread receives
   the next round's StartGame and runs `onStartGame`. Line 249 clears
   `pendingDecision`.
3. Scenario thread invokes `bet()` → `getAndSet(Optional.empty())` returns
   empty → `throw new IllegalStateException("...condition/supplier ordering
   violated")`.

This isn't a once-in-a-blue-moon race: the scenario engine schedules
condition and supplier in two distinct steps, the bet interval is 1s, and
StartGame is the very event that ends "BET" and begins the next round.
A bot that loses this race kills its scenario with an exception.

Fix shape: have `bet()` treat an empty `pendingDecision` as a benign skip
(return a sentinel "no-op" message, or — better — drop the parked-decision
indirection entirely and let the supplier compute the decision itself,
returning a clearly-handled "no bet" result the scenario engine ignores).
The parked-decision pattern only makes sense if condition and supplier are
guaranteed to run atomically with respect to all other writers, which they
are not.

### [bug] `RoundState.sessionId` cross-thread read in `RandomBehaviorStrategy.decide` has no happens-before edge to `BotMemory.beginRound`
`src/main/java/com/vingame/bot/domain/bot/strategy/RandomBehaviorStrategy.java:68`
`src/main/java/com/vingame/bot/domain/bot/strategy/RoundState.java:22-39`
`src/main/java/com/vingame/bot/domain/bot/strategy/BotMemory.java:96-101`

`BotMemory.beginRound` writes `currentRound.setSessionId(sessionId)` under
the BotMemory intrinsic lock (netty message-processor thread).
`RandomBehaviorStrategy.decide` reads `ctx.currentRound().getSessionId()`
(line 68) on the scenario thread, **outside** any synchronized block, and
**without** acquiring BotMemory's monitor. The strategy then enters
`synchronized(this)` (its own monitor) — that block establishes happens-before
edges for its own counter fields, but not for the RoundState read it already
performed.

`sessionId` is a plain non-volatile `long` on `RoundState`. There is no
synchronization edge from `beginRound`'s write to the strategy's read.
The "Thread-safety" Javadoc on `BotMemory` claims all reads exposing internal
state are guarded by `synchronized` — `getCurrentRound()` is synchronized,
but that only guards the **reference read**; any subsequent field access on
the returned object is unsynchronized. The thread-safety claim is therefore
not what the implementation provides.

In practice on x86 + 64-bit JVM a torn read is unlikely and the visibility
window is small, so this would mostly look like "the strategy occasionally
sees the prior round's sessionId for a tick". Two consequences:

- The "per-round bet counter resets on sessionId change" property is not
  guaranteed at a round boundary — the strategy can miss the change and
  apply the prior round's counter to one tick of the new round.
- `BettingMiniGameBotStrategyEquivalenceTest` only verifies single-threaded
  invocation, so the equivalence pin doesn't catch this.

Fix shape: either mark `RoundState.sessionId` `volatile`, or expose the read
through a `BotMemory.getCurrentSessionId()` method that is itself
synchronized — and make sure the strategy doesn't keep a stale reference to
`RoundState` across the boundary.

### [smell] `BotMemory.getCurrentRound()` returns the mutable internal `RoundState` while pretending to be synchronized
`src/main/java/com/vingame/bot/domain/bot/strategy/BotMemory.java:76-82`

The method body is `synchronized` but only the reference read benefits.
Once the caller has the reference (the strategy via `BetContext.currentRound()`),
it can read/write the mutable map and the non-volatile fields freely without
holding the BotMemory monitor. The Javadoc gestures at "go through
`snapshotCurrentRound` for a defensive copy", but `snapshotCurrentRound` does
not exist; `snapshotCurrentRoundBets` does (line 193). And the production
call site (`BettingMiniGameBot.buildBetContext`) hands out the live reference,
not a snapshot.

Either remove the public getter and force all reads through snapshot methods,
or rename it to make clear it returns a live reference the caller is
responsible for synchronizing on. The current shape contradicts the class's
own thread-safety contract.

### [smell] Defensive `if (memory != null)` guards in `BettingMiniGameBot` are dead branches in production
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:244,303,310-318,375,408`

`memory` is assigned in `initializeSubclass()` and never cleared. All call
sites that check `memory != null` run after `initialize()` has returned, so
the check never fires in production. The `else if (strategy != null)` branch
at 310-318 synthesises a fake `RoundResult` so a test fixture without memory
still drives the strategy — that's the only real consumer of the guard.

The test-fixture path is reasonable but it's worth either (a) commenting
that the guards exist purely for the bypass-builder test path and not
production, or (b) letting test fixtures fail loud if they forget to wire
memory. The current state ships dead branches in the hot path and
production code that reads "we might not have memory" when in fact we
always do.

### [smell] `BettingStrategyFactory.create(StrategyId, long seed)` ignores `seed` for the only existing implementation
`src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java:88-97`
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:151`

The factory accepts a `seed` parameter for "future strategies that hold
their own RNG"; the only call site at `BettingMiniGameBot:151` passes
`rng.nextLong()` and `RandomBehaviorStrategy` ignores it. This is
speculative future-proofing: the moment a strategy needs its own seeded
RNG, the signature can be extended. Until then, the parameter is dead
weight that confuses readers about what does and doesn't seed strategy
behaviour today. Consider dropping the parameter and re-adding it when a
strategy actually needs it.

### [smell] `BotMemory` constructor does not null-check `game`
`src/main/java/com/vingame/bot/domain/bot/strategy/BotMemory.java:54-66`

Capacity is validated; `game` is accepted as-is and stored. A null `game`
will only surface much later when a strategy calls
`ctx.game().getEffectiveOptionAffinities()`. Add an
`Objects.requireNonNull(game, "game")` so the misconfiguration fails at
construction with a clear message.

### [smell] Stale Javadoc on `BotConfiguration.game`
`src/main/java/com/vingame/bot/config/bot/BotConfiguration.java:40-43`

The Javadoc still says "Game configuration containing offset,
**numberOfOptions**, pluginName, md5, etc." — `numberOfOptions` is now the
legacy-only field used purely for read-side fallback. Update to mention
`optionAffinities` or just remove the field enumeration.

### [smell] `StrategyAssignment.assign` "pinned across restarts" only holds when `botCount` is unchanged
`src/main/java/com/vingame/bot/domain/bot/strategy/StrategyAssignment.java:28-33,162-166`

The hash-sort pins each identifier to its position in the sorted list. If a
group's `botCount` is increased (e.g. 10 → 20), the new identifiers
`<prefix>11..20` slot into the sorted list at arbitrary positions, shifting
chunk boundaries and re-assigning some pre-existing bots. The Javadoc claims
"a group restart re-assigns each bot to the same strategy it had before",
which is true for a pure restart with the same `botCount` and same mix, but
not for a scale-up. Worth a one-line caveat in the Javadoc so an operator
doesn't expect the property to survive group resize.

### [style] Inline reflective use of fully-qualified names in `BettingMiniGameBot`
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:157`

`this.strategy = new com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy();`
uses a fully-qualified name despite `RandomBehaviorStrategy` being in the
imported `strategy.*` package set used by other declarations in the file.
The `RandomBehaviorStrategy` import is the only one missing — add it and
use the simple name to match surrounding code style.

## Notes

- Phase 1's read-side fallback for `Game` (`getEffectiveOptionAffinities`)
  is a clean pattern; my objection is only that it omits `bettingOptions`.
- The largest-remainder apportionment in `StrategyAssignment.apportion` is
  well-structured and has good unit coverage. The tiebreaker (stable by
  declaration order, deterministic) is the right call.
- INFO/DEBUG levels in the strategy code conform to `CLAUDE.md`: per-tick
  is DEBUG, group-level "Bot {}: assigned strategy {}" is INFO at
  `BotGroupBehaviorService:483`.
- `EndGameMessage.getSessionId()` is correctly added to all four
  product-specific subclasses (Tip, Bom, Nohu, B52). The abstract base is
  the right place for it.
- `BotGroupMapper.updateEntityFromDTO` correctly implements PATCH
  full-replace for `strategyMix` and rejects an empty list with 400 — that
  matches the plan's Architecture Decision 9.
- `GameMapper.updateEntityFromDTO` correctly implements PATCH full-replace
  for `optionAffinities` (no field-merge) and silently ignores
  `numberOfOptions` on PATCH — matches Architecture Decision 6.
- Phase 6 release runbook is unusually thorough — pre-flight, ordered
  steps, reverse migration, operator quick-reference. The
  `bettingOptions` data loss is the one substantive gap.
