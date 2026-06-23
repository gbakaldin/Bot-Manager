package com.vingame.bot.domain.botgroup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
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
    private String environmentId;

    private String namePrefix;
    private String password;

    private String gameId;
    private Integer botCount;

    private Long minBet;
    private Long maxBet;
    private Long betIncrement;

    private Long maxTotalBetPerRound;

    private Integer minBetsPerRound;
    private Integer maxBetsPerRound;

    private Boolean timeBased;

    private LocalDateTime timeFrom;
    private LocalDateTime timeUntil;

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
}
