package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Validator for {@link GameType#TAI_XIU} groups (TAI_XIU_BOT plan AD-8).
 *
 * <p>Promoted from a permissive no-op to the same STRICT-GRID rule set as
 * {@link BettingMiniConfigValidator}: Tai Xiu shares the identical
 * {@code minBet/maxBet/betIncrement/...} group config and round-based betting
 * semantics, and — once Phase 6 lands — it can actually start a bot, so a bad
 * config must be rejected rather than let through to a runnable bot. Both
 * validators delegate to the shared {@link BettingGridRules#validate(BotGroup)}
 * rule body so the rule list is defined exactly once (AD-8). A valid config
 * returns normally; an invalid one throws an aggregated
 * {@link BadRequestException}.
 */
@Component
public class TaiXiuConfigValidator implements GameConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.TAI_XIU;
    }

    @Override
    public void validate(BotGroup group) {
        BettingGridRules.validate(group);
    }
}
