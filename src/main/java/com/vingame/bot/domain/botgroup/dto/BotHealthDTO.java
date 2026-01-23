package com.vingame.bot.domain.botgroup.dto;

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
    private boolean connected;
    private long balance;
    private long lastFetchedBalance;
    private long totalBetsPlaced;
    private long totalBetAmount;
    private long lastRoundWinnings;
}
