package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;

/**
 * Game-type-specific validator for a {@link BotGroup}'s betting-behavior
 * configuration. Implementations are discovered and keyed by their
 * {@link #supportedType()} in {@link GameConfigValidatorFactory}.
 *
 * <p>Each {@link GameType} has exactly one validator. Unlike the betting/slot
 * strategy factories — which key on a fine-grained id read from a marker
 * annotation — the key here is the coarse {@link GameType} itself, so it is
 * returned directly from the interface (see
 * {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}, AD-1/AD-2). This keeps the
 * lookup switch-free: a new game type's rules are a new {@code @Component}, not
 * an edited switch.
 *
 * <p>A validator runs against the persisted/merged {@link BotGroup} entity (its
 * numeric fields are primitives), accumulates every violation, and throws a
 * single aggregated {@link com.vingame.bot.common.exception.BadRequestException}
 * when invalid. A valid config returns normally.
 */
public interface GameConfigValidator {

    /**
     * @return the {@link GameType} this validator owns. The factory keys its
     *         registry on this value; two validators returning the same type is
     *         a wiring error ({@link IllegalStateException} at startup).
     */
    GameType supportedType();

    /**
     * Validate the betting-behavior configuration of the given group.
     *
     * @param group the bot group to validate (post-merge on the PATCH path).
     * @throws com.vingame.bot.common.exception.BadRequestException if the
     *         configuration is invalid; the message aggregates all violations
     *         and is forwarded verbatim to the client as a 400.
     */
    void validate(BotGroup group);
}
