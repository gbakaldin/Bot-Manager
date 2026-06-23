package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Permissive no-op validator for {@link GameType#TAI_XIU} groups (AD-5). This
 * type cannot start a bot today ({@code BotFactory} rejects it), so blocking
 * group creation adds no safety and would break pre-staging config.
 */
@Component
public class TaiXiuConfigValidator extends NoOpConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.TAI_XIU;
    }
}
