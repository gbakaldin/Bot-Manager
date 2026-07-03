package com.vingame.bot.domain.botgroup.sort;

/**
 * Shared null-last, direction-aware value compare for the in-memory sort surfaces
 * (BOTGROUP_GAME_MANAGEMENT AD-12). Extracted so the bot-group filter (Phase 4)
 * and the game filter (Phase 5) share one N/A-to-bottom rule rather than each
 * duplicating it.
 *
 * <p>Nulls (N/A) always sort <b>after</b> present values regardless of
 * {@code direction}; present values are ordered ascending then negated for
 * {@link SortDirection#DESC}.
 */
public final class SortComparators {

    private SortComparators() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compareNaLast(Comparable a, Comparable b, SortDirection direction) {
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
