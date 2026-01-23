package com.vingame.bot.domain.session.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionHistoryDTO {

    private String id;
    private String sessionId;
    private String gameId;
    private String gameName;
    private String environmentId;

    private Instant startedAt;
    private Instant endedAt;

    private Integer botCount;

    private Long totalBotBet;
    private Long totalPlayerBet;

    private Double botRtp;
    private Double totalRtp;

    private Boolean jackpot;
    private Long botJackpotWinnings;
    private Long playerJackpotWinnings;

    private Long totalWinningsSinceLastDeposit;
    private String lastDepositSessionId;
}
