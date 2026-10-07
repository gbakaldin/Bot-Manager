package com.vingame.bot.plugin.it;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.common.logging.ScopedDebugFilter;
import com.vingame.bot.common.logging.ScopedDebugRegistry;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-5</b>: a plugin's logger binds to the application's log4j2
 * {@code LoggerContext}. The spike proved logging does not <em>pin</em> a plugin loader
 * (S6); it did not prove context <em>identity</em> — that a plugin class, whose defining
 * loader is a child loader, gets the app's context rather than a fresh one of its own
 * (log4j2's default selector keys contexts by class loader). If it got its own, the levels
 * and appenders below would not apply to it, and every plugin line would vanish or go to
 * the console. Asserted three ways, through the plugin's <em>own</em> {@code @Slf4j}
 * logger:
 * <ul>
 *   <li>the logger's context is the app's context;</li>
 *   <li>a level set on {@code com.vingame.bot} applies to it, both ways;</li>
 *   <li>{@code ScopedDebugFilter} on the app's {@code Configuration} admits a scoped plugin
 *       DEBUG line, and only for the scoped group.</li>
 * </ul>
 */
@DisplayName("L-5: plugin loggers bind to the application's LoggerContext")
class PluginLoggingContextIT {

    private static final String PLUGIN_LOGGER = "com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy";

    private PluginRegistries registries;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = new LogCapture("com.vingame.bot", Level.TRACE);
        registries = ShippedBundle.load();
    }

    @AfterEach
    void tearDown() {
        ThreadContext.remove(BotMdc.BOT_GROUP_ID);
        registries.bundle().close();
        capture.close();
    }

    @Test
    @DisplayName("the plugin's @Slf4j logger lives in the app's LoggerContext")
    void pluginLoggerIsInTheAppContext() throws Exception {
        BettingStrategy strategy = registries.bettingStrategies().create("RANDOM");
        assertThat(strategy.getClass().getClassLoader()).isSameAs(registries.bundle().classLoader());

        org.apache.logging.log4j.core.Logger core = coreLogger(pluginSlf4jLogger(strategy));

        assertThat(core.getName()).isEqualTo(PLUGIN_LOGGER);
        assertThat(core.getContext()).isSameAs(capture.context());
    }

    @Test
    @DisplayName("a level set on com.vingame.bot applies to plugin code: TRACE shows its lines, WARN hides them")
    void levelOnComVingameBotApplies() {
        BettingStrategy strategy = registries.bettingStrategies().create("RANDOM");

        ShippedBundle.exercise(strategy, new Random(7));
        assertThat(pluginEvents(Level.TRACE)).as("RandomBehaviorStrategy logs its decisions at TRACE").isNotEmpty();

        capture.setLevel(Level.WARN);
        capture.clear();
        ShippedBundle.exercise(strategy, new Random(7));
        assertThat(pluginEvents(Level.TRACE)).isEmpty();
    }

    @Test
    @DisplayName("ScopedDebugFilter admits a scoped plugin DEBUG line through the INFO logger, for the scoped group only")
    void scopedDebugFilterAdmitsAPluginLine() throws Exception {
        capture.setLevel(Level.INFO);
        org.slf4j.Logger pluginLogger = pluginSlf4jLogger(registries.bettingStrategies().create("RANDOM"));
        Configuration configuration = capture.context().getConfiguration();
        ScopedDebugRegistry scopes = new ScopedDebugRegistry();
        ScopedDebugFilter filter = new ScopedDebugFilter(scopes);
        filter.start();
        configuration.addFilter(filter);
        capture.context().updateLoggers();
        try {
            scopes.enable("g-scoped");

            ThreadContext.put(BotMdc.BOT_GROUP_ID, "g-other");
            assertThat(pluginLogger.isDebugEnabled()).as("another group stays at INFO").isFalse();
            pluginLogger.debug("plugin line for another group");

            ThreadContext.put(BotMdc.BOT_GROUP_ID, "g-scoped");
            assertThat(pluginLogger.isDebugEnabled()).isTrue();
            pluginLogger.debug("plugin line for the scoped group");

            List<LogEvent> debug = pluginEvents(Level.DEBUG);
            assertThat(debug).extracting(e -> e.getMessage().getFormattedMessage())
                    .containsExactly("plugin line for the scoped group");
            assertThat(debug.get(0).getContextData().<String>getValue(BotMdc.BOT_GROUP_ID)).isEqualTo("g-scoped");
        } finally {
            configuration.removeFilter(filter);
            filter.stop();
            capture.context().updateLoggers();
        }
    }

    private List<LogEvent> pluginEvents(Level level) {
        return capture.events().stream()
                .filter(e -> PLUGIN_LOGGER.equals(e.getLoggerName()) && e.getLevel() == level)
                .toList();
    }

    /** The {@code private static final Logger log} Lombok's {@code @Slf4j} generated in the plugin class. */
    private static org.slf4j.Logger pluginSlf4jLogger(Object pluginBean) throws Exception {
        Field field = pluginBean.getClass().getDeclaredField("log");
        field.setAccessible(true);
        return (org.slf4j.Logger) field.get(null);
    }

    private static org.apache.logging.log4j.core.Logger coreLogger(org.slf4j.Logger slf4j) throws Exception {
        Field delegate = slf4j.getClass().getDeclaredField("logger");
        delegate.setAccessible(true);
        return (org.apache.logging.log4j.core.Logger) delegate.get(slf4j);
    }
}
