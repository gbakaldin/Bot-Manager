package com.vingame.bot.infrastructure.runtime;

import com.vingame.bot.common.exception.GatewayRequestCancelledException;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.core.SessionSetupHandedOffException;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * GATEWAY_REQUEST_BUDGET A33 review: {@code startBot} logs ERROR (with stack) only for a start
 * failure that is final. A bot handed to its reconnect loop has already been logged at WARN by
 * {@code Bot}, and a start called off by {@code /stop} is an operator decision; a second ERROR for
 * either would page about bots that are recovering, or that were meant to stop.
 */
@DisplayName("BotGroupRuntime.startBot — ERROR only for a final start failure (A33)")
class BotGroupRuntimeStartFailureLogTest {

    private static final class Capture extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        Capture() {
            super("a33-runtime-capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> at(Level level) {
            return events.stream().filter(e -> e.getLevel() == level).toList();
        }
    }

    private Capture capture;
    private BotGroupRuntime runtime;

    @BeforeEach
    void setUp() {
        // Created before the capture is attached: its constructor's one group-level INFO line
        // ("Created virtual thread executor") is not what these tests are about.
        runtime = new BotGroupRuntime("g-1", 1, "env-1", "Staging");
        capture = new Capture();
        capture.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig own = new LoggerConfig(BotGroupRuntime.class.getName(), Level.INFO, false);
        own.addAppender(capture, Level.INFO, null);
        ctx.getConfiguration().addLogger(BotGroupRuntime.class.getName(), own);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        runtime.getExecutor().shutdownNow();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().removeLogger(BotGroupRuntime.class.getName());
        ctx.updateLoggers();
        capture.stop();
    }

    private void startBotThatThrows(RuntimeException failure) throws Exception {
        Bot bot = mock(Bot.class);
        lenient().when(bot.getConfiguration()).thenReturn(BotConfiguration.builder()
                .game(Game.builder().id("g").name("ZicZac").gameType(GameType.BETTING_MINI).build())
                .environmentId("env-1").botGroupId("g-1").botIndex(1).build());
        lenient().when(bot.getUserName()).thenReturn("rikzz1");
        doThrow(failure).when(bot).start();

        runtime.startBot(bot);
        runtime.getBotFutures().get(0).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("handed to the reconnect loop: no ERROR")
    void handedOffIsNotAnError() throws Exception {
        startBotThatThrows(new SessionSetupHandedOffException(
                new RuntimeException("Failed to fetch balance for user: rikzz1")));
        assertThat(capture.at(Level.ERROR)).isEmpty();
        assertThat(capture.at(Level.INFO)).as("per-bot lines never at INFO").isEmpty();
    }

    @Test
    @DisplayName("called off by /stop: no ERROR")
    void calledOffIsNotAnError() throws Exception {
        startBotThatThrows(new GatewayRequestCancelledException("env-1", "g-1/rikzz1"));
        assertThat(capture.at(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("a final failure still logs one ERROR with its stack trace")
    void aFinalFailureIsStillAnError() throws Exception {
        startBotThatThrows(new IllegalStateException("boom"));
        assertThat(capture.at(Level.ERROR)).hasSize(1)
                .allSatisfy(e -> assertThat(e.getThrown()).isInstanceOf(IllegalStateException.class));
    }
}
