package com.vingame.bot.infrastructure.logging;

import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-7 — the shipped default level is INFO and it is overridable
 * from the environment, <em>without</em> losing the appenders that make logging work.
 * <p>
 * <b>Why this is a test and not a note.</b> AD-7 is written as an assumption ("Spring
 * Boot's {@code Log4J2LoggingSystem} lists {@code log4j2.properties} as a standard
 * config location and applies {@code logging.level.*} over it afterwards") with an
 * explicit instruction that it "must be verified, not assumed". The failure mode if it
 * does not hold is not a compile error and not a visibly broken app: it is either the
 * level override silently doing nothing (prod keeps running at DEBUG and the whole
 * phase is inert), or — much worse — {@code setLogLevel} creating a <em>new</em>
 * {@code LoggerConfig} for {@code com.vingame.bot} that inherits neither
 * {@code additivity = false} nor the two appenderRefs, so application logs either
 * double-write through the root logger or stop reaching the rolling file entirely.
 * Both are invisible until someone reads the box's disk. So the exact post-conditions
 * are pinned here.
 * <p>
 * <b>The two halves of the chain.</b> The compose variable is
 * {@code LOGGING_LEVEL_COM_VINGAME_BOT}; what Log4j2 ends up with is a
 * {@code LoggerConfig} level. Between them sit two independent mechanisms, and each is
 * covered by one of the tests below:
 * <ol>
 *   <li>{@link EnvVarRelaxedBinding} — Spring's relaxed binding maps the SCREAMING_SNAKE
 *       environment variable onto the {@code logging.level.com.vingame.bot} property,
 *       binding it exactly the way {@code LoggingApplicationListener} does.</li>
 *   <li>The outer test — that property, applied over a {@code log4j2.properties}
 *       configuration, lands on the existing {@code com.vingame.bot} LoggerConfig and
 *       leaves its additivity and appenderRefs intact.</li>
 * </ol>
 * If the outer test ever fails, the fallback is the one AD-7 names:
 * {@code logger.app.level = ${env:BOT_LOG_LEVEL:-info}} in <b>both</b>
 * {@code log4j2.properties} twins, dropping the compose variable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/bot-manager-logging-test"
                + "?serverSelectionTimeoutMS=200&connectTimeoutMS=200",
        "logging.level.org.mongodb.driver.cluster=OFF",
        // Exactly what LOGGING_LEVEL_COM_VINGAME_BOT relaxed-binds to (see the nested
        // test). Set to INFO here because INFO is the shipped prod posture; the nested
        // DEBUG context proves the override is real in both directions.
        "logging.level.com.vingame.bot=INFO",
})
@DisplayName("AD-7 — logging.level.com.vingame.bot overrides log4j2.properties in place")
class LoggingLevelOverrideTest {

    /** The two appenders {@code log4j2.properties} attaches to {@code com.vingame.bot}. */
    private static final String CONSOLE_APPENDER = "ConsoleAppender";
    private static final String ASYNC_APPENDER = "AsyncRolling";

    /** Same stub, same reason, as {@code ApplicationContextLoadsTest}. */
    @MockitoBean
    private BotGroupRepository botGroupRepository;

    private static LoggerConfig appLoggerConfig() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        return ctx.getConfiguration().getLoggerConfig("com.vingame.bot");
    }

    @Test
    @DisplayName("the override reaches the com.vingame.bot LoggerConfig itself, not a new one")
    void overrideMutatesTheExistingLoggerConfig() {
        LoggerConfig config = appLoggerConfig();

        // getLoggerConfig() walks up to the nearest ancestor, so a name mismatch here
        // would mean the property created (or failed to find) a different config and we
        // are actually looking at root.
        assertThat(config.getName())
                .as("logging.level.* must land on the LoggerConfig declared in log4j2.properties")
                .isEqualTo("com.vingame.bot");
        assertThat(config.getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("additivity = false and both appenderRefs survive the override")
    void overridePreservesAdditivityAndAppenders() {
        LoggerConfig config = appLoggerConfig();

        // If setLogLevel had replaced the config, this would flip to true (the default)
        // and every application line would ALSO be written by the root logger.
        assertThat(config.isAdditive())
                .as("additivity=false must survive; otherwise every line double-writes via root")
                .isFalse();
        // And this would be empty, which is the silent catastrophe: no rolling file, no
        // console, an application that looks like it stopped logging.
        assertThat(config.getAppenders().keySet())
                .as("both appenderRefs must survive the level override")
                .contains(CONSOLE_APPENDER, ASYNC_APPENDER);
    }

    /**
     * The other direction, in a second context: staging sets {@code BOT_LOG_LEVEL=DEBUG}
     * and must actually get DEBUG. Without this, the outer test would still pass if the
     * override were inert and the file simply said {@code info}.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
            "spring.data.mongodb.uri=mongodb://localhost:27017/bot-manager-logging-test"
                    + "?serverSelectionTimeoutMS=200&connectTimeoutMS=200",
            "logging.level.org.mongodb.driver.cluster=OFF",
            "logging.level.com.vingame.bot=DEBUG",
    })
    @DisplayName("with the staging posture (DEBUG)")
    class StagingLevelContext {

        @MockitoBean
        private BotGroupRepository botGroupRepository;

        @Test
        @DisplayName("the level is DEBUG and the appenders are still attached")
        void stagingGetsDebug() {
            LoggerConfig config = appLoggerConfig();

            assertThat(config.getName()).isEqualTo("com.vingame.bot");
            assertThat(config.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(config.isAdditive()).isFalse();
            assertThat(config.getAppenders().keySet()).contains(CONSOLE_APPENDER, ASYNC_APPENDER);
        }
    }

    /**
     * The first half of the chain, with no container: the compose variable name really
     * does bind to the property the outer test sets.
     * <p>
     * This mirrors {@code LoggingApplicationListener.setLogLevels} exactly — it binds the
     * {@code logging.level} prefix as a {@code Map<String, String>} through a
     * {@link Binder} over an environment whose {@code systemEnvironment} source has been
     * replaced with our fixture. Using the real
     * {@link SystemEnvironmentPropertySource} is the load-bearing part: it is that class
     * (not the {@link Binder}) which knows that {@code LOGGING_LEVEL_COM_VINGAME_BOT}
     * and {@code logging.level.com.vingame.bot} are the same name.
     */
    @Nested
    @DisplayName("LOGGING_LEVEL_COM_VINGAME_BOT relaxed-binds to logging.level.com.vingame.bot")
    class EnvVarRelaxedBinding {

        @Test
        @DisplayName("the environment variable name resolves to the map key the listener reads")
        void envVarBindsToLoggingLevelMapKey() {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("LOGGING_LEVEL_COM_VINGAME_BOT", "INFO")));

            Map<String, String> levels = Binder.get(environment)
                    .bind("logging.level", Bindable.mapOf(String.class, String.class))
                    .orElseGet(Collections::emptyMap);

            // The key must be the DOTTED logger name. If relaxed binding produced
            // `com-vingame-bot` (the hyphenated form it uses for ordinary properties),
            // Log4j2 would create a LoggerConfig for a logger nothing logs to and the
            // override would be silently inert.
            assertThat(levels).containsEntry("com.vingame.bot", "INFO");
        }
    }
}
