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
     * Accounts known to exist for this group, from the additive {@code registeredCount} document
     * field (GATEWAY_REQUEST_BUDGET A1, populated from Phase 4). {@code null} for a group that
     * pre-dates asynchronous registration or was created with {@code existingGroup=true}.
     * <p>
     * This is the progress an operator polls after {@code POST /} answers {@code 200} with
     * {@code targetStatus: "REGISTRATION_PENDING"}: {@code registeredCount} climbing toward
     * {@code botCount} is how a paced 500-account create is told apart from a stalled one.
     */
    private Integer registeredCount;

    /**
     * Accounts that also have a display name — never ahead of {@link #registeredCount} (A17.3).
     * <p>
     * Worth surfacing separately rather than folding into one number: a group sitting at
     * {@code registeredCount=500, namedCount=499} has one nameless account, and on the RIK ziczac
     * tables a single nameless account stalls the round engine for every player in the room. The
     * two counters are also what the worker resumes from, so what an operator sees is exactly
     * what the worker will do next.
     */
    private Integer namedCount;

    /**
     * The most recent start <b>or registration</b> failure, whichever is more recent, or
     * {@code null}. A start failure is retained until the next start or stop; a registration
     * failure until {@code POST /{id}/registration/retry} clears it.
     * <p>
     * It exists because the failure can no longer be an HTTP status: a build that dies forty
     * minutes after its {@code 200} has nowhere else to report, and neither does a registration
     * that gives up on account 63 of 500 an hour after {@code POST /} answered. In particular a
     * {@code /restart} that produced zero bots lands here rather than as a {@code 500}.
     * <p>
     * A start error wins a tie because it is the newer event by construction: a group cannot be
     * started until registration has cleared, so any start error postdates every registration
     * error the group has.
     */
    private String lastError;
}
