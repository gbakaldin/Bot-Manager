package com.vingame.bot.domain.bot.strategy.martingale;

/**
 * Option-picking preference for the Martingale-family strategies. Locked enum
 * with exactly two values (see {@code docs/plans/MARTINGALE_STRATEGIES.md},
 * Architecture Decision A2).
 *
 * <p>Passed into {@link AffinityOptionPicker}'s constructor so the picker is
 * stateless across calls — the profile never changes for a given bot's
 * lifetime. The eight concrete Martingale strategies pair one
 * {@link com.vingame.bot.domain.bot.strategy.StrategyId} per
 * {@code (progression, riskProfile)} cell, and forward their chosen
 * {@link RiskProfile} to the shared base class via the {@code super(...)}
 * constructor call (Architecture Decision A4).
 *
 * <ul>
 *   <li>{@link #CAUTIOUS} — weights each option by its raw affinity. Higher
 *       affinity → more often. Models a risk-averse player who chases the
 *       options the game designer marked as "safer."</li>
 *   <li>{@link #AGGRESSIVE} — inverts the weighting around
 *       {@code (maxAffinity + 1)}. Lower affinity → more often. Models a
 *       risk-seeking player who chases the harder-to-hit, higher-payout
 *       options.</li>
 * </ul>
 */
public enum RiskProfile {
    CAUTIOUS,
    AGGRESSIVE
}
