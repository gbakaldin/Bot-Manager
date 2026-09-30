package com.vingame.bot.domain.botgroup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
     * Enable the crowd-aware coordination tier (CROWD_AWARE_COORDINATION AD-C6).
     * Boxed — PATCH-null keeps the persisted value; a non-null value full-replaces
     * it. A sub-mode of coordination: validation requires {@link #coordinationEnabled}
     * to also be true when this is set.
     */
    private Boolean crowdAwareCoordination;

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
     * <p>
     * A {@code String} registry key, not a {@link SlotStrategyId}, since
     * PLUGIN_HOT_RELOAD Phase 2b (AD-12). The JSON is unchanged in both
     * directions — the enum already serialised as its bare constant name. An
     * unknown key is rejected with a 400 by
     * {@code BotGroupConfigValidationService} (AD-15), which is what Jackson's
     * enum deserializer used to do implicitly.
     */
    private String slotStrategyId;

    /**
     * The persisted lifecycle state — <b>rendered, never accepted</b>
     * (GATEWAY_REQUEST_BUDGET R1/Q1).
     * <p>
     * {@code READ_ONLY} is load-bearing rather than tidy. Until this feature the enum's whole
     * alphabet was {@code ACTIVE}/{@code STOPPED}/{@code DEAD}, so a client writing this field
     * was at worst a data-consistency annoyance and Jackson rejected everything else with a 400.
     * Three appended constants changed that: {@code STARTING},
     * {@code REGISTRATION_PENDING} and {@code REGISTRATION_FAILED} cannot be read back by any
     * jar built before them — {@code Enum.valueOf} throws while mapping the document — so one
     * {@code PATCH {"targetStatus":"STARTING"}} was enough to put a value in Mongo that makes a
     * rollback to {@code vingame-bot:rollback-*} unsafe, and to drop the group out of
     * {@code findByTargetStatus(ACTIVE)} and out of auto-recovery on the current jar too.
     * <p>
     * Lifecycle is what {@code POST /{id}/start} and {@code /stop} are for. Ignoring the field
     * inbound (rather than rejecting it with a 400) is the deliberate choice: {@code POST /}
     * renders {@code REGISTRATION_PENDING} from Phase 4 on, so a read-modify-write client hands
     * the value straight back, and a validator would then answer 400 to every PATCH such a
     * client makes — the same trap CLAUDE.md records for the strategy-key validation. The mapper
     * does not copy it in either write direction either, so no in-process caller can slip past
     * Jackson; {@code BotGroupStatusPersistenceGuardTest} pins both halves.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BotGroupStatus targetStatus;

    /**
     * Accounts known to exist for this group, so a list view can render "120/500" without a
     * second call (GATEWAY_REQUEST_BUDGET A1 / A17.3). {@code null} for a group that pre-dates
     * asynchronous registration or was created with {@code existingGroup=true}.
     * <p>
     * <b>{@code READ_ONLY}, and the mapper copies it in neither write direction</b> — the same
     * pair {@code targetStatus} uses, for a sharper reason. This is the high-water mark the
     * worker resumes from: a request body that could set it would make the worker skip a block of
     * accounts that were never created, or re-register a block that already exists and spend the
     * Cloudflare window doing it. {@code BotGroupStatusPersistenceGuardTest} asserts by value
     * that neither {@code toEntity} nor {@code updateEntityFromDTO} carries it.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer registeredCount;

    /**
     * Accounts that also have a display name — never ahead of {@link #registeredCount}, and the
     * field that makes "registered but not named" a distinguishable resume state (A17.3).
     * Same {@code READ_ONLY} + no-mapper-write rules, for the same reason.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer namedCount;

    /**
     * Why registration stopped, when {@code targetStatus} renders {@code REGISTRATION_FAILED}.
     * Read-only; {@code POST /{id}/registration/retry} is what clears it.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String registrationError;

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
