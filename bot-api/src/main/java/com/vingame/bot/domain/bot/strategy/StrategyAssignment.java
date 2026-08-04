package com.vingame.bot.domain.bot.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure function that distributes a {@link WeightedStrategy} mix across a fixed
 * set of bot identifiers, returning a deterministic per-bot strategy assignment.
 *
 * <p><b>Algorithm — fill-to-target by hash-sorted chunking.</b>
 * <ol>
 *   <li>Normalize weights against the sum and multiply by {@code botCount} →
 *       fractional targets per strategy.</li>
 *   <li>Apply the largest-remainder method so the integer targets sum to
 *       exactly {@code botCount}. This is the standard apportionment rule and
 *       guarantees no rounding drift (e.g. weights 0.3/0.5/0.2 over 10 bots →
 *       exactly 3/5/2, not 3/5/2 ±1 from naive {@code round()}).</li>
 *   <li>Sort the bot identifiers by a stable hash so the same identifier
 *       lands at the same position across restarts. Slice the sorted list
 *       into contiguous chunks of size {@code target[i]}, assigning chunk
 *       {@code i} to strategy {@code i}.</li>
 * </ol>
 *
 * <p><b>Determinism contract.</b> Re-running with the same
 * {@code (strategyMix, botIdentifiers)} produces a bit-identical map. This is
 * the property that lets us "pin" a bot's strategy across restarts: the
 * identifier is {@code namePrefix + botIndex} which is stable per bot, so a
 * group restart re-assigns each bot to the same strategy it had before
 * (Architecture Decision 8 in {@code docs/plans/BETTING_STRATEGIES.md}).
 *
 * <p><b>Edge cases.</b>
 * <ul>
 *   <li>Empty {@code strategyMix} or empty {@code botIdentifiers} returns an
 *       empty map. Callers are expected to fall back to the default mix
 *       {@code [(RANDOM, 1.0)]} before invoking this method.</li>
 *   <li>{@code botCount < |strategyMix|}: strategies with a rounded target of
 *       zero receive no bots — observable via the per-bot strategy field on
 *       {@code BotHealthDTO} so an operator can spot a mix that under-fills.</li>
 *   <li>Non-positive weights are rejected with {@link IllegalArgumentException}
 *       — a strategy with weight {@code <= 0} should be removed from the mix
 *       rather than silently dropped here.</li>
 *   <li>Duplicate {@link StrategyId} entries in the mix are summed before
 *       apportionment so the caller does not have to pre-dedupe.</li>
 * </ul>
 */
public final class StrategyAssignment {

    private StrategyAssignment() {
        // utility class
    }

    /**
     * Apportionment vector — internal output of the largest-remainder step.
     * Exposed package-private so {@code StrategyAssignmentTest} can drive the
     * apportionment math directly with mixes of distinct enum entries (the
     * full {@link #assign} call requires a {@link StrategyId} per strategy
     * which limits distinct-bucket testing in v1 with only one enum value).
     *
     * @param ids    strategy ids in the order they were submitted (post-coalesce)
     * @param target integer per-strategy target counts that sum to {@code botCount}
     */
    record ApportionmentResult(List<StrategyId> ids, int[] target) {
    }

