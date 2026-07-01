package com.vingame.bot.infrastructure.observability;

import java.util.Map;

/**
 * {@link SessionAggregationStrategy} for slot machines (AGGREGATED_SESSION_LOGGING
 * plan AD-4/AD-12). Slots are spin-based ({@code cmd:1302}) with <b>no shared
 * {@code sid} round</b> and no StartGame/EndGame ({@code SlotMachineBot} lines
 * 44-46), so {@link #hasRoundBoundary()} returns {@code false} and there is no
 * session lifecycle to log — only the periodic window summary.
 * <p>
 * <b>Synthetic window (AD-12, resolved Open Item).</b> Because there is no server
 * session id, all spins for a {@code (botGroupId, gameId)} fold into ONE long-lived
 * rolling accumulator keyed by {@link SessionAggregationService#SLOT_WINDOW_SID}.
 * The single app-wide 5s flush renders one window summary per {@code (group, gameId)}
 * — {@code spins since last | total staked | total win | jackpot hits} — and resets
 * the {@code spins since last} baseline each tick (total staked / total win stay
 * cumulative over the window's life). The window is evicted by the TTL sweep (60s
 * idle — a group that stopped spinning) or by {@code evictGroup} on group stop; it
 * never marks {@code ended}, so grace-then-evict does not apply. This is the plan's
 * recommended keying: a single per-group accumulator over a fixed 5s tumbling window
 * (aligned to the flush), which keeps the map bounded at one entry per live slot
 * group and gives a stable, meaningful "spins/5s" throughput read.
 * <p>
 * Stateless singleton — see {@link #INSTANCE}. All per-window state lives on the
 * {@link SessionAccumulator}. The group display name is not in MDC, so lines use
 * {@code gameName} + {@code botGroupId} (plan note).
 */
public final class SlotSessionStrategy implements SessionAggregationStrategy {

    /** Shared per-{@code GameType} singleton (AD-4). */
    public static final SlotSessionStrategy INSTANCE = new SlotSessionStrategy();

    private SlotSessionStrategy() {
    }

    @Override
    public boolean hasRoundBoundary() {
        return false;
    }

    /**
     * Slots have no StartGame lifecycle (AD-12); the window is created lazily on the
     * first spin feed and never logs an entry line. Retained only to satisfy the
     * strategy contract — never invoked for slots (the bot calls neither
     * {@code onSessionStart} nor {@code onSessionEnd}).
     */
    @Override
    public String renderStartLine(SessionAccumulator acc, SessionContext ctx) {
        return "SlotWindow " + ctx.gameName() + "/" + ctx.botGroupId() + " opened";
    }

    @Override
    public String renderFlushLine(SessionAccumulator acc, SessionContext ctx) {
        // Tumbling-window delta: spins staked since the previous 5s tick. Rendered
        // from the flush snapshot captured once before this call (lost-update fix) so
        // the delta and the caller's baseline advance use identical values; a spin
        // arriving mid-flush lands in the next tick's delta rather than vanishing.
        // Total staked / total win / jackpot hits are cumulative over the window life
        // (AD-12); only "spins since last" resets each flush via advanceSpinBaseline.
        long spinsSinceLast = acc.flushSpinSnapshot() - acc.spinBaseline();
        // STRATEGY_DECISION_AGGREGATION Phase 2 (AD-6): fold the per-spin slot decision
        // into this same line — the tumbling bet-size histogram (keyed on the per-line
        // bet) and the window's amount min/avg/max over the total stake. Both read the
        // same window deltas the spins-since-last count uses, so no new baseline is
        // needed; a fixed-bet slot collapses to a single [500]xN bucket, which is
        // correct and not special-cased. Total staked/win/jackpot are cumulative
        // (unchanged); only the histogram + min/max reset each window via the snapshot.
        long windowStaked = acc.flushStakedSnapshot() - acc.stakedBaseline();
        return "SlotWindow " + ctx.gameName() + "/" + ctx.botGroupId()
                + " #" + acc.flushSeq()
                + " | spins since last: " + spinsSinceLast
                + " | total staked: " + acc.flushStakedSnapshot()
                + " | total win: " + acc.winningsTotal()
                + " | jackpot hits: " + acc.jackpotHits()
                + " | bets: " + renderBetHistogram(acc.flushOptionSnapshot())
                + " | amount min/avg/max: " + renderAmountSummary(acc, spinsSinceLast, windowStaked);
    }

    /**
     * Render the tumbling bet-size histogram compactly and deterministically, e.g.
     * {@code [100]x2 [500]x1} (already sorted by bet value — the snapshot is a
     * {@code TreeMap}). Renders {@code -} for a window with no spins. Mirrors the
     * betting option histogram (STRATEGY_DECISION_AGGREGATION Phase 1) so both flush
     * lines share one greppable shape.
     */
    private static String renderBetHistogram(Map<Integer, Long> histogram) {
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
     * Render {@code min/avg/max} of the per-spin total stake over the window, or
     * {@code -} for a zero-spin window (avoids a divide-by-zero on avg and a
     * meaningless identity min/max). Consistent with the betting flush line's amount
     * summary (STRATEGY_DECISION_AGGREGATION Phase 1).
     */
    private static String renderAmountSummary(SessionAccumulator acc, long windowSpins, long windowStaked) {
        if (windowSpins <= 0) {
            return "-";
        }
        long avg = windowStaked / windowSpins;
        return acc.flushMinSnapshot() + "/" + avg + "/" + acc.flushMaxSnapshot();
    }

    /** Slots have no EndGame lifecycle (AD-12) — never invoked. */
    @Override
    public String renderEndLine(SessionAccumulator acc, SessionContext ctx) {
        return "SlotWindow " + ctx.gameName() + "/" + ctx.botGroupId() + " closed";
    }
}
