package com.vingame.bot.domain.bot.strategy;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Scope-agnostic, Spring-free weighted-categorical option picker. Given a map
 * of option id → weight and an externally owned {@link Random}, it performs a
 * standard cumulative-weight + single uniform draw over the (clamped, ≥ 0)
 * weights, in the map's insertion order.
 *
 * <p>This is the extracted core of
 * {@link com.vingame.bot.domain.bot.strategy.martingale.AffinityOptionPicker}
 * (see {@code docs/plans/AFFINITY_AWARE_PROPOSAL.md} AD-1, Open Decision D1 =
 * EXTRACT). {@code AffinityOptionPicker} computes its {@link
 * com.vingame.bot.domain.bot.strategy.martingale.RiskProfile}-transformed
 * weights and then delegates the cumulative-sum/draw to this helper;
 * {@code RandomBehaviorStrategy} (Phase 3) uses the plain {@code max(0, w)}
 * weighting directly. The picker itself applies no risk transform — it treats
 * the passed weights as the final categorical distribution, clamping each to
 * {@code max(0, w)}.
 *
 * <p><b>Sampling implementation.</b> Standard cumulative-weight + uniform draw.
 * Total weights for a real game are tiny (sum of small integers across ≤ ~10
 * options), so {@code int} arithmetic is sufficient and matches what a seeded
 * {@link Random#nextInt(int)} produces — pinning RNG ordering for the unit
 * tests. Exactly <b>one</b> {@code rng.nextInt(Σw)} draw per call on the happy
 * path (and one {@code rng.nextInt(keys)} draw on the all-non-positive
 * fallback), so callers can rely on a fixed RNG-consumption count.
 *
 * <p><b>Defensive fallback.</b> If every clamped weight is 0 — every weight is
 * ≤ 0, or the map is uniformly bad — the picker falls back to a uniform draw
 * over the input keys, protecting the per-tick hot path from a
 * divide-by-zero / empty-cumulative-weights crash on the scenario thread. A
 * WARN is logged once per picker instance (a {@code volatile boolean warned}
 * flag) so the operator notices the misconfiguration without log-spam.
 *
 * <p><b>RNG ownership.</b> The picker holds no {@link Random} of its own — the
 * RNG is threaded in on every {@code pick(...)} call from
 * {@link BetContext#rng()}, matching the v1 strategy plumbing.
 */
@Slf4j
public final class WeightedOptionPicker {

    private volatile boolean warned;

    /**
     * Pick a single option id from {@code weights}, proportional to the
     * (clamped {@code max(0, w)}) weight of each key, in insertion order.
     *
     * @param weights option id → weight. Must be non-null and non-empty.
     * @param rng     per-bot {@link Random}; exactly one draw per call on the
     *                happy path (one draw over the keys on the all-non-positive
     *                fallback).
     * @return an option id that is a key of {@code weights}.
     * @throws IllegalArgumentException if {@code weights} is null or empty.
     */
    public int pick(Map<Integer, Integer> weights, Random rng) {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("weights must be non-empty");
        }

        // Deterministic insertion-order view — pinned by tests that depend on
        // the cumulative-weight layout matching a specific RNG draw.
        List<Integer> options = new ArrayList<>(weights.keySet());
        int[] clamped = new int[options.size()];
        long totalWeight = 0L;
        for (int i = 0; i < options.size(); i++) {
            Integer raw = weights.get(options.get(i));
            int w = raw == null ? 0 : Math.max(0, raw);
            clamped[i] = w;
            totalWeight += w;
        }

        if (totalWeight <= 0L) {
            warnOnce(weights);
            // Uniform fallback over the input keys — picks deterministically
            // from the same insertion-order view we built above so seeded tests
            // are reproducible.
            return options.get(rng.nextInt(options.size()));
        }

        int draw = rng.nextInt((int) totalWeight);
        int cumulative = 0;
        for (int i = 0; i < options.size(); i++) {
            cumulative += clamped[i];
            if (draw < cumulative) {
                return options.get(i);
            }
        }
        // Numerically unreachable — the loop is exhaustive over the cumulative
        // window. Defensive fallback to the last option keeps the contract
        // (returns a key from weights) intact even if a future refactor
        // misaligns the weights array.
        return options.get(options.size() - 1);
    }

    /**
     * True when all non-null values of {@code weights} are equal after a
     * {@code max(0, ·)} clamp. Used by the Phase 3 (AD-3) short-circuit: an
     * equal-weight map (including all-zero) is indistinguishable from a uniform
     * pick, so callers route it through today's exact {@code nextInt(n)} uniform
     * draw rather than the weighted path — preserving byte-for-byte RNG
     * consumption.
     *
     * <p>Empty and single-distinct-value maps are treated as equal (there is
     * nothing to be unequal against). A null map is treated as equal.
     *
     * @param weights option id → weight (may be null/empty).
     * @return {@code true} if all clamped values are identical (or there are
     * fewer than two distinct clamped values).
     */
    public static boolean weightsAreEqual(Map<Integer, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return true;
        }
        Integer first = null;
        for (Integer raw : weights.values()) {
            int w = raw == null ? 0 : Math.max(0, raw);
            if (first == null) {
                first = w;
            } else if (first != w) {
                return false;
            }
        }
        return true;
    }

    private void warnOnce(Map<Integer, Integer> weights) {
        if (!warned) {
            warned = true;
            log.warn("WeightedOptionPicker: all weights ≤ 0 for weights={} — falling back to uniform pick",
                    weights);
        }
    }
}
