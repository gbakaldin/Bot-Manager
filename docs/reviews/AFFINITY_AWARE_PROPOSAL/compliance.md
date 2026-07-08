# Compliance — AFFINITY_AWARE_PROPOSAL

Branch: `feat/affinity-aware-proposal`
Plan reviewed: `docs/plans/AFFINITY_AWARE_PROPOSAL.md` (at commit 84d091c)
Diff reviewed: `git diff main...feat/affinity-aware-proposal` (HEAD 229a200)

## Verdict

PASS

The diff faithfully implements the plan and honors all three user-resolved Open
Decisions (D1 = extract, D2 = skip Phase 4, D3 = independent per-BotGroup flag).
All 132 relevant unit tests pass, including the untouched Martingale suites and
the RNG-consumption equivalence canary.

## Phase-by-phase

### Phase 1 — Weighted-pick helper (scope-agnostic, unit-tested)
Status: implemented
Notes:
- New `com.vingame.bot.domain.bot.strategy.WeightedOptionPicker` — Spring-free
  (no `@Component`/injection; plain `final class`, `@Slf4j` only), holds no
  `Random`, RNG threaded in per `pick(...)` call. Matches D1 = **extract**.
- Cumulative-weight core is the verbatim extraction of `AffinityOptionPicker.pick`
  (`:88-122`): `new ArrayList<>(keySet())` insertion-order view, `max(0, w)`
  clamp, `totalWeight <= 0` → uniform fallback + one-shot volatile-`warned` WARN,
  `rng.nextInt((int) totalWeight)` draw, cumulative walk, unreachable last-option
  guard. RNG-consumption count preserved (one draw happy path, one on fallback).
- `AffinityOptionPicker` refactored to a thin wrapper: keeps its `RiskProfile`
  `computeWeights` (CAUTIOUS/AGGRESSIVE transforms unchanged), builds a
  `LinkedHashMap` in the affinities' insertion order, and delegates the draw to
  `WeightedOptionPicker`. Its own `warned` flag / fallback removed (now sourced
  from the delegate). Public behavior unchanged.
- `static boolean weightsAreEqual(Map<Integer,Integer>)` present, clamps with
  `max(0,·)`, treats null/empty/single-distinct and all-zero as equal (feeds the
  AD-3 short-circuit).
- `WeightedOptionPickerTest` added (skewed distribution, equal-weights,
  determinism, fallback, single-key). `AffinityOptionPickerTest` /
  `MartingaleStrategySupportTest` are **byte-for-byte unchanged** and green —
  the behavior-preserving-refactor requirement (AD-5, Phase-1 note) is met.

### Phase 2 — Write-side config (1 field)
Status: implemented
Notes:
- Exactly **one** new field per surface: `BotGroup.affinityWeightedProposal`
  (`boolean`), `BotGroupDTO.affinityWeightedProposal` (boxed `Boolean`,
  PATCH-null-keeps), `BotBehaviorConfig.affinityWeightedProposal` (`boolean`).
- `BotGroupMapper` (hand-written): field added to `toDTO`,
  `toEntity` (`orElse(false)`), and `updateEntityFromDTO` (replace-if-present via
  `orElse(entity.isAffinityWeightedProposal())`) — mirrors the ramp/coordination
  scalar flags.
- `createSingleBot` guard (`BotGroupBehaviorService:566+`): the
  `.affinityWeightedProposal(group.isAffinityWeightedProposal())` builder call is
  inside the `BETTING_MINI || TAI_XIU` block; SLOT/other keep the `false`
  default (AD-7). Correctly gated.
- No validation rule added (per plan).
- `BotGroupMapperTest` and `BotGroupBehaviorServiceTest` extended; green.

### Phase 3 — Strategy seam
Status: implemented
Notes:
- `BetContext` gains `boolean affinityWeightedProposal` as the last canonical
  record component; the terse convenience constructor defaults it to `false`
  (existing/test callers stay on the off path). Javadoc updated.
- `BettingMiniGameBot.buildBetContext()` passes
  `behavior.isAffinityWeightedProposal()` into the canonical constructor.
- `RandomBehaviorStrategy.decide` replaces the flat pick with the AD-3 branch
  verbatim: `if (ctx.affinityWeightedProposal() && !WeightedOptionPicker.weightsAreEqual(affinities))`
  → `picker.pick(...)`; else the **unchanged** `List.copyOf(keySet()).get(rng.nextInt(size))`.
  The skip→amount→option RNG order is untouched; the amount path is untouched.
  A `private final WeightedOptionPicker picker` field mirrors Martingale's
  picker ownership. The stale "Affinity-aware strategies are a future concern"
  javadoc is corrected to describe the opt-in weighted path.
- Tests: existing `$Equivalence` assertions unchanged (only a required new
  `false` arg appended to one `new BetContext(...)` call). New `$AffinityWeighted`
  nest pins toggle-on-equal-weights == toggle-off byte-identity and skewed-weight
  bias (option-0 share ≈ w0/(w0+n-1), materially > uniform). Green.

### Phase 4 — (Optional) read-side toggle surface
Status: out-of-scope (correctly SKIPPED per D2)
Notes: No `BotGroupHealthDTO` / health-block / new metric changes anywhere in the
diff (`git diff --stat | grep -i health` is empty). Not half-added.

## Architecture Decision compliance

- **AD-1** (scope-agnostic pure helper, reuse the proven math): met — see Phase 1.
- **AD-2** (deterministic given injected rng, holds no `Random`): met — picker is
  stateless w.r.t. RNG; RNG threaded via `ctx.rng()`.
- **AD-3** (off = byte-for-byte today; equal-weights short-circuits to the same
  single `nextInt(n)` draw; only skewed diverges): met exactly. The `else` branch
  is today's verbatim code; `weightsAreEqual` keeps the equal-weight case out of
  the picker (no `int`-cast/extra-draw risk); the weighted branch is the sole
  divergence, gated behind flag AND skew. The equivalence pin is untouched and
  green.
- **AD-4** (toggle rides `BetContext`, convenience ctor defaults false): met.
- **AD-5** (Martingale untouched except via shared-picker delegation): met — only
  `AffinityOptionPicker.java` changed in the martingale package, behavior-preserving;
  its intrinsic RiskProfile weighting and all Martingale tests are unchanged/green.
- **AD-6** (composition seams: coordinator / TaiXiu lock / jackpot-ramp): met by
  construction — the weighted pick stays inside `decide()`, no new coupling; no
  changes to `TaiXiuGameBot`, `applyCoordination`, or jackpot/ramp code.
- **AD-7** (game-type gating BETTING_MINI/TAI_XIU only): met via the
  `createSingleBot` guard; SLOT excluded.
- **AD-8** (no new observability surface): met — no new metric/DTO/log line.

## Open Decision resolution compliance

- **D1 = extract** — honored. A shared `WeightedOptionPicker` in
  `.../strategy/` was created (not reuse-in-place); `AffinityOptionPicker`
  delegates to it. Not the "import CAUTIOUS in place" alternative.
- **D2 = skip Phase 4** — honored. No read-side surface added, none half-added.
- **D3 = independent per-BotGroup flag** — honored. The field is on `BotGroup`
  (not `Game`) and is set/read independently of `coordinationEnabled` (the guard
  block sets ramp + affinity together only by game-type gating, with no coupling
  to the coordination flag). Javadoc explicitly documents the independence.

## Drift

None.

## Out-of-scope changes

None. Every touched file maps to a plan phase. No crowd/`bs` logic (Open Items
respected), no Slot changes, no validation rule.

## Amendments to the plan

None.
