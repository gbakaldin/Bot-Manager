# MARTINGALE_STRATEGIES

Feature branch: `feat/martingale-strategies`.

## Goal

Add eight new `BettingStrategy` implementations on top of the framework shipped
by `docs/plans/BETTING_STRATEGIES.md` — the Cartesian product of four classic
progressions (Classic Martingale, Paroli, D'Alembert, Fibonacci) with two
affinity-driven option-pickers (Cautious, Aggressive). The eight ids show up in
the `GET /api/v1/strategy/` listing and can immediately be referenced inside
`BotGroup.strategyMix`. No framework changes: each strategy is a new
`@StrategyImpl`-annotated prototype-scoped bean discovered by the existing
`BettingStrategyFactory` at startup.

## Findings — Current State

### Framework anchors (already shipped)

- `BettingStrategy` interface — `decide(BetContext)` + `onRoundEnd(RoundResult)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategy.java:18-39`).
- `RandomBehaviorStrategy` is the **reference impl** for thread-safety,
  per-round counter handling, and skip-tick gating
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/RandomBehaviorStrategy.java:50-114`).
  In particular:
  - Prototype-scoped `@Component` with `@StrategyImpl(StrategyId.RANDOM)`
    (line 46-50). No-arg constructor.
  - Per-round bet counter resets via `sessionId` change detection inside a
    `synchronized (this)` block (line 68-87).
  - RNG-consumption order: skip-check → bet-amount → option (line 82-101).
- `BetContext` (record) carries `behavior`, `game`, `currentBalance`,
  `currentRound`, `memory`, `rng`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BetContext.java:42-49`).
- `BetDecision(int optionId, long amount)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BetDecision.java:19`).
- `RoundResult` exposes `balanceDelta = payout - staked`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/RoundResult.java:28-35`).
  Strategies key win/loss/no-bet off `delta > 0` / `delta < 0` /
  `delta == 0 && betsByOption.isEmpty()`.
- `BettingStrategyFactory.init()` discovers all `@StrategyImpl` beans at
  startup; no factory change needed
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java:54-75`).
- `StrategyController` (`GET /api/v1/strategy/`) returns one
  `StrategyInfoDTO(id, displayName, description)` per enum value
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/controller/StrategyController.java:22-24`).
  Frontend renders `displayName` and `description` as plain text.

### `Game.getEffectiveOptionAffinities()` is `Map<Integer, Integer>`

`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/model/Game.java:110-130`.
- Returns the persisted `optionAffinities` map if non-null/non-empty.
- Falls back to a flat-prior map synthesized from legacy `bettingOptions` or
  `numberOfOptions` (every option gets affinity 1).
- Throws `IllegalStateException` only if neither field is set.
- **Affinity semantics**: integer ≥ 1 in practice; the flat-prior fallback
  always emits 1. No formal contract that affinity > 0 — we must defensively
  guard against affinity = 0 or negative values to avoid the `1/affinity`
  blow-up the user flagged in the prompt.

### `BetContext.rng()` and `behavior` config

- `BotBehaviorConfig` provides `minBet`, `maxBet`, `betIncrement`,
  `maxBetsPerRound`, `betSkipPercentage`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/config/bot/BotBehaviorConfig.java:12-58`).
- `betSkipPercentage` field exists and is consumed by `RandomBehaviorStrategy`
  (line 82). Note: the BETTING_STRATEGIES plan Implementation Note 2 says the
  field is not currently populated by `BotGroupBehaviorService.createSingleBot()`
  — confirmed still the case (`grep` returns no occurrences). Martingale
  strategies will still consult it (defaults to 0 → never skips), so when it
  does get wired through the BotGroup form the new strategies pick it up for
  free.

### No existing affinity-aware picker

`grep -rn "affinity\|pickOption\|weighted"` over
`src/main/java` returns only docstring mentions and `StrategyAssignment`'s
weighted apportionment (unrelated — it apportions strategies to bots, not
options to bets). No `OptionPicker` / `AffinityPicker` / similar class exists.
Introducing `AffinityOptionPicker` is greenfield.

### `RandomBehaviorStrategyTest` is the test-harness template

`/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/strategy/RandomBehaviorStrategyTest.java`:
- Hand-rolled `BotMemory` + `BetContext` factories (lines 43-62).
- Seeded `Random` for deterministic behaviour assertions.
- Per-round boundary driven by `mem.completeRound(...)` + `mem.beginRound(...)`
  (line 220-221).
- This is the pattern the Martingale tests must mirror — no Spring context,
  no Mockito for the strategy itself, just `BotMemory` + handwritten
  `BetContext`.

### Strategy listing endpoint and validation

- `GET /api/v1/strategy/` returns all `StrategyId.values()` mapped through
  `StrategyInfoDTO.of(id)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/controller/StrategyController.java:22-24`).
  Adding eight enum entries automatically expands this endpoint with no
  controller change.
