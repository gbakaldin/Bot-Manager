package com.vingame.bot.domain.botgroup.sort;

import java.util.Comparator;
import java.util.List;

/**
 * In-memory sort for the env-scoped bot-group filter (BOTGROUP_GAME_MANAGEMENT
 * Phase 4 / AD-11 / AD-12).
 *
 * <p>The comparator (AD-12) is deterministic:
 * <ol>
 *   <li>present values are ordered by {@code sortDir};</li>
 *   <li>N/A (null-extracted) values always sort to the <b>bottom</b>, regardless of
 *       direction;</li>
 *   <li>ties and the N/A block break by {@code NAME} ascending (stable secondary),
 *       then by {@code id} ascending.</li>
 * </ol>
 */
public final class BotGroupSorter {

    private BotGroupSorter() {
    }

    /**
     * Sort the enriched rows by the resolved key/direction (AD-11/AD-12).
     *
     * @param rows    enriched rows (not mutated)
     * @param sortBy  raw sort-key string; null/blank → {@code CREATED_TIME}, unknown → 400
     * @param sortDir raw direction string; null/blank/unknown → {@code desc}
     * @return a new sorted list
     */
    public static List<BotGroupSortRow> sort(List<BotGroupSortRow> rows, String sortBy, String sortDir) {
        BotSortKey key = BotSortKey.resolve(sortBy);
        SortDirection direction = SortDirection.resolve(sortDir);
        return rows.stream().sorted(comparator(key, direction)).toList();
    }

    /**
     * Build the AD-12 comparator for a resolved key/direction. Package-visible so
     * the comparator can be unit-tested directly.
     */
    static Comparator<BotGroupSortRow> comparator(BotSortKey key, SortDirection direction) {
        Comparator<BotGroupSortRow> primary =
                (a, b) -> compareNaLast(key.extract(a), key.extract(b), direction);
        return primary
                .thenComparing(row -> row.group().getName(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> row.group().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Null-last, direction-aware value compare. Nulls (N/A) always sort after
     * present values regardless of {@code direction}; present values are ordered
     * ascending then negated for {@code DESC}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNaLast(Comparable a, Comparable b, SortDirection direction) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;   // a is N/A → after b
        }
        if (b == null) {
            return -1;  // b is N/A → after a
        }
        int cmp = a.compareTo(b);
        return direction == SortDirection.DESC ? -cmp : cmp;
    }
}
