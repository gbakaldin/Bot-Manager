# QA — BETTING_STRATEGIES

**Verdict:** PASS
**Build:** `mvn test` → 637 tests, 0 failures, 0 errors

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/message/EndGameMessageSessionIdTest.java`
  — 5 tests pinning that `EndGameMessage.getSessionId()` is abstract on the
  base class and that each concrete subclass (Tip, Bom, Nohu, B52) returns
  the underlying `sid` field. A subclass that left this as a stub returning
  `0L` would silently make every round look mismatched and discard every bet
  from the rolling history.
- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotStrategyHookTest.java`
  — 5 tests pinning the Phase 5 / Implementation Note 4 contract that
  `BettingMiniGameBot.onEndGame` calls `strategy.onRoundEnd(...)` after
  `BotMemory.completeRound`, with `winningOption=Optional.empty()` for v1.
  Also pins that `BotMemory.snapshotGlobalRecentWins()` stays empty in v1
  because `recordGlobalWin(Optional.empty())` is a no-op — a future
  strategy that depends on globalRecentWins will fail loudly here.
- `src/test/java/com/vingame/bot/domain/bot/strategy/StrategyAssignmentApportionmentMathTest.java`
  — 12 tests pinning the largest-remainder math algebraically. Includes the
  explicit (30, 50, 20) assertion called out in the plan's Phase 4
  verification (against a reference re-implementation, since the production
  routine coalesces same-id entries — see "Coverage gaps" below). Also
  pins the rounding edge cases (5-bot tie-break, sum-invariant across n=1..200,
  zero-bucket on tiny weight).

## Coverage of the diff

- `src/main/java/com/vingame/bot/domain/game/model/Game.java` ←
  `GameTest.java` (already in diff) — covers the affinity-map / legacy
  `numberOfOptions` fallback. The "un-migrated Mongo doc" case is covered by
  the existing `shouldSynthesizeFromLegacyWhenAffinitiesNull` test, which
  invokes the field-injection path via the builder.
- `src/main/java/com/vingame/bot/domain/game/mapper/GameMapper.java` ←
  `GameMapperTest.java` (already in diff) — covers the synthesized-affinities
  read path, the `numberOfOptions` create shorthand, the PATCH full-replace
  semantics, and the ignore-on-PATCH for the create shorthand.
- `src/main/java/com/vingame/bot/domain/bot/strategy/BotMemory.java` ←
  `BotMemoryTest.java` (already in diff) — 17 tests covering bounded FIFO
  eviction, bet→result correlation, sessionId mismatch WARN logs, defensive
  snapshots, balance snapshot on `beginRound`.
- `src/main/java/com/vingame/bot/domain/bot/message/EndGameMessage.java` and
  the four concrete subclasses ← **NEW** `EndGameMessageSessionIdTest.java`.
- `src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategy.java`,
  `BetContext.java`, `BetDecision.java`, `StrategyId.java`,
  `WeightedStrategy.java`, `StrategyImpl.java` ← Exercised transitively by
  `RandomBehaviorStrategyTest` (already in diff) and `BettingStrategyFactoryTest`
  (already in diff).
- `src/main/java/com/vingame/bot/domain/bot/strategy/RandomBehaviorStrategy.java` ←
  `RandomBehaviorStrategyTest.java` (already in diff) — pins RNG-consumption
  order (skip-check → bet-amount → option), bit-for-bit equivalence with a
  legacy-replay helper, per-round counter reset on sessionId change,
  `betSkipPercentage` gating, `maxBetsPerRound` cap, `onRoundEnd` no-op
  invariance.
- `src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java` ←
  `BettingStrategyFactoryTest.java` (already in diff) — `@StrategyImpl`
  discovery, prototype instance creation, unknown-id throws, duplicate-impl
  throws, unannotated bean skipped with WARN.
- `src/main/java/com/vingame/bot/domain/bot/strategy/StrategyAssignment.java` ←
  `StrategyAssignmentTest.java` (already in diff) + **NEW**
  `StrategyAssignmentApportionmentMathTest.java`. Covers single-bucket
  apportionment, sum-to-n invariant, determinism / input-order independence
  (restart pinning surrogate), duplicate identifier rejection, the explicit
  (30, 50, 20) math via the reference implementation.
- `src/main/java/com/vingame/bot/domain/botgroup/model/BotGroup.java`,
  `BotGroupDTO.java`, `BotGroupMapper.java`, `BotHealthDTO.java` ←
  `BotGroupMapperTest.java` (extended in diff) — full-replace PATCH
  semantics, null-DTO retain, empty-list reject, `strategyMix` round-trip.
- `src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java` ←
  `BotGroupBehaviorServiceTest.java` (extended in diff) — `start()` propagates
  the assigned `StrategyId` into `BotConfiguration`, read-side fallback to
  `[(RANDOM, 1.0)]` for unmigrated docs, `getHealth()` surfaces `strategyId`
  on `BotHealthDTO`.
