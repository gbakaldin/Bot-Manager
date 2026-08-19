package com.vingame.bot.infrastructure.logging;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.common.logging.ScopedDebugFilter;
import com.vingame.bot.common.logging.ScopedDebugRegistry;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-9 / AD-10 — end-to-end proof that scoped per-group DEBUG actually
 * reaches an appender through an INFO-level logger, in a real Spring context, with the real
 * log4j2 configuration.
 * <p>
 * <b>Why a Spring test and not a unit test.</b> {@code ScopedDebugFilterTest} proves the
 * filter's decisions in isolation; what it cannot prove is the part that has three
 * independent ways to be silently wrong in production:
 * <ol>
 *   <li>the filter is attached where it actually has an effect. It is installed
 *       programmatically because the pinned {@code annotationProcessorPaths} means a
 *       {@code @Plugin} would have no {@code Log4j2Plugins.dat} entry in the fat jar — and
 *       it goes on the {@code Configuration}, not on the {@code com.vingame.bot}
 *       LoggerConfig as AD-9 says: only the Configuration's filter is consulted before the
 *       level check. This test is what caught that; on the LoggerConfig the filter installs
 *       cleanly and promotes nothing;</li>
 *   <li>{@code ACCEPT} really does beat the level check, i.e. a DEBUG event is emitted by a
 *       logger configured at INFO (this is the entire mechanism, and it rests on
 *       {@code Logger.PrivateConfig.filter} consulting the filter <em>before</em> the
 *       level);</li>
 *   <li>the installation survives the level-override path that Phase 1 shipped
 *       ({@code LOGGING_LEVEL_COM_VINGAME_BOT} / {@code /actuator/loggers}), which mutates
 *       this same LoggerConfig. A level change that quietly dropped the filter would leave
 *       an endpoint that answers 200 and does nothing.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/bot-manager-scoped-debug-test"
                + "?serverSelectionTimeoutMS=200&connectTimeoutMS=200",
        "logging.level.org.mongodb.driver.cluster=OFF",
        // The shipped production posture: INFO. The whole point is getting DEBUG out of it.
        "logging.level.com.vingame.bot=INFO",
})
@DisplayName("Scoped per-group DEBUG, installed (AD-9/AD-10)")
class ScopedDebugFilterInstallationTest {

    private static final String APP_LOGGER = "com.vingame.bot";
    private static final String TEST_LOGGER = "com.vingame.bot.scopedDebugProbe";
    private static final String SCOPED_GROUP = "group-under-investigation";
    private static final String OTHER_GROUP = "group-minding-its-own-business";

    @MockitoBean
    private BotGroupRepository botGroupRepository;

    @Autowired
    private ScopedDebugRegistry registry;

    @Autowired
    private ScopedDebugInstaller installer;

    @Autowired
    private LoggingSystem loggingSystem;

    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig appLoggerConfig;

