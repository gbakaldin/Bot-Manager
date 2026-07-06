package com.vingame.bot.infrastructure.observability;

import java.util.Map;

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
        // STRATEGY_DECISION_AGGREGATION Phase 1 (AD-5): fold the per-bot strategy
        // decision into this same line — the tumbling option histogram and the window's
        // amount min/avg/max. avg = windowStaked / windowBets, both read as deltas
        // against the pre-advance baselines (identical to "new bettors since last"), so
        // no new baseline is needed. total staked is unchanged and NOT re-emitted.
        long windowBets = acc.flushSpinSnapshot() - acc.spinBaseline();
        long windowStaked = acc.flushStakedSnapshot() - acc.stakedBaseline();
        return "UpdateBet #" + acc.flushSeq()
                + " | new bettors since last: " + newBettors
                + " | total bettors this round: " + acc.flushBettorSnapshot()
                + " | total staked: " + acc.flushStakedSnapshot()
                + " | options: " + renderOptionHistogram(acc.flushOptionSnapshot())
                + " | amount min/avg/max: " + renderAmountSummary(acc, windowBets, windowStaked);
    }

    /**
     * Render the tumbling option histogram compactly and deterministically, e.g.
     * {@code [0]x2 [1]x1 [5]x3} (already sorted by option id — the snapshot is a
     * {@code TreeMap}). Renders {@code -} for a window with no option-bearing bets
     * (e.g. a flush landing entirely in the PAYOUT phase).
     */
    private static String renderOptionHistogram(Map<Integer, Long> histogram) {
        if (histogram.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Long> e : histogram.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('[').append(e.getKey()).append("]x").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Render {@code min/avg/max} over the window, or {@code -} for a zero-bet window
     * (avoids a divide-by-zero on avg and a meaningless identity min/max).
     */
    private static String renderAmountSummary(SessionAccumulator acc, long windowBets, long windowStaked) {
        if (windowBets <= 0) {
            return "-";
        }
        long avg = windowStaked / windowBets;
        return acc.flushMinSnapshot() + "/" + avg + "/" + acc.flushMaxSnapshot();
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
