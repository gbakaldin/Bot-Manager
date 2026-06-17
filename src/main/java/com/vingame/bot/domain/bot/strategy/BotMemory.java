package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.domain.game.model.Game;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Factual rolling history owned by the {@link com.vingame.bot.domain.bot.core.Bot}.
 * Populated by {@link com.vingame.bot.domain.bot.core.BettingMiniGameBot} from
 * incoming WS messages — never by strategies (strategies own interpretive state
 * privately).
 *
 * <p>Contents (Architecture Decision 3 in {@code docs/plans/BETTING_STRATEGIES.md}):
 * <ul>
 *   <li>{@link #lastResults} — last {@link #capacity} {@link RoundResult}s, FIFO bounded.</li>
 *   <li>{@link #currentRound} — in-flight {@link RoundState}; cleared on
 *       {@link #beginRound}, accumulated by {@link #recordBetSent}, finalized
 *       by {@link #completeRound}.</li>
 *   <li>{@link #globalRecentWins} — last {@link #capacity} winning options across
 *       all rounds the bot observed (whether it bet or not). Empty when v1
 *       can't extract {@code winningOption} from the EndGame payload.</li>
 *   <li>{@link #currentBalance} — mirrors {@code Bot.expectedCurrentBalance};
 *       snapshotted by {@link #beginRound}.</li>
 *   <li>{@link #game} — read-only handle for option-affinity / bet-window lookups.</li>
 * </ul>
 *
 * <p><b>Thread-safety.</b> Mutators ({@link #beginRound}, {@link #recordBetSent},
 * {@link #completeRound}, {@link #recordGlobalWin}) hold an intrinsic lock on
 * {@code this}. Writers are the netty message-processor thread
 * ({@code onStartGame} / {@code onEndGame}) and the scenario thread
 * ({@code bet()} → {@code recordBetSent}).
 *
 * <p>Strategy reads on the scenario thread are heterogeneous:
 * <ul>
 *   <li>Collections ({@link #lastResults}, {@link #globalRecentWins},
 *       {@link RoundState#getBetsByOption()}) are read via the
 *       {@code snapshot*} methods which hold the monitor and return immutable
 *       copies.</li>
 *   <li>Primitive scalar fields on {@link RoundState} ({@code sessionId},
 *       {@code phase}, {@code remainingTimeMs}) are declared {@code volatile}
 *       on {@code RoundState} itself so the scenario thread can read them
 *       directly via {@link #getCurrentRound()} without acquiring the monitor.
 *       This is the cheapest sound option for the per-tick decide() hot path,
 *       which only inspects the {@code sessionId} primitive (see
 *       {@code RandomBehaviorStrategy.decide}).</li>
 *   <li>{@link #currentBalance} is itself {@code volatile} for the same
 *       reason.</li>
 * </ul>
 *
 * <p>{@link #getCurrentRound()} returns the live {@link RoundState} reference,
 * not a defensive snapshot — strategies that need a coherent view of the bets
 * map must call {@link #snapshotCurrentRoundBets()} instead. The method is
 * declared {@code synchronized} historically for the reference read; the
 * effective happens-before edge on the returned object's primitive fields
 * comes from the {@code volatile} declarations on {@link RoundState}.
 */
@Slf4j
public final class BotMemory {

    /** Default rolling-buffer capacity per Architecture Decision 3. */
    public static final int DEFAULT_CAPACITY = 50;

    private final Game game;
    private final int capacity;
    private final Deque<RoundResult> lastResults;
    private final Deque<Integer> globalRecentWins;
    private final RoundState currentRound = new RoundState();
    private volatile long currentBalance;

    public BotMemory(Game game) {
        this(game, DEFAULT_CAPACITY);
    }

    public BotMemory(Game game, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        // Fail loud at construction if game is null — strategies dereferencing
        // ctx.game().getEffectiveOptionAffinities() would otherwise NPE much
        // later inside decide() on the scenario thread, with a misleading
        // stack trace far from the misconfiguration site.
        this.game = Objects.requireNonNull(game, "game");
        this.capacity = capacity;
        this.lastResults = new ArrayDeque<>(capacity);
        this.globalRecentWins = new ArrayDeque<>(capacity);
    }

    public Game getGame() {
        return game;
    }

    public long getCurrentBalance() {
        return currentBalance;
    }

    /**
     * @return the live in-flight {@link RoundState}. Reference is final and
     *         non-null. The returned object's primitive fields ({@code sessionId},
     *         {@code phase}, {@code remainingTimeMs}) are {@code volatile}, so
     *         single-field reads from any thread are safe without acquiring
     *         this monitor. Strategies that need a coherent snapshot of the
     *         bets map must call {@link #snapshotCurrentRoundBets()} instead —
     *         the live {@link RoundState#getBetsByOption()} is a plain
     *         {@link HashMap} mutated under this monitor.
     */
    public RoundState getCurrentRound() {
        return currentRound;
    }

    public synchronized Deque<RoundResult> getLastResults() {
        return lastResults;
    }

    public synchronized Deque<Integer> getGlobalRecentWins() {
        return globalRecentWins;
    }

    /**
     * Begin a new round: clear the in-flight {@link RoundState} and snapshot
     * the current balance. Called from {@code onStartGame}.
     */
    public synchronized void beginRound(long sessionId, long currentBalance) {
        currentRound.reset();
        currentRound.setSessionId(sessionId);
        this.currentBalance = currentBalance;
        log.debug("BotMemory.beginRound: sessionId={}, balance={}", sessionId, currentBalance);
    }

    /**
     * Accumulate a bet the bot just sent. Called from {@code bet()} after
     * {@code creditBalance}. {@code sessionId} is the bot's current
     * {@code sidStore} value at the moment the bet is sent; if it mismatches
     * the in-flight round (e.g. late tick straddling a session boundary), the
     * bet is dropped with a WARN — the round's result will then not include it.
     */
    public synchronized void recordBetSent(long sessionId, int optionId, long amount) {
        if (currentRound.getSessionId() == 0L || currentRound.getSessionId() != sessionId) {
            log.warn("BotMemory.recordBetSent: sessionId mismatch (bet sessionId={}, in-flight sessionId={}) — bet dropped from round accumulator",
                    sessionId, currentRound.getSessionId());
            return;
        }
        currentRound.addBet(optionId, amount);
    }

    /**
     * Finalize the in-flight round into a {@link RoundResult} and push onto
     * {@link #lastResults}. If {@code sessionId} does not match the in-flight
     * round, the in-flight round is discarded (logged WARN) and the EndGame is
     * still pushed as a result with empty {@code betsByOption} — strategies
     * that care about per-bot bets can detect "we didn't bet this round" via
     * the empty map; strategies that only care about the winning option (e.g.
     * trend-followers) still get the global event.
     *
     * @param sessionId      EndGame's session id (from {@code EndGameMessage.getSessionId()}).
     * @param winningOption  best-effort winning option (Optional.empty for v1
     *                       when no {@code HasWinningOption} marker is implemented).
     * @param payout         this bot's gross payout from {@code HasBotWinnings}.
     * @return the {@link RoundResult} pushed onto {@link #lastResults}.
     */
    public synchronized RoundResult completeRound(long sessionId, Optional<Integer> winningOption, long payout) {
        Map<Integer, Long> betsByOption;
        if (currentRound.getSessionId() != 0L && currentRound.getSessionId() == sessionId) {
            betsByOption = Map.copyOf(currentRound.getBetsByOption());
        } else {
            log.warn("BotMemory.completeRound: sessionId mismatch (EndGame sessionId={}, in-flight sessionId={}) — in-flight round discarded",
                    sessionId, currentRound.getSessionId());
            betsByOption = Map.of();
        }

        long staked = 0L;
        for (Long v : betsByOption.values()) staked += v;
        long balanceDelta = payout - staked;

        RoundResult result = new RoundResult(
                sessionId,
                winningOption,
                betsByOption,
                payout,
                balanceDelta,
                Instant.now());

        pushBounded(lastResults, result);
        currentRound.reset();
        log.debug("BotMemory.completeRound: sessionId={}, payout={}, staked={}, delta={}, lastResults.size={}",
                sessionId, payout, staked, balanceDelta, lastResults.size());
        return result;
    }

    /**
     * Record the winning option from any observed EndGame, independent of whether
     * this bot placed any bets. v1 callers typically pass through whatever they
     * can extract; an empty Optional is silently dropped.
     */
    public synchronized void recordGlobalWin(Optional<Integer> winningOption) {
        if (winningOption.isEmpty()) return;
        pushBounded(globalRecentWins, winningOption.get());
    }

    /**
     * Mirror an external balance change (e.g. after a deposit). The bot's own
     * {@code expectedCurrentBalance} stays authoritative — this exists so a
     * strategy reading {@code currentBalance} between rounds is not stale.
     */
    public void setCurrentBalance(long currentBalance) {
        this.currentBalance = currentBalance;
    }

    /** Defensive snapshot for strategy reads on the scenario thread. */
    public synchronized List<RoundResult> snapshotLastResults() {
        return List.copyOf(lastResults);
    }

    /** Defensive snapshot for strategy reads on the scenario thread. */
    public synchronized List<Integer> snapshotGlobalRecentWins() {
        return List.copyOf(globalRecentWins);
    }

    /** Defensive snapshot of the in-flight round (immutable bets map copy). */
    public synchronized Map<Integer, Long> snapshotCurrentRoundBets() {
        return new HashMap<>(currentRound.getBetsByOption());
    }

    public int getCapacity() {
        return capacity;
    }

    private <T> void pushBounded(Deque<T> deque, T value) {
        deque.addLast(value);
        while (deque.size() > capacity) {
            deque.pollFirst();
        }
    }
}
