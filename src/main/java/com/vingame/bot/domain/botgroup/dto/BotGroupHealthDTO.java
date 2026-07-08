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

    /**
     * Bet-coordinator state (BET_COORDINATION Phase 4, AD-6). Nullable: present
     * only when the group is running with coordination enabled; absent (null)
     * when coordination is off or the group is not running.
     */
    private CoordinationStateDTO coordination;

    /**
     * Jackpot volume scaler state (JACKPOT_SCALE_AND_RAMP Phase J4, AD-J10).
     * Nullable: present only when the group is running with a jackpot scaler
     * active (jackpot-scale enabled and eligible); absent (null) when
     * jackpot-scale is off/ineligible or the group is not running.
     */
    private JackpotScaleStateDTO jackpotScale;
}
