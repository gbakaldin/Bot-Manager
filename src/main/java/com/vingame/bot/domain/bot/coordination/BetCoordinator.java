package com.vingame.bot.domain.bot.coordination;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Central, in-memory, scope-agnostic bet coordinator. Each bot's strategy
 * proposes a bet {@code (optionId, amount)} for the in-flight round; the
 * coordinator returns APPROVE / TRIM / REJECT so the fleet's <em>realized
 * aggregate</em> stake stays under a per-round aggregate cap and near a target
 * distribution derived from the game's option affinities. It steers by
 * suppression / trim only — it never manufactures bets.
 *
 * <p>See {@code docs/plans/BET_COORDINATION.md}. This class is deliberately
 * Spring-free and carries no {@code BotGroup}/{@code BotGroupRuntime}
 * dependency (AD-8): the group wiring builds one from {@code BotGroup} fields;
 * a future Fleet builds one identically from fleet-level fields and owns it.
 *
 * <p><b>Concurrency (AD-3).</b> A single {@link ReentrantLock} guards a
 * {@code volatile} {@link RoundBudget current}. {@code reserve} runs on the
 * scenario thread; {@code onRound}/{@code onRoundComplete} on the netty
 * message-processor thread. All mutation of the committed totals and every DTO
 * read go through the lock, so readers never see a torn view. {@code ReentrantLock}
 * (unlike {@code synchronized}) does not pin virtual threads.
 */
@Slf4j
public final class BetCoordinator {

    private final Map<Integer, Integer> optionAffinities;
    private final long maxAggregateStakePerRound;
    private final long minBet;
    private final long betIncrement;

    /** Round-independent per-option target budgets: {@code floor(w(o)/W * cap)}. */
    private final Map<Integer, Long> targetBudget;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile RoundBudget current;

    // Cumulative decision counters since construction (mirroring roundsObserved),
    // read by the health DTO. AtomicLong so the read path never needs the lock
    // for a single counter; they are still incremented under the lock so a
    // full snapshot() reads a coherent view.
    private final AtomicLong approveCount = new AtomicLong();
    private final AtomicLong trimCount = new AtomicLong();
    private final AtomicLong rejectCount = new AtomicLong();