- `BotGroup.strategyMix` is **not validated against registered StrategyIds**
  at the API boundary. `BotGroupService.save` only validates username length
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:120,184`).
  An unknown strategy id is caught later at start-time via
  `BettingStrategyFactory.create()` which throws
  `IllegalArgumentException`
  (`BettingStrategyFactory.java:86-90`). Adding new enum entries cannot break
  existing groups — at worst a stale Mongo doc references an id that no
  longer exists, and that's a config error, not a regression on our side.

### Thread model recap (must obey)

- `decide()` runs on the scenario thread (`pool-N-thread-1`).
- `onRoundEnd()` runs on the netty processor thread
  (`netty-ws-message-processor-ws-<userName>`).
- Per-strategy mutable state must be `synchronized` on `this` — matches
  `RandomBehaviorStrategy.decide` line 69 and the documented thread model
  in `BettingStrategy` interface javadoc (line 13-17).

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| Enum entries — 8 new values | Ready | Pure edit to `StrategyId.java`; auto-flows to `/strategy/` listing. |
| `AffinityOptionPicker` helper | Ready | Greenfield class. No existing picker to coexist with. |
| Shared `MartingaleStrategySupport` base class | Ready | Pure code; no framework changes. |
| Four progression base classes | Ready | Sub-types of `MartingaleStrategySupport`. |
| Eight concrete strategy classes (2 risk profiles × 4 progressions) | Ready | Thin subclasses; each is `@Component @Scope("prototype") @StrategyImpl(<id>)`. |
| Factory discovery | Ready | `BettingStrategyFactory.init()` is generic; no code edit. |
| `GET /api/v1/strategy/` listing | Ready | Auto-expanded via enum. |
| `BotGroup.strategyMix` validation | Ready | No api-side validation today; new ids are safe to add. |
| Unit tests for progressions | Ready | Mirror `RandomBehaviorStrategyTest` harness pattern. |
| Affinity picker distribution test | Ready | Seeded RNG + Chi-square-style ratio assertions with tolerance. |
| Frontend (`displayName`/`description`) | Ready | Strings live on enum; renders as plain text — no rich formatting. |
| Mongo migration | N/A | No schema change. |

## Architecture Decisions

These are the locked-in answers Dev and the reviewer measure against. The
user has already chosen the broad shape (8 enum entries, orthogonal
progression × risk profile, share via base class); these decisions pin the
details.

### A1. Eight enum entries (full enumeration)

Add to `StrategyId` exactly these values in this order (after `RANDOM`):

```java
MARTINGALE_CLASSIC_CAUTIOUS("Classic Martingale (Cautious)",
        "Doubles the bet after every loss and resets to the minimum after a win. "
        + "Picks the safer entries more often."),
MARTINGALE_CLASSIC_AGGRESSIVE("Classic Martingale (Aggressive)",
        "Doubles the bet after every loss and resets to the minimum after a win. "
        + "Chases the riskier entries with bigger payouts."),
PAROLI_CAUTIOUS("Paroli (Cautious)",
        "Doubles the bet after every win and resets after a loss or after a short winning streak. "
        + "Picks the safer entries more often."),
PAROLI_AGGRESSIVE("Paroli (Aggressive)",
        "Doubles the bet after every win and resets after a loss or after a short winning streak. "
        + "Chases the riskier entries with bigger payouts."),
DALEMBERT_CAUTIOUS("D'Alembert (Cautious)",
        "Raises the bet by one step after a loss and lowers it by one step after a win. "
        + "Picks the safer entries more often."),
DALEMBERT_AGGRESSIVE("D'Alembert (Aggressive)",
        "Raises the bet by one step after a loss and lowers it by one step after a win. "
        + "Chases the riskier entries with bigger payouts."),
FIBONACCI_CAUTIOUS("Fibonacci (Cautious)",
        "Follows the Fibonacci sequence — one step forward after a loss, two steps back after a win. "
        + "Picks the safer entries more often."),
FIBONACCI_AGGRESSIVE("Fibonacci (Aggressive)",
        "Follows the Fibonacci sequence — one step forward after a loss, two steps back after a win. "
        + "Chases the riskier entries with bigger payouts.");
```

Strings are plain text (no markdown, no parentheticals beyond the
risk-profile suffix). `displayName` is what the UI dropdown shows;
`description` populates a tooltip / hint line. **Do not rename existing
entries; only append.** Reordering existing values would not break Mongo
(which stores enum names as strings) but reordering tests pin the listing
shape.

### A2. `RiskProfile` is a simple enum (not a constructor arg pattern shift)

Add `RiskProfile { CAUTIOUS, AGGRESSIVE }` in the same package as the
picker. The eight concrete classes each pass their `RiskProfile` to the
shared base class. Rationale: a `RiskProfile` constructor parameter on
`AffinityOptionPicker` makes the picker stateless and self-contained — no
need to thread the profile through every `pick(...)` call.

```java
public final class AffinityOptionPicker {
    private final RiskProfile profile;
    public AffinityOptionPicker(RiskProfile profile) { ... }
    public int pick(Map<Integer, Integer> affinities, Random rng) { ... }
}
```

`pick` is the only method. RNG is passed per call (matches the
`BetContext.rng()` plumbing — picker holds no RNG of its own).

### A3. Affinity picker weighting math (lock the formulae)

Input: `Map<Integer, Integer> affinities` from
`Game.getEffectiveOptionAffinities()`. Per option `o` with raw affinity
`a(o)`:

- **CAUTIOUS weight**: `max(0, a(o))`. Negative or zero affinities receive
  weight 0 (cannot be picked). If **every** affinity is ≤ 0 the picker
  falls back to uniform pick over `affinities.keySet()` — defensive guard
  against a misconfigured game; logged WARN once per picker call (rare hot
  path).
- **AGGRESSIVE weight**: `(maxAffinity + 1) - max(0, a(o))` where
  `maxAffinity = affinities.values().stream().filter(v -> v != null).mapToInt(Integer::intValue).max().orElse(1)`.
  The `+1` guarantees the highest-affinity option still has weight 1 (never
  starves), and reflecting around `(maxAffinity + 1)` inverts the
  preference symmetrically.
- **Sampling**: standard cumulative-weight + `rng.nextDouble() * totalWeight`
  (or `rng.nextInt(totalWeightInt)` if total fits in int). Determinism with
  a seeded `Random` is required — the picker tests pin this.

Example with affinities `{0:5, 1:1}`:
- CAUTIOUS → weights `{0:5, 1:1}` → option 0 picked 5/6 of the time.
- AGGRESSIVE → `maxAffinity=5`, weights `{0:1, 1:5}` → option 1 picked 5/6
  of the time.

### A4. Class layout — abstract base + 4 progression classes + 8 thin concrete subclasses

Locked structure (no negotiation):

```
strategy/martingale/
  RiskProfile.java                       (enum)
  AffinityOptionPicker.java              (final class)
  MartingaleStrategySupport.java         (abstract base)
  ClassicMartingaleStrategy.java         (abstract — extends Support)
  ParoliStrategy.java                    (abstract — extends Support)
  DAlembertStrategy.java                 (abstract — extends Support)
  FibonacciStrategy.java                 (abstract — extends Support)
  ClassicMartingaleCautious.java         (final — @StrategyImpl(MARTINGALE_CLASSIC_CAUTIOUS))
  ClassicMartingaleAggressive.java       (final — @StrategyImpl(MARTINGALE_CLASSIC_AGGRESSIVE))
  ParoliCautious.java                    (final — @StrategyImpl(PAROLI_CAUTIOUS))
  ParoliAggressive.java                  (final — @StrategyImpl(PAROLI_AGGRESSIVE))
  DAlembertCautious.java                 (final — @StrategyImpl(DALEMBERT_CAUTIOUS))
  DAlembertAggressive.java               (final — @StrategyImpl(DALEMBERT_AGGRESSIVE))
  FibonacciCautious.java                 (final — @StrategyImpl(FIBONACCI_CAUTIOUS))
  FibonacciAggressive.java               (final — @StrategyImpl(FIBONACCI_AGGRESSIVE))
