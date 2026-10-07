package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.infrastructure.plugin.ClasspathPluginBundle;
import com.vingame.bot.infrastructure.plugin.PluginBundle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Real {@link BettingStrategyFactory} / {@link SlotStrategyFactory} instances for engine
 * tests that build a bot by hand (PLUGIN_HOT_RELOAD_3_4 D-4).
 * <p>
 * The bots used to fall back to {@code new RandomBehaviorStrategy()} /
 * {@code new FixedBetStrategy()} when no factory was wired. That tied engine code to two
 * plugin classes, so the fallback is now an {@link IllegalStateException} and a fixture
 * wires a factory instead — this one, built the way production builds it: a component scan
 * of the strategy packages, wrapped in a {@link ClasspathPluginBundle} (PLUGIN_HOT_RELOAD_3_4
 * Phase 4a — the factories are per-bundle objects now, not beans), and {@code getBean(Class)}
 * for a fresh prototype per bot. A fixture that relied on the old
 * fallback and leaves {@code strategyId} / {@code slotStrategyId} null gets the same
 * {@code RANDOM} / {@code FIXED} strategy as before, because the bots default those keys.
 * <p>
 * One context per test JVM, built on first use and never closed: the factories hold only
 * their key→class tables, and every {@code create} returns a new instance, so nothing leaks
 * between tests.
 */
public final class TestStrategyFactories {

    /** The package every strategy bean lives under — StrategyCatalogParityTest's scan. */
    private static final String SCAN_BASE = "com.vingame.bot.domain.bot.strategy";

    private TestStrategyFactories() {
    }

    /** @return the real betting strategy factory, shared across tests. */
    public static BettingStrategyFactory betting() {
        return Holder.BETTING;
    }

    /** @return the real slot strategy factory, shared across tests. */
    public static SlotStrategyFactory slot() {
        return Holder.SLOT;
    }

    /** Lazy holder: only tests that ask for a factory pay for the scan. */
    private static final class Holder {
        private static final AnnotationConfigApplicationContext CONTEXT = boot();
        private static final PluginBundle BUNDLE = new ClasspathPluginBundle(CONTEXT);
        private static final BettingStrategyFactory BETTING = new BettingStrategyFactory(BUNDLE);
        private static final SlotStrategyFactory SLOT = new SlotStrategyFactory(BUNDLE);

        private static AnnotationConfigApplicationContext boot() {
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            context.scan(SCAN_BASE);
            context.refresh();
            return context;
        }
    }
}
