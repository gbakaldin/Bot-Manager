package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Validator for {@link GameType#BETTING_MINI} groups.
 *
 * <p>Enforces the definitive STRICT-GRID cross-field numeric rules (AD-3 in
 * {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}) by delegating to the shared
 * {@link BettingGridRules#validate(BotGroup)} rule body. The rule list itself
 * lives in {@link BettingGridRules} so it can be reused verbatim by
 * {@link TaiXiuConfigValidator} (TAI_XIU_BOT plan AD-8) without duplication. The
 * rules accumulate <b>every</b> violation and throw a single aggregated
 * {@link BadRequestException}; a valid config returns normally.
 */
@Component
public class BettingMiniConfigValidator implements GameConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.BETTING_MINI;
    }

    @Override
    public void validate(BotGroup group) {
        BettingGridRules.validate(group);
    }
}
