package com.vingame.bot.domain.botgroup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Read-side view of a running group's bet coordinator (BET_COORDINATION Phase 4,
 * AD-6). Nullable block on {@link BotGroupHealthDTO}: present only when the group
 * is running <em>and</em> {@code coordinationEnabled} — absent (null) otherwise.
 * <p>
 * Built from the coordinator's coherent {@code snapshot()} accessor (read under a
 * single lock acquisition so the view is never torn against a concurrent
 * reservation). This is strictly read-only — the health path never mutates the
 * coordinator.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoordinationStateDTO {

    /** True when a coordinator is active for the running group. */
    private boolean enabled;

    /** Aggregate per-round stake ceiling for the whole group (the hard cap). */
    private long maxAggregateStakePerRound;

    /** Committed aggregate stake for the in-flight round; {@code <= maxAggregateStakePerRound}. */
    private long currentAggregateStake;

    /** Cumulative APPROVE decisions since the coordinator was created. */
    private long approveCount;

    /** Cumulative TRIM decisions since the coordinator was created. */
    private long trimCount;

    /** Cumulative REJECT decisions since the coordinator was created. */
    private long rejectCount;

    /** Per-option target/realized breakdown, one entry per game option. */
    private List<OptionStateDTO> options;

    /**
     * Per-option target-vs-realized view (AD-6). {@code realizedFraction} is the
     * committed stake as a fraction of this option's target budget (0 when the
     * budget is 0), i.e. how filled the option is against its target — a value
     * below 1.0 means the option is under-filled (the AD-7 floor caveat).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionStateDTO {

        /** Game option id (matches the affinity key set). */
        private int optionId;

        /** Affinity weight for this option. */
        private int targetWeight;

        /** Target budget {@code floor(w(o)/W * cap)} for this option. */
        private long targetBudget;

        /** Committed stake so far on this option in the in-flight round. */
        private long committedStake;

        /** {@code committedStake / targetBudget}, or 0.0 when the target budget is 0. */
        private double realizedFraction;
    }
}
