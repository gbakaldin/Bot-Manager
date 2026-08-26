package com.vingame.bot.domain.bot.strategy.slot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link SlotStrategy} implementation as the canonical Java class for a
 * given <b>registry key</b>. Discovered at startup by
 * {@code SlotStrategyFactory.init()}: every Spring bean implementing
 * {@link SlotStrategy} must carry this annotation, and every
 * {@link SlotStrategyId} constant name must be claimed by exactly one bean.
 *
 * <p><b>The key is a {@code String}, not a {@link SlotStrategyId}</b>
 * (PLUGIN_HOT_RELOAD Phase 2a, AD-12/AD-13) — see the betting twin
 * {@code StrategyImpl} for the full reasoning. The two built-ins spell their key
 * as a literal equal to the corresponding {@link SlotStrategyId} constant name,
 * and {@code StrategyCatalogParityTest} is what type-checks that now.
 *
 * <p>Mirrors the betting {@code StrategyImpl}. See
 * {@code docs/plans/SLOT_MACHINE_BOT.md} AD-9 and
 * {@code docs/plans/PLUGIN_HOT_RELOAD.md} AD-12/AD-13.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SlotStrategyImpl {

    /**
     * Registry key. For a built-in this is exactly the {@link SlotStrategyId}
     * constant name; for a plugin-supplied strategy it is any key not already
     * claimed.
     */
    String value();
}
