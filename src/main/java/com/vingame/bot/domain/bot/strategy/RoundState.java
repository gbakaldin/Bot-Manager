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
 * <p>Not thread-safe on its own — all access goes through {@link BotMemory}'s
 * synchronization (see Architecture Decision 3 / 15).
 *
 * <p>{@code sessionId == 0} is the "no active round" sentinel — matches the
 * existing {@code SessionIdStore} convention.
 */
public final class RoundState {

    private long sessionId;
    private GameState phase;
    private long remainingTimeMs;
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