```

- `MartingaleStrategySupport` (abstract `implements BettingStrategy`):
  - Holds `currentBet` (long), `currentRoundSessionId` (long),
    `numberOfBetsInCurrentSession` (int), `AffinityOptionPicker picker`,
    `RiskProfile profile` (kept for logging / health introspection only).
  - Constructor `MartingaleStrategySupport(RiskProfile)` instantiates
    `new AffinityOptionPicker(profile)`. `currentBet` is set to 0L and
    lazily initialized to `behavior.getMinBet()` on first `decide()` (we
    cannot construct with `minBet` because the strategy has no access to
    `BotBehaviorConfig` at construction time).
  - Implements `decide(BetContext)` once with the full shared algorithm
    (see A6 below). Concrete progressions don't override `decide`.
  - Declares abstract `protected long nextBetAfterWin(long currentBet, BetContext ctx)`,
    `protected long nextBetAfterLoss(long currentBet, BetContext ctx)`, and
    `protected long nextBetAfterNoBet(long currentBet, BetContext ctx)`
    (default: `currentBet` unchanged — only Fibonacci or Paroli might
    override). The progression classes implement only these.
  - Owns `onRoundEnd(RoundResult)` which delegates win/loss/no-bet
    branching to the three abstract methods, applies the clamp/align/reset
    rules, all under `synchronized (this)`.

- `ClassicMartingaleStrategy` / `ParoliStrategy` / `DAlembertStrategy` /
  `FibonacciStrategy` (abstract): each implements the three abstract methods
  for its own progression and adds any extra interpretive state it needs
  (Paroli: `consecutiveWins`; Fibonacci: `int fibIndex`).
  They do NOT carry `@StrategyImpl` (not directly instantiable beans).
  Each declares a `protected` constructor taking `RiskProfile`.

- The eight concrete classes are 4-line thin subclasses:

  ```java
  @Component
  @Scope("prototype")
  @StrategyImpl(StrategyId.MARTINGALE_CLASSIC_CAUTIOUS)
  public final class ClassicMartingaleCautious extends ClassicMartingaleStrategy {
      public ClassicMartingaleCautious() { super(RiskProfile.CAUTIOUS); }
  }
  ```

  No-arg constructor (required by `ApplicationContext.getBean(Class)` in
  prototype scope; matches `RandomBehaviorStrategy` line 55). Spring
  instantiates a fresh bean per `BettingStrategyFactory.create()` call.

### A5. Progression mechanics — exact rules

All four progressions key off `RoundResult.balanceDelta`. The
`onRoundEnd` logic in `MartingaleStrategySupport` is:

```
synchronized (this) {
    long staked = sum(result.betsByOption().values());
    if (staked == 0L) {            // no-bet round
        currentBet = align(clamp(nextBetAfterNoBet(currentBet, ctx-snapshot)));
        return;
    }
    if (result.balanceDelta() > 0L) {
        currentBet = align(clamp(nextBetAfterWin(currentBet, ...)));
    } else {
        // includes balanceDelta < 0 (loss) and == 0 with bets (push). Push is
        // treated as a loss by all four progressions — see A5e.
        currentBet = align(clamp(nextBetAfterLoss(currentBet, ...)));
    }
}
```

`onRoundEnd` does not need `BetContext`; the necessary values
(`minBet`, `maxBet`, `betIncrement`) must be cached on the strategy. **Per
A4 we cannot get these at construction time**, so `MartingaleStrategySupport`
captures them on the first `decide()` call into private fields and uses them
in subsequent `onRoundEnd`. Bot lifetime guarantees `decide` runs before any
`onRoundEnd` (a round must start, the bot must tick, then EndGame arrives).

A defensive guard logs WARN and skips the progression update if
`onRoundEnd` runs before any `decide` populated the cached bounds — the
first cached value will arrive on the next `decide`, so this is recoverable
without crashing.

#### A5a. Classic Martingale

- Win → `currentBet = minBet`.
- Loss → `currentBet = currentBet * 2`. If the doubled value exceeds
  `maxBet` after alignment, reset to `minBet` (capped Martingale — see A5f).
- No-bet → unchanged.

#### A5b. Paroli

- Additional state: `int consecutiveWins`.
- Configurable streak cap `STREAK_CAP = 3` (a `private static final` constant
  on `ParoliStrategy`; not exposed in config — this is a strategy-personality
  knob, not an operator setting).
- Win → `consecutiveWins++`. If `consecutiveWins >= STREAK_CAP`:
  bank (reset `currentBet = minBet`, `consecutiveWins = 0`). Else
  `currentBet = currentBet * 2` (clamp/reset rule applies).
- Loss → `currentBet = minBet`, `consecutiveWins = 0`.
- No-bet → unchanged (streak preserved across skipped rounds — the streak
  is "rounds we won when we bet," not "consecutive rounds total").

#### A5c. D'Alembert

- Win → `currentBet = max(minBet, currentBet - betIncrement)`.
- Loss → `currentBet = currentBet + betIncrement` (then clamp/reset).
- No-bet → unchanged.

#### A5d. Fibonacci

- Additional state: `int fibIndex` (starts at 0).
- Bet amount derivation: `currentBet = minBet * fib(fibIndex)` where
  `fib(0) = 1, fib(1) = 1, fib(2) = 2, fib(3) = 3, fib(4) = 5, ...`.
- `decide()` does NOT re-derive `currentBet` from `fibIndex` — the cached
  `currentBet` field is the source of truth at bet time (matches the other
  three progressions; keeps the shared `decide()` logic uniform). Win/loss
  callbacks recompute it from the new `fibIndex`:
  - Loss → `fibIndex++`; `currentBet = minBet * fib(fibIndex)` then
    clamp/align/reset.
  - Win → `fibIndex = max(0, fibIndex - 2)`;
    `currentBet = minBet * fib(fibIndex)` then clamp/align/reset.
  - No-bet → unchanged.
- `fib` is a small `static long fib(int)` helper inside `FibonacciStrategy`.
  Compute iteratively. Cap at index 64 — beyond that any reasonable
  `minBet * fib(i)` already overflows long; the clamp/reset rule kicks in
  long before we reach it, so `Math.multiplyExact` overflow guard is
  belt-and-braces. On overflow: reset to `minBet` and `fibIndex = 0`,
  logged WARN once.

#### A5e. Push (`balanceDelta == 0` with non-empty `betsByOption`)

This is `winnings == staked` — bot wagered and broke even. Treat as a loss
for all four progressions. Rationale: in Martingale-family logic a "win"
means strict net-positive; a push doesn't earn back the lost ground. Pinned
by a unit test per progression.

#### A5f. Clamp / align / reset rules (locked)

Applied to every progression's output bet **in this order**:

1. **Align to `betIncrement`**: if `minBet=100`, `betIncrement=50`, raw
   target `225` → align to `200` (round down to the nearest
   `minBet + k*betIncrement` step). Formula:
   `aligned = minBet + ((raw - minBet) / betIncrement) * betIncrement`
   (integer truncation toward minBet).
2. **Floor at `minBet`**: if `aligned < minBet`, use `minBet`.
3. **Cap at `maxBet`**: if `aligned > maxBet`, **reset to `minBet`**.
   This is the capped-Martingale rule the user asked us to lock in
   explicitly: when the next step would exceed the configured ceiling, the
   strategy bankrupts its progression and starts over rather than placing a
   bet bigger than the operator allowed. Document with an INFO log at the
   strategy bot level:
   `log.debug("Martingale cap hit: target={}, maxBet={}, resetting to minBet={}", target, maxBet, minBet)`.

For Paroli specifically, "cap hit" also resets `consecutiveWins = 0`. For
Fibonacci, "cap hit" also resets `fibIndex = 0`. Pinned by tests.

### A6. `decide()` flow (shared by all eight strategies)

Implemented once in `MartingaleStrategySupport.decide`. Order matches
`RandomBehaviorStrategy` for consistency:

```
synchronized (this):
    1. sessionId boundary: if ctx.currentRound().getSessionId() != currentRoundSessionId
       → currentRoundSessionId = sid; numberOfBetsInCurrentSession = 0
    2. Cache behavior bounds on first call: minBet/maxBet/betIncrement.
       If currentBet == 0L, initialize currentBet = minBet (lazy init).
    3. If numberOfBetsInCurrentSession >= behavior.maxBetsPerRound → return empty
    4. Skip-tick gate: if rng.nextInt(100) < behavior.betSkipPercentage → return empty
    5. numberOfBetsInCurrentSession++
