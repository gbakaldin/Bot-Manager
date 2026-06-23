package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.botgroup.model.BotGroup;

/**
 * Permissive base validator that accepts any configuration. Phase 1 placeholder
 * (see {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}, Phase 1) that lets the
 * factory satisfy its "every {@link com.vingame.bot.domain.game.model.GameType}
 * has a validator" invariant before any real rule exists.
 *
 * <p>Concrete per-type registrations are thin subclasses that only override
 * {@link GameConfigValidator#supportedType()} (Implementation-Notes option (a) —
 * one bean ↔ one key, closest mirror of the strategy factories). The
 * BETTING_MINI and SLOT subclasses are replaced by real validators in Phases
 * 2-3; the TAI_XIU / CARD_GAME / UP_DOWN subclasses remain permissive no-ops by
 * design (AD-5 — these types cannot start a bot, so blocking group creation adds
 * no safety and would break pre-staging config).
 */
public abstract class NoOpConfigValidator implements GameConfigValidator {

    /**
     * Accepts any configuration — intentionally does nothing.
     */
    @Override
    public void validate(BotGroup group) {
        // No-op: no rules in Phase 1 / for unimplemented types (AD-5).
    }
}
