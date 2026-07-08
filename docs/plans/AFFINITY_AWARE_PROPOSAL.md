# Affinity-Aware Proposal — Weighted Per-Bot Option Choice (Strategy)

## Goal

Make a betting bot's **option choice** biased by the game's affinity **weights**,
instead of a flat uniform pick over the option ids. Today `RandomBehaviorStrategy`
picks the option **uniformly** over `Game.getEffectiveOptionAffinities().keySet()`
and throws the weight *values* away
(`RandomBehaviorStrategy.java:104-105`, javadoc `:24`). This is the *raw-material*
half of the bet coordinator (WP#4, `BET_COORDINATION.md`): the coordinator steers
by **trim/reject only** and can enforce the per-option/aggregate *ceiling* but not
the *floor* (`BET_COORDINATION.md` AD-7). If the fleet collectively *under-proposes*
a high-affinity option, that option stays under-filled and the realized
distribution is pushed *toward* the skewed target but can never fully reach it.
WP#7 fixes the supply side: bias each bot's *own* option choice by the affinity
weights so bots propose more on high-affinity options, giving the coordinator
enough raw proposals on the right options to actually hit a skewed target. This is
**feed-free** — it reads only the game's own `optionAffinities`, never crowd data —
and is opt-in, degrading to today's exact uniform pick when off or when weights are
equal. This is work package #7 ("Affinity-aware proposal") of
`docs/plans/BETTING_INTELLIGENCE_ROADMAP.md` (`:109-113`).

## Findings — Current State

### Option-selection sites (every strategy that produces `optionId`)

Mapped by reading each strategy. Only the **option-picking** sites need weighting;
amount-only sites are out of scope.

| Site | Picks option? | How today | In scope? |
|---|---|---|---|
| `RandomBehaviorStrategy.decide` (`.../strategy/RandomBehaviorStrategy.java:104-105`) | **Yes** | `options.get(rng.nextInt(options.size()))` — **uniform over `keySet()`, weights ignored** | **YES — the target of this WP** |
| `MartingaleStrategySupport.decide` (`.../strategy/martingale/MartingaleStrategySupport.java:177`) | Yes | `picker.pick(affinities, rng)` via `AffinityOptionPicker` — **already affinity-weighted** (CAUTIOUS = weight by `max(0,a)`, AGGRESSIVE = reflected) | No — already weighted (see below) |
| 8 concrete Martingale subclasses (`ClassicMartingaleStrategy`, `ParoliStrategy`, `DAlembertStrategy`, `FibonacciStrategy` × cautious/aggressive) | Yes (inherited) | Inherit `MartingaleStrategySupport.decide` → same `picker.pick` | No — already weighted |
| `FixedBetStrategy.chooseBet` (`.../strategy/slot/FixedBetStrategy.java:27`) | **No** | Returns an *amount* (`allowedBetValues().get(0)`); slots have no option id | No — amount-only, slot |
| `RandomBetStrategy.chooseBet` (`.../strategy/slot/RandomBetStrategy.java:27`) | **No** | Returns an *amount* (`allowed.get(rng.nextInt(...))`); slots have no option id | No — amount-only, slot |

**Key finding: an affinity-weighted picker already exists in the codebase.**
`AffinityOptionPicker` (`.../strategy/martingale/AffinityOptionPicker.java`) is a
scope-agnostic, Spring-free, cumulative-weight + uniform-draw picker
(`:88-122`), unit-tested (`AffinityOptionPickerTest`) with a proven
"equal affinities degenerate to uniform under both profiles" pin and a seeded
determinism pin. The `RandomBehaviorStrategy` uniform pick is the **one remaining
option-selection site that ignores the weights**. The `chooseBet` family the brief
asked about (`FixedBetStrategy`/`RandomBetStrategy`) are **slot amount pickers with
no option id** — confirmed out of scope.

### Affinity source + shape (usable directly as a categorical distribution)

- `Game.getEffectiveOptionAffinities()` (`.../domain/game/model/Game.java:223`)
  returns `Map<Integer,Integer>` (option id → weight), insertion-ordered
  (`LinkedHashMap`). It resolves from `optionAffinities`, else synthesizes uniform
  `{o:1}` from `bettingOptions`, else uniform `0..numberOfOptions-1`
  (`:223-239`) — so the *effective* map is never null/empty for an eligible game.
- The weights are **directly usable as a categorical distribution**: `w(o)`,
  `W = Σ w(o)`, `P(o) = w(o)/W`. `AffinityOptionPicker` CAUTIOUS already does
  exactly this (`computeWeights` CAUTIOUS branch, `:127-132`; sample `:98-122`).
- **TaiXiu N=2 defaults `{1:1, 2:1}`** (`defaultTaiXiuOptionAffinities`,
  `Game.java:186-191`) — **equal weights → weighted pick == uniform pick**, so
  there is **no behavior change** on a default TaiXiu game. This is the safe-default
  property: the feature is only observable on a game with **skewed** affinities.

### The RNG-consumption regression pin (the load-bearing constraint)

- `RandomBehaviorStrategy` consumes RNG in a fixed order: **skip-check
  `nextInt(100)` → bet-amount `nextInt(maxSteps+1)` → option
  `nextInt(options.size())`** (`:86,98,105`).
- `RandomBehaviorStrategyTest$Equivalence` (`.../strategy/RandomBehaviorStrategyTest.java:64-143`)
  pins this **byte-for-byte** against a `legacyReplay` that draws the option as
  `options.get(rng.nextInt(options.size()))` (`:92`) — a **single `nextInt(n)`
  draw** where `n = options.size()`. It runs with **equal weights**
  (`gameWithOptions` sets `affinities.put(i, 1)`, `:44-48`).
- **The invariant:** any weighted pick that (a) draws a *different number* of rng
  values or (b) draws `nextInt` with a *different bound* than `n` on the equal-weight
  path will break this test. `AffinityOptionPicker` on **equal weights** draws
  `rng.nextInt((int) totalWeight)` where `totalWeight = Σ 1 = n` (`:98-109`) — i.e.
  **`nextInt(n)`, one draw, same bound** as the uniform path. So the equal-weight
  path is already RNG-compatible with the pin. The design MUST preserve this: on
  off/equal-weight, consume exactly one `nextInt(n)` (see AD-2/AD-3).

### The opt-in seam (how a flag reaches a no-arg strategy)

- Strategies are **no-arg, prototype-scoped** beans built by
  `BettingStrategyFactory.create(id)` via `context.getBean(clazz)`
  (`.../strategy/BettingStrategyFactory.java:85-93`) — they cannot take a
  constructor flag. Per-tick config already flows through the immutable
  **`BetContext`** record (`.../strategy/BetContext.java:51-58`), which the bot
  builds fresh every tick in `buildBetContext()`
  (`.../bot/core/BettingMiniGameBot.java:579-589`). JACKPOT_SCALE_AND_RAMP added
  `effectiveMaxBetsPerRound` to `BetContext` exactly this way (`BetContext.java:42-49`,
  `effectiveMaxBetsPerRound(behavior)` `:598-605`) — the established precedent for
  "a per-round config value the strategy consults." The affinity toggle rides the
  same seam: a new boolean field on `BetContext`.

### Composition seams (all must hold — stated in ADs)

- **With the coordinator (WP#4):** the affinity-weighted pick runs **inside
  `decide()`**, which is called by `decideBet()`
  (`BettingMiniGameBot.java:625-626`) **before** `applyCoordination`
  (`:813` calls `decideBet`, then coordination gates the result — `BET_COORDINATION`
  AD-2). Weighting only changes *which option* a proposal names; the coordinator
  still trims/rejects against its budget. It is exactly the "over-propose on the
  right options" raw material AD-7 calls for. **No coupling** — neither knows about
  the other.
- **With the TaiXiu single-entry lock:** `TaiXiuGameBot.decideBet`
  (`.../bot/core/TaiXiuGameBot.java:128-145`) runs `super.decideBet(ctx)` (which
  runs the weighted `decide`), then remaps the chosen entry to the round's
  `lockedEntry` if one exists. So on the *first* bet of a round the weighted pick
  chooses which of Tài/Xỉu to lock; subsequent ticks are remapped to that entry.
  Weighting composes with the lock (it biases the initial choice; the lock still
  constrains to one entry/round). On default TaiXiu weights `{1:1,2:1}` the pick is
  uniform anyway, so no change unless an operator skews the TaiXiu affinities.
- **With jackpot-scale / ramp (shipped):** orthogonal. Jackpot-scale shapes *how
  many* bets (`effectiveMaxBetsPerRound`, `BettingMiniGameBot.java:598-605`); ramp
  shapes *when* within the window (`rampAccepts`, per JACKPOT_SCALE_AND_RAMP AD-R1);
  affinity shapes *which option*. Different axes, no shared state, no interaction.

### Config-placement precedent

- The codebase already splits **game-intrinsic → Game** vs **behavior-preference →
  BotGroup** (JACKPOT_SCALE_AND_RAMP AD-R4 states this explicitly:
  `optionAffinities` on Game, `strategyMix`/`betSkipPercentage`/`maxBetsPerRound`
  on BotGroup). The affinity **weights** already live on the Game; the **decision to
  bias by them** is a betting-behavior preference of *this fleet of bots* — so it
  belongs on the **BotGroup**, mirroring `rampEnabled` (`BotGroup`, per
  JACKPOT_SCALE_AND_RAMP Phase R1) and `coordinationEnabled` (`BotGroup`, per
  BET_COORDINATION AD-1). `BotGroup`/`BotGroupDTO`/hand-written `BotGroupMapper` +
  `BotBehaviorConfig` (built in `createSingleBot`) are the write surface — the same
  path `betSkipPercentage`/`rampEnabled` already use.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Weighted-pick algorithm | **ready** | `AffinityOptionPicker` CAUTIOUS already implements cumulative-weight pick; extract a scope-agnostic helper (AD-1). |
| Affinity source | **ready** | `Game.getEffectiveOptionAffinities()` reused as-is; weights usable directly as a categorical distribution. |
| RNG-order preservation | **ready (delicate)** | Equal-weight path draws one `nextInt(n)`, matching the uniform pin (AD-3). Off path is byte-for-byte today. |
| Opt-in flag → strategy | **ready** | New boolean on `BetContext`, mirroring `effectiveMaxBetsPerRound` (AD-4). |
| Config write surface (1 field) | **ready** | `affinityWeightedProposal` on BotGroup/DTO/Mapper/BotBehaviorConfig (Phase 2). |
| Compose with coordinator | **ready** | Runs in `decide()` before `applyCoordination`; no coupling (AD-6). |
| Compose with TaiXiu lock | **ready** | Weighted pick chooses initial entry; lock still constrains to one/round (AD-6). |
| Compose with jackpot/ramp | **ready** | Orthogonal axis (which option vs how many / when); no interaction (AD-6). |
| Game-type gating | **ready** | `BETTING_MINI`/`TAI_XIU` only; slots excluded (AD-7, mirrors BET_COORDINATION AD-10). |
| Martingale family | **ready (no change)** | Already affinity-weighted via `AffinityOptionPicker`; explicitly untouched (AD-5). |
| Observability | **ready (minimal)** | Existing 5s strategy-decision aggregate already shows the option histogram; no new surface (AD-8). |
| Crowd-aware tier (#6) | **out of scope** | Feed-free WP; no `bs`/crowd logic (Open Items). |

## Architecture Decisions

**AD-1 — Weighted pick is a scope-agnostic pure helper, reusing the proven
`AffinityOptionPicker` math.** The core is a small, Spring-free, deterministic
weighted-categorical picker: given `(Map<Integer,Integer> weights, Random rng)`,
build a deterministic insertion-order key view, cumulative-sum the (clamped-≥0)
weights, draw `rng.nextInt((int) totalWeight)`, and walk the cumulative window —
**exactly** `AffinityOptionPicker`'s CAUTIOUS path (`AffinityOptionPicker.java:88-122`).
Two implementation options, **Dev picks one (Open Decision D1)**:
- **(Recommended) Reuse `AffinityOptionPicker` directly** with a new *neutral*
  profile (or the existing CAUTIOUS profile with a straight `max(0,w)` weighting),
  invoked from `RandomBehaviorStrategy` only when the toggle is on. `CAUTIOUS`'s
  `max(0, a(o))` weighting IS "weight directly by affinity" — no `RiskProfile`
  reflection — so `new AffinityOptionPicker(RiskProfile.CAUTIOUS)` already produces
  the plain affinity-proportional pick. Cost: `RandomBehaviorStrategy` would import
  from the `martingale` package (mild layering smell) OR the picker moves up to
  `.../strategy/`.
- **(Alternative) Extract** the cumulative-weight core into a shared
  `WeightedOptionPicker` in `.../strategy/` (no `RiskProfile`), have both
  `AffinityOptionPicker` (as a thin profile-transform wrapper) and
  `RandomBehaviorStrategy` depend on it. Cleaner layering; a small refactor of the
  martingale picker (kept behavior-identical, its tests must still pass).

Either way the helper is **scope-agnostic and unit-testable**, mirroring how
BET_COORDINATION/JACKPOT kept cores Spring-free (BET_COORDINATION AD-8). The helper
holds **no `Random`** — the RNG is threaded in per call from `BetContext.rng()`, per
the established plumbing (`AffinityOptionPicker.java:53-55`).

**AD-2 — The pick is deterministic given the injected `rng`.** Exactly like the
uniform path and `AffinityOptionPicker`, the weighted pick draws from the strategy's
per-tick `ctx.rng()` and holds no RNG of its own — so a seeded run is reproducible
and testable, and the strategy stays a pure function of `(ctx, rng-state)`.

**AD-3 — Off = today, byte-for-byte, and equal-weights = uniform with identical RNG
consumption (the regression-sensitive invariant).** `RandomBehaviorStrategy.decide`
keeps its **skip-check → amount → option** RNG order unchanged. The option step
becomes:
```
if (ctx.affinityWeightedProposal() && !weightsAreEqual(affinities)) {
    option = weightedPicker.pick(affinities, ctx.rng());   // one nextInt(W) draw
} else {
    option = options.get(ctx.rng().nextInt(options.size())); // today's exact draw
}
```
- **Toggle off** → the `else` branch runs verbatim: byte-for-byte today, same single
  `nextInt(n)` draw. `RandomBehaviorStrategyTest$Equivalence` (default weights, no
  toggle) is untouched.
- **Toggle on but weights equal** → also take the `else` branch (a cheap
  `weightsAreEqual` short-circuit), so the RNG draw is still `nextInt(n)`. This keeps
  a *toggle-on, equal-weight* run (e.g. default TaiXiu, or any not-yet-skewed game)
  byte-for-byte identical to today and RNG-compatible with the pin. (Even without the
  short-circuit, `AffinityOptionPicker` on equal weights draws `nextInt(Σw)=nextInt(n)`
  — see Findings — so the equivalence would hold anyway; the short-circuit makes the
  intent explicit and removes the `int`-cast/edge risk.)
- **Toggle on and weights skewed** → the weighted branch draws `nextInt(W)`
  (`W=Σw ≠ n` in general), a *deliberately different* draw. This is the only path
  that diverges from the uniform pin, and it is gated behind both the flag AND a
  skewed map — never hit by the existing equivalence test. Do **not** extend the
  equivalence pin to cover the skewed weighted path; instead pin the weighted path
  in its own test against the proven `AffinityOptionPicker`/distribution assertions
  (Phase 3).

**AD-4 — The toggle rides `BetContext`, not a strategy constructor.** Add
`boolean affinityWeightedProposal` to the `BetContext` record
(`.../strategy/BetContext.java`), sourced from the bot's `BotBehaviorConfig` in
`buildBetContext()` (`BettingMiniGameBot.java:579-589`), mirroring
`effectiveMaxBetsPerRound`. Keep the terse convenience constructor
(`BetContext.java:67-75`) defaulting the new flag to `false` so existing/test
callers stay byte-for-byte on the off path. Strategies read
`ctx.affinityWeightedProposal()`; the Martingale family ignores it (AD-5).

**AD-5 — Martingale family is untouched.** `MartingaleStrategySupport` already picks
via `AffinityOptionPicker` (`:177`) — it is *always* affinity-aware by design
(with a `RiskProfile` transform). The new toggle governs **only**
`RandomBehaviorStrategy`; Martingale strategies ignore `affinityWeightedProposal`
entirely. Rationale: Martingale's weighting is intrinsic to the strategy's risk
profile (a documented feature, `MARTINGALE_STRATEGIES.md` AD-A3), not an opt-in
group preference — flipping it off would corrupt those strategies' defined behavior.
The toggle is specifically the fix for RANDOM's flat pick.

**AD-6 — Composition seams (locked).**
- *Coordinator:* weighting runs in `decide()` (inside `decideBet`), strictly
  **before** `applyCoordination` (`BettingMiniGameBot.java:813` then coordination) —
  the coordinator sees the final, TaiXiu-remapped, affinity-influenced option and
  still trims/rejects. This is the WP#4 AD-7 floor fix: bias supply so the trim gate
  can reach a skewed target. No code coupling between the two.
- *TaiXiu lock:* `TaiXiuGameBot.decideBet` remaps to `lockedEntry` *after*
  `super.decideBet` runs the weighted pick (`TaiXiuGameBot.java:128-145`); the
  weighted pick therefore only influences the **first** (locking) entry of a round.
  Composes cleanly.
- *Jackpot/ramp:* orthogonal (which option vs how many / when) — no shared state.

**AD-7 — Game-type gating: option-based `BETTING_MINI`/`TAI_XIU` only.** Mirror
BET_COORDINATION AD-10 and JACKPOT_SCALE_AND_RAMP AD-S1: the toggle is only wired
into `BotBehaviorConfig` for `BETTING_MINI`/`TAI_XIU` bots (both extend
`BettingMiniGameBot`, share the `decide`/`decideBet` seam). SLOT
(`SlotMachineBot`, `chooseBet`) has no option id and is excluded — the flag is simply
never set on non-eligible bots (guard in `createSingleBot`, mirroring the
`rampEnabled`/`coordinationEnabled` guards).

**AD-8 — Observability: no new surface.** The existing **5s strategy-decision
aggregate** already emits the per-session **option histogram** (`options: [0]x12
[5]x20`) at DEBUG (per CLAUDE.md STRATEGY_DECISION_AGGREGATION). A shift from uniform
to affinity-weighted proposals is *directly visible* there as a skewed histogram — no
new metric, DTO block, or log line is warranted (lean-minimal, mirroring
JACKPOT_SCALE_AND_RAMP AD-R7's "already visible in the 5s aggregate"). The toggle's
*state* is a one-line addition to the health read only if desired (Open Decision D2);
recommended **not** to add a health block. The per-bot weighted pick stays TRACE
(the existing `decide` TRACE line, `RandomBehaviorStrategy.java:107`, already logs
`option=`).

## Plan

### Phase 1 — Weighted-pick helper (scope-agnostic, unit-tested)

Resolve Open Decision **D1** first (reuse vs extract).

- **If reuse (recommended):** confirm `new AffinityOptionPicker(RiskProfile.CAUTIOUS)`
  yields the plain affinity-proportional pick (it does — `computeWeights` CAUTIOUS =
  `max(0,w)`). Decide whether `RandomBehaviorStrategy` imports it from the
  `martingale` package or the picker is moved to `.../strategy/`
  (**recommend move to `.../strategy/WeightedOptionPicker` shared, or keep as-is and
  import** — Dev + reviewer call).
- **If extract:** create `com.vingame.bot.domain.bot.strategy.WeightedOptionPicker`
  with `int pick(Map<Integer,Integer> weights, Random rng)` — cumulative-weight core
  copied verbatim from `AffinityOptionPicker.pick` (`:88-122`), minus the
  `RiskProfile` transform (plain `max(0,w)` weights, same uniform-fallback + one-shot
  WARN). Refactor `AffinityOptionPicker` to delegate its cumulative-sum/draw to it
  (keeping the CAUTIOUS/AGGRESSIVE weight transforms), verify
  `AffinityOptionPickerTest` still passes unchanged.
- Add a `static boolean weightsAreEqual(Map<Integer,Integer> weights)` helper
  (all non-null values equal after `max(0,·)` clamp) used by AD-3's short-circuit.
- Unit tests (`WeightedOptionPickerTest`, or extend `AffinityOptionPickerTest`):
  skewed `{0:5,1:1}` → option 0 ~5/6 (distribution assert, reuse the existing
  ±3pp/10k-pick harness `AffinityOptionPickerTest.java:44-45`); equal weights → one
  `nextInt(n)` draw and uniform distribution; seeded determinism; single-key map
  returns that key; all-zero → uniform fallback + one WARN.

### Phase 2 — Write-side config (1 field)

- `BotGroup` (`.../botgroup/model/BotGroup.java`): add
  `boolean affinityWeightedProposal;`.
- `BotGroupDTO` (`.../botgroup/dto/BotGroupDTO.java`): add
  `Boolean affinityWeightedProposal;` (boxed, PATCH-null = keep).
- `BotGroupMapper` (hand-written): add the field to `toDTO`, `toEntity`
  (`Optional.ofNullable(...).orElse(false)`), and `updateEntityFromDTO`
  (replace-if-present, mirroring `coordinationEnabled`/`rampEnabled`).
- `BotBehaviorConfig`: add `boolean affinityWeightedProposal;`.
- `createSingleBot` (`BotGroupBehaviorService`, near the ramp/coordination wiring):
  set `.affinityWeightedProposal(group.isAffinityWeightedProposal())` on the
  `BotBehaviorConfig` builder **only** for `BETTING_MINI`/`TAI_XIU` (AD-7 guard;
  leave default `false` for SLOT).
- Update `BotGroupMapperTest` for the new field.
- No validation rule needed: any boolean is valid; equal-weight games no-op by
  construction (AD-3).

### Phase 3 — Strategy seam

- `BetContext` (`.../strategy/BetContext.java`): add
  `boolean affinityWeightedProposal` as a new record component (canonical
  constructor), defaulted to `false` in the terse convenience constructor
  (`:67-75`) so existing/test callers stay on the off path.
- `BettingMiniGameBot.buildBetContext()` (`:579-589`): pass
  `configuration.getBehaviorConfig().isAffinityWeightedProposal()` into the new
  `BetContext` component.
- `RandomBehaviorStrategy.decide` (`:101-108`): replace the uniform pick with the
  AD-3 branch — `else` = today's exact `options.get(rng.nextInt(options.size()))`;
  the weighted branch only when `ctx.affinityWeightedProposal() &&
  !WeightedOptionPicker.weightsAreEqual(affinities)`. The strategy holds a
  `WeightedOptionPicker`/`AffinityOptionPicker` instance (or a static helper) —
  it is stateless, so a field or a static call both work; prefer a `private final`
  field initialized in the no-arg constructor (mirrors Martingale). Update the
  class javadoc (`:24-27`) — it currently states "option selection is uniform … 
  Affinity-aware strategies are a future-strategy concern"; correct it to describe
  the opt-in weighted path.
- Unit tests (extend `RandomBehaviorStrategyTest`):
  - **Off path unchanged:** existing `$Equivalence` tests pass verbatim (the new
    flag defaults false; assert no extra RNG draw).
  - **Toggle-on, equal weights = uniform:** with `affinityWeightedProposal=true`
    and default `{i:1}` weights, output is byte-identical to the toggle-off run
    (same seed) — proves the short-circuit preserves RNG consumption.
  - **Toggle-on, skewed weights:** with `{0:5, rest:1}`, over N picks option 0's
    share ≈ 5/(5+n-1) within tolerance (reuse the distribution harness), and the
    result differs from the uniform run — proves biasing works.

### Phase 4 — (Optional) read-side toggle surface

Only if Open Decision **D2** says yes. Recommended **skip** — the 5s aggregate's
option histogram already reveals the shift (AD-8). If added: a single
`boolean affinityWeightedProposal` echoed on `BotGroupHealthDTO` (or reuse the
existing group config echo), no new stateful block.

## Implementation Notes / Concerns

- **RNG-order is the one trap.** The equal-weight/off path MUST draw exactly one
  `nextInt(n)` (AD-3). Do not restructure `decide`'s skip→amount→option order. Verify
  `RandomBehaviorStrategyTest$Equivalence` is green *before and after* — it is the
  canary. The short-circuit (`weightsAreEqual`) is what keeps a toggle-on default
  game byte-for-byte and RNG-identical.
- **`int` cast on total weight.** `AffinityOptionPicker` casts `totalWeight` to
  `int` for `nextInt` (`:109`). Real affinity sums are tiny (small ints across ≤~10
  options), so overflow is not a practical concern — but the weighted branch inherits
  that assumption; keep weights small (they already are). Document, do not
  over-engineer to `long` sampling.
- **All-zero / negative weights fallback.** Reuse `AffinityOptionPicker`'s
  uniform-fallback-with-one-WARN on `totalWeight <= 0` (`:101-107`) so a misconfigured
  skewed-but-all-zero map degrades to uniform on the hot path, never crashes the
  scenario thread. `weightsAreEqual` should treat all-equal (including all-zero) as
  "equal" → uniform `else` branch, so the fallback is only reachable via the picker
  on genuinely mixed-but-nonpositive maps.
- **Martingale must stay untouched.** Do not route the toggle into
  `MartingaleStrategySupport` — its weighting is intrinsic (AD-5). If Phase 1
  extracts a shared picker, the Martingale tests
  (`AffinityOptionPickerTest`, `MartingaleStrategySupportTest`, the 8 strategy tests)
  must remain green with **no assertion changes** — behavior-preserving refactor only.
- **TaiXiu only bites on skewed TaiXiu affinities.** Default `{1:1,2:1}` = uniform =
  no change. Only an operator who skews a TaiXiu game's `optionAffinities` sees the
  weighted initial-entry choice; the single-entry lock still holds (AD-6).
- **This is the WP#4 floor fix, not a full guarantee.** Affinity-weighted proposals
  *raise the probability* the fleet supplies enough on high-affinity options; they do
  not *force* it (a small fleet, low `maxBetsPerRound`, or RNG variance can still
  under-fill). The coordinator's trim/reject still owns the ceiling. Realized ≈ target
  is now *reachable*, not *guaranteed* — an honest improvement over AD-7's ceiling-only
  steering.

## Open Items

- **Crowd-aware tier (#6) — out of scope.** WP#7 is **feed-free** biasing of the
  bots' OWN option choice by the game's own affinity weights. Even though the crowd
  feed (per-option data in the `bs` array) is now confirmed to exist, **no crowd/`bs`
  logic enters this plan.** Steering relative to real players' imbalance is roadmap
  #6, gated behind the feed spike (#5).
- **Per-option target vs affinity divergence — future.** Today the proposal bias and
  the coordinator's budget both derive from the *same* `optionAffinities`, so they
  agree. If a future feature lets the coordinator target a distribution *different*
  from the affinity weights, the proposal bias should follow that target, not the raw
  affinities. Not needed now (they are the same source).
- **Fleet ownership — future.** The weighted pick is per-bot/per-strategy and
  Fleet-neutral by construction (no group-scoped object) — nothing to own at the Fleet
  level, unlike the coordinator.

## Open Decisions — for user approval

**D1 — Helper strategy: reuse `AffinityOptionPicker` in place vs extract a shared
`WeightedOptionPicker`.** *(Recommend: extract a plain `WeightedOptionPicker` in
`.../strategy/` and have `AffinityOptionPicker` delegate its cumulative-sum/draw to
it — cleaner layering, `RandomBehaviorStrategy` doesn't reach into the `martingale`
package, and the martingale tests stay green as a behavior-preserving refactor. The
lighter alternative is to import `AffinityOptionPicker` directly with
`RiskProfile.CAUTIOUS`.)*

**D2 — Any read-side toggle surface on health?** *(Recommend: no — the existing 5s
strategy-decision aggregate's option histogram already makes the shift observable
(AD-8). Add only a one-line config echo if the UI needs to display the toggle state.)*

**D3 — Confirm config placement on BotGroup (not Game).** *(Recommend: BotGroup — it
is a betting-behavior preference of this fleet, mirroring `rampEnabled` /
`coordinationEnabled` / `strategyMix`; the affinity *weights* stay on the Game. The
alternative — tie it implicitly to `coordinationEnabled` — is rejected: the two are
independent (one can want biased proposals without the coordinator, and vice-versa),
and an implicit coupling hides behavior.)*

## Verification

Run on staging after deploy. **Prerequisite that finally makes this testable:** a
`BETTING_MINI` group (e.g. BauCua, N=6) — or a `TAI_XIU` group with skewed
affinities — whose game has a **skewed `optionAffinities`** (e.g. one option weight
5, the rest weight 1) and whose environment reaches live rounds. **Note (call-out):**
BET_COORDINATION's own release could NOT verify realized-vs-target divergence because
the live game had **uniform** affinities — with uniform weights the affinity-weighted
pick is identical to uniform (AD-3), so there is nothing to observe. WP#7 is only
observable on a **skewed-affinity** game; provisioning one is the thing this WP makes
testable. Substitute the staging base URL and a real group id `$GID`. Prefer a
BOM/B52/Nohu-backed BauCua-style group (per MEMORY, staging **TIP** WS-connect may be
DNS-blocked).

**1. Config round-trips (Phase 2).**
```
curl -s -X PATCH $BASE/api/v1/bot-group -H 'Content-Type: application/json' \
  -d '{"id":"'$GID'","affinityWeightedProposal":true}'
curl -s $BASE/api/v1/bot-group/$GID | jq '.affinityWeightedProposal'
```
Expect `true`. PATCH it back to `false` and expect `false` (round-trips both ways).

**2. Skewed game precondition.** Confirm the group's game has skewed affinities:
```
curl -s $BASE/api/v1/game/$GAMEID | jq '.optionAffinities'
```
Expect a map where at least one weight differs (e.g. `{"0":5,"1":1,...}`). If all
equal, this WP is a no-op by design and steps 3-4 will show no shift — fix the game
first.

**3. Option histogram shifts toward the high-affinity option (AD-8, the core check).**
Ensure `com.vingame.bot` is at DEBUG and read the 5s strategy-decision aggregate
option histogram over a sustained run (≥ a few minutes) with the toggle **on**:
```
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
grep -E "options: \[" <app-log> | grep "$GID" | tail
```
Expect the histogram to be **skewed toward the high-affinity option** (its `x<count>`
materially higher than the low-affinity options), roughly tracking the weight ratio
(e.g. the weight-5 option ~5× the weight-1 options), NOT the flat/uniform spread.

**4. Toggle-off = uniform (regression + contrast).** PATCH `affinityWeightedProposal`
back to `false`, restart the group, and read the same histogram over a comparable
window:
```
curl -s -X PATCH $BASE/api/v1/bot-group -H 'Content-Type: application/json' \
  -d '{"id":"'$GID'","affinityWeightedProposal":false}'
curl -s -X POST $BASE/api/v1/bot-group/$GID/restart
grep -E "options: \[" <app-log> | grep "$GID" | tail
```
Expect the histogram to be **roughly uniform** across options again (byte-for-byte
today's behavior — AD-3). The contrast between step 3 (skewed) and step 4 (uniform)
on the same skewed-affinity game is the proof.

**5. Compose with the coordinator (AD-6, best-effort).** With BOTH
`affinityWeightedProposal=true` AND `coordinationEnabled=true` on a skewed-affinity
game, poll the coordination health block over ≥20 rounds:
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.coordination.options'
```
Expect the high-affinity option's `committedStake` to now sit **closer to its
`targetBudget`** than it did under uniform proposals (the AD-7 floor caveat is
relaxed — bots now propose enough on that option for the trim gate to fill it), while
`currentAggregateStake <= maxAggregateStakePerRound` still holds. This is the whole
point of the WP: the coordinator can now approach a skewed target, not just enforce
its ceiling.

**6. TaiXiu default is unchanged (AD-6).** On a **default** TaiXiu group (weights
`{1:1,2:1}`) with the toggle on, expect the Tài/Xỉu split to stay ~50/50 in the
option histogram — equal weights = uniform, no change. (Only a skewed TaiXiu
affinity map would shift it.)

**7. Off path is the universal smoke test.** For any group with
`affinityWeightedProposal=false`, betting flows exactly as before; there is no new
on-server surface beyond the config field and the (unchanged-shape) option histogram.
The universal smoke test otherwise applies.

Plan written: docs/plans/AFFINITY_AWARE_PROPOSAL.md
Ready for user approval before Dev begins.
