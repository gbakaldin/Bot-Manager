package com.vingame.bot.infrastructure.plugin;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.LRUMap;
import com.fasterxml.jackson.databind.util.LookupCache;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;

import static org.mockito.Mockito.mock;

/**
 * {@link PluginRuntime}s for bot-app unit tests that build a {@code BotFactory},
 * {@code StrategyCatalog} or {@code BotGroupConfigValidationService} by hand
 * (PLUGIN_HOT_RELOAD_3_4 Phase 4a, plan step 7). Those classes used to take the three
 * registries as constructor arguments; they take a {@link PluginRuntime} now, and this
 * wraps whatever registries — real or mocked — the test already had.
 * <p>
 * The bundle is a Mockito mock: none of these consumers reads it. The type factory is a
 * real one with a private cache, built the way {@link PluginBundle} builds its own, so a
 * bot built from it carries a non-default factory exactly as in production.
 */
public final class TestPluginRuntimes {

    private TestPluginRuntimes() {
    }

    public static PluginRuntime of(BettingStrategyFactory betting,
                                   SlotStrategyFactory slot,
                                   MessageTypesRegistry messageTypes) {
        TypeFactory typeFactory = TypeFactory.defaultInstance()
                .withCache((LookupCache<Object, JavaType>) new LRUMap<Object, JavaType>(16, 200));
        return PluginRuntime.of(new PluginRegistries(mock(PluginBundle.class),
                betting, slot, messageTypes, typeFactory));
    }
}
