package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.GameState;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable, in-flight state for the currently active round. Cleared on
 * {@code StartGame}, accumulated as the bot sends bets, finalized into a
 * {@link RoundResult} on {@code EndGame}. Lives on {@link BotMemory}.
 *
 * <p><b>Thread-safety.</b> Mutators run under {@link BotMemory}'s intrinsic
 * lock on the netty message-processor thread ({@code onStartGame} /
 * {@code onEndGame}) and the scenario thread ({@code bet()} →
 * {@code recordBetSent}). Strategies on the scenario thread read individual
 * fields outside the BotMemory monitor via {@link BotMemory#getCurrentRound()},
 * so the primitive scalar fields here are declared {@code volatile} to
 * publish writes across threads without forcing every read site through the
 * monitor. The {@link #betsByOption} map is not volatile — strategies that
 * need a coherent snapshot of the bets go through
 * {@link BotMemory#snapshotCurrentRoundBets()} which holds the lock and
 * returns a defensive copy.
 *
 * <p>{@code sessionId == 0} is the "no active round" sentinel — matches the
 * existing {@code SessionIdStore} convention.
 */
public final class RoundState {

    // volatile: read by strategies (e.g. RandomBehaviorStrategy.decide) on the
    // scenario thread outside the BotMemory monitor; written by the netty
    // processor thread in beginRound (inside the monitor). A plain long read
    // has no happens-before edge to the write, which on a 32-bit JVM allows a
    // torn read and on any JVM allows visibility lag. Marking volatile gives a
    // single-word atomic read and a happens-before edge for the strategy's
    // sessionId-change check (see RandomBehaviorStrategy.decide:68).
    private volatile long sessionId;
    private volatile GameState phase;
    private volatile long remainingTimeMs;
    private final Map<Integer, Long> betsByOption = new HashMap<>();

    public RoundState() {
        this.sessionId = 0L;
        this.phase = null;
        this.remainingTimeMs = 0L;
    }

    public long getSessionId() {
        return sessionId;
    }

    void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public GameState getPhase() {
        return phase;
    }

    void setPhase(GameState phase) {
        this.phase = phase;
    }

    public long getRemainingTimeMs() {
        return remainingTimeMs;
    }

    void setRemainingTimeMs(long remainingTimeMs) {
        this.remainingTimeMs = remainingTimeMs;
    }

    /**
     * @return a read-only view of bets placed in this round, keyed by option id.
     *         Strategies must not mutate the returned map; {@link BotMemory}
     *         hands strategies a defensive copy.
     */
    public Map<Integer, Long> getBetsByOption() {
        return betsByOption;
    }

    void addBet(int optionId, long amount) {
        betsByOption.merge(optionId, amount, Long::sum);
    }

    /**
     * Reset to "no active round" before a new {@code StartGame} arrives.
     */
    void reset() {
        this.sessionId = 0L;
        this.phase = BettingMiniGameState.PAYOUT;
        this.remainingTimeMs = 0L;
        this.betsByOption.clear();
    }
}
