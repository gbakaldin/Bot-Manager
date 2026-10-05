package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator for {@link GameType#CASHOUT} groups ({@code docs/plans/CASHOUT_BOT.md}
 * AD-15).
 *
 * <p><b>Validates only what CASHOUT reads.</b> A cash-out bot's stake is a uniform
 * pick from the server's {@code bets} ∩ {@code [minBet, maxBet]} (AD-6), with
 * {@code maxBet <= 0} meaning "unset — use the default window". So the only rules are
 * {@code minBet >= 0} and, when {@code maxBet} is set, {@code minBet <= maxBet}.
 * {@code betIncrement}, the per-round bet counts and {@code maxTotalBetPerRound} are
 * never read and are ignored, not rejected — the same reasoning as
 * {@link SlotConfigValidator}: a UI sending leftover zeros for fields this game type
 * does not use must not block the group.
 *
 * <p>Ships in the same phase as the enum constant because
 * {@link GameConfigValidatorFactory} fails boot for any {@link GameType} without a
 * validator.
 */
@Component
public class CashoutConfigValidator implements GameConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.CASHOUT;
    }

    @Override
    public void validate(BotGroup group) {
        long minBet = group.getMinBet();
        long maxBet = group.getMaxBet();

        List<String> violations = new ArrayList<>();
        if (minBet < 0) {
            violations.add("minBet (" + minBet + ") must be >= 0");
        }
        if (maxBet > 0 && minBet > maxBet) {
            violations.add("minBet (" + minBet + ") must be <= maxBet (" + maxBet + ")");
        }

        if (!violations.isEmpty()) {
            throw new BadRequestException(
                    "Invalid bot-group config: " + String.join("; ", violations));
        }
    }
}
