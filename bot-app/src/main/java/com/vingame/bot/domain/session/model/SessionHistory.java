package com.vingame.bot.domain.session.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sessionHistory")
public class SessionHistory {

    @Id
    private String id;

    private String sessionId;
    private String gameId;
    private String gameName;
    private String environmentId;

    private Instant startedAt;
    private Instant endedAt;

    // Bot participation
    private int botCount;

    // Bet totals
    private long totalBotBet;
    private long totalPlayerBet;

    // RTP (Return To Player) - stored as percentage (e.g., 95.5)
    private Double botRtp;
    private Double totalRtp;

    // Jackpot
    private boolean jackpot;
    private long botJackpotWinnings;
    private long playerJackpotWinnings;

    // Aggregated winnings since last deposit
    private long totalWinningsSinceLastDeposit;
    private String lastDepositSessionId;
}
