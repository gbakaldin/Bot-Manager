package com.vingame.bot.domain.botgroup.model;

import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "botGroups")
public class BotGroup {

    @Id
    private String id;
    private String name;
    private String environmentId;

    private String namePrefix;
    private String password;

    private String gameId;
    private int botCount;

    private long minBet;
    private long maxBet;
    private long betIncrement;

    private long maxTotalBetPerRound;

    private int minBetsPerRound;
    private int maxBetsPerRound;

    /**
     * Whether the group-scoped bet coordinator is active for this group
     * (BET_COORDINATION AD-1). When true, {@code BotGroupBehaviorService.start}
     * builds a {@code BetCoordinator} and injects it into every bot; when false,
     * behavior is byte-for-byte the pre-coordination status quo (AD-9).
     */
    private boolean coordinationEnabled;

    /**
     * Group/fleet-level aggregate per-round stake ceiling enforced by the
     * coordinator (BET_COORDINATION AD-1). This is <b>distinct</b> from the
     * per-bot {@link #maxTotalBetPerRound}: this cap bounds the summed stake of
     * the <i>whole group</i> across one round, whereas {@code maxTotalBetPerRound}
     * bounds a single bot's total. Only meaningful when {@link #coordinationEnabled}
     * is true. Validation requires it to be {@code >= minBet} (a cap below one
     * min-bet can never approve anything).
     */
    private long maxAggregateStakePerRound;

    /**
     * Whether the crowd-aware coordination tier is active for this group
     * (CROWD_AWARE_COORDINATION AD-C6). It is a <b>sub-mode of the coordinator</b>:
     * when true the coordinator steers its per-round budget by the observed live
     * crowd distribution (the {@code bs} feed) instead of the internal affinity
     * split alone. It cannot run without the internal coordinator, so validation
     * requires {@link #coordinationEnabled} to also be true. When false, behavior
     * is byte-for-byte BET_COORDINATION (internal tier), and existing groups are
     * unchanged.
     */
    private boolean crowdAwareCoordination;

    /**
     * Whether per-round bet ramp-up is active for this group (JACKPOT_SCALE_AND_RAMP
     * AD-R4). When true and the group's game type is {@code BETTING_MINI}/{@code TAI_XIU},
     * bots shape <i>when</i> within the bet window their ticks land — ramping acceptance
     * from low at window-open toward high at window-close (AD-R1). When false, behavior
     * is byte-for-byte today's flat every-second cadence (AD-R5). Ramp is a behavior
     * preference of the fleet, so it lives on the BotGroup (not the Game — contrast
     * jackpot-scale, which is game-intrinsic).
     */
    private boolean rampEnabled;

    /**
     * Ramp curve exponent {@code k} in the accept-probability power curve
     * {@code pAccept = pMin + (1 - pMin) * elapsedFraction^k} (JACKPOT_SCALE_AND_RAMP
     * AD-R3). {@code k = 1} is a linear ramp; {@code k > 1} back-loads bets toward
     * window close (the real-player pile-in); {@code k <= 0} / ramp disabled degrades
     * to today's flat cadence. Only meaningful when {@link #rampEnabled} is true;
     * validation then requires {@code rampShape > 0}.
     */
    private double rampShape;

    /**
     * Whether bots in this group bias their per-bet <i>option</i> choice by the game's
     * affinity weights instead of a flat uniform pick (AFFINITY_AWARE_PROPOSAL AD-7).
     * When true and the group's game type is {@code BETTING_MINI}/{@code TAI_XIU}, the
     * RANDOM strategy weights each option by {@code Game.getEffectiveOptionAffinities()}
     * so bots propose more on high-affinity options; when false (or on equal weights),
     * behavior is byte-for-byte today's uniform pick (AD-3). Independent of
     * {@link #coordinationEnabled} — a group may want biased proposals with or without
     * the coordinator (Open Decision D3). Like {@link #rampEnabled}, this is a
     * betting-behavior preference of the fleet, so it lives on the BotGroup (the
     * affinity <i>weights</i> stay on the Game).
     */
    private boolean affinityWeightedProposal;

