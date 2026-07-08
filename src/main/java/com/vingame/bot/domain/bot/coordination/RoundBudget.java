package com.vingame.bot.domain.bot.coordination;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-round budget snapshot owned by {@link BetCoordinator}: the immutable
 * per-option targets and aggregate cap for one {@code sessionId}, plus the
 * mutable committed running totals accumulated as bots reserve stake.
 *
 * <p>See {@code docs/plans/BET_COORDINATION.md}, AD-3 / AD-4 / AD-5.
 *
 * <p><b>Thread-safety.</b> Instances are constructed and swapped into
 * {@code BetCoordinator.current} under the coordinator's {@code ReentrantLock},
 * and both {@link #commit(int, long)} and every read of the committed totals
 * happen under that same lock. This class holds no locking of its own — it is a
 * plain data holder whose mutation is package-private so only the coordinator
 * (holding the lock) can touch it. The {@code budget}, {@code cap} and
 * {@code sessionId} fields are effectively final per round; only
 * {@code committed}/{@code committedAggregate} change.
 */
final class RoundBudget {

    /** Session id this budget belongs to; {@code 0} is the "no active round" sentinel. */
    private final long sessionId;
    /** Aggregate per-round stake ceiling for the whole group/fleet. */
    private final long cap;
    /** Immutable per-option target budgets, {@code B(o) = floor(w(o)/W * cap)}. */
    private final Map<Integer, Long> budget;

    /** Mutable per-option committed stake; only mutated under the coordinator lock. */
    private final Map<Integer, Long> committed;
    /** Mutable committed aggregate across all options; only mutated under the lock. */
    private long committedAggregate;

    RoundBudget(long sessionId, long cap, Map<Integer, Long> budget) {
        this.sessionId = sessionId;
        this.cap = cap;
        this.budget = Map.copyOf(budget);
        this.committed = new HashMap<>();
        this.committedAggregate = 0L;
    }

    /**
     * Private copy constructor for {@link #withBudget(Map)}: same {@code sessionId}
     * and {@code cap}, a fresh immutable {@code budget}, but the committed running
     * totals carried over from {@code src} (a defensive copy so the source stays
     * independent).
     */
    private RoundBudget(RoundBudget src, Map<Integer, Long> newBudget) {
        this.sessionId = src.sessionId;
        this.cap = src.cap;
        this.budget = Map.copyOf(newBudget);
        this.committed = new HashMap<>(src.committed);
        this.committedAggregate = src.committedAggregate;
    }

    long sessionId() {
        return sessionId;
    }

    /** @return the immutable per-option target budget map. */
    Map<Integer, Long> budget() {
        return budget;
    }

    /**
     * Return a new {@link RoundBudget} for the same round with {@code newBudget}
     * replacing the per-option targets while <em>preserving</em> the committed
     * running totals ({@code committed}/{@code committedAggregate}). Used by the
     * coordinator's intra-round crowd recompute (AD-C9): the fleet's spend so far
     * this round is real and must survive a mid-round budget swap. Caller holds
     * the coordinator lock.
     */
    RoundBudget withBudget(Map<Integer, Long> newBudget) {
        return new RoundBudget(this, newBudget);
    }

    /** @return this option's target budget, or {@code 0} if the option is unknown. */
    long budgetOf(int optionId) {
        return budget.getOrDefault(optionId, 0L);
    }

    /** @return this option's committed stake so far, or {@code 0}. */
    long committedOf(int optionId) {
        return committed.getOrDefault(optionId, 0L);
    }

    long committedAggregate() {
        return committedAggregate;
    }

    /** Remaining headroom on this option: {@code B(o) - committed(o)}, never negative. */
    long remainingOption(int optionId) {
        return Math.max(0L, budgetOf(optionId) - committedOf(optionId));
    }

    /** Remaining headroom on the aggregate cap: {@code cap - committedAggregate}, never negative. */
    long remainingAggregate() {
        return Math.max(0L, cap - committedAggregate);
    }

    /** Commit {@code amount} to an option and the aggregate. Caller holds the coordinator lock. */
    void commit(int optionId, long amount) {
        committed.merge(optionId, amount, Long::sum);
        committedAggregate += amount;
    }
}
