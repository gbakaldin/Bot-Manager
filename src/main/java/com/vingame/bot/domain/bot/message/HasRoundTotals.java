package com.vingame.bot.domain.bot.message;

/**
 * Marker for {@link EndGameMessage} subtypes exposing round-wide aggregates
 * (sum of all players' bets and winnings for the just-completed round).
 * <p>
 * Drives the Phase 5 game-aggregate counters
 * {@code game_total_bet_amount_total} and {@code game_total_winnings_total},
 * which carry only the {@code gameType} tag (Architecture Decision 5 in
 * {@code docs/plans/OBSERVABILITY.md}).
 * <p>
 * Dispatched from {@link com.vingame.bot.domain.bot.core.BettingMiniGameBot}'s
 * {@code onEndGame} via an {@code instanceof} branch; see
 * {@code docs/plans/ENDGAME_METRICS.md} AD-2 / AD-5.
 */
public interface HasRoundTotals {

    /** Sum of all players' bets for the just-completed round. */
    long totalBetAmount();

    /** Sum of all players' winnings for the just-completed round. */
    long totalWinnings();
}
