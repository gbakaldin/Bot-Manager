package com.vingame.bot.domain.game.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Filter body for the env-scoped game filter route
 * ({@code POST /api/v1/game/{brandCode}/{productCode}/{envId}/filter}).
 *
 * <p>{@code brandCode}/{@code productCode}/{@code envId} moved to the request
 * path (BOTGROUP_GAME_MANAGEMENT AD-4), so they are no longer body fields.
 *
 * <p>{@code sortBy}/{@code sortDir} (Phase 5) drive the in-memory sort applied
 * after the env-scoped load + aggregate enrichment. {@code sortBy} is resolved
 * case-insensitively against {@link com.vingame.bot.domain.game.sort.GameSortKey}
 * (null/blank → {@code CREATED_TIME}; unknown → 400); {@code sortDir} against
 * {@code asc}/{@code desc} (null/blank/unknown → {@code desc}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameFilter {

    private GameType gameType;
    private String name;
    private String sortBy;
    private String sortDir;
}