    /**
     * How this group's lifecycle is governed relative to {@link #activationWindow}
     * (TIMED_ACTIVATION AD-1). {@code null} = legacy non-timed group, governed
     * solely by {@link #targetStatus}. Only {@link ActivationMode#SCHEDULED} groups
     * are driven by the activation reconciler.
     */
    private ActivationMode activationMode;

    /**
     * Recurring time-of-day activation window (TIMED_ACTIVATION AD-1). Nullable —
     * required only when {@link #activationMode} is {@link ActivationMode#SCHEDULED}.
     */
    private ActivationWindow activationWindow;

    private boolean chatEnabled;
    private boolean autoDepositEnabled;

    /**
     * Weighted mix of betting strategies for bots in this group.
     * <p>
     * Each entry is a {@link WeightedStrategy} pairing a strategy id with its weight.
     * At group start, strategies are assigned to bots via fill-to-target distribution
     * keyed by stable bot identity, so the resulting per-bot strategy is reproducible
     * across restarts (Architecture Decision 8 in {@code docs/plans/BETTING_STRATEGIES.md}).
     * <p>
     * Single strategy is just {@code [(RANDOM, 1.0)]} — same code path as multi-strategy.
     * <p>
     * <b>Read-side fallback:</b> a null or empty list defaults to {@code [(RANDOM, 1.0)]}
     * at assignment time (Architecture Decision 7). Mongo docs that pre-date Phase 4
     * have no field; the Phase 6 migration backfills them.
     * <p>
     * <b>PATCH semantics:</b> full-replace of the list (not merge). A mid-flight
     * {@code PATCH /api/v1/bot-group} does NOT re-assign already-running bots — their
     * existing strategy instances keep running with accumulated state. Only
     * newly-created or restarted bots draw from the new mix (Architecture Decision 9).
     */
    private List<WeightedStrategy> strategyMix;

    /**
     * Slot strategy applied to every bot in a SLOT group. Nullable — a null value
     * falls back to {@link SlotStrategyId#FIXED} at bot-build time in
     * {@code BotGroupBehaviorService.createSingleBot}. Betting groups ignore this
     * field (their per-bot strategy comes from {@link #strategyMix}).
     * <p>
     * <b>A {@code String} registry key, not a {@link SlotStrategyId}</b>
     * (PLUGIN_HOT_RELOAD Phase 2b, AD-12/AD-14). The persisted BSON is unchanged:
     * with no Spring Data converters in this application an enum was already
     * stored as its {@code name()} string, so every existing document reads back
     * into this field verbatim — no migration script, no dual-read. Pinned by
     * {@code PersistedStrategyKeyCompatTest}. Null still means "fall back to
     * FIXED"; it is not defaulted here.
     * <p>
     * <b>PATCH semantics:</b> full-replace — a non-null DTO value overwrites the
     * persisted value; a null DTO value keeps the existing one. Mid-flight changes
     * do NOT re-assign already-running bots — only newly-created / restarted bots
     * pick up the new value, mirroring {@link #strategyMix}.
     */
    private String slotStrategyId;

    /**
     * Instant this group was first persisted. Stamped by
     * {@link com.vingame.bot.domain.botgroup.service.BotGroupService#save(BotGroup, boolean)}
     * when null; never overwritten afterwards. Backs the {@code CREATED_TIME} sort
     * key (BOTGROUP_GAME_MANAGEMENT AD-14). Existing docs are backfilled to a fixed
     * timestamp by the Phase 4 migration script so nothing sorts as N/A.
     */
    private Instant createdAt;

    /**
     * Instant of the most recent persist. Stamped on every save (create and
     * update). Backs the {@code UPDATED_TIME} sort key (AD-16). Backfilled to the
     * same fixed timestamp as {@link #createdAt} by the Phase 4 migration script.
     */
    private Instant updatedAt;

    // Lifecycle management - target state (what admin wants)
    private BotGroupStatus targetStatus;

