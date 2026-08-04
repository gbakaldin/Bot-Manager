package com.vingame.bot.domain.bot.strategy.slot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link SlotStrategy} implementation as the canonical Java class for a
 * given {@link SlotStrategyId}. Discovered at startup by
 * {@link SlotStrategyFactory#init()}: every Spring bean implementing
 * {@link SlotStrategy} must carry this annotation, and every
 * {@link SlotStrategyId} value must be claimed by exactly one bean.
 *
 * <p>Mirrors the betting {@code StrategyImpl}. See
 * {@code docs/plans/SLOT_MACHINE_BOT.md}, AD-9.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SlotStrategyImpl {
    SlotStrategyId value();
}
