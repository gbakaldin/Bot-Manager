package com.vingame.bot.domain.botgroup.dto;

import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotGroupHealthDTO {

    private String groupId;
    private String groupName;
    private BotGroupStatus status;
    private BotGroupPlayingStatus playingStatus;

    private Instant startedAt;
    private int consecutiveFailures;

    private int totalBots;
    private int connectedBots;
    private int disconnectedBots;

    private List<BotHealthDTO> bots;
}