end synchronized
    6. amount = currentBet (already aligned/clamped from previous onRoundEnd)
    7. option = picker.pick(game.getEffectiveOptionAffinities(), rng)
    8. return Optional.of(new BetDecision(option, amount))
```

The skip-gate runs **before** the option pick (mirrors `RandomBehaviorStrategy`
line 82 ordering — keeps RNG consumption deterministic in tests). Within
each `decide()` call, the picker consumes one or more RNG draws (cumulative
weight sampling typically draws once via `nextInt(totalWeight)`).

### A7. `BotMemory` is read-only for these strategies (no new memory plumbing)

Martingale-family decisions key entirely on the latest `RoundResult` handed
to `onRoundEnd` plus per-strategy interpretive state. They do not read
`memory.snapshotLastResults()` or `globalRecentWins`. No additions to
`BotMemory` required. `RoundResult.balanceDelta` is sufficient.

### A8. Logging

Match the existing pattern from `RandomBehaviorStrategy` (DEBUG per
`decide`/`onRoundEnd`, no new INFO/WARN/ERROR lines for routine operation):

- DEBUG per `decide()`: `"{}.decide: bet option={}, amount={} (currentBet={}, profile={})"`.
- DEBUG per `onRoundEnd()`: `"{}.onRoundEnd: delta={}, prevBet={}, nextBet={}"`.
- DEBUG when cap hits / progression resets (A5f).
- WARN once if affinities are all ≤ 0 (A3 fallback path).
- WARN once if `onRoundEnd` runs before any `decide` cached behavior
  bounds (A5).
- WARN once if Fibonacci overflow guard fires (A5d).
- No INFO lines. The existing "Bot {userName}: assigned strategy {id}"
  INFO from `BotGroupBehaviorService` already covers strategy assignment.

MDC is inherited from the bot's caller threads — same as
`RandomBehaviorStrategy`. No new MDC plumbing.

### A9. No frontend / API changes beyond the enum

- `GET /api/v1/strategy/` automatically lists the 8 new ids.
- `BotGroup.strategyMix` already accepts arbitrary `StrategyId` values; no
  schema migration.
- No `BotHealthDTO` changes (already exposes `strategyId`).
- Operators with existing groups configured to `[(RANDOM, 1.0)]` are
  unaffected.

## Plan

Four phases. Each phase compiles independently. Phase 1 ships the
infrastructure with no live strategies; Phases 2-3 add strategies in pairs;
Phase 4 verifies end-to-end behaviour against a deterministic stream.

### Phase 1 — Enum entries, RiskProfile, AffinityOptionPicker, shared base

**Goal**: ship the supporting infrastructure with no live strategies yet.
`BettingStrategyFactory.registeredIds()` size still 1 (`RANDOM`) at the end
of this phase — abstract base classes don't get instantiated.

**Files to create:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/RiskProfile.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/AffinityOptionPicker.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleStrategySupport.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/strategy/martingale/AffinityOptionPickerTest.java`

