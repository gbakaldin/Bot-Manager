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
 * ({@code sortBy}/{@code sortDir} are added in Phase 5.)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameFilter {

    private GameType gameType;
    private String name;
}
