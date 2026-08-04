package com.vingame.bot.infrastructure.observability;

/**
 * Per-game-type rendering behavior for the {@link SessionAggregationService}
 * (AGGREGATED_SESSION_LOGGING plan AD-4).
 * <p>
 * The service holds <b>no</b> game-type knowledge and never uses {@code instanceof};
 * each bot supplies its strategy (a per-{@code GameType} singleton) when it registers
 * feeds. Round-based games (betting-mini, Tai Xiu) share
 * {@link BettingSessionStrategy}; slot machines (Phase 3) will use a distinct
 * strategy with {@link #hasRoundBoundary()} returning {@code false}.
 * <p>
 * Implementations must be stateless singletons — all per-session state lives on the
 * {@link SessionAccumulator} passed in, so a single strategy instance serves every
 * live session of that game type.
 */
public interface SessionAggregationStrategy {

    /**
     * @return {@code true} for games with a shared StartGame/EndGame round clock
     *         (betting-mini, Tai Xiu); {@code false} for slot-style continuous play
     *         with no {@code sid} round boundary (Phase 3).
     */
    boolean hasRoundBoundary();

    /** Render the one-per-group StartGame session-entry line (INFO). */
    String renderStartLine(SessionAccumulator acc, SessionContext ctx);

    /** Render the per-5s UpdateBet running summary line (Phase 2, DEBUG). */
    String renderFlushLine(SessionAccumulator acc, SessionContext ctx);

    /** Render the one-per-session EndGame results summary line (INFO). */
    String renderEndLine(SessionAccumulator acc, SessionContext ctx);
}
