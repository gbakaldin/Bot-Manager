package com.vingame.bot.domain.game.sort;

import com.vingame.bot.domain.game.model.Game;

/**
 * Enriched sort row for the env-scoped game filter (BOTGROUP_GAME_MANAGEMENT
 * Phase 5). Bundles the persisted {@link Game} with the aggregates computed over
 * the bot groups referencing it (via {@code BotGroupRepository.findByGameId}, keyed
 * on the Game Mongo {@code _id}):
 * <ul>
 *   <li>{@code botGroupCount} — number of referencing groups (never N/A, 0 when none);</li>
 *   <li>{@code botCount} — Σ configured {@code botCount} (never N/A);</li>
 *   <li>{@code activeGroupCount} — Σ running groups;</li>
 *   <li>{@code activeBotCount} — Σ running bots across those groups.</li>
 * </ul>
 *
 * <p>A game is considered <b>active</b> when at least one referencing group is
 * running ({@code activeGroupCount > 0}). The {@code ACTIVE_GROUP_COUNT} /
 * {@code ACTIVE_BOT_COUNT} sort keys are N/A (sort to the bottom, AD-12) for an
 * inactive game. See {@link GameSortKey} for the per-key extractors.
 */
public record GameSortRow(
        Game game,
        int botGroupCount,
        int botCount,
        int activeGroupCount,
        int activeBotCount) {

    /** A game is "active" when at least one referencing bot group is running. */
    public boolean active() {
        return activeGroupCount > 0;
    }
}
