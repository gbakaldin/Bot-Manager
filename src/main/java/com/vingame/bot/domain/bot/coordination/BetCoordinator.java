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

    /**
     * Crowd-tier gate (CROWD_AWARE_COORDINATION AD-C6). When {@code false} the
     * coordinator is byte-for-byte BET_COORDINATION: {@link #observeCrowd} is a
     * no-op and the per-round budget is always the internal-tier affinity split.
     * Phase 3 flips this from {@code BotGroup.crowdAwareCoordination}.
     */
    private final boolean crowdAware;

    /**
     * The game's per-game crowd count semantic (CROWD_AWARE_COORDINATION AD-C5),
     * carried as a {@code String} so the core stays Spring-/domain-free (it is a
     * game-intrinsic {@code CrowdCountSemantic} enum name at the wiring layer). It
     * is <b>never load-bearing</b> in the v1 budget math (steering is on {@code v}
     * alone, AD-C5); it is stored purely for the Phase 4 health snapshot (AD-C10).
     * {@code "UNKNOWN"} when unset.
     */
    private final String crowdCountSemantic;

    /** Round-independent per-option target budgets: {@code floor(w(o)/W * cap)}. */
    private final Map<Integer, Long> targetBudget;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile RoundBudget current;

    // Latest per-option crowd snapshot for the in-flight round (AD-C3). Keyed on
    // option id (eid). observeCrowd REPLACES this (bs is the running aggregate,
    // not a delta), then recomputes the round's budget. Mutated only under the
    // lock. crowdStake = v(o); crowdCount = bc(o) (observability only, AD-C5).
    // At round rollover (onRound) this is SEEDED from the last observed
    // distribution (the one-round-lagged prior, AD-C3) rather than cleared, so the
    // BOM/B52/Nohu EndGame `bs` steers the opening budget of the next round; fresh
    // intra-round observeCrowd calls (Tip) then overwrite it. When no crowd was
    // ever observed (or crowd-off) the seed is empty ⇒ X(o)=0 → internal tier.
    private final Map<Integer, Long> crowdStake = new LinkedHashMap<>();
    private final Map<Integer, Integer> crowdCount = new LinkedHashMap<>();

    // The last crowd distribution observed on ANY round (AD-C3 one-round-lag). Kept
    // across the onRound boundary so the next round's opening budget is seeded from
    // it — the BOM/B52/Nohu EndGame `bs` is their only crowd signal (no intra-round
    // `bs`), so it must survive rollover instead of being discarded. Empty until the
    // first observation; empty ⇒ the seed reduces to the internal tier. Mutated only
    // under the lock.
    private final Map<Integer, Long> lastObservedCrowdStake = new LinkedHashMap<>();
    private final Map<Integer, Integer> lastObservedCrowdCount = new LinkedHashMap<>();

    // Monotonic-by-arrival high-water mark (AD-C3): the largest Σv(o) accepted for
    // the in-flight round. A straggler frame whose aggregate is not greater than the
    // stored mark is ignored so a slow bot's older/smaller snapshot cannot overwrite
    // a newer/larger one (all N bots replay the same running-aggregate frames on
    // their own IO threads, possibly reordered). Reset per round in onRound. -1 = no
    // frame accepted yet for the current round (so the first frame always applies,
    // including an all-zero opening snapshot).
    private long currentCrowdSum = -1L;

    // First-seen guard for onRoundComplete (AD-6). onRoundComplete does not swap
    // current, so its N per-bot calls for one round would otherwise each emit the
    // per-round DEBUG summary. Track the last sid already finalized so only the
    // first bot to complete a given round logs; the rest are no-ops. Mutated only
    // under the lock. 0 = no round finalized yet (never collides with the sentinel).
    private long lastCompletedSessionId;

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
        this(optionAffinities, maxAggregateStakePerRound, minBet, betIncrement, false);
    }

    /**
     * Crowd-aware overload (CROWD_AWARE_COORDINATION Phase 2). Identical to the
     * 4-arg constructor except for the {@code crowdAware} gate (AD-C6): with
     * {@code crowdAware=false} the instance is byte-for-byte the internal-tier
     * coordinator and {@link #observeCrowd} is inert. The existing 4-arg call
     * sites are untouched (they delegate here with {@code crowdAware=false}).
     *
     * @param crowdAware when {@code true}, {@link #observeCrowd} recomputes the
     *                   per-round budget from the observed crowd distribution
     *                   (AD-C2); when {@code false}, the internal affinity split
     *                   is always used.
     */
    public BetCoordinator(Map<Integer, Integer> optionAffinities,
                          long maxAggregateStakePerRound,
                          long minBet,
                          long betIncrement,
                          boolean crowdAware) {
        this(optionAffinities, maxAggregateStakePerRound, minBet, betIncrement, crowdAware, "UNKNOWN");
    }

    /**
     * Full crowd-aware overload (CROWD_AWARE_COORDINATION Phase 3) carrying the
     * game's crowd count semantic for the Phase 4 health snapshot (AD-C10). The
     * semantic is observability-only and never enters the budget math (AD-C5) — a
     * wrong value cannot corrupt steering. The 5-arg overload delegates here with
     * {@code "UNKNOWN"}.
     *
     * @param crowdCountSemantic the {@code CrowdCountSemantic} enum name
     *                           ({@code BETS}/{@code PLAYERS}/{@code UNKNOWN});
     *                           {@code null} resolves to {@code "UNKNOWN"}.
     */
    public BetCoordinator(Map<Integer, Integer> optionAffinities,
                          long maxAggregateStakePerRound,
                          long minBet,
                          long betIncrement,
                          boolean crowdAware,
                          String crowdCountSemantic) {
        // Preserve the affinity iteration order (insertion order of the source
        // map) so the health DTO's option list is stable; Map.copyOf would not.
        this.optionAffinities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(optionAffinities));
        this.maxAggregateStakePerRound = maxAggregateStakePerRound;
        this.minBet = minBet;
        this.betIncrement = betIncrement;
        this.crowdAware = crowdAware;
        this.crowdCountSemantic = crowdCountSemantic != null ? crowdCountSemantic : "UNKNOWN";
        this.targetBudget = computeTargetBudget(this.optionAffinities, maxAggregateStakePerRound);
        // Sentinel budget for "no active round" — sessionId 0, empty targets.
        this.current = new RoundBudget(0L, maxAggregateStakePerRound, Map.of());
    }

    /** @return whether the crowd tier is enabled on this coordinator (AD-C6). */
    public boolean isCrowdAware() {
        return crowdAware;
    }

    /**
     * @return the game's crowd count semantic ({@code BETS}/{@code PLAYERS}/
     *         {@code UNKNOWN}) for the health snapshot (AD-C10); never load-bearing
     *         in the budget math (AD-C5).
     */
    public String getCrowdCountSemantic() {
        return crowdCountSemantic;
    }

    /**
     * Per-option budget {@code B(o) = floor(w(o)/W * cap)} (AD-5). Long math;
     * rounding slack falls under the aggregate cap, which remains the hard
     * total ceiling.
     *
     * <p>This is precisely the {@code X(o)=0 ∀o} special case of the crowd-aware
     * recompute (CROWD_AWARE_COORDINATION AD-C2): with no observed crowd,
     * {@code B_crowd(o) = floor(w(o)·(0+cap)/W) − 0 = floor(w(o)·cap/W) = B(o)}.
     * Both are routed through {@link #computeCrowdBudget} with an empty crowd map
     * so the crowd-off reduction is byte-for-byte the internal tier.
     */
    private static Map<Integer, Long> computeTargetBudget(Map<Integer, Integer> affinities, long cap) {
        return computeCrowdBudget(affinities, cap, Map.of());
    }

    /**
     * Crowd-adjusted per-option budget (CROWD_AWARE_COORDINATION AD-C2):
     * <pre>
     *   P(o)       = w(o)/W                               (affinity target share)
     *   X(o)       = pure crowd stake on o (crowdStake arg, already fleet-subtracted)
     *   X_total    = Σ X(o)
     *   B_crowd(o) = clamp( P(o)·(X_total + C) − X(o), 0, C )
     * </pre>
     * The combined-share term {@code P(o)·(X_total + C)} is evaluated with the
     * <em>same</em> integer floor-division ordering as the internal tier —
     * {@code weight * (X_total + cap) / totalWeight} — so that when
     * {@code X_total = X(o) = 0} it reduces bit-for-bit to
     * {@code weight * cap / totalWeight} (the internal split). The subtraction
     * and the {@code [0, cap]} clamp then apply. {@code cap} is {@code C}, the
     * hard per-round total ceiling.
     *
     * @param affinities  option id → weight (the P(o) source).
     * @param cap         the fleet aggregate cap {@code C}.
     * @param crowdStake  pure crowd stake {@code X(o)} per option; empty ⇒ all
     *                    zero ⇒ internal tier. Options absent from the map are
     *                    treated as {@code X=0}. Both loops iterate the affinity
     *                    key/entry set and look {@code crowdStake} up by key, so
     *                    any entry whose key is not an affinity option is inert
     *                    here (it enters neither {@code X_total} nor a per-option
     *                    budget) regardless of the caller. The caller
     *                    ({@link #observeCrowd}) also filters unknown eids, so the
     *                    defense is doubled.
     */
    private static Map<Integer, Long> computeCrowdBudget(Map<Integer, Integer> affinities,
                                                         long cap,
                                                         Map<Integer, Long> crowdStake) {
        long totalWeight = 0L;
        for (Integer w : affinities.values()) {
            if (w != null) totalWeight += w;
        }
        // X_total = Σ X(o) over the affinity option set only (unknown eids ignored).
        long crowdTotal = 0L;
        for (Integer o : affinities.keySet()) {
            long x = crowdStake.getOrDefault(o, 0L);
            if (x > 0L) crowdTotal += x;
        }
        long combinedPool = crowdTotal + cap; // X_total + C

        Map<Integer, Long> budget = new LinkedHashMap<>(affinities.size());
        for (Map.Entry<Integer, Integer> e : affinities.entrySet()) {
            long weight = e.getValue() == null ? 0L : e.getValue();
            // P(o)·(X_total + C) with the internal-tier floor ordering. When
            // crowdTotal == 0 this is exactly weight * cap / totalWeight = B(o).
            long combinedShare = totalWeight <= 0L ? 0L : weight * combinedPool / totalWeight;
            long x = crowdStake.getOrDefault(e.getKey(), 0L);
            if (x < 0L) x = 0L;
            long bCrowd = combinedShare - x;      // may be negative if crowd over-fills
            bCrowd = Math.max(0L, Math.min(bCrowd, cap)); // clamp to [0, C]
            budget.put(e.getKey(), bCrowd);
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
                // AD-C3 one-round-lag: seed the new round's crowd snapshot from the
                // LAST observed distribution rather than clearing it, so the prior
                // round's EndGame `bs` (the only crowd signal for BOM/B52/Nohu, which
                // have no intra-round `bs`) steers the opening budget. Fresh
                // intra-round observeCrowd calls (Tip) then overwrite this seed.
                // When nothing was ever observed (or crowd-off), the seed is empty ⇒
                // X(o)=0 → the internal-tier budget, byte-for-byte (reduction
                // identity preserved). The monotonic high-water mark resets per round.
                crowdStake.clear();
                crowdCount.clear();
                currentCrowdSum = -1L;
                Map<Integer, Long> seededBudget;
                if (crowdAware && !lastObservedCrowdStake.isEmpty()) {
                    crowdStake.putAll(lastObservedCrowdStake);
                    crowdCount.putAll(lastObservedCrowdCount);
                    // At round open committed(o)=0, so pure crowd X(o)=v(o) directly.
                    seededBudget = computeCrowdBudget(
                            optionAffinities, maxAggregateStakePerRound, crowdStake);
                } else {
                    seededBudget = targetBudget;
                }
                current = new RoundBudget(sessionId, maxAggregateStakePerRound, seededBudget);
                log.trace("BetCoordinator.onRound: new round sid={}, cap={}, options={}, crowdSeed={}",
                        sessionId, maxAggregateStakePerRound, seededBudget, crowdStake);
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
     * Fold an observed crowd distribution for the in-flight round into the
     * per-option budgets (CROWD_AWARE_COORDINATION AD-C2 / AD-C3 / AD-C9).
     *
     * <p>Under the coordinator lock:
     * <ul>
     *   <li><b>Gated (AD-C6):</b> a no-op when {@code crowdAware} is off — the
     *       internal tier is never disturbed.</li>
     *   <li><b>Stale sid dropped (AD-C3):</b> a no-op when {@code sessionId}
     *       does not match the in-flight round (mirrors {@code reserve}'s guard);
     *       the {@code 0} sentinel never observes.</li>
     *   <li><b>Replace, not accumulate:</b> the {@code bs} feed is the running
     *       aggregate, so the latest snapshot replaces the stored crowd map for
     *       this round. Unknown {@code eid}s (not in the affinity option set) are
     *       ignored defensively.</li>
     *   <li><b>Double-count avoidance (AD-C4):</b> pure crowd stake is
     *       {@code X(o) = max(0, v(o) − committed(o))} — the crowd {@code v(o)}
     *       already includes the fleet's own confirmed bets (the bots are
     *       subscribers), so the coordinator's own committed stake is subtracted.
     *       The Tip-only {@code b} field is NOT used (deferred, D3).</li>
     *   <li><b>Value-only steering (AD-C5):</b> the {@code count} ({@code bc}) is
     *       stored for observability but never enters the budget math.</li>
     *   <li>Recompute {@code B_crowd(o)} per AD-C2 and swap it into {@code current}
     *       via {@link RoundBudget#withBudget}, <b>preserving</b>
     *       {@code committed}/{@code committedAggregate} (AD-C9) so the fleet's
     *       spend so far this round is not lost on the mid-round budget swap.</li>
     * </ul>
     *
     * <p>First-seen idempotent across the group's N bots reporting the same frame:
     * same input → same recompute. {@code reserve} continues to run unchanged
     * against the now crowd-adjusted budgets.
     *
     * @param sessionId the round the observation belongs to.
     * @param options   the per-option crowd entries derived from {@code bs}.
     */
    public void observeCrowd(long sessionId, List<CrowdOption> options) {
        if (!crowdAware || sessionId == 0L || options == null) {
            return;
        }
        lock.lock();
        try {
            RoundBudget b = current;
            if (sessionId != b.sessionId()) {
                return; // stale / straddling frame — mirror reserve's sid guard
            }

            // Build the incoming snapshot from bs (running aggregate, not a delta),
            // filtering unknown eids defensively (AD-C notes).
            Map<Integer, Long> incomingStake = new LinkedHashMap<>();
            Map<Integer, Integer> incomingCount = new LinkedHashMap<>();
            long incomingSum = 0L;
            for (CrowdOption o : options) {
                if (o == null || !optionAffinities.containsKey(o.optionId())) {
                    continue;
                }
                long v = Math.max(0L, o.value());
                incomingStake.put(o.optionId(), v);
                incomingCount.put(o.optionId(), o.count());
                incomingSum += v;
            }

            // Monotonic-by-arrival (AD-C3): all N bots replay the same running-
            // aggregate frames on their own IO threads and may reorder; a straggler
            // frame whose aggregate is not greater than the high-water mark must not
            // overwrite a newer/larger snapshot. currentCrowdSum starts at -1 so the
            // first frame (even an all-zero opening snapshot, sum 0) always applies.
            if (incomingSum <= currentCrowdSum) {
                return;
            }
            currentCrowdSum = incomingSum;

            // Replace the crowd snapshot for this round with the newer frame.
            crowdStake.clear();
            crowdCount.clear();
            crowdStake.putAll(incomingStake);
            crowdCount.putAll(incomingCount);

            // Carry this distribution forward as the one-round-lagged prior (AD-C3):
            // the next onRound seeds its opening budget from it (the BOM/B52/Nohu
            // EndGame-lag path — their only crowd signal).
            lastObservedCrowdStake.clear();
            lastObservedCrowdCount.clear();
            lastObservedCrowdStake.putAll(incomingStake);
            lastObservedCrowdCount.putAll(incomingCount);

            // Isolate pure crowd X(o) = max(0, v(o) − committed(o)) (AD-C4).
            Map<Integer, Long> pureCrowd = new LinkedHashMap<>(crowdStake.size());
            for (Map.Entry<Integer, Long> e : crowdStake.entrySet()) {
                long x = e.getValue() - b.committedOf(e.getKey());
                pureCrowd.put(e.getKey(), Math.max(0L, x));
            }

            Map<Integer, Long> newBudget = computeCrowdBudget(
                    optionAffinities, maxAggregateStakePerRound, pureCrowd);
            current = b.withBudget(newBudget);

            if (log.isTraceEnabled()) {
                log.trace("BetCoordinator.observeCrowd: sid={} crowd={} pureCrowd={} adjustedBudget={}",
                        sessionId, crowdStake, pureCrowd, newBudget);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Snapshot the finished round for observability and emit the one-per-round
     * DEBUG summary — realized-vs-target histogram + cumulative approve / trim /
     * reject counts (AD-6). No-op logging when there is no active round.
     *
     * <p>First-seen idempotent across the group's bots (AD-6), mirroring
     * {@link #onRound(long)}: this is called from every bot's {@code onEndGame},
     * i.e. once per bot per round. Under the lock, only the first call for a given
     * round's sid emits the summary; the other N−1 bots' calls for the same sid
     * are a no-op, so the fleet logs one DEBUG line per group per round rather than
     * one per bot. Since {@code onRoundComplete} does not swap {@code current}, the
     * round being finalized is {@code current.sessionId()}; the {@code 0} sentinel
     * (no active round) never logs.
     */
    public void onRoundComplete(long sessionId) {
        long completedSid;
        long committedAggregate;
        List<String> histogram;
        List<String> crowdHistogram = List.of();
        lock.lock();
        try {
            RoundBudget b = current;
            if (b.sessionId() == 0L || b.sessionId() == lastCompletedSessionId) {
                return;
            }
            lastCompletedSessionId = b.sessionId();
            completedSid = b.sessionId();
            committedAggregate = b.committedAggregate();
            histogram = optionAffinities.keySet().stream()
                    .sorted()
                    .map(o -> "[opt=" + o + " target=" + b.budgetOf(o) + " realized=" + b.committedOf(o) + "]")
                    .collect(Collectors.toList());
            // Crowd histogram segment (AD-C10): observed crowd v(o) vs the crowd-
            // adjusted budget the fleet steers to. Same-line, crowd-tier only — the
            // internal tier keeps the terse original line (no crowd segment).
            if (crowdAware) {
                crowdHistogram = optionAffinities.keySet().stream()
                        .sorted()
                        .map(o -> "[opt=" + o + " v=" + crowdStake.getOrDefault(o, 0L)
                                + " adj=" + b.budgetOf(o) + "]")
                        .collect(Collectors.toList());
            }
        } finally {
            lock.unlock();
        }
        log.debug("Coordination sid={} realized={}/{} target-histogram {}{} approve={} trim={} reject={}",
                completedSid, committedAggregate, maxAggregateStakePerRound,
                String.join(" ", histogram),
                crowdHistogram.isEmpty() ? "" : " crowd=[" + String.join(" ", crowdHistogram) + "]",
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
     * The in-flight round's per-option budget map — the crowd-adjusted
     * {@code B_crowd(o)} after any {@link #observeCrowd}, or the internal-tier
     * {@code B(o)} when no crowd has been observed. Read under the lock so it is
     * never torn against a concurrent {@code reserve}/{@code observeCrowd}.
     *
     * <p>Package-private: the Phase 2 crowd unit tests assert the recomputed
     * budgets directly; the public health surface (Phase 4, AD-C10) will expose
     * these through the extended {@link #snapshot()}.
     */
    Map<Integer, Long> currentBudget() {
        lock.lock();
        try {
            return new LinkedHashMap<>(current.budget());
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
            // CRITICAL (CROWD_AWARE_COORDINATION Phase 2 caveat): iterate the ORDERED
            // optionAffinities key set and look up the crowd stake by key. Do NOT
            // iterate the crowd map's values() — the crowd/budget maps carry no
            // ordering guarantee for the health list, and the affinity order is the
            // authoritative, stable option order the rest of the block already uses.
            // bc(o) is observability-only (AD-C5): surfaced only when the game's
            // count semantic is known (BETS/PLAYERS), 0 when UNKNOWN — it never
            // enters the budget math either way.
            boolean countKnown = crowdAware && !"UNKNOWN".equals(crowdCountSemantic);
            for (Map.Entry<Integer, Integer> e : optionAffinities.entrySet()) {
                int optionId = e.getKey();
                int weight = e.getValue() == null ? 0 : e.getValue();
                // AD-C10 per-option crowd view. All read by key from the ordered
                // affinity set — never from the crowd/budget map values (unordered).
                //   observedCrowdStake = raw v(o) (the latest observed crowd stake).
                //   crowdStake         = pure X(o) = max(0, v(o) − committed(o)),
                //                        the fleet-subtracted crowd the recompute
                //                        isolated (AD-C4) — kept alongside the raw
                //                        v(o) so no information is lost.
                //   crowdAdjustedBudget= B_crowd(o), the in-flight round's crowd-
                //                        adjusted per-option budget (from current
                //                        RoundBudget) — the internal-tier B(o) when
                //                        no crowd is observed / crowd-off.
                //   observedCrowdCount = bc(o) (obs-only, semantic-gated).
                // Absent / crowd-off ⇒ v(o)=0 ⇒ X(o)=0 (present-but-inert).
                long observedCrowdStake = crowdStake.getOrDefault(optionId, 0L);
                long pureCrowd = Math.max(0L, observedCrowdStake - b.committedOf(optionId));
                long observedCrowdCount = countKnown ? crowdCount.getOrDefault(optionId, 0) : 0L;
                options.add(new OptionSnapshot(
                        optionId,
                        weight,
                        targetBudget.getOrDefault(optionId, 0L),
                        b.committedOf(optionId),
                        pureCrowd,
                        observedCrowdStake,
                        b.budgetOf(optionId),
                        observedCrowdCount));
            }
            return new Snapshot(
                    maxAggregateStakePerRound,
                    b.committedAggregate(),
                    approveCount.get(),
                    trimCount.get(),
                    rejectCount.get(),
                    crowdAware,
                    crowdCountSemantic,
                    List.copyOf(options));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Per-option view for the health DTO (CROWD_AWARE_COORDINATION AD-C10). The
     * crowd fields are all {@code 0} for a non-crowd coordinator (present-but-inert):
     * <ul>
     *   <li>{@code crowdStake} — pure crowd {@code X(o) = max(0, v(o) − committed(o))}
     *       the coordinator isolated (AD-C4); the fleet-subtracted crowd.</li>
     *   <li>{@code observedCrowdStake} — raw {@code v(o)}, the latest observed crowd
     *       stake (AD-C10) before fleet subtraction.</li>
     *   <li>{@code crowdAdjustedBudget} — {@code B_crowd(o)}, the in-flight round's
     *       crowd-adjusted per-option budget (AD-C2/AD-C10); the internal-tier
     *       {@code B(o)} when no crowd is observed.</li>
     *   <li>{@code observedCrowdCount} — {@code bc(o)}, surfaced only when the count
     *       semantic is known (AD-C5); observability-only, never load-bearing.</li>
     * </ul>
     */
    public record OptionSnapshot(int optionId, int weight, long targetBudget, long committedStake,
                                 long crowdStake, long observedCrowdStake, long crowdAdjustedBudget,
                                 long observedCrowdCount) {
    }

    /**
     * Coherent whole-coordinator view for the health DTO. {@code crowdAware} and
     * {@code crowdCountSemantic} are the crowd-tier additions (AD-C10); a non-crowd
     * coordinator serializes them as {@code false} / {@code "UNKNOWN"} with the
     * per-option {@code crowdStake} at {@code 0}.
     */
    public record Snapshot(long maxAggregateStakePerRound,
                           long currentAggregateStake,
                           long approveCount,
                           long trimCount,
                           long rejectCount,
                           boolean crowdAware,
                           String crowdCountSemantic,
                           List<OptionSnapshot> options) {
    }
}
