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

    private boolean timeBased;

    private LocalDateTime timeFrom;
    private LocalDateTime timeUntil;

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
     * <b>PATCH semantics:</b> full-replace — a non-null DTO value overwrites the
     * persisted value; a null DTO value keeps the existing one. Mid-flight changes
     * do NOT re-assign already-running bots — only newly-created / restarted bots
     * pick up the new value, mirroring {@link #strategyMix}.
     */
    private SlotStrategyId slotStrategyId;

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

    // Scheduled operations
    private LocalDateTime scheduledRestartTime;

    // Audit trail
    private LocalDateTime lastStartedAt;
    private LocalDateTime lastStoppedAt;
    private String lastFailureReason;
}