**Files to edit:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/StrategyId.java`
  - Append the 8 new enum values per A1 (verbatim strings).

**Step-by-step:**
1. Add 8 enum entries to `StrategyId`.
2. Create `RiskProfile { CAUTIOUS, AGGRESSIVE }`.
3. Implement `AffinityOptionPicker(RiskProfile)` with `pick(Map<Integer,Integer>, Random)`
   per A3.
4. Implement abstract `MartingaleStrategySupport` per A4 / A5 / A6. Three
   abstract methods (`nextBetAfter{Win,Loss,NoBet}`) and a default
   no-op `nextBetAfterNoBet` returning the input unchanged.
5. Write `AffinityOptionPickerTest`:
   - CAUTIOUS distribution test: affinities `{0:5, 1:1}`, seeded RNG,
     10 000 picks, assert option 0 appears with frequency in `[0.80, 0.86]`
     (true rate 5/6 ≈ 0.833, ±3%).
   - AGGRESSIVE distribution test on same affinities: option 1 appears in
     `[0.80, 0.86]`.
   - Equal affinities `{0:1, 1:1, 2:1}`: both profiles produce ~uniform
     `[0.30, 0.37]` per option.
   - All-zero affinities → uniform fallback (logs WARN).
   - Single-option map → always returns that option.
6. `BettingStrategyFactoryTest` already passes — Phase 1 does not register
   any new beans.

**Verifiable, this phase only:**
- `mvn test` green.
- `GET /api/v1/strategy/` returns 9 entries (RANDOM + 8 new ids) — the
  controller is enum-driven so the new ids appear immediately, but
  `POST /api/v1/bot-group` with `strategyMix: [{strategyId:"PAROLI_CAUTIOUS",weight:1}]`
  followed by `/start` would fail at `BettingStrategyFactory.create()` with
  `IllegalArgumentException: No BettingStrategy registered for PAROLI_CAUTIOUS`.
  This is intentional and pinned: the listing exposes future-shippable ids
  but the runtime guards against using them before they're wired.
  Document this in the Phase 1 PR description.
- `BettingStrategyFactory.registeredIds()` still contains only `RANDOM`.

### Phase 2 — Classic Martingale + Paroli

**Goal**: ship the two doublers. Both progressions share the "doubling-on-X
plus reset-to-minBet" pattern so they're closest in code.

**Files to create:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/ClassicMartingaleStrategy.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/ClassicMartingaleCautious.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/ClassicMartingaleAggressive.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/ParoliStrategy.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/ParoliCautious.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/ParoliAggressive.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/strategy/martingale/ClassicMartingaleStrategyTest.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/strategy/martingale/ParoliStrategyTest.java`

**Step-by-step:**
1. Implement `ClassicMartingaleStrategy` per A5a: `nextBetAfterWin → minBet`,
   `nextBetAfterLoss → currentBet * 2`. Use `Math.multiplyExact` to detect
   overflow; on overflow trigger the cap-reset path (A5f).
2. Implement `ClassicMartingaleCautious`/`Aggressive` as 4-line thin
   subclasses per A4.
3. Implement `ParoliStrategy` per A5b. Extra field `int consecutiveWins`,
   constant `STREAK_CAP = 3`. Override `nextBetAfterWin` and
   `nextBetAfterLoss` to mutate `consecutiveWins` as well as `currentBet`.
   These mutations happen inside the synchronized block in the base class
   — concrete classes don't need their own synchronization.
4. Implement `ParoliCautious`/`Aggressive` thin subclasses.
5. Write `ClassicMartingaleStrategyTest`:
   - Deterministic loss sequence: feed 5 losses, assert `currentBet`
     evolution `minBet → 2x → 4x → 8x → 16x → 32x` (or cap reset earlier
     if `32x > maxBet`).
   - Win after losses: feed L,L,W → assert reset to `minBet`.
   - No-bet round: feed `RoundResult` with empty `betsByOption` → assert
     `currentBet` unchanged.
   - Push (`balanceDelta == 0` with non-empty bets) treated as loss (A5e):
     feed bet of 100 with payout 100 → assert `currentBet` doubled.
   - Cap reset: configure `minBet=100, maxBet=300, betIncrement=100`, feed
     L,L → second loss would target 400 > 300 → assert reset to 100.
   - Alignment: configure `minBet=100, betIncrement=70`, feed L → raw
     target 200, aligned target 170 (`100 + 1*70`); assert `currentBet == 170`.
