package com.vingame.bot.infrastructure.observability;

/**
 * {@link SessionAggregationStrategy} for round-based games — betting-mini and
 * Tai Xiu (AGGREGATED_SESSION_LOGGING plan AD-4). Both game types share this one
 * strategy: Tai Xiu differs only in its message/CMD layer and rides
 * {@code BettingMiniGameBot}'s round behavior wholesale, so the same StartGame /
 * EndGame summary shape applies. Any per-game field differences come from the
 * accumulator's numeric values, not from the strategy.
 * <p>
 * Stateless singleton — see {@link #INSTANCE}. The group display name is not in
 * MDC, so lines use {@code gameName} + {@code botGroupId} (plan note).
 */
public final class BettingSessionStrategy implements SessionAggregationStrategy {

    /** Shared per-{@code GameType} singleton (AD-4). */
    public static final BettingSessionStrategy INSTANCE = new BettingSessionStrategy();

    private BettingSessionStrategy() {
    }

    @Override
    public boolean hasRoundBoundary() {
        return true;
    }

    @Override
    public String renderStartLine(SessionAccumulator acc, SessionContext ctx) {
        return "BotGroup " + ctx.gameName() + "/" + ctx.botGroupId()
                + " entered session " + ctx.sid();
    }

    @Override
    public String renderFlushLine(SessionAccumulator acc, SessionContext ctx) {
        // Phase 2 (5s UpdateBet flush). Rendered from the flush snapshot captured
        // once before this call (lost-update fix), so the delta below and the caller's
        // baseline advance use identical counter values — an arrival mid-flush lands
        // in the next tick's delta rather than vanishing from both.
        int newBettors = acc.flushBettorSnapshot() - acc.bettorBaseline();
        return "UpdateBet #" + acc.flushSeq()
                + " | new bettors since last: " + newBettors
                + " | total bettors this round: " + acc.flushBettorSnapshot()
                + " | total staked: " + acc.flushStakedSnapshot();
    }

    @Override
    public String renderEndLine(SessionAccumulator acc, SessionContext ctx) {
        // Totals are "as of first close" (Implementation Notes): the first bot to
        // observe EndGame logs; the metrics counters remain the authoritative totals.
        return "BotGroup " + ctx.gameName() + "/" + ctx.botGroupId()
                + " session " + ctx.sid() + " ended"
                + " | total staked: " + acc.totalStaked()
                + " | total win: " + acc.winningsTotal()
                + " | bettors: " + acc.bettorCount()
                + " | confirmed staked: " + acc.confirmedBetTotal();
    }
}
