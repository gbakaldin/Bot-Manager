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

    /**
     * The group's configured bot count — the denominator for both progress senses below
     * (GATEWAY_REQUEST_BUDGET A1). Always present.
     */
    private int botCount;

    /**
     * Bots built by the current start, or by the last one that finished. Non-null while
     * {@code actualStatus == STARTING} and retained until the next start or stop; {@code null}
     * for a group that has not been started in this JVM.
     * <p>
     * This is the field that makes an asynchronous start readable: {@code POST /start} answers
     * {@code 200 STARTING} immediately, and a 3,000-bot group legitimately spends 33-50 minutes
     * getting up once its gateway requests are paced. {@code botsUp} climbing toward
     * {@code botCount} is how an operator tells that apart from a stuck build.
     */
    private Integer botsUp;

    /**
     * Accounts registered for this group. Always {@code null} today — asynchronous registration
     * (Phase 4) is what populates it, from an additive document field. Declared now so the
     * response shape does not change again when it lands.
     */
    private Integer registeredCount;

    /**
     * The most recent start failure, or {@code null}. Retained until the next start or stop.
     * <p>
     * It exists because the failure can no longer be an HTTP status: a build that dies forty
     * minutes after its {@code 200} has nowhere else to report. In particular a {@code /restart}
     * that produced zero bots lands here rather than as a {@code 500}.
     */
    private String lastError;
}
