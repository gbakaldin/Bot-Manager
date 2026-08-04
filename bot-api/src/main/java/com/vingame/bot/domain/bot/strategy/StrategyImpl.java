package com.vingame.bot.domain.bot.strategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link BettingStrategy} implementation as the canonical Java class
 * for a given {@link StrategyId}. Discovered at startup by
 * {@link BettingStrategyFactory#init()}: every Spring bean implementing
 * {@link BettingStrategy} must carry this annotation, and every
 * {@link StrategyId} value must be claimed by exactly one bean.
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decision 12.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface StrategyImpl {
    StrategyId value();
}
