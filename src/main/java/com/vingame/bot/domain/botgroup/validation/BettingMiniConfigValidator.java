package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator for {@link GameType#BETTING_MINI} groups.
 *
 * <p>Implements the definitive STRICT-GRID cross-field numeric rules (AD-3 in
 * {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}). The validator accumulates
 * <b>every</b> violation into a list and throws a single aggregated
 * {@link BadRequestException} — it never fails on the first violation — so the
 * operator sees all problems at once. A valid config returns normally.
 *
 * <p>Rule set:
 * <ul>
 *   <li><b>Non-negativity:</b> no field may be negative.</li>
 *   <li><b>Minimum fields — zero explicitly valid:</b> {@code minBet >= 0},
 *       {@code minBetsPerRound >= 0}.</li>
 *   <li><b>Strictly positive fields:</b> {@code maxBet > 0},
 *       {@code betIncrement > 0}, {@code maxBetsPerRound >= 1},
 *       {@code maxTotalBetPerRound > 0}.</li>
 *   <li><b>Ordering:</b> {@code minBet <= maxBet};
 *       {@code minBetsPerRound <= maxBetsPerRound}.</li>
 *   <li><b>Grid:</b> {@code (maxBet - minBet) % betIncrement == 0}.</li>
 *   <li><b>Cross-field caps:</b> {@code maxTotalBetPerRound >= maxBet} and
 *       {@code maxTotalBetPerRound >= minBet * minBetsPerRound}.</li>
 * </ul>
 *
 * <p>The entity's numeric fields are primitives ({@code long}/{@code int}), so by
 * the time the validator runs every value is concrete — an omitted field was
 * already defaulted to {@code 0} by {@code BotGroupMapper}, and {@code 0} is a
 * valid value for the minimum fields (so "omitted" and "explicit 0" are
 * deliberately treated identically). No null handling is required or possible.
 */
@Component
public class BettingMiniConfigValidator implements GameConfigValidator {

    @Override
    public GameType supportedType() {
        return GameType.BETTING_MINI;
    }

    @Override
    public void validate(BotGroup group) {
        long minBet = group.getMinBet();
        long maxBet = group.getMaxBet();
        long betIncrement = group.getBetIncrement();
        long maxTotalBetPerRound = group.getMaxTotalBetPerRound();
        int minBetsPerRound = group.getMinBetsPerRound();
        int maxBetsPerRound = group.getMaxBetsPerRound();

        List<String> violations = new ArrayList<>();

        // Non-negativity (all fields).
        if (minBet < 0) {
            violations.add("minBet (" + minBet + ") must be >= 0");
        }
        if (maxBet < 0) {
            violations.add("maxBet (" + maxBet + ") must be >= 0");
        }
        if (betIncrement < 0) {
            violations.add("betIncrement (" + betIncrement + ") must be >= 0");
        }
        if (maxTotalBetPerRound < 0) {
            violations.add("maxTotalBetPerRound (" + maxTotalBetPerRound + ") must be >= 0");
        }
        if (minBetsPerRound < 0) {
            violations.add("minBetsPerRound (" + minBetsPerRound + ") must be >= 0");
        }
        if (maxBetsPerRound < 0) {
            violations.add("maxBetsPerRound (" + maxBetsPerRound + ") must be >= 0");
        }

        // Strictly positive maximum / step / cap fields.
        if (maxBet <= 0) {
            violations.add("maxBet (" + maxBet + ") must be > 0");
        }
        if (betIncrement <= 0) {
            violations.add("betIncrement (" + betIncrement + ") must be > 0");
        }
        if (maxBetsPerRound <= 0) {
            violations.add("maxBetsPerRound (" + maxBetsPerRound + ") must be >= 1");
        }
        if (maxTotalBetPerRound <= 0) {
            violations.add("maxTotalBetPerRound (" + maxTotalBetPerRound + ") must be > 0");
        }

        // Ordering.
        if (minBet > maxBet) {
            violations.add("minBet (" + minBet + ") must be <= maxBet (" + maxBet + ")");
        }
        if (minBetsPerRound > maxBetsPerRound) {
            violations.add("minBetsPerRound (" + minBetsPerRound
                    + ") must be <= maxBetsPerRound (" + maxBetsPerRound + ")");
        }

        // Grid: (maxBet - minBet) exactly divisible by betIncrement. Only
        // meaningful when betIncrement is positive and the range is non-negative;
        // otherwise the dedicated checks above already report the problem.
        if (betIncrement > 0 && maxBet >= minBet) {
            long range = maxBet - minBet;
            if (range % betIncrement != 0) {
                violations.add("(maxBet (" + maxBet + ") - minBet (" + minBet
                        + ")) must be exactly divisible by betIncrement (" + betIncrement + ")");
            }
        }

        // Cross-field caps. minBet * minBetsPerRound uses long math to avoid overflow.
        if (maxTotalBetPerRound < maxBet) {
            violations.add("maxTotalBetPerRound (" + maxTotalBetPerRound
                    + ") must be >= maxBet (" + maxBet + ")");
        }
        long minTotal = minBet * (long) minBetsPerRound;
        if (maxTotalBetPerRound < minTotal) {
            violations.add("maxTotalBetPerRound (" + maxTotalBetPerRound
                    + ") must be >= minBet * minBetsPerRound (" + minBet + " * "
                    + minBetsPerRound + " = " + minTotal + ")");
        }

        if (!violations.isEmpty()) {
            throw new BadRequestException(
                    "Invalid bot-group config: " + String.join("; ", violations));
        }
    }
}
