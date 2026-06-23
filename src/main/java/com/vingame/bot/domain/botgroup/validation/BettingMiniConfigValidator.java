package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Validator for {@link GameType#BETTING_MINI} groups.
 *
 * <p><b>Phase 1 placeholder.</b> Currently a permissive no-op so the factory's
 * "every game type has a validator" invariant holds. Phase 2 replaces this with
 * the real STRICT-GRID cross-field numeric rules (AD-3): non-negativity, zero
 * valid for the minimum fields only, strictly-positive maximum/step/cap fields,
 * ordering, grid divisibility, and cross-field caps — accumulated into one
 * aggregated {@code BadRequestException}.
 */
@Component
public class BettingMiniConfigValidator extends NoOpConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.BETTING_MINI;
    }
}
