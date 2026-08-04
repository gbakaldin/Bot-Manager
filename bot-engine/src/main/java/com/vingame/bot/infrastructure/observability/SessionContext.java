package com.vingame.bot.infrastructure.observability;

/**
 * Immutable render context for a single game session, handed to a
 * {@link SessionAggregationStrategy} when it formats a StartGame / UpdateBet /
 * EndGame summary line (AGGREGATED_SESSION_LOGGING plan AD-4/AD-5).
 * <p>
 * Carries the human-facing identity of the session. The group <b>display name</b>
 * is not in MDC (only the {@code botGroupId} UUID), so the summary lines use
 * {@code gameName} + {@code botGroupId} (plan "Group display name" note).
 *
 * @param botGroupId the group UUID (MDC {@code botGroupId})
 * @param gameName   the game display name (MDC {@code gameName})
 * @param sid        the session id for this round
 */
public record SessionContext(String botGroupId, String gameName, long sid) {
}