    /**
     * Run the largest-remainder apportionment for a given mix + bot count.
     * Package-private for direct testing of the math without the
     * identifier-sorting step.
     *
     * @param mix      weighted strategy mix; must be non-null and non-empty.
     *                 Each weight must be {@code > 0}. Duplicate
     *                 {@link StrategyId} entries are coalesced (weights summed).
     * @param botCount total number of bots to distribute. Must be {@code >= 0}.
     * @return the per-strategy target vector. Iteration order of
     *         {@link ApportionmentResult#ids()} matches the user-supplied
     *         {@code strategyMix} order (after coalescing).
     * @throws IllegalArgumentException if any weight is {@code <= 0}.
     */
    static ApportionmentResult apportion(List<WeightedStrategy> mix, int botCount) {
        // Coalesce duplicate StrategyId entries (caller may submit the same id
        // twice). EnumMap preserves enum-declaration order which we later use
        // as the secondary sort key for largest-remainder ties.
        Map<StrategyId, Double> coalesced = new LinkedHashMap<>();
        for (WeightedStrategy w : mix) {
            if (w.weight() <= 0) {
                throw new IllegalArgumentException(
                        "WeightedStrategy weight must be > 0 (got " + w.weight()
                                + " for " + w.strategyId() + ")");
            }
            coalesced.merge(w.strategyId(), w.weight(), Double::sum);
        }

        // Largest-remainder apportionment. We compute the floor of each
        // fractional target, then distribute the remainder ones-by-one to the
        // strategies with the largest fractional part. Ties break by the
        // strategy's index in the coalesced map (stable), which matches the
        // user-supplied ordering in BotGroup.strategyMix.
        double sumWeights = coalesced.values().stream().mapToDouble(Double::doubleValue).sum();

        List<StrategyId> ids = new ArrayList<>(coalesced.keySet());
        int[] target = new int[ids.size()];
        double[] remainder = new double[ids.size()];
        int allocated = 0;
        for (int i = 0; i < ids.size(); i++) {
            double exact = coalesced.get(ids.get(i)) / sumWeights * botCount;
            target[i] = (int) Math.floor(exact);
            remainder[i] = exact - target[i];
            allocated += target[i];
        }

        int leftover = botCount - allocated;
        if (leftover > 0) {
            List<Integer> indices = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) indices.add(i);
            indices.sort((a, b) -> {
                int cmp = Double.compare(remainder[b], remainder[a]);
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });
            for (int k = 0; k < leftover; k++) {
                target[indices.get(k)]++;
            }
        }

        return new ApportionmentResult(ids, target);
    }

    /**
     * Compute the deterministic per-bot strategy assignment.
     *
     * @param mix            weighted strategy mix (typically from
     *                       {@code BotGroup.strategyMix}); must be non-null but
     *                       may be empty.
     * @param botIdentifiers stable, unique per-bot identifiers (typically
     *                       {@code namePrefix + botIndex}). Order is irrelevant;
     *                       the routine re-sorts deterministically by hash.
     * @return a map from bot identifier to assigned {@link StrategyId}. Every
     *         entry of {@code botIdentifiers} appears exactly once in the
     *         result (unless the mix is empty, in which case the result is
     *         empty).
     * @throws IllegalArgumentException if any weight is {@code <= 0} or if
     *                                  {@code botIdentifiers} contains
     *                                  duplicates.
     */
    public static Map<String, StrategyId> assign(List<WeightedStrategy> mix,
                                                 List<String> botIdentifiers) {
        if (mix == null || mix.isEmpty() || botIdentifiers == null || botIdentifiers.isEmpty()) {
            return Collections.emptyMap();
        }

        int botCount = botIdentifiers.size();
        ApportionmentResult apportionment = apportion(mix, botCount);
        List<StrategyId> ids = apportionment.ids;
        int[] target = apportionment.target;

        // Sort bot identifiers deterministically by hash for reproducible
        // assignment across restarts. The secondary key (identifier itself) is
        // a defensive tiebreaker — String.hashCode collisions are rare but
        // we'd prefer a stable order over an arbitrary one.
        List<String> sorted = new ArrayList<>(botIdentifiers);
        sorted.sort(Comparator
                .comparingInt((String s) -> s.hashCode() & 0x7fffffff)
                .thenComparing(Comparator.naturalOrder()));

        // Defensive: detect duplicate identifiers after sort. A duplicate would
        // mean a single bot lands twice in the slicing window and another bot
        // is silently dropped from the result.
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw new IllegalArgumentException(
                        "Duplicate bot identifier in assignment input: " + sorted.get(i));
            }
        }

        // Slice the sorted identifier list into contiguous chunks per strategy.
        Map<String, StrategyId> assignment = new LinkedHashMap<>(botCount);
        int cursor = 0;
        for (int i = 0; i < ids.size(); i++) {
            StrategyId id = ids.get(i);
            int slotCount = target[i];
            for (int k = 0; k < slotCount; k++) {
                assignment.put(sorted.get(cursor++), id);
            }
        }
        // Invariant: every identifier is assigned. If not, the apportionment
        // math has a bug.
        if (cursor != botCount) {
            throw new IllegalStateException(
                    "Strategy assignment apportionment bug: assigned " + cursor
                            + " of " + botCount + " bots");
        }

        return assignment;
    }
}