6. Write `ParoliStrategyTest`:
   - Win streak below cap: feed W,W → assert `currentBet = 4x minBet`,
     `consecutiveWins = 2`.
   - Win streak hits cap (`STREAK_CAP = 3`): feed W,W,W → assert reset to
     `minBet`, `consecutiveWins = 0`.
   - Loss mid-streak: feed W,W,L → assert reset to `minBet`,
     `consecutiveWins = 0`.
   - No-bet preserves streak: feed W, no-bet, W → assert
     `currentBet = 4x minBet`, `consecutiveWins = 2`.
   - Cap reset clears streak: tiny `maxBet`, feed W,W → second win would
     exceed cap → reset to `minBet`, `consecutiveWins = 0`.

**Verifiable, this phase only:**
- `mvn test` green.
- `BettingStrategyFactory.registeredIds()` contains 5 entries: RANDOM +
  MARTINGALE_CLASSIC_{CAUTIOUS,AGGRESSIVE} + PAROLI_{CAUTIOUS,AGGRESSIVE}.
  Add an assertion to `BettingStrategyFactoryTest` (or a new integration
  test) that asserts this exact set after Phase 2.
- Spring boot starts cleanly: log line `BettingStrategyFactory initialized:
  registered 5 strategies — [RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, ...]`
  (existing INFO at `BettingStrategyFactory.java:73`).

### Phase 3 — D'Alembert + Fibonacci

**Goal**: complete the four progressions.

**Files to create:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/DAlembertStrategy.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/DAlembertCautious.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/DAlembertAggressive.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/FibonacciStrategy.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/FibonacciCautious.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/FibonacciAggressive.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/strategy/martingale/DAlembertStrategyTest.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/strategy/martingale/FibonacciStrategyTest.java`

**Step-by-step:**
1. Implement `DAlembertStrategy` per A5c: `+betIncrement` on loss,
   `-betIncrement` on win floored at `minBet`.
2. Implement `DAlembertCautious`/`Aggressive` thin subclasses.
3. Implement `FibonacciStrategy` per A5d: `int fibIndex` field,
   `static long fib(int)` iterative helper with overflow guard at index 64,
   `currentBet = minBet * fib(fibIndex)` recomputed on win/loss.
4. Implement `FibonacciCautious`/`Aggressive` thin subclasses.
5. Write `DAlembertStrategyTest`:
   - Linear loss progression: `minBet=100, betIncrement=50`, feed L,L,L
     → assert `currentBet` sequence `150, 200, 250`.
   - Linear win progression: feed L,L,L,W,W → assert `100, 150, 200, 250,
     200, 150`.
   - Floor at `minBet`: feed W,W,W from initial `currentBet = minBet` →
     stays at `minBet`.
   - Cap reset: `minBet=100, maxBet=300, betIncrement=100`, feed L,L,L →
     third loss targets 400 > 300 → reset to 100.
6. Write `FibonacciStrategyTest`:
   - Loss progression: `minBet=10`, feed L,L,L,L,L → assert `currentBet`
     sequence `10*1=10, 10*1=10, 10*2=20, 10*3=30, 10*5=50` (fib
     indices 0,1,2,3,4 driving each post-loss decide).
     Wait — re-derive: initial `fibIndex=0`, `currentBet=minBet`. After
     first L: `fibIndex=1`, `currentBet=10*fib(1)=10`. After second L:
     `fibIndex=2`, `currentBet=10*2=20`. After third L: `fibIndex=3`,
     `currentBet=10*3=30`. After fourth L: `fibIndex=4`, `currentBet=10*5=50`.
     Pin this exact sequence in the test.
   - Win retreats two indices: feed L,L,L,L (fibIndex=4),W → assert
     `fibIndex=2`, `currentBet=10*2=20`.
   - Win floor at index 0: feed L,W,W → after L: index=1, after W:
     `max(0, 1-2)=0`, index=0; after W: still index=0.
   - Cap reset clears index: tight `maxBet`, feed L until cap hits → assert
     `fibIndex=0`, `currentBet=minBet`.
   - Overflow guard: synthetic case — set `fibIndex` to 63 via a
     test-only setter (or run 63 losses; pick the simpler) — assert that
     hitting overflow at `fib(64)` resets cleanly.

**Verifiable, this phase only:**
- `mvn test` green.
- `BettingStrategyFactory.registeredIds()` contains all 9 entries.
  Update / extend the factory test to assert this set.
- Spring boot logs `BettingStrategyFactory initialized: registered 9
  strategies` at start.

### Phase 4 — End-to-end deterministic stream + factory registration sanity

**Goal**: catch wiring regressions and confirm the strategies behave end-to-end
when driven by a synthetic `RoundResult` stream that exercises all four
progressions with a mix of win/loss/no-bet/push outcomes.

**Files to create:**
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleEndToEndTest.java`

**Step-by-step:**
1. Build a small deterministic outcome script — e.g.
   `[LOSS, LOSS, WIN, NOBET, LOSS, PUSH, WIN, WIN, WIN]`.
2. For each of the 8 concrete strategies:
   - Construct a `BotMemory` with a 6-option `Game` and varied affinities.
   - Drive the strategy through the script: for each outcome,
     - Call `decide()` with a seeded RNG → record the bet.
     - Synthesize a `RoundResult` matching the script entry (use the
       recorded bet to compute `betsByOption`, set `balanceDelta`
       appropriately).
     - Call `onRoundEnd(result)`.
     - Advance `BotMemory` via `mem.completeRound(sid, ..., payout)` and
       `mem.beginRound(sid+1, balance)`.
   - Assert the final `currentBet` matches a hand-computed expected value
     for that strategy.
