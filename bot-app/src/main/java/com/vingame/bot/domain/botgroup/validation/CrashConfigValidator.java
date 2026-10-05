package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.bot.core.crash.CrashStakes;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator for {@link GameType#CRASH} groups ({@code docs/plans/AVIATOR_BOT.md} AD-15).
 *
 * <p><b>Validates only what CRASH reads.</b> A crash bot's stake is a uniform pick from
 * the group's step ladder {@code {k·step : minBet ≤ k·step ≤ maxBet}} (AD-6, computed by
 * {@link CrashStakes#ladder}), with {@code maxBet <= 0} meaning "unset — legacy window
 * [20,000, 200,000]" and {@code betIncrement <= 0} meaning "step 5,000". So the rules are
 * {@code minBet >= 0}, {@code betIncrement >= 0}, {@code minBet <= maxBet} when
 * {@code maxBet} is set, and a non-empty ladder. The per-round bet counts and
 * {@code maxTotalBetPerRound} are never read (one bet per round) and are ignored, not
 * rejected — the same reasoning as {@link CashoutConfigValidator}.
 *
 * <p>Ships in the same phase as the enum constant because
 * {@link GameConfigValidatorFactory} fails boot for any {@link GameType} without a
 * validator.
 */
@Component
public class CrashConfigValidator implements GameConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.CRASH;
    }

    @Override
    public void validate(BotGroup group) {
        long minBet = group.getMinBet();
        long maxBet = group.getMaxBet();
        long betIncrement = group.getBetIncrement();

        List<String> violations = new ArrayList<>();
        if (minBet < 0) {
            violations.add("minBet (" + minBet + ") must be >= 0");
        }
        if (betIncrement < 0) {
            violations.add("betIncrement (" + betIncrement + ") must be >= 0");
        }
        if (maxBet > 0 && minBet > maxBet) {
            violations.add("minBet (" + minBet + ") must be <= maxBet (" + maxBet + ")");
        }
        // Only meaningful once the three fields are individually sane; otherwise it would
        // restate the violation above in a less direct way.
        if (violations.isEmpty() && CrashStakes.ladder(minBet, maxBet, betIncrement).isEmpty()) {
            CrashStakes.Window w = CrashStakes.window(minBet, maxBet, betIncrement);
            violations.add("no stake fits: no multiple of step " + w.step()
                    + " lies in [" + w.min() + ", " + w.max() + "]"
                    + " (step = betIncrement, or " + CrashStakes.DEFAULT_STEP + " when unset)");
        }

        if (!violations.isEmpty()) {
            throw new BadRequestException(
                    "Invalid bot-group config: " + String.join("; ", violations));
        }
    }
}
