package com.vingame.bot.infrastructure.observability;

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
        // Tumbling-window delta: spins staked since the previous 5s tick. Total
        // staked / total win / jackpot hits are cumulative over the window life
        // (AD-12); only "spins since last" resets each flush via advanceSpinBaseline.
        long spinsSinceLast = acc.betEventCount() - acc.spinBaseline();
        return "SlotWindow " + ctx.gameName() + "/" + ctx.botGroupId()
                + " #" + acc.flushSeq()
                + " | spins since last: " + spinsSinceLast
                + " | total staked: " + acc.totalStaked()
                + " | total win: " + acc.winningsTotal()
                + " | jackpot hits: " + acc.jackpotHits();
    }

    /** Slots have no EndGame lifecycle (AD-12) — never invoked. */
    @Override
    public String renderEndLine(SessionAccumulator acc, SessionContext ctx) {
        return "SlotWindow " + ctx.gameName() + "/" + ctx.botGroupId() + " closed";
    }
}