3. Assertion table is the test's contract — list expected end-of-script
   `currentBet` for each strategy.
4. Add a factory-registration test that asserts
   `BettingStrategyFactory.registeredIds()` contains exactly:
   `{RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE,
     PAROLI_CAUTIOUS, PAROLI_AGGRESSIVE, DALEMBERT_CAUTIOUS,
     DALEMBERT_AGGRESSIVE, FIBONACCI_CAUTIOUS, FIBONACCI_AGGRESSIVE}`.

**Verifiable, this phase only:**
- `mvn test` green.
- `MartingaleEndToEndTest` covers all 8 strategies.
- `BettingStrategyFactoryTest` (or a new boot-context test) asserts the
  exact 9-id registered set.

## Implementation Notes / Concerns

1. **`currentBet` is lazy-initialized on first `decide()`**, not in the
   constructor — strategies can't see `BotBehaviorConfig` before `decide`
   is called. This means a strategy that receives `onRoundEnd` before any
   `decide` (theoretically impossible but defensively guarded) has no
   cached `minBet` / `maxBet` / `betIncrement`. The base class **must**
   log WARN and skip the progression update rather than NPE. Dev: write
   one test that calls `onRoundEnd` directly without prior `decide` and
   asserts no crash + WARN log.

2. **RNG ordering for tests**: `decide()` consumes RNG only at the
   skip-tick gate and inside the picker. The bet *amount* in
   Martingale-family strategies is `currentBet` (no RNG draw). This is a
   simpler ordering than `RandomBehaviorStrategy` but pinning the order in
   tests is still important so refactors don't silently shift draws.

3. **Overflow on `currentBet * 2`**: Classic Martingale doubling can
   overflow long around `1.15 * 10^19`. Use `Math.multiplyExact` in the
   doubling path and trigger the cap-reset on `ArithmeticException`.
   Document in the strategy class. Tests: feed losses with
   `minBet = Long.MAX_VALUE / 4` to force overflow.

4. **Cap-reset is the only "memory" of failure**: a bot whose Classic
   Martingale is mid-progression at restart starts fresh (`currentBet =
   minBet`, no streak). Matches the BETTING_STRATEGIES Architecture
   Decision 10 (strategy state is in-memory only). Explicitly out of
   scope to persist across restarts.

5. **AffinityOptionPicker uniform-fallback path**: a `Game` with all
   `affinity = 0` is misconfigured but technically valid per
   `Game.getEffectiveOptionAffinities()` (it returns the map as-is when
   non-empty). The picker logs WARN once-per-bot-lifecycle (use a
   `volatile boolean warned` field on the picker to avoid log spam — every
   tick would log otherwise) and falls back to uniform.

6. **`betSkipPercentage` defaults to 0** (not wired through `BotGroup`
   yet, per BETTING_STRATEGIES Implementation Note 2). Martingale
   strategies should still consult it (so when the field finally is wired
   the strategies pick it up for free). Today this means Martingale bots
   bet every tick up to `maxBetsPerRound` — matches `RandomBehaviorStrategy`
   behavior on the same bot group.

7. **Frontend rendering**: the user's prompt pins "plain text — no rich
   formatting; express meaning with words." The description strings
   already avoid markdown, code spans, and bullet points. Reviewer: spot-check
   the rendered tooltip in the UI is parseable as one continuous sentence.

8. **Bean-discovery cost**: 8 new prototype-scoped beans add negligible
   startup cost. `BettingStrategyFactory.init()` iterates a 9-element list
   at startup. Memory cost per bot is ~80 bytes (currentBet + index +
   counter + picker ref). At 100k bots ≈ 8 MB — fine.

9. **No factory signature change**: `BettingStrategyFactory.create(StrategyId)`
   already returns a prototype-scoped bean for any registered id. The 8
   new beans drop in without touching the factory's discovery, lookup, or
   test code (beyond the assertion on `registeredIds()` set membership).

10. **PR diff scope**: each phase's PR should touch only its own files.
    Phase 1 explicitly leaves `StrategyId` with new entries that have no
    backing bean — this is intentional (the registry only checks duplicates,
    not coverage). The listing endpoint exposing un-implemented ids for
    one PR cycle is acceptable; the runtime guard at create-time prevents
    actual misuse.

## Open Items

- **`betSkipPercentage` wire-through**: a separate ticket from the
  BETTING_STRATEGIES backlog. Not in scope here, but worth flagging again:
  Martingale strategies are most interesting when bots actually skip
  rounds — without it they bet every tick.
- **Affinity values in production data**: today most `Game` docs were
  populated by the read-side fallback (all affinities = 1). Cautious /
  Aggressive picking on a flat-prior map degenerates to uniform — visually
  indistinguishable from `RandomBehaviorStrategy`. To exercise the
  Cautious/Aggressive split in staging, the operator must PATCH a game's
  `optionAffinities` to a non-uniform shape, e.g.
  `{0: 5, 1: 3, 2: 1, 3: 1, 4: 3, 5: 5}`. Document this in the Phase 2 PR
  description.
- **Streak cap configurability for Paroli**: locked at `STREAK_CAP = 3`
  (Architecture Decision A5b). If operators later want per-group
  configuration, that requires `BotGroup.strategyConfig` (open field) —
  out of scope.

## Verification

The Releaser runs these on staging after deploying the new bot-manager
image. No Mongo migration is required; the only change on the server is
the new bean set.

### A. App boot + strategy listing

