package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.infrastructure.plugin.PluginBundle;
import com.vingame.bot.infrastructure.plugin.PluginJar;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import com.vingame.bot.infrastructure.plugin.PluginRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PLUGIN_HOT_RELOAD_3_4 D-15: the production constructor registers the accepted bundle's
 * loader under the bundle's version — exactly once — and the boot line keeps its
 * {@code plugin runtime: version=…, classloader=…} prefix with {@code source=} and
 * {@code jars=} appended.
 * <p>
 * {@code PluginClassLoaderMetricsTest} is deliberately untouched (Phase 4a's gate): it
 * drives the Phase 1 resolver constructor, which reproduces the classpath reading. This
 * class covers the path Spring actually wires since 4a, with a bundle that is <em>not</em>
 * the classpath one, so a constructor that ignored the bundle and kept registering the
 * application loader under {@code builtin} fails here.
 */
@DisplayName("PluginClassLoaderMetrics — registers the accepted bundle (D-15)")
class PluginClassLoaderMetricsBundleTest {

    private static final String VERSION = "20261007.101500";

    private SimpleMeterRegistry registry;
    private PluginClassLoaderMetrics metrics;
    private URLClassLoader bundleLoader;
    private Capture capture;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        bundleLoader = new URLClassLoader("plugin-" + VERSION, new URL[0], getClass().getClassLoader());
        FixedBundle bundle = new FixedBundle(bundleLoader);
        PluginRuntime runtime = new PluginRuntime(new PluginRegistries(bundle,
                mock(BettingStrategyFactory.class), mock(SlotStrategyFactory.class),
                mock(MessageTypesRegistry.class)));
        metrics = new PluginClassLoaderMetrics(registry, runtime);
        capture = new Capture();
    }

    @AfterEach
    void tearDown() throws Exception {
        capture.close();
        metrics.stop();
        registry.close();
        bundleLoader.close();
    }

    @Test
    @DisplayName("the bundle's loader is registered once, under the bundle's version, and nothing under builtin")
    void registersTheBundleLoaderUnderItsVersion() {
        metrics.start();

        assertThat(registry.get(PluginClassLoaderMetrics.LIVE)
                .tag(PluginClassLoaderMetrics.TAG, VERSION).gauge().value()).isEqualTo(1d);
        assertThat(registry.get(PluginClassLoaderMetrics.REGISTERED)
                .tag(PluginClassLoaderMetrics.TAG, VERSION).counter().count()).isEqualTo(1d);
        assertThat(registry.get(PluginClassLoaderMetrics.RECLAIMED)
                .tag(PluginClassLoaderMetrics.TAG, VERSION).counter().count()).isEqualTo(0d);
        assertThat(registry.find(PluginClassLoaderMetrics.LIVE)
                .tag(PluginClassLoaderMetrics.TAG, "builtin").gauges())
                .as("the application loader is not registered as well — exactly once, the accepted bundle only")
                .isEmpty();
    }

    @Test
    @DisplayName("the boot line keeps its prefix and appends source= and jars= with 12-hex digests")
    void bootLineAppendsSourceAndJars() {
        metrics.start();

        List<String> lines = capture.lines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("plugin runtime: version=" + VERSION
                + ", classloader=" + System.identityHashCode(bundleLoader)
                + ", source=/app/plugins/" + VERSION
                + ", jars=[bot-messages-1.0.jar sha256=0123456789ab,"
                + " bot-strategies-1.0.jar sha256=fedcba987654]");
    }

    /** A non-classpath bundle with fixed identity. */
    private static final class FixedBundle extends PluginBundle {
        private final ClassLoader loader;

        FixedBundle(ClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public String version() {
            return VERSION;
        }

        @Override
        public String source() {
            return "/app/plugins/" + VERSION;
        }

        @Override
        public ClassLoader classLoader() {
            return loader;
        }

        @Override
        public List<PluginJar> jars() {
            return List.of(
                    new PluginJar("bot-messages-1.0.jar", "0123456789abcdef0123456789abcdef"),
                    new PluginJar("bot-strategies-1.0.jar", "fedcba9876543210fedcba9876543210"));
        }

        @Override
        public <T> List<T> beansOfType(Class<T> type) {
            return List.of();
        }

        @Override
        public <T> T newInstance(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void closeContext() {
        }

        @Override
        protected void closeClassLoader() {
        }
    }

    /** The {@code plugin runtime:} lines emitted by {@link PluginClassLoaderMetrics}. */
    private static final class Capture extends AbstractAppender implements AutoCloseable {
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final LoggerContext ctx;
        private final LoggerConfig config;
        private final Level previous;

        Capture() {
            super("CapturingAppender-plugin-runtime", null, PatternLayout.createDefaultLayout(), false, null);
            start();
            ctx = (LoggerContext) LogManager.getContext(false);
            config = ctx.getConfiguration().getLoggerConfig("com.vingame.bot");
            previous = config.getLevel();
            config.addAppender(this, Level.ALL, null);
            config.setLevel(Level.INFO);
            ctx.updateLoggers();
        }

        @Override
        public void append(LogEvent event) {
            String message = event.getMessage().getFormattedMessage();
            if (PluginClassLoaderMetrics.class.getName().equals(event.getLoggerName())
                    && message.startsWith("plugin runtime:")) {
                lines.add(message);
            }
        }

        List<String> lines() {
            return List.copyOf(lines);
        }

        @Override
        public void close() {
            config.removeAppender(getName());
            config.setLevel(previous);
            ctx.updateLoggers();
        }
    }
}
