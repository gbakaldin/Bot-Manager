package com.vingame.bot.domain.botgroup.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BotGroup {

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

    // Lifecycle management - target state (what admin wants)
    private BotGroupStatus targetStatus;

    // Scheduled operations
    private LocalDateTime scheduledRestartTime;

    // Audit trail
    private LocalDateTime lastStartedAt;
    private LocalDateTime lastStoppedAt;
    private String lastFailureReason;
}
