package com.vingame.bot.domain.botgroup.dto;

/**
 * Bean Validation group marker for the create (POST) path only.
 * <p>
 * The universal required-field constraints on {@link BotGroupDTO}
 * ({@code @NotBlank} on {@code environmentId}/{@code gameId}/{@code namePrefix}/
 * {@code password}, {@code @Positive} on {@code botCount}) are assigned to this
 * group so they fire on {@code POST /api/v1/bot-group} (full object) but are
 * skipped on {@code PATCH} — which is an intentionally partial update and must
 * not be subjected to required-field checks. PATCH continues to rely on the
 * post-merge entity validation of the game-type config validators
 * (see {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}, AD-6 / AD-10).
 */
public interface OnCreate {
}
