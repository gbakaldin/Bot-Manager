package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.BotGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared STRICT-GRID cross-field numeric rule body for round-based betting games
 * (AD-3 in {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}). Both
 * {@link BettingMiniConfigValidator} and {@link TaiXiuConfigValidator} delegate
 * here so the rule list lives in exactly one place (TAI_XIU_BOT plan AD-8) — Tai
 * Xiu shares the same {@code minBet/maxBet/betIncrement/...} group config and
 * betting semantics as BettingMini, so it enforces the identical grid.
 *
 * <p>{@link #validate(BotGroup)} accumulates <b>every</b> violation into a list
 * and throws a single aggregated {@link BadRequestException} — it never fails on
 * the first violation — so the operator sees all problems at once. A valid config
 * returns normally.
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
 *   <li><b>Coordination cap (BET_COORDINATION AD-1):</b> when
 *       {@code coordinationEnabled} is true, {@code maxAggregateStakePerRound >= minBet}.
 *       Only enforced when coordination is on; decoupled from the per-bot
 *       {@code maxTotalBetPerRound}.</li>
 *   <li><b>Ramp shape (JACKPOT_SCALE_AND_RAMP AD-R4):</b> when {@code rampEnabled}
 *       is true, {@code rampShape > 0}. Only enforced when ramp is on.</li>
 *   <li><b>Crowd-aware coordination (CROWD_AWARE_COORDINATION AD-C6):</b> when
 *       {@code crowdAwareCoordination} is true, {@code coordinationEnabled} must
 *       also be true — the crowd tier is a sub-mode of the coordinator.</li>
 * </ul>
 *
 * <p>The entity's numeric fields are primitives ({@code long}/{@code int}), so by
 * the time this runs every value is concrete — an omitted field was already
 * defaulted to {@code 0} by {@code BotGroupMapper}, and {@code 0} is a valid value
 * for the minimum fields (so "omitted" and "explicit 0" are deliberately treated
 * identically). No null handling is required or possible.
 */
final class BettingGridRules {

    private BettingGridRules() {
        // Utility holder for the shared rule body — not instantiable.
    }

    /**
     * Validate the betting-grid numeric rules against a bot group, throwing a
     * single aggregated {@link BadRequestException} if any are violated.
     *
     * @param group the bot group to validate
     */
    static void validate(BotGroup group) {
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

        // Coordination cap (BET_COORDINATION AD-1). Only constrained when the
        // group-scoped coordinator is enabled: the aggregate per-round stake cap
        // must be at least one minBet, since a cap below one min-bet can never
        // approve a single bet. This is the group/fleet-level cap and is kept
        // deliberately decoupled from the per-bot maxTotalBetPerRound.
        if (group.isCoordinationEnabled()) {
            long maxAggregateStakePerRound = group.getMaxAggregateStakePerRound();
            if (maxAggregateStakePerRound < minBet) {
                violations.add("maxAggregateStakePerRound (" + maxAggregateStakePerRound
                        + ") must be >= minBet (" + minBet
                        + ") when coordinationEnabled is true");
            }
        }

        // Crowd-aware coordination (CROWD_AWARE_COORDINATION AD-C6). The crowd tier
        // is a sub-mode of the coordinator — it steers the coordinator's per-round
        // budget by the live crowd feed and cannot run without the internal
        // coordinator existing. So crowdAwareCoordination requires coordinationEnabled.
        if (group.isCrowdAwareCoordination() && !group.isCoordinationEnabled()) {
            violations.add("crowdAwareCoordination requires coordinationEnabled to be true");
        }

        // Ramp shape (JACKPOT_SCALE_AND_RAMP AD-R3/AD-R4). Only constrained when
        // bet ramp-up is enabled: the power-curve exponent must be strictly
        // positive (k <= 0 would be a flat/degenerate curve, i.e. today's cadence,
        // which is expressed by rampEnabled=false — enabling it with a non-positive
        // shape is a misconfiguration). Only meaningful for the betting-mini/Tai Xiu
        // grid this rule body serves.
        if (group.isRampEnabled()) {
            double rampShape = group.getRampShape();
            if (rampShape <= 0) {
                violations.add("rampShape (" + rampShape
                        + ") must be > 0 when rampEnabled is true");
            }
        }

        if (!violations.isEmpty()) {
            throw new BadRequestException(
                    "Invalid bot-group config: " + String.join("; ", violations));
        }
    }
}
