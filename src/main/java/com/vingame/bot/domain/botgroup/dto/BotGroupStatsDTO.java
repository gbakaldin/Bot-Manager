package com.vingame.bot.domain.botgroup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Group-level runtime statistics surfaced in the UI (BOTGROUP_GAME_MANAGEMENT
 * Phase 3). Embedded in {@code GET /{id}}, {@code GET /{id}/health}, and the
 * env-scoped filter list items via
 * {@link com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService#computeStats(String)}.
 * <p>
 * All fields are nullable boxed types; a {@code null} field renders as N/A in the
 * UI. A stopped / not-running group yields an all-null instance. Averages
 * ({@code averageBalance}, {@code averageWinning}) are computed over the group's
 * <em>active</em> ({@code isConnected()}) bots only and are {@code null} when the
 * group has zero active bots — never {@code 0} (AD-10, Implementation Note 5).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotGroupStatsDTO {

    /**
     * Rounds observed since the last start/restart — the MAX of the per-bot
     * {@code roundsObserved} counter across the group's bots (AD-9). Null when
     * the group is not running.
     */
    private Long roundsSinceRestart;

    /**
     * Seconds between the runtime's {@code startedAt} and now (AD-9). Null when
     * the group is not running.
     */
    private Long activeTimeSeconds;

    /**
     * Live count of active ({@code isConnected()}) bots (AD-10). Null when the
     * group is not running.
     */
    private Integer activeBots;

    /**
     * Mean expected balance over the active bots (AD-10). Null when the group is
     * not running or has zero active bots.
     */
    private Long averageBalance;

    /**
     * Mean cumulative winnings over the active bots (AD-8/AD-10). Null when the
     * group is not running or has zero active bots.
     */
    private Long averageWinning;
}
