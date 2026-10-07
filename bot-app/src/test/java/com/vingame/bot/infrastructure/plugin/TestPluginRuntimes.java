package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersions;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;

import java.util.List;
import java.util.Objects;

/**
 * {@link PluginRuntime}s for bot-app unit tests that build a {@code BotFactory},
 * {@code StrategyCatalog} or {@code BotGroupConfigValidationService} by hand
 * (PLUGIN_HOT_RELOAD_3_4 Phase 4a, plan step 7). Those classes used to take the three
 * registries as constructor arguments; they take a {@link PluginRuntime} now, and this
 * wraps whatever registries — real or mocked — the test already had.
 * <p>
 * The bundle is a real, inert {@link PluginBundle} (not a Mockito mock): its
 * {@code typeFactory()} is {@code final}, so a mock would answer {@code null}, and since
 * review-4a {@code BotFactory} stamps every bot's {@code pluginVersion} from
 * {@code bundle().version()}. Its type factory is the bundle's own, with a private cache,
 * so a bot built from it carries a non-default factory exactly as in production.
 */
public final class TestPluginRuntimes {

    private TestPluginRuntimes() {
    }

    /** A runtime over these registries, with a bundle of version {@code builtin}. */
    public static PluginRuntime of(BettingStrategyFactory betting,
                                   SlotStrategyFactory slot,
                                   MessageTypesRegistry messageTypes) {
        return of(PluginVersions.BUILTIN, betting, slot, messageTypes);
    }

    /** A runtime over these registries, with a bundle of the given version. */
    public static PluginRuntime of(String version,
                                   BettingStrategyFactory betting,
                                   SlotStrategyFactory slot,
                                   MessageTypesRegistry messageTypes) {
        return new PluginRuntime(new PluginRegistries(new InertBundle(version),
                betting, slot, messageTypes));
    }

    /** No beans, no jars, the test's own loader; nothing to release. */
    static class InertBundle extends PluginBundle {
        private final String version;

        InertBundle(String version) {
            this.version = Objects.requireNonNull(version, "version");
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public String source() {
            return "test";
        }

        @Override
        public ClassLoader classLoader() {
            return InertBundle.class.getClassLoader();
        }

        @Override
        public List<PluginJar> jars() {
            return List.of();
        }

        @Override
        public <T> List<T> beansOfType(Class<T> type) {
            return List.of();
        }

        @Override
        public <T> T newInstance(Class<T> type) {
            throw new UnsupportedOperationException("TestPluginRuntimes bundles hold no beans");
        }

        @Override
        protected void closeContext() {
        }

        @Override
        protected void closeClassLoader() {
        }
    }
}
