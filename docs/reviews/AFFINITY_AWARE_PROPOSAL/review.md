# Code Review — AFFINITY_AWARE_PROPOSAL

Branch: `feat/affinity-aware-proposal`
Reviewed diff: `git diff main...HEAD`

## Verdict

PASS

No `bug` or `security` findings. Two low-severity smells and one advisory note below; all are optional.

## Findings

### [smell] `weightsAreEqual` short-circuits before `pick`, so the picker's own re-clamp is the sole guard on the weighted path
`src/main/java/com/vingame/bot/domain/bot/strategy/RandomBehaviorStrategy.java:132`

The gate `ctx.affinityWeightedProposal() && !WeightedOptionPicker.weightsAreEqual(affinities)` is correct, but it couples two independently-defined clamps: `weightsAreEqual` clamps with `max(0, w)` and `WeightedOptionPicker.pick` clamps with `max(0, w)`. They agree today, so the all-non-positive fallback inside `pick` is effectively dead code on this call path — `weightsAreEqual` returns `true` for an all-zero/all-equal map and routes it to the uniform branch, and a mixed map with some negatives still reaches `pick` with a positive Σw. That is fine and RNG-safe, but the invariant "these two clamps must stay identical" is implicit. If a future edit changes one clamp (e.g. `weightsAreEqual` starts treating negatives as distinct) the two could disagree and the picker's fallback would silently activate. No runtime failure today; flagged only so the coupling is documented. A one-line comment at the call site (or a shared clamp helper) would make it explicit. Advisory.

### [smell] `WeightedOptionPicker` re-clamps weights that `AffinityOptionPicker.computeWeights` has already made non-negative
`src/main/java/com/vingame/bot/domain/bot/strategy/martingale/AffinityOptionPicker.java:107`

`computeWeights` guarantees non-negative outputs (CAUTIOUS = `max(0, raw)`; AGGRESSIVE = `reflectAround - clamped` with `reflectAround = maxAffinity + 1 > clamped`), then the delegate re-applies `max(0, w)`. The double-clamp is a harmless no-op and RNG-neutral — the reviewed concern (that delegation changed how many RNG values the Martingale family draws) does **not** occur: the delegate walks the same `LinkedHashMap` insertion-order key view and issues exactly one `nextInt((int) Σw)` over an identical Σw, byte-for-byte with the pre-extraction code. Noting only that the redundant clamp is a small readability wart; no action required.

## Notes

- **RNG-consumption identity (critical invariant) — verified.**
  - Off path (`RandomBehaviorStrategy.java:150-153`) is the pre-change code verbatim: `List.copyOf(affinities.keySet())` then one `rng.nextInt(options.size())`. Unchanged.
  - Toggle-on-equal-weights path: `weightsAreEqual` short-circuits to the same off-path branch, so still one `nextInt(n)` — the picker (and its `int`-cast / Σw arithmetic) is never entered. Confirmed.
  - Weighted path: exactly one `rng.nextInt((int) Σw)` (or one `nextInt(keys)` on the all-non-positive fallback). Single draw per call.
  - Martingale delegation (`AffinityOptionPicker.pick`): the transformed weights are copied into a `LinkedHashMap` in `affinities.keySet()` order, and the delegate builds `new ArrayList<>(weights.keySet())` over that same order. Σw and the cumulative layout are identical to the deleted inline loop, so `nextInt((int) Σw)` consumes the same value with the same key mapping. No stream shift.

- **`weightsAreEqual` correctness — verified across the required cases.** null/empty → `true` (treated uniform, routes to `nextInt(n)`); single key → `true`; all-equal (incl. all-zero) → `true`; negative-clamped equal to another clamped value → compares post-`max(0,·)` so `{a:-3, b:0}` is correctly `true`; unequal → `false`. The `first != w` comparison is `Integer` vs `int`, which unboxes to a value comparison (not reference identity) — no autoboxing-cache bug. A spurious `false` on genuinely-uniform weights would still draw `nextInt(Σw) == nextInt(n)`, so even a mistaken `false` is harmless, as the plan notes.

- **`WeightedOptionPicker` edges.** `warned` is `volatile boolean` set under a check-then-set that is not atomic — under concurrent first-hits two threads could both log once, but the picker is per-strategy-instance / per-bot (constructed as an instance field in both `RandomBehaviorStrategy` and `AffinityOptionPicker`), and `pick` runs on the single scenario thread, so there is no real contention; `volatile` is sufficient for the "eventually stops warning" contract. The `(int) totalWeight` cast is safe for trust-bounded operator config (≤ ~10 options, small integer affinities); an overflow would require Σw > `Integer.MAX_VALUE`, unreachable for legit games, and is documented in the javadoc. The unreachable `return options.get(size-1)` tail keeps the "returns a key of weights" contract under a future misalignment. Insertion-order determinism holds: `getEffectiveOptionAffinities` returns `LinkedHashMap` for synthesized maps and the stored (Mongo `LinkedHashMap`) map otherwise, matching the legacy `List.copyOf(keySet())` ordering.

- **Off = today (AD-3) — verified end to end.** `BotGroupDTO.affinityWeightedProposal` is boxed and defaults via `Optional.orElse(false)` in both the create-map and PATCH paths (`BotGroupMapper.java:83,129`), mirroring `rampEnabled`. `BotGroupBehaviorService` sets the flag only inside the `BETTING_MINI || TAI_XIU` block (`:576-580`), so SLOT/other bots keep the builder default `false`. The convenience `BetContext` constructor passes `false` (`BetContext.java:85-86`), keeping every non-hot-path/test caller on the off path — only the canonical constructor at `BettingMiniGameBot.java:581` threads the resolved flag, and `TaiXiuGameBot` inherits that same builder.

- **Composition.** The option pick sits in `RandomBehaviorStrategy.decide` ahead of coordination / TaiXiu-lock (those consume the proposed option downstream); the amount path (`minBet + steps*betStep`) is untouched; no reads/writes of jackpot or ramp state. `effectiveMaxBetsPerRound` and the new flag are independent record components. Consistent with the shipped ramp/coordinator/jackpot conventions (boxed DTO + `orElse` default + game-type-gated builder set).

- **Javadoc fix confirmed.** The stale `RandomBehaviorStrategy` "Ignores affinity values / uniform over keySet" paragraph was replaced with an accurate description of the opt-in weighted branch and the equal-weight short-circuit (`RandomBehaviorStrategy.java:25-34`). `AffinityOptionPicker`'s thread-model note was correctly trimmed to reflect that the `warned`/WARN state now lives in the delegate.

- **Logging.** New WARN in `WeightedOptionPicker.warnOnce` uses the SLF4J `{}` form and logs only option-id→weight integers (no tokens / no secrets). No swallowed or over-broadened exceptions in the diff; the only thrown exception (`IllegalArgumentException` on null/empty weights) is a genuine misuse guard.
