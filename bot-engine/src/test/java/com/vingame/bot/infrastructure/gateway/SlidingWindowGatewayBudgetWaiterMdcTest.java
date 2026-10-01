package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.common.logging.BotMdc;
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
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A27.1: an admitted <b>waiter</b>'s DEBUG line carries the MDC of the bot that queued, not of
 * whichever thread happened to run the admission pass — and that thread's own MDC survives it.
 * <p>
 * The pass here is run from the test thread while it carries a <em>different</em> group's context,
 * which is the {@code reserve}/{@code release} shape: another group's start thread admitting this
 * group's waiter.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — a waiter's admission line carries the waiter's MDC")
class SlidingWindowGatewayBudgetWaiterMdcTest {

    private static final String LOGGER = SlidingWindowGatewayBudget.class.getName();

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private AbstractAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level originalLevel;
    private AtomicLong clock;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() {
        appender = new AbstractAppender("waiter-mdc-capture", null, PatternLayout.createDefaultLayout(), true, null) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration().getLoggerConfig(LOGGER);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        loggerConfig.setLevel(Level.DEBUG);
        ctx.updateLoggers();

        clock = new AtomicLong(0L);
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE).withHardCap(900),
                new SimpleMeterRegistry(), clock::get);
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
        loggerConfig.removeAppender("waiter-mdc-capture");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("the waiter's group tags its own admission; the admitting thread keeps its own context")
    void theWaitersMdcTagsItsAdmission() throws Exception {
        GatewayRequestScope scope = GatewayRequestScope.forBot("group-waiter", "bot-w", () -> false);
        for (int i = 0; i < 900; i++) {
            budget.execute(RequestTier.ESSENTIAL, scope, () -> "fill");
        }

        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            BotMdc.setGroupContext("group-waiter", "env-1", "116");
            try {
                budget.execute(RequestTier.ESSENTIAL, scope, () -> "queued", Duration.ofSeconds(20));
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                MDC.clear();
                done.countDown();
            }
        });
        for (int i = 0; i < 400 && budget.snapshot().queuedEssential() < 1; i++) {
            Thread.sleep(5);
        }
        assertThat(budget.snapshot().queuedEssential()).isEqualTo(1);
        events.clear();

        // Another group's thread runs the pass that admits the waiter.
        BotMdc.setGroupContext("group-other", "env-1", "116");
        clock.set(Duration.ofMinutes(5).toNanos() + 1);
        budget.admitWaiters();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        LogEvent admitted = events.stream()
                .filter(e -> e.getLoggerName().equals(LOGGER))
                .filter(e -> e.getMessage().getFormattedMessage().contains("admitted ESSENTIAL request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no admission DEBUG line"));
        assertThat(admitted.getContextData().<String>getValue(BotMdc.BOT_GROUP_ID))
                .as("the line belongs to the waiter's group, not to the thread that admitted it")
                .isEqualTo("group-waiter");
        assertThat(MDC.get(BotMdc.BOT_GROUP_ID))
                .as("restored, not cleared — the admitting thread may be in its own group's start")
                .isEqualTo("group-other");
    }

    @Test
    @DisplayName("under SCOPED debug (logger at INFO, only the waiter's group armed) the line is still emitted")
    void theWaitersLineSurvivesScopedDebug() throws Exception {
        // review-phase5. ScopedDebugFilter decides from the CALLING thread's MDC. The level check used
        // to run before the waiter's MDC was put on, i.e. under the admitting thread's context — here
        // another group's, which is not armed — so the line was filtered out entirely.
        loggerConfig.setLevel(Level.INFO);
        com.vingame.bot.common.logging.ScopedDebugRegistry scopes = new com.vingame.bot.common.logging.ScopedDebugRegistry();
        scopes.enable("group-waiter", Duration.ofMinutes(5));
        com.vingame.bot.common.logging.ScopedDebugFilter filter = new com.vingame.bot.common.logging.ScopedDebugFilter(scopes);
        filter.start();
        ctx.getConfiguration().addFilter(filter);
        ctx.updateLoggers();
        try {
            GatewayRequestScope scope = GatewayRequestScope.forBot("group-waiter", "bot-w", () -> false);
            for (int i = 0; i < 900; i++) {
                budget.execute(RequestTier.ESSENTIAL, scope, () -> "fill");
            }
            CountDownLatch done = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                BotMdc.setGroupContext("group-waiter", "env-1", "116");
                try {
                    budget.execute(RequestTier.ESSENTIAL, scope, () -> "queued", Duration.ofSeconds(20));
                } catch (Exception e) {
                    throw new AssertionError(e);
                } finally {
                    MDC.clear();
                    done.countDown();
                }
            });
            for (int i = 0; i < 400 && budget.snapshot().queuedEssential() < 1; i++) {
                Thread.sleep(5);
            }
            events.clear();

            BotMdc.setGroupContext("group-other", "env-1", "116");
            clock.set(Duration.ofMinutes(5).toNanos() + 1);
            budget.admitWaiters();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(events.stream()
                    .filter(e -> e.getLoggerName().equals(LOGGER))
                    .filter(e -> e.getMessage().getFormattedMessage().contains("admitted ESSENTIAL request"))
                    .map(e -> e.getContextData().<String>getValue(BotMdc.BOT_GROUP_ID)))
                    .as("the scoped group's admission line reaches the appender, tagged with that group")
                    .containsExactly("group-waiter");
        } finally {
            ctx.getConfiguration().removeFilter(filter);
            ctx.updateLoggers();
        }
    }
}
