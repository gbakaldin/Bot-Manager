package com.vingame.bot.domain.botgroup.dto;

import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for querying runtime status of a bot group
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotGroupStatusDTO {

    private String groupId;
    private String groupName;

    // Target state (from database)
    private BotGroupStatus targetStatus;

    // Actual runtime state (from BehaviorService)
    private BotGroupStatus actualStatus;

    // Playing state (only relevant when actualStatus == ACTIVE)
    private BotGroupPlayingStatus playingStatus;
}
