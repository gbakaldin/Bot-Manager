package com.vingame.bot.domain.bot.strategy.martingale;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Weighted option-picker shared by every Martingale-family strategy. Stateless
 * with respect to per-call inputs: holds only the immutable
 * {@link RiskProfile} and a one-shot WARN guard for the all-non-positive
 * fallback path.
 *
 * <p>Sampling math (see {@code docs/plans/MARTINGALE_STRATEGIES.md},
 * Architecture Decision A3):
 * <ul>
 *   <li><b>CAUTIOUS</b>: weight per option {@code o} is {@code max(0, a(o))}.
 *       Options with affinity ≤ 0 receive weight 0 and never get picked. The
 *       higher the raw affinity, the more often the option is picked.</li>
 *   <li><b>AGGRESSIVE</b>: weight per option is
 *       {@code (maxAffinity + 1) - max(0, a(o))} where {@code maxAffinity} is
 *       the largest non-null affinity value in the map. The {@code +1} guarantees
 *       the highest-affinity option still has weight 1 (never starves), and
 *       reflecting around {@code (maxAffinity + 1)} inverts the preference
 *       symmetrically.</li>
 * </ul>
 *
 * <p>Defensive fallback (Architecture Decision A3 + Implementation Note 5): if
 * every computed weight is 0 — which happens when every affinity is ≤ 0, or
 * when the affinities map is uniformly bad — the picker falls back to a uniform
 * draw over the input keys. This protects against a misconfigured {@code Game}
 * causing a divide-by-zero / empty-cumulative-weights crash inside the per-tick
 * hot path on the scenario thread. A WARN is logged once per picker instance
 * (a {@code volatile boolean warned} flag) so the operator notices the
 * misconfiguration without log-spam.
 *
 * <p><b>Sampling implementation.</b> Standard cumulative-weight + uniform
 * draw. Total weights for a real game are tiny (sum of small integers across
 * ≤ ~10 options), so {@code int} arithmetic is sufficient and matches what a
 * seeded {@link Random#nextInt(int)} produces — pinning RNG ordering for the
 * unit tests.
 *
 * <p><b>Thread model.</b> The picker is owned by a single Martingale strategy
 * instance, which is itself per-bot. {@code pick(...)} is called from the
 * scenario thread (inside {@code MartingaleStrategySupport.decide}). No
 * cross-thread state — the {@code warned} flag is {@code volatile} for the
 * one-time WARN, but is otherwise idempotent and immaterial to correctness.
 *
 * <p><b>RNG ownership.</b> The picker holds no {@link Random} of its own — the
 * RNG is threaded in on every {@code pick(...)} call from
 * {@link com.vingame.bot.domain.bot.strategy.BetContext#rng()}, matching the
 * v1 strategy plumbing established by
 * {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy}.
 */
@Slf4j
public final class AffinityOptionPicker {

    private final RiskProfile profile;
    private volatile boolean warned;

    public AffinityOptionPicker(RiskProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        this.profile = profile;
    }

    public RiskProfile getProfile() {
        return profile;
    }

    /**
     * Pick a single option id from {@code affinities} weighted by the
     * configured {@link RiskProfile}.
     *
     * @param affinities option id → raw affinity weight, as returned by
     *                   {@link com.vingame.bot.domain.game.model.Game#getEffectiveOptionAffinities()}.
     *                   Must be non-null and non-empty.
     * @param rng        per-bot {@link Random}; one draw per call on the happy
     *                   path (potentially zero on the single-option fallback,
     *                   but that's deterministic so it doesn't matter for the
     *                   downstream RNG ordering).
     * @return an option id that is a key of {@code affinities}.
     * @throws IllegalArgumentException if {@code affinities} is null or empty.
     */
    public int pick(Map<Integer, Integer> affinities, Random rng) {
        if (affinities == null || affinities.isEmpty()) {
            throw new IllegalArgumentException("affinities must be non-empty");
        }

        // Deterministic insertion-order view — pinned by tests that depend on
        // the cumulative-weight layout matching a specific RNG draw.
        List<Integer> options = new ArrayList<>(affinities.keySet());
        int[] weights = computeWeights(options, affinities);

        long totalWeight = 0L;
        for (int w : weights) totalWeight += w;

        if (totalWeight <= 0L) {
            warnOnce(affinities);
            // Uniform fallback over the input keys — picks deterministically
            // from the same insertion-order view we built above so seeded tests
            // are reproducible.
            return options.get(rng.nextInt(options.size()));
        }

        int draw = rng.nextInt((int) totalWeight);
        int cumulative = 0;
        for (int i = 0; i < options.size(); i++) {
            cumulative += weights[i];
            if (draw < cumulative) {
                return options.get(i);
            }
        }
        // Numerically unreachable — the loop is exhaustive over the cumulative
        // window. Defensive fallback to the last option keeps the contract
        // (returns a key from affinities) intact even if a future refactor
        // misaligns the weights array.
        return options.get(options.size() - 1);
    }

    private int[] computeWeights(List<Integer> options, Map<Integer, Integer> affinities) {
        int[] weights = new int[options.size()];
        switch (profile) {
            case CAUTIOUS -> {
                for (int i = 0; i < options.size(); i++) {
                    Integer raw = affinities.get(options.get(i));
                    weights[i] = raw == null ? 0 : Math.max(0, raw);
                }
            }
            case AGGRESSIVE -> {
                int maxAffinity = 1;
                for (Integer key : options) {
                    Integer raw = affinities.get(key);
                    if (raw != null && raw > maxAffinity) maxAffinity = raw;
                }
                int reflectAround = maxAffinity + 1;
                for (int i = 0; i < options.size(); i++) {
                    Integer raw = affinities.get(options.get(i));
                    int clamped = raw == null ? 0 : Math.max(0, raw);
                    weights[i] = reflectAround - clamped;
                }
            }
        }
        return weights;
    }

    private void warnOnce(Map<Integer, Integer> affinities) {
        if (!warned) {
            warned = true;
            log.warn("AffinityOptionPicker[{}]: all weights ≤ 0 for affinities={} — falling back to uniform pick",
                    profile, affinities);
        }
    }
}
