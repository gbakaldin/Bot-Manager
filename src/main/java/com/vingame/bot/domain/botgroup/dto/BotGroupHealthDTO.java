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
    private int reconnectingBots;
    private int deadBots;
    private int disconnectedBots;

    private List<BotHealthDTO> bots;

    /**
     * Group-level runtime statistics (BOTGROUP_GAME_MANAGEMENT Phase 3). Present
     * on every response; all fields null (N/A) when the group is not running.
     */
    private BotGroupStatsDTO stats;
}