```bash
# 1. App health
curl -fsS https://staging.bot-manager/actuator/health
# expect: HTTP 200, body contains "status":"UP"

# 2. Strategy listing exposes all 9 ids
curl -fsS https://staging.bot-manager/api/v1/strategy/ | jq 'length'
# expect: 9

curl -fsS https://staging.bot-manager/api/v1/strategy/ | jq -r '.[].id' | sort
# expect (exact set, sorted):
# DALEMBERT_AGGRESSIVE
# DALEMBERT_CAUTIOUS
# FIBONACCI_AGGRESSIVE
# FIBONACCI_CAUTIOUS
# MARTINGALE_CLASSIC_AGGRESSIVE
# MARTINGALE_CLASSIC_CAUTIOUS
# PAROLI_AGGRESSIVE
# PAROLI_CAUTIOUS
# RANDOM

# 3. Each new entry has a non-empty displayName and description
curl -fsS https://staging.bot-manager/api/v1/strategy/ \
  | jq '.[] | select(.id != "RANDOM") | {id, displayName, description} | select(.displayName == "" or .description == "")'
# expect: empty output (no entries with blank text)

# 4. Boot log line — factory registered 9 strategies
# Grep the startup log for the BettingStrategyFactory init line:
# expect a log line matching: BettingStrategyFactory initialized: registered 9 strategies
```

### B. End-to-end smoke — create a group using a Martingale strategy

```bash
# 1. Pick (or create) a Game with non-flat affinities so Cautious/Aggressive
#    actually differ from uniform.
GAME_ID=<staging-game-id>
curl -fsS -X PATCH https://staging.bot-manager/api/v1/game \
  -H 'Content-Type: application/json' \
  -d "{\"id\":\"$GAME_ID\",\"optionAffinities\":{\"0\":5,\"1\":3,\"2\":1,\"3\":1,\"4\":3,\"5\":5}}"
# expect: HTTP 200

# 2. Create a small bot group whose strategyMix uses the new strategies.
curl -fsS -X POST https://staging.bot-manager/api/v1/bot-group \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "martingale-smoke",
        "environmentId": "<env>",
        "gameId": "'"$GAME_ID"'",
        "namePrefix": "martsmoke",
        "botCount": 8,
        "minBet": 100, "maxBet": 1600, "betIncrement": 100,
        "minBetsPerRound": 1, "maxBetsPerRound": 1,
        "strategyMix": [
          {"strategyId":"MARTINGALE_CLASSIC_CAUTIOUS","weight":1},
          {"strategyId":"MARTINGALE_CLASSIC_AGGRESSIVE","weight":1},
          {"strategyId":"PAROLI_CAUTIOUS","weight":1},
          {"strategyId":"PAROLI_AGGRESSIVE","weight":1},
          {"strategyId":"DALEMBERT_CAUTIOUS","weight":1},
          {"strategyId":"DALEMBERT_AGGRESSIVE","weight":1},
          {"strategyId":"FIBONACCI_CAUTIOUS","weight":1},
          {"strategyId":"FIBONACCI_AGGRESSIVE","weight":1}
        ]
      }'
GROUP_ID=<response.id>
# expect: HTTP 200; response contains all 8 strategy entries in strategyMix.

# 3. Start the group.
curl -fsS -X POST https://staging.bot-manager/api/v1/bot-group/$GROUP_ID/start
sleep 10

# 4. Health DTO shows each bot's strategy assignment.
curl -fsS https://staging.bot-manager/api/v1/bot-group/$GROUP_ID/health \
  | jq '.bots | map(.strategyId) | group_by(.) | map({key: .[0], count: length}) | sort_by(.key)'
# expect: 8 entries, one bot per strategy id (largest-remainder apportionment
#         of equal weights over 8 bots → exactly 1 bot per strategy).

# 5. Bots are placing bets — Prometheus counter increases.
curl -fsS https://staging.bot-manager/actuator/prometheus \
  | grep -E '^bot_bets_placed_total\{[^}]*bot_group_id="'$GROUP_ID'"' \
  | awk '{print $2}' \
  | paste -sd+ - | bc
sleep 60
curl -fsS https://staging.bot-manager/actuator/prometheus \
  | grep -E '^bot_bets_placed_total\{[^}]*bot_group_id="'$GROUP_ID'"' \
  | awk '{print $2}' \
  | paste -sd+ - | bc
# expect: second total > first total (any non-zero delta confirms bots are betting).
```

### C. Logs — Martingale strategies emit DEBUG decisions

```bash
# In the application log for the group above, after ~30 seconds:
# Expect log lines matching:
#   ^.*ClassicMartingaleCautious.decide: bet option=.*amount=.*$
#   ^.*ParoliAggressive.decide: bet option=.*amount=.*$
#   ^.*onRoundEnd: delta=.*prevBet=.*nextBet=.*$
# (Log level com.vingame.bot must be at DEBUG to see these. Production
# runs at DEBUG today, per CLAUDE.md.)

# Sanity: no ERROR log lines from the strategy package.
# Grep "com.vingame.bot.domain.bot.strategy" + level=ERROR in the last 5 minutes.
# expect: zero matches.
```

### D. Stop and tear down

```bash
curl -fsS -X POST https://staging.bot-manager/api/v1/bot-group/$GROUP_ID/stop
# expect: HTTP 200

curl -fsS -X DELETE https://staging.bot-manager/api/v1/bot-group/$GROUP_ID
# expect: HTTP 200
```

### E. Rollback

Pure code rollback. Reverting the deployment removes the 8 enum entries
and the new beans; existing groups configured with the new ids would fail
at `start` time with `IllegalArgumentException: No BettingStrategy
registered for ...`. Mitigation: before rollback, PATCH any affected
groups back to `strategyMix: [{"strategyId":"RANDOM","weight":1}]`. No
Mongo data migration involved.
