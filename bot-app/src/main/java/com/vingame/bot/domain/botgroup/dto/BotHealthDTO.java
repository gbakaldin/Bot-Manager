package com.vingame.bot.domain.botgroup.dto;

import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotHealthDTO {

    private String username;
    private BotStatus status;
    private boolean connected;
    private long balance;
    private long lastFetchedBalance;
    private long totalBetsPlaced;
    private long totalBetAmount;
    private long lastRoundWinnings;

    /**
     * Strategy id assigned to this bot at start (see
     * {@code BotGroup.strategyMix} and Architecture Decision 8 in
     * {@code docs/plans/BETTING_STRATEGIES.md}). Null on legacy paths that
     * bypass the assignment.
     */
    private StrategyId strategyId;
}
