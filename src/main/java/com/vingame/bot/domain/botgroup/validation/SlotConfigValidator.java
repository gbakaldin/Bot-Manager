package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Validator for {@link GameType#SLOT} groups.
 *
 * <p><b>Phase 1 placeholder.</b> A permissive no-op so the factory's "every
 * game type has a validator" invariant holds. Phase 3 formalises this as the
 * real slot validator — which is still a no-op on the betting fields by design
 * (AD-4): slot bet values come from the server Js set / {@code cmd:1300}
 * subscribe response at runtime, so {@code minBet}/{@code betIncrement}/
 * round-bet-counts are meaningless and must be ignored, not rejected.
 */
@Component
public class SlotConfigValidator extends NoOpConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.SLOT;
    }
}
