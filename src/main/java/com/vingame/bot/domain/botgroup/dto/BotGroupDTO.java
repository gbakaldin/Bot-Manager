package com.vingame.bot.domain.botgroup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    // Lifecycle management
    private BotGroupStatus targetStatus;

    // Scheduled operations
    private LocalDateTime scheduledRestartTime;

    // Audit trail (read-only, set by system)
    private LocalDateTime lastStartedAt;
    private LocalDateTime lastStoppedAt;
    private String lastFailureReason;
}