    /**
     * @param optionAffinities          option id → weight; typically
     *                                  {@code Game.getEffectiveOptionAffinities()}.
     *                                  The key set is the authoritative N-option set.
     * @param maxAggregateStakePerRound aggregate per-round stake ceiling for the
     *                                  whole group/fleet.
     * @param minBet                    group-uniform minimum bet (grid floor).
     * @param betIncrement              group-uniform bet grid step.
     */
    public BetCoordinator(Map<Integer, Integer> optionAffinities,
                          long maxAggregateStakePerRound,
                          long minBet,
                          long betIncrement) {
        // Preserve the affinity iteration order (insertion order of the source
        // map) so the health DTO's option list is stable; Map.copyOf would not.
        this.optionAffinities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(optionAffinities));
        this.maxAggregateStakePerRound = maxAggregateStakePerRound;
        this.minBet = minBet;
        this.betIncrement = betIncrement;
        this.targetBudget = computeTargetBudget(this.optionAffinities, maxAggregateStakePerRound);
        // Sentinel budget for "no active round" — sessionId 0, empty targets.
        this.current = new RoundBudget(0L, maxAggregateStakePerRound, Map.of());
    }

    /**
     * Per-option budget {@code B(o) = floor(w(o)/W * cap)} (AD-5). Long math;
     * rounding slack falls under the aggregate cap, which remains the hard
     * total ceiling.
     */
    private static Map<Integer, Long> computeTargetBudget(Map<Integer, Integer> affinities, long cap) {
        long totalWeight = 0L;
        for (Integer w : affinities.values()) {
            if (w != null) totalWeight += w;
        }
        Map<Integer, Long> budget = new LinkedHashMap<>(affinities.size());
        for (Map.Entry<Integer, Integer> e : affinities.entrySet()) {
            long weight = e.getValue() == null ? 0L : e.getValue();
            long b = totalWeight <= 0L ? 0L : weight * cap / totalWeight;
            budget.put(e.getKey(), b);
        }
        return budget;
    }

    /**
     * Begin (or re-affirm) the round for {@code sessionId}. First-seen
     * idempotent across the group's bots (AD-4): under the lock, if
     * {@code sessionId} differs from the current round, swap in a fresh
     * {@link RoundBudget}; otherwise (the other N−1 bots seeing the same
     * {@code sid}) it is a no-op. The {@code 0} sentinel never starts a budget.
     */
    public void onRound(long sessionId) {
        if (sessionId == 0L) {
            return;
        }
        lock.lock();
        try {
            if (sessionId != current.sessionId()) {
                current = new RoundBudget(sessionId, maxAggregateStakePerRound, targetBudget);
                log.trace("BetCoordinator.onRound: new round sid={}, cap={}, options={}",
                        sessionId, maxAggregateStakePerRound, targetBudget);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserve stake for a proposal against the in-flight round (AD-5). Rejects a
     * {@code 0} sentinel or a {@code sessionId} that does not match the current
     * round (stale/straddling tick). Otherwise clamps to the option and
     * aggregate headroom, grid-aligns <em>down</em> to {@code minBet + k·betIncrement}
     * (never up — trimming up could breach the cap), and commits the aligned
     * amount if it is at least {@code minBet}.
     *
     * @return APPROVE when the full proposed amount is committed, TRIM when a
     *         smaller grid-aligned amount is committed, REJECT when nothing is.
     */
    public ReservationOutcome reserve(long sessionId, int optionId, long amount) {
        lock.lock();
        try {
            RoundBudget b = current;
            if (sessionId == 0L || sessionId != b.sessionId()) {
                rejectCount.incrementAndGet();
                return ReservationOutcome.reject();
            }

            long allow = Math.min(amount, Math.min(b.remainingOption(optionId), b.remainingAggregate()));
            long aligned = gridAlignDown(allow);
            if (aligned < minBet) {
                rejectCount.incrementAndGet();
                return ReservationOutcome.reject();
            }

            b.commit(optionId, aligned);
            if (aligned == amount) {
                approveCount.incrementAndGet();
                return ReservationOutcome.approve(aligned);
            }
            trimCount.incrementAndGet();
            return ReservationOutcome.trim(aligned);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Grid-align {@code allow} DOWN to {@code minBet + k·betIncrement} (AD-5
     * step 3). Returns {@code 0} when {@code allow < minBet}. A non-positive
     * increment degrades to "no grid" (any value ≥ minBet is acceptable).
     */
    private long gridAlignDown(long allow) {
        if (allow < minBet) {
            return 0L;
        }
        if (betIncrement <= 0L) {
            return allow;
        }
        long steps = (allow - minBet) / betIncrement;
        return minBet + steps * betIncrement;
    }

    /**
     * Snapshot the finished round for observability and emit the one-per-round
     * DEBUG summary — realized-vs-target histogram + cumulative approve / trim /
     * reject counts (AD-6). No-op logging when there is no active round.
     */
    public void onRoundComplete(long sessionId) {
        long completedSid;
        long committedAggregate;
        List<String> histogram;
        lock.lock();
        try {
            RoundBudget b = current;
            if (b.sessionId() == 0L) {
                return;
            }
            completedSid = b.sessionId();
            committedAggregate = b.committedAggregate();
            histogram = optionAffinities.keySet().stream()
                    .sorted()
                    .map(o -> "[opt=" + o + " target=" + b.budgetOf(o) + " realized=" + b.committedOf(o) + "]")
                    .collect(Collectors.toList());
        } finally {
            lock.unlock();
        }
        log.debug("Coordination sid={} realized={}/{} target-histogram {} approve={} trim={} reject={}",
                completedSid, committedAggregate, maxAggregateStakePerRound,
                String.join(" ", histogram),
                approveCount.get(), trimCount.get(), rejectCount.get());
    }

    // --- Read accessors for the health DTO (Phase 4). ---------------------

    public long getMaxAggregateStakePerRound() {
        return maxAggregateStakePerRound;
    }

    public long getApproveCount() {
        return approveCount.get();
    }

    public long getTrimCount() {
        return trimCount.get();
    }

    public long getRejectCount() {
        return rejectCount.get();
    }

    /**
     * @return the current committed aggregate stake, read under the lock so it
     *         is never torn against a concurrent {@code reserve}.
     */
    public long getCurrentAggregateStake() {
        lock.lock();
        try {
            return current.committedAggregate();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Coherent snapshot of the coordinator's live state for the health DTO,
     * read under a single lock acquisition to avoid a torn view (AD-3). The
     * per-option list follows the affinity key set; {@code targetBudget} is
     * round-independent, {@code committedStake} reflects the in-flight round.
     */
    public Snapshot snapshot() {
        lock.lock();
        try {
            RoundBudget b = current;
            List<OptionSnapshot> options = new ArrayList<>(optionAffinities.size());
            for (Map.Entry<Integer, Integer> e : optionAffinities.entrySet()) {
                int optionId = e.getKey();
                int weight = e.getValue() == null ? 0 : e.getValue();
                options.add(new OptionSnapshot(
                        optionId,
                        weight,
                        targetBudget.getOrDefault(optionId, 0L),
                        b.committedOf(optionId)));
            }
            return new Snapshot(
                    maxAggregateStakePerRound,
                    b.committedAggregate(),
                    approveCount.get(),
                    trimCount.get(),
                    rejectCount.get(),
                    List.copyOf(options));
        } finally {
            lock.unlock();
        }
    }

    /** Per-option view for the health DTO. */
    public record OptionSnapshot(int optionId, int weight, long targetBudget, long committedStake) {
    }

    /** Coherent whole-coordinator view for the health DTO. */
    public record Snapshot(long maxAggregateStakePerRound,
                           long currentAggregateStake,
                           long approveCount,
                           long trimCount,
                           long rejectCount,
                           List<OptionSnapshot> options) {
    }
}