    /**
     * How many of this group's accounts are known to exist on the auth gateway
     * (GATEWAY_REQUEST_BUDGET A2, asynchronous registration).
     * <p>
     * <b>A high-water mark, and it only means "indices 1..k are done" because the worker is
     * serial and in-order</b> (A2.1). Usernames are {@code namePrefix + index}, so this one
     * integer is the whole of the job's progress — there is no job collection and no id to
     * correlate. A parallel worker would make the integer meaningless, which is the second
     * reason {@code RegistrationWorker} stays single-threaded.
     * <p>
     * <b>System-managed.</b> Rendered by {@code BotGroupMapper.toDTO}, copied by neither write
     * path, and {@code @JsonProperty(access = READ_ONLY)} on the DTO — because a client-writable
     * high-water mark lets a request body make the worker skip a block of accounts (and never
     * create them) or re-register one (and spend the window on accounts that exist).
     * {@code BotGroupStatusPersistenceGuardTest} pins both halves by value.
     * <p>
     * It may legitimately <b>exceed</b> {@link #botCount}: PATCHing {@code botCount} down never
     * un-registers anything, because this is a fact about accounts that exist rather than an
     * intent (A2.7). Progress renders as {@code min(registeredCount, botCount)/botCount}.
     */
    private int registeredCount;

    /**
     * How many of this group's accounts have had a display name set — the same monotonic,
     * in-order rule as {@link #registeredCount}, and never ahead of it (A17.3).
     * <p>
     * Two fields rather than one because "registered but not named" is a real resume state and a
     * single counter cannot express it. A re-register returns <b>no tokens at all</b>
     * ({@code docs/reviews/GATEWAY_REQUEST_BUDGET/gwms-register-envelope.md}), so an index that
     * registered and then lost its display-name call cannot be finished from the register
     * response — it has to go straight to {@code update-fullname}, and the worker can only know
     * to do that if the two counts are separate. A nameless account is not cosmetic: on the RIK
     * ziczac tables one stalls the round engine for every player in the room.
     * <p>
     * System-managed on exactly the same terms as {@link #registeredCount}.
     */
    private int namedCount;

    /**
     * {@code PENDING}, {@code FAILED}, or absent — see {@link RegistrationState}, whose javadoc
     * explains why this is a {@code String} and must stay one.
     * <p>
     * This is the field the two derived {@code BotGroupStatus} values are rendered from at the
     * DTO boundary. It is never mapped onto {@code targetStatus}, which would make the document
     * unreadable by an older jar.
     */
    private String registrationState;

    /**
     * Why registration stopped, when {@link #registrationState} is {@code FAILED}. Operator-
     * facing: it names the username the worker gave up on and the upstream message, which is
     * what a {@code POST /{id}/registration/retry} decision is made on.
     */
    private String registrationError;

    /**
     * Amount credited to each account this group registers (BOT_PROVISIONING AD-1), in brand
     * currency units. {@code 0} — the default, and what an older document reads as — means no
     * money moves. Distinct from the global auto top-up {@code bot.deposit.amount}. Mutable only
     * while registration is complete (AD-3), so one job never mixes two amounts.
     */
    private long initialDeposit;

    /**
     * <b>Display mirror</b> of {@code DepositLedger}'s high-water mark: indices {@code 1..k} have
     * been funded (AD-5). The worker decides from the ledger, never from this field, because every
     * whole-document {@code save} of a group (a PATCH, a start, a stop) writes back whatever value
     * it read — harmless for {@link #registeredCount} (a re-register answers {@code EXISTED}),
     * a second deposit for this one. System-managed, read-only on the DTO.
     */
    private int depositedCount;

    /**
     * Display mirror of the ledger's write-ahead marker (AD-6): the index whose deposit outcome
     * is unknown. Set when a group goes {@code REGISTRATION_FAILED} on an unknown outcome and
     * cleared by {@code /registration/retry?depositOutcome=…}. System-managed, read-only.
     */
    private Integer depositInFlight;

    // Scheduled operations
    private LocalDateTime scheduledRestartTime;

    // Audit trail
    private LocalDateTime lastStartedAt;
    private LocalDateTime lastStoppedAt;
    private String lastFailureReason;
}
