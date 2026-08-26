package com.vingame.bot.domain.bot.strategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link BettingStrategy} implementation as the canonical Java class
 * for a given <b>registry key</b>. Discovered at startup by
 * {@code BettingStrategyFactory.init()}: every Spring bean implementing
 * {@link BettingStrategy} must carry this annotation, and every
 * {@link StrategyId} constant name must be claimed by exactly one bean.
 *
 * <p><b>The key is a {@code String}, not a {@link StrategyId}</b>
 * (PLUGIN_HOT_RELOAD Phase 2a, AD-12/AD-13). Annotation members must be
 * compile-time constants, so a strategy shipped from a plugin cannot name an
 * enum constant that the engine does not yet declare — a string can. The eleven
 * built-ins therefore spell their key as a literal <em>equal to the
 * corresponding {@link StrategyId} constant name</em>
 * ({@code @StrategyImpl("RANDOM")}).
 *
 * <p>That literal is what the enum used to type-check, so
 * {@code StrategyCatalogParityTest} is the replacement guard: it fails the build
 * if a built-in key drifts from its enum constant name (a typo'd
 * {@code "RANDOM "} registers a phantom key that nothing can ever look up) and
 * — unlike the enum ever could — if a bean goes missing entirely.
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md} Architecture Decision 12, and
 * {@code docs/plans/PLUGIN_HOT_RELOAD.md} AD-12/AD-13.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface StrategyImpl {

    /**
     * Registry key. For a built-in this is exactly the {@link StrategyId}
     * constant name; for a plugin-supplied strategy it is any key not already
     * claimed.
     */
    String value();
}
