package com.vingame.bot.domain.botgroup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.ActivationWindow;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BotGroupDTO {

    private String id;
    private String name;

    // Universal required-field validation (POST/create path only — OnCreate group).
    // Behavior fields (minBet/maxBet/betIncrement/min|maxBetsPerRound/maxTotalBetPerRound)
    // are intentionally NOT annotated here: their rules (including the fact that
    // minBet=0 / minBetsPerRound=0 are valid) live entirely in the game-type
    // GameConfigValidator strategies, not this universal layer.
    // See docs/plans/BOT_GROUP_CONFIG_VALIDATION.md AD-10.
    @NotBlank(groups = OnCreate.class, message = "environmentId must not be blank")
    private String environmentId;

    @NotBlank(groups = OnCreate.class, message = "namePrefix must not be blank")
    private String namePrefix;

    @NotBlank(groups = OnCreate.class, message = "password must not be blank")
    private String password;

    @NotBlank(groups = OnCreate.class, message = "gameId must not be blank")
    private String gameId;

    @Positive(groups = OnCreate.class, message = "botCount must be >= 1")
    private Integer botCount;

    private Long minBet;
    private Long maxBet;
    private Long betIncrement;

    private Long maxTotalBetPerRound;

    private Integer minBetsPerRound;
    private Integer maxBetsPerRound;

    /**
     * Enable the group-scoped bet coordinator (BET_COORDINATION AD-1). Boxed —
     * PATCH-null keeps the persisted value; a non-null value full-replaces it.
     */
    private Boolean coordinationEnabled;

    /**
     * Group/fleet-level aggregate per-round stake ceiling for the coordinator
     * (BET_COORDINATION AD-1). Boxed — PATCH-null keeps the persisted value.
     * <b>Not</b> the per-bot {@link #maxTotalBetPerRound}: this bounds the whole
     * group's summed stake per round, not a single bot's.
     */
    private Long maxAggregateStakePerRound;

    /**
     * Enable per-round bet ramp-up (JACKPOT_SCALE_AND_RAMP AD-R4). Boxed —
     * PATCH-null keeps the persisted value; a non-null value full-replaces it.
     */
    private Boolean rampEnabled;

    /**
     * Ramp curve exponent {@code k} (JACKPOT_SCALE_AND_RAMP AD-R3). Boxed —
     * PATCH-null keeps the persisted value. Only meaningful when
     * {@link #rampEnabled} is true; validation then requires {@code rampShape > 0}.
     */
    private Double rampShape;

    /**
     * Enable affinity-weighted per-bot option proposal (AFFINITY_AWARE_PROPOSAL AD-7).
     * Boxed — PATCH-null keeps the persisted value; a non-null value full-replaces it.
     * Independent of {@link #coordinationEnabled} (Open Decision D3).
     */
    private Boolean affinityWeightedProposal;

    /**
     * Activation mode (TIMED_ACTIVATION AD-1). Null = legacy non-timed group.
     * PATCH is full-replace — a non-null value overwrites the persisted mode
     * (mirrors {@code slotStrategyId}).
     */
    private ActivationMode activationMode;

    /**
     * Recurring time-of-day activation window (TIMED_ACTIVATION AD-1). Null unless
     * the group opts into scheduling. PATCH is full-replace — a non-null value
     * overwrites the persisted window (mirrors {@code slotStrategyId}).
     */
    private ActivationWindow activationWindow;

    private Boolean chatEnabled;
    private Boolean autoDepositEnabled;

    /**
     * Weighted mix of betting strategies; null/empty falls back to {@code [(RANDOM, 1.0)]}
     * at assignment time. PATCH is full-replace — supplying this field overwrites the
     * persisted list wholesale, but does NOT re-assign already-running bots
     * (Architecture Decision 9 in {@code docs/plans/BETTING_STRATEGIES.md}).
     */
    private List<WeightedStrategy> strategyMix;

    /**
     * Slot strategy applied to all bots in a SLOT group. Nullable — null falls back to
     * {@code SlotStrategyId.FIXED} at bot-build time. Betting groups ignore this field.
     * PATCH is full-replace — supplying this field overwrites the persisted value, but
     * does NOT re-assign already-running bots (mirrors {@code strategyMix} semantics).
     */
    private SlotStrategyId slotStrategyId;

    // Lifecycle management
    private BotGroupStatus targetStatus;

    // Scheduled operations
    private LocalDateTime scheduledRestartTime;

    // Audit trail (read-only, set by system)
    private LocalDateTime lastStartedAt;
    private LocalDateTime lastStoppedAt;
    private String lastFailureReason;

    // Migration flag - when true, skips user registration (for importing existing bots)
    private Boolean existingGroup;

    /**
     * Group-level runtime statistics (BOTGROUP_GAME_MANAGEMENT Phase 3), read-only.
     * Set by the controller/service enrichment via
     * {@link com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService#computeStats(String)}
     * — NOT part of the create/update write surface (AD-13) and never mapped from
     * the entity. Null (with @JsonInclude NON_NULL, absent) on write responses;
     * populated with an all-null-fields block for a stopped group on read.
     */
    private BotGroupStatsDTO stats;
}