- `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java` ←
  `BettingMiniGameBotTest.java` (extended in diff) + `BettingMiniGameBotMemoryTest.java`
  (already in diff) + `BettingMiniGameBotStrategyEquivalenceTest.java` (already
  in diff) + **NEW** `BettingMiniGameBotStrategyHookTest.java`. The
  equivalence test in particular drives the new `betCondition` → parked
  decision → supplier pipeline via reflection on `bot.setRandom(...)` and
  verifies the captured decision sequence matches a legacy replay byte-for-byte.
- `src/main/java/com/vingame/bot/config/bot/BotConfiguration.java` and
  `src/main/java/com/vingame/bot/domain/bot/core/Bot.java` — `strategyId` getter
  exercised through `BotGroupBehaviorServiceTest.healthSurfacesStrategyId`.
- `src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java` ←
  `BotFactoryFailLoudTest.java` (touched in diff) — verifies the strategy
  factory is wired in.
- `docs/reviews/BETTING_STRATEGIES/release.md` — release runbook, no code.

## Scrutiny notes (the items explicitly called out)

### Bit-for-bit equivalence (`BettingMiniGameBotStrategyEquivalenceTest`)

The equivalence test does prove what it claims, with one caveat. The
`legacyReplay` helper reimplements the pre-Phase-5 decision pipeline
algebraically: it consumes RNG in the same order
(`nextInt(100)` for skip-pct → `nextInt(maxSteps+1)` for amount →
`nextInt(options.size())` for option), increments the per-round counter only
when the skip-pct gate passes, and gates against `maxBetsPerRound`. This
mirrors the structure documented in `RandomBehaviorStrategy.decide()` and
matches the Findings section of the plan
(`docs/plans/BETTING_STRATEGIES.md:283-315` references to the pre-Phase-5
methods). The actual pre-Phase-5 source is no longer in the tree (Phase 5
deletion was clean), so the equivalence is anchored against the documented
contract rather than against the literal old code — that's an acceptable
risk given the existing in-strategy `RandomBehaviorStrategyTest.Equivalence`
nested class proves the strategy itself is RNG-equivalent to the same
helper, and this test then proves the bot's pipeline routes through the
strategy correctly.

The test actually exercises the new code path: it drives `betCondition()`
(returns true / parks a `BetDecision`) then `bet()` (pops the parked decision
and books the bet into memory), and asserts the captured decision sequence
matches the legacy replay. It is NOT testing that something matches itself
— it is testing that the new condition→supplier pipeline produces the same
decisions as the documented legacy code, given the same seed. The round
boundary test (`equivalenceAcrossRoundBoundary`) additionally verifies that
the strategy's per-round counter resets on `sessionId` change, matching the
legacy `onStartGame numberOfBetsInCurrentSession = 0` behavior.

### Hash-pinned strategy assignment (`StrategyAssignmentTest`)

The determinism contract is well-pinned by `sameInputsSameOutput` (entry-set
equality, including iteration order) and `inputOrderDoesNotMatter` (the same
identifier always maps to the same `StrategyId` regardless of input list
ordering). `hashBasedSortingPinsAssignment` shuffles the input three times
with different seeds and confirms full identifier coverage.

**Structural limitation:** With only `StrategyId.RANDOM` in the enum, every
bot in every test ends up with `RANDOM`. The "restart-stable" property in its
strongest form (bot `foo` keeps the same *distinct* strategy across restarts)
cannot be exercised end-to-end until a second `StrategyId` exists. What
IS proven: (a) the algorithm is deterministic given the same inputs; (b)
input order does not affect the output. Combined with the hash-sort code
path being run unconditionally, this is sufficient to be confident that a
restart with the same identifiers + mix will produce the same assignment.
A second strategy entry will let us write a stronger "bot X gets distinct
strategy Y" test — flagged for the next strategy implementer.

### Fill-to-target apportionment

**Critical finding:** The existing `hundredBotsThreeWay` test in
`StrategyAssignmentTest.ApportionTests` does NOT prove the 30/50/20 split.
Because v1 ships only `StrategyId.RANDOM`, the three weighted entries
(0.3, 0.5, 0.2) all coalesce into one bucket in `apportion()` — the
production routine produces `target=[100]`, not `target=[30, 50, 20]`. The
existing test asserts this correctly, but the comment is confusing and the
30/50/20 verification specifically called out in the plan
(`docs/plans/BETTING_STRATEGIES.md:499-504`) is not actually run against
the production code with distinct buckets.

I added `StrategyAssignmentApportionmentMathTest` which:
1. **Algebraically** proves the largest-remainder formula produces
   `[30, 50, 20]` for `(0.3, 0.5, 0.2) * 100` via a reference reimplementation
   of the same algorithm, inline in the test for forensic clarity.
2. Confirms the production routine (single bucket after coalesce) agrees
   with the reference algorithm fed `[1.0]` over the same `n`.
3. Pins the 5-bot edge case explicitly: weights `(0.3, 0.5, 0.2)` produce
   `(2, 2, 1)` (matching the plan's documented expected value at line 503,
   "e.g. 2 / 2 / 1 — exact values pinned by the remainder rule"). Note: the
   plan's example is illustrative — the actual largest-remainder rule with
   tie-break by index puts the leftover on slot 0, not slot 1.
