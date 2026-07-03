package com.vingame.bot.domain.botgroup.sort;

import com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;

/**
 * Enriched sort row for the env-scoped bot-group filter (BOTGROUP_GAME_MANAGEMENT
 * Phase 4). Bundles the persisted {@link BotGroup} with the runtime-derived state
 * the sort keys read: the Phase 3 {@link BotGroupStatsDTO} (never null — a
 * not-running group yields an all-null-fields instance), the runtime
 * {@code actualStatus}, and the resolved {@code gameType} name (looked up once per
 * distinct gameId; null when the referenced game is missing).
 *
 * <p>The row carries the persisted {@code createdAt}/{@code updatedAt} and configured
 * fields (botCount, maxBet, maxTotalBetPerRound, name) via {@link #group()}, and the
 * runtime-only fields (balance, active bots, active time, avg winning) via
 * {@link #stats()}. See {@link BotSortKey} for the per-key extractors.
 */
public record BotGroupSortRow(
        BotGroup group,
        BotGroupStatsDTO stats,
        BotGroupStatus actualStatus,
        String gameType) {
}
