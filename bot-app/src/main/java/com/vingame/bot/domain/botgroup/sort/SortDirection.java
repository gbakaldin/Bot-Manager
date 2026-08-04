package com.vingame.bot.domain.botgroup.sort;

/**
 * Sort direction for the env-scoped bot-group filter (BOTGROUP_GAME_MANAGEMENT
 * Phase 4 / AD-11). The incoming {@code sortDir} string is resolved
 * case-insensitively; anything null, blank, or unrecognised defaults to
 * {@link #DESC} (lenient — only an unknown sort <em>key</em> is a 400, never an
 * unknown direction).
 */
public enum SortDirection {

    ASC,
    DESC;

    /**
     * Resolve a raw direction string case-insensitively. Null/blank/unrecognised
     * → {@link #DESC} (AD-11 default).
     */
    public static SortDirection resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return DESC;
        }
        for (SortDirection dir : values()) {
            if (dir.name().equalsIgnoreCase(raw.trim())) {
                return dir;
            }
        }
        return DESC;
    }
}