4. Sweeps `n=1..200` confirming the sum invariant.

The sum invariant in the existing test is real (and a real check on the
production code), but it does not prove per-bucket counts within ±1 of
target — the assertion the user asked for. The new
`StrategyAssignmentApportionmentMathTest.thirtyFiftyTwenty` does, against
the reference algorithm; coverage of the production routine on distinct
buckets is structurally blocked until a second `StrategyId` exists.

### Read-side fallback on `Game` (un-migrated doc path)

The `GameTest.shouldSynthesizeFromLegacyWhenAffinitiesNull` test covers
this: a `Game` built with only `numberOfOptions=4` (via the builder, which
exposes the package-private legacy field) produces
`getEffectiveOptionAffinities() = {0:1, 1:1, 2:1, 3:1}`. The
`shouldSynthesizeFromLegacyWhenAffinitiesEmpty` variant covers the
optionAffinities-is-empty-map case. `shouldPreferExplicitAffinitiesOverLegacy`
locks the priority order. `shouldThrowWhenNeitherSet` and
`shouldThrowWhenLegacyIsZero` cover the misconfigured-doc throws.

The `GameMapperTest.shouldSynthesizeAffinitiesFromLegacyOnRead` further
verifies that the synthesized affinity map flows through the mapper into the
DTO, so the API surface for unmigrated docs returns `optionAffinities`
synthesized from the legacy field.

**Gap:** No Mongo-round-trip integration test exists for the legacy field —
the read path is exercised through the builder's package-private setter,
not through a real Spring Data Mongo deserialization. Spring Data Mongo's
default behavior is to set fields by reflection so the legacy field
populated from BSON would land on the entity correctly, but this is
unverified at the integration level. Given the existing test pattern in
the repo (services use mocked `MongoTemplate`), adding a real Mongo round-trip
test would require Testcontainers and is out of scope for this QA pass — the
contract is well-pinned at the field-level. Flagged for the Releaser to
verify on staging per the plan's `## Verification` section A.3.

### `onRoundEnd` called after `BotMemory.completeRound`, with `winningOption=empty`

Pinned explicitly by **NEW** `BettingMiniGameBotStrategyHookTest`:

- `onRoundEndCalledOncePerRound` — one invocation per EndGame, RoundResult
  carries the EndGame sessionId + payout.
- `winningOptionIsEmptyInV1` — `Optional.empty()` for every round (
  Implementation Note 4 acknowledged).
- `onRoundEndCalledAfterCompleteRound` — at the moment of the callback,
  `BotMemory.snapshotLastResults()` already contains the new
  `RoundResult`, proving the bot calls `completeRound` first.
- `globalRecentWinsStaysEmptyInV1` — five rounds, `snapshotGlobalRecentWins`
  remains empty because the bot passes `Optional.empty()` to
  `recordGlobalWin` and that's a documented no-op.
- `multipleRoundsDeliverInOrder` — five sequential rounds delivered in
  order with matching sessionIds + payouts.

## Gaps

1. **Multi-bucket apportionment via the public API.** With only one
   `StrategyId` value, `StrategyAssignment.apportion()` always coalesces to
   a single bucket. The 30/50/20 invariant is proven algebraically against
   a reference reimplementation, not against the production routine driving
   distinct buckets. Add a real multi-bucket test when a second `StrategyId`
   ships (the reference test should then be deleted).
2. **Mongo round-trip for legacy `numberOfOptions`.** The field-level
   fallback is exercised through the builder; the actual Spring Data Mongo
   deserialization of a pre-migration document is unverified. Acceptable for
   v1 — the field has a `@Field("numberOfOptions")`-equivalent setter
   (package-private with default name match), and the read-side fallback
   covers the deploy window per Releaser procedure. Flagged for staging
   verification (plan section A.3).
3. **Restart-stability across distinct strategies.** Cannot be proven with
   one enum value. Determinism + order-invariance is proven (necessary
   conditions). The hash-sort step is exercised unconditionally; combined
   with the determinism proof, a restart with the same inputs will produce
   an identical assignment by construction.
4. **`betSkipPercentage` field not populated.** Plan Implementation Note 2:
   `BotBehaviorConfig.betSkipPercentage` is not set in
   `BotGroupBehaviorService.createSingleBot()`, so it defaults to 0 and bots
   always bet. This is documented as "preserves latent-bug behavior" — out
   of scope, not a test gap.
5. **Watchdog / netty thread interactions.** The strategy hook test invokes
   `onEndGame` reflectively from the test thread; in production the call
   path is `netty-ws-message-processor-ws-<userName>`. MDC propagation in
   that scenario is covered by `BettingMiniGameBotMdcTest` (unchanged in
   this diff). No additional coverage needed here.

## Failures (if any)

None. `mvn test` exits 0 with 637 tests run, 0 failures, 0 errors.

QA verdict: PASS
Tests added: 22 (5 + 5 + 12)
Tests passing: 637 (615 baseline + 22 new)