    @BeforeEach
    void setUp() {
        // Re-assert the installation: other cached Spring contexts in this module install
        // their own filter onto the same (JVM-global) LoggerContext, and whichever started
        // last owns it. install() is idempotent, so this simply guarantees the filter under
        // test is the one wired to THIS context's registry.
        installer.install();

        ctx = (LoggerContext) LogManager.getContext(false);
        appLoggerConfig = ctx.getConfiguration().getLoggerConfig(APP_LOGGER);
        appender = new CapturingAppender("CapturingAppender-scoped-debug");
        appender.start();
        appLoggerConfig.addAppender(appender, Level.ALL, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        appLoggerConfig.removeAppender(appender.getName());
        ctx.updateLoggers();
        registry.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("the filter is attached to the Configuration — the only place it can work")
    void filterIsAttachedToTheConfiguration() {
        assertThat(appLoggerConfig.getName())
                .as("the promoted lines still route through the application LoggerConfig")
                .isEqualTo(APP_LOGGER);
        assertThat(ownFilters()).hasSize(1);
    }

    @Test
    @DisplayName("a scoped group does NOT promote third-party loggers")
    void thirdPartyLoggersAreNotPromoted() {
        registry.enable(SCOPED_GROUP, Duration.ofMinutes(5));
        MDC.put(BotMdc.BOT_GROUP_ID, SCOPED_GROUP);

        // The filter hangs off the Configuration, which every logger consults, so without the
        // logger-name gate a scoped bot thread would also surface the Mongo driver's and
        // Netty's DEBUG — which is a flood, not a drill-in.
        assertThat(LoggerFactory.getLogger("org.mongodb.driver.protocol").isDebugEnabled()).isFalse();
        assertThat(LoggerFactory.getLogger("io.netty.channel").isDebugEnabled()).isFalse();
        assertThat(LoggerFactory.getLogger(TEST_LOGGER).isDebugEnabled()).isTrue();
    }

    @Test
    @DisplayName("with no scope open, DEBUG stays off — the default posture is untouched")
    void noScopeMeansNoDebug() {
        Logger logger = LoggerFactory.getLogger(TEST_LOGGER);
        MDC.put(BotMdc.BOT_GROUP_ID, SCOPED_GROUP);

        logger.debug("must not be emitted");

        assertThat(logger.isDebugEnabled()).isFalse();
        assertThat(messages()).isEmpty();
    }

    @Test
    @DisplayName("a scoped group's DEBUG is emitted through an INFO-level logger")
    void scopedGroupGetsDebugThroughAnInfoLogger() {
        assertThat(appLoggerConfig.getLevel()).as("precondition: the logger is at INFO").isEqualTo(Level.INFO);
        registry.enable(SCOPED_GROUP, Duration.ofMinutes(5));

        Logger logger = LoggerFactory.getLogger(TEST_LOGGER);
        MDC.put(BotMdc.BOT_GROUP_ID, SCOPED_GROUP);
        logger.debug("scoped line for {}", SCOPED_GROUP);

        assertThat(logger.isDebugEnabled())
                .as("ACCEPT must beat the level check — that is the whole mechanism")
                .isTrue();
        assertThat(messages()).containsExactly("scoped line for " + SCOPED_GROUP);
    }

    @Test
    @DisplayName("every other group stays at INFO while one group is scoped (P2-2)")
    void otherGroupsAreUnaffected() {
        registry.enable(SCOPED_GROUP, Duration.ofMinutes(5));
        Logger logger = LoggerFactory.getLogger(TEST_LOGGER);

        MDC.put(BotMdc.BOT_GROUP_ID, OTHER_GROUP);
        logger.debug("other group DEBUG");
        assertThat(logger.isDebugEnabled()).isFalse();

        MDC.remove(BotMdc.BOT_GROUP_ID);
        logger.debug("untagged DEBUG");

        // …but INFO from anyone still flows: the filter is never DENY.
        logger.info("everyone's INFO");

        assertThat(messages()).containsExactly("everyone's INFO");
    }

    @Test
    @DisplayName("TRACE is not promoted with DEBUG — the raw frame dumps stay opt-in")
    void traceIsNotPromoted() {
        registry.enable(SCOPED_GROUP, Duration.ofMinutes(5));
        Logger logger = LoggerFactory.getLogger(TEST_LOGGER);
        MDC.put(BotMdc.BOT_GROUP_ID, SCOPED_GROUP);

        logger.trace("raw frame dump");

        assertThat(logger.isTraceEnabled()).isFalse();
        assertThat(messages()).isEmpty();
    }

    @Test
    @DisplayName("disabling the scope stops the DEBUG immediately (P2-3)")
    void disablingStopsTheFlow() {
        registry.enable(SCOPED_GROUP, Duration.ofMinutes(5));
        Logger logger = LoggerFactory.getLogger(TEST_LOGGER);
        MDC.put(BotMdc.BOT_GROUP_ID, SCOPED_GROUP);
        logger.debug("while scoped");

        registry.disable(SCOPED_GROUP);
        logger.debug("after the scope is gone");

        assertThat(messages()).containsExactly("while scoped");
    }

    @Test
    @DisplayName("the filter survives a logging.level.* override of the same LoggerConfig")
    void filterSurvivesTheLevelOverridePath() {
        // Exactly the path LOGGING_LEVEL_COM_VINGAME_BOT and /actuator/loggers take:
        // Spring Boot's LoggingSystem mutating the com.vingame.bot LoggerConfig in place.
        // Phase 1 verified that additivity and the appenderRefs survive it; the filter has
        // to survive it too, or scoped DEBUG silently dies the first time anyone touches
        // the level.
        try {
            loggingSystem.setLogLevel(APP_LOGGER, LogLevel.DEBUG);
            loggingSystem.setLogLevel(APP_LOGGER, LogLevel.INFO);

            LoggerContext current = (LoggerContext) LogManager.getContext(false);
            assertThat(current.getConfiguration().getLoggerConfig(APP_LOGGER).getName())
                    .isEqualTo(APP_LOGGER);
            assertThat(current.getConfiguration()).isSameAs(ctx.getConfiguration());
            assertThat(ownFilters())
                    .as("the level override must not drop the scoped-debug filter")
                    .hasSize(1);

            // And it still works, not just still present.
            registry.enable(SCOPED_GROUP, Duration.ofMinutes(5));
            MDC.put(BotMdc.BOT_GROUP_ID, SCOPED_GROUP);
            assertThat(LoggerFactory.getLogger(TEST_LOGGER).isDebugEnabled()).isTrue();
        } finally {
            loggingSystem.setLogLevel(APP_LOGGER, LogLevel.INFO);
        }
    }

    @Test
    @DisplayName("re-installing does not stack duplicate filters")
    void installIsIdempotent() {
        installer.install();
        installer.install();

        assertThat(ownFilters())
                .as("addFilter composes rather than replaces, so a re-install must detach first")
                .hasSize(1);
    }

    @Test
    @DisplayName("isInstalled() reports ATTACHMENT, not the fact that install() once succeeded")
    void isInstalledReflectsAttachment() {
        assertThat(installer.isInstalled()).isTrue();

        // What a stale installer from a torn-down Spring context does on its way past: strip
        // any ScopedDebugFilter from the live configuration. The flag stays true, so
        // reporting the flag would have GET /api/v1/logging/debug claim `enabled: true`
        // while every POST answered 200 and promoted nothing.
        detachSilently();

        assertThat(installer.isAttached()).isFalse();
        assertThat(installer.isInstalled())
                .as("the DTO documents `enabled` as the filter actually being attached")
                .isFalse();

        installer.install();
        assertThat(installer.isInstalled()).isTrue();
    }

    @Test
    @DisplayName("the sweeper re-asserts attachment, so a lost re-install is not permanent")
    void sweepReattachesADetachedFilter() {
        detachSilently();
        assertThat(installer.isAttached()).isFalse();

        installer.sweepOnce();

        assertThat(installer.isAttached())
                .as("install() drops a concurrent request on its CAS rather than retrying; "
                        + "without this re-assert a single lost re-install is permanent")
                .isTrue();
        assertThat(ownFilters()).hasSize(1);
    }

    /**
     * Strip every {@link ScopedDebugFilter} from the live Configuration <em>without</em>
     * calling {@code updateLoggers()} — that fires the context's {@code config} property
     * change, which the installer's own listener answers by re-installing, so a test that
     * called it would silently repair the very state it is trying to create. (That the
     * listener does repair it is the point of the listener; this method models the paths
     * that do not go through it, e.g. a stale installer's {@code removeExistingFilters}.)
     */
    private void detachSilently() {
        for (Filter each : ownFilters()) {
            ctx.getConfiguration().removeFilter(each);
        }
    }

    /**
     * Occurrences of <em>this installer's own</em> filter instance on the live Configuration.
     * Counting by type instead would count filters belonging to other cached Spring contexts'
     * installers, which are legitimately attached in a shared test JVM and are none of this
     * test's business.
     */
    private List<Filter> ownFilters() {
        List<Filter> found = new ArrayList<>();
        for (Filter each : scopedFilters(ctx.getConfiguration().getFilter())) {
            if (each == installer.installedFilter()) {
                found.add(each);
            }
        }
        return found;
    }

    private static List<Filter> scopedFilters(Filter filter) {
        List<Filter> found = new ArrayList<>();
        if (filter instanceof ScopedDebugFilter) {
            found.add(filter);
        } else if (filter instanceof CompositeFilter composite) {
            for (Filter each : composite.getFiltersArray()) {
                if (each instanceof ScopedDebugFilter) {
                    found.add(each);
                }
            }
        }
        return found;
    }

    private List<String> messages() {
        List<String> messages = new ArrayList<>();
        for (LogEvent event : appender.events()) {
            if (TEST_LOGGER.equals(event.getLoggerName())) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }
        return messages;
    }

    /** Minimal in-memory appender (same idiom as StrategyDecisionLogLevelTest). */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return new ArrayList<>(events);
        }
    }
}
