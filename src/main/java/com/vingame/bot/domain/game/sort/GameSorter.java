package com.vingame.bot.domain.game.sort;

import com.vingame.bot.domain.botgroup.sort.SortComparators;
import com.vingame.bot.domain.botgroup.sort.SortDirection;

import java.util.Comparator;
import java.util.List;

/**
 * In-memory sort for the env-scoped game filter (BOTGROUP_GAME_MANAGEMENT
 * Phase 5 / AD-11 / AD-12). Mirrors the Phase 4
 * {@link com.vingame.bot.domain.botgroup.sort.BotGroupSorter}, reusing
 * {@link SortDirection} and the shared {@link SortComparators} N/A-to-bottom rule.
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
public final class GameSorter {

    private GameSorter() {
    }

    /**
     * Sort the enriched rows by the resolved key/direction (AD-11/AD-12).
     *
     * @param rows    enriched rows (not mutated)
     * @param sortBy  raw sort-key string; null/blank → {@code CREATED_TIME}, unknown → 400
     * @param sortDir raw direction string; null/blank/unknown → {@code desc}
     * @return a new sorted list
     */
    public static List<GameSortRow> sort(List<GameSortRow> rows, String sortBy, String sortDir) {
        GameSortKey key = GameSortKey.resolve(sortBy);
        SortDirection direction = SortDirection.resolve(sortDir);
        return rows.stream().sorted(comparator(key, direction)).toList();
    }

    /**
     * Build the AD-12 comparator for a resolved key/direction. Package-visible so
     * the comparator can be unit-tested directly.
     */
    static Comparator<GameSortRow> comparator(GameSortKey key, SortDirection direction) {
        Comparator<GameSortRow> primary =
                (a, b) -> SortComparators.compareNaLast(key.extract(a), key.extract(b), direction);
        return primary
                .thenComparing(row -> row.game().getName(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> row.game().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
