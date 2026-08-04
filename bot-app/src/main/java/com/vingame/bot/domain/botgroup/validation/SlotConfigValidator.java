package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Validator for {@link GameType#SLOT} groups.
 *
 * <p><b>Intentional no-op on the betting fields (AD-4).</b> Unlike
 * BETTING_MINI, a slot group's bet values are <em>not</em> taken from the
 * configured {@code minBet}/{@code maxBet}/{@code betIncrement}/
 * round-bet-counts. They come from the server's Js set / {@code cmd:1300}
 * subscribe response at runtime ({@code Game.gameId} javadoc; SLOT_MACHINE_BOT
 * plan AD-8/AD-11), so those behavior fields are meaningless here. Rejecting a
 * group because a UI sent leftover zeros (or any other value) for fields slots
 * never read would be hostile, so they are deliberately ignored, not rejected.
 *
 * <p>This validator validates only what slots actually use — which, in v1, is
 * nothing — and therefore accepts any configuration. It is a finalized,
 * deliberate no-op, not a placeholder. See
 * {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}, AD-4 / Phase 3.
 */
@Component
public class SlotConfigValidator extends NoOpConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.SLOT;
    }
}
