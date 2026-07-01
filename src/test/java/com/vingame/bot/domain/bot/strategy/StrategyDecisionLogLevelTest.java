package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.domain.bot.strategy.slot.FixedBetStrategy;
import com.vingame.bot.domain.bot.strategy.slot.RandomBetStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotBetContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STRATEGY_DECISION_AGGREGATION Phase 3 — assert the per-bet strategy-decision
 * lines were demoted DEBUG → TRACE (the signal now rides the 5s aggregate flush)
 * while balance / status / race-fallback lines stay DEBUG.
 * <p>
 * Two complementary checks:
 * <ol>
 *   <li><b>Behavioral</b> — the slot bet strategies ({@link FixedBetStrategy},
 *       {@link RandomBetStrategy}) are directly invocable, so we capture their
 *       output at a DEBUG threshold (suppressed) vs a TRACE threshold (emitted),
 *       proving the demotion is real and level-routed, not just a text change.</li>
 *   <li><b>Structural</b> — for the strategy/bot files whose decision paths are
 *       not cheaply invocable, scan the source and assert each enumerated decision
 *       phrase is on a {@code log.trace} line and each "do not demote"
 *       balance/status phrase remains on a {@code log.debug} line. This guards
 *       against accidental re-promotion or over-demotion in future edits.</li>
 * </ol>
 */
@DisplayName("STRATEGY_DECISION_AGGREGATION Phase 3 — decision lines demoted to TRACE")
class StrategyDecisionLogLevelTest {

    private CapturingAppender appender;
    private LoggerContext ctx;
    private final List<LoggerConfig> touched = new ArrayList<>();
    private final List<Level> prevLevels = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (appender != null) {
            for (int i = 0; i < touched.size(); i++) {
                touched.get(i).removeAppender(appender.getName());
                touched.get(i).setLevel(prevLevels.get(i));
            }
            ctx.updateLoggers();
        }
    }

    private void attach(String loggerName, Level level) {
        appender = new CapturingAppender("CapturingAppender-loglevel");
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig cfg = ctx.getConfiguration().getLoggerConfig(loggerName);
        touched.add(cfg);
        prevLevels.add(cfg.getLevel());
        cfg.addAppender(appender, Level.ALL, null);
        cfg.setLevel(level);
        ctx.updateLoggers();
    }

    private static SlotBetContext slotCtx() {
        return new SlotBetContext(List.of(100L, 500L, 1000L), 25, 50_000L, new Random(1));
    }

    @Test
    @DisplayName("FixedBetStrategy.chooseBet: suppressed at DEBUG, emitted at TRACE")
    void fixedBetStrategy_logsAtTrace() {
        // At DEBUG threshold the demoted line must NOT appear.
        attach(FixedBetStrategy.class.getName(), Level.DEBUG);
        new FixedBetStrategy().chooseBet(slotCtx());
        assertThat(chooseBetEvents()).as("no chooseBet line at DEBUG").isEmpty();
        tearDown();
        touched.clear();
        prevLevels.clear();

        // At TRACE threshold it reappears — the drill-in is preserved.
        attach(FixedBetStrategy.class.getName(), Level.TRACE);
        new FixedBetStrategy().chooseBet(slotCtx());
        List<LogEvent> traceEvents = chooseBetEvents();
        assertThat(traceEvents).hasSize(1);
        assertThat(traceEvents.get(0).getLevel()).isEqualTo(Level.TRACE);
    }

    @Test
    @DisplayName("RandomBetStrategy.chooseBet: suppressed at DEBUG, emitted at TRACE")
    void randomBetStrategy_logsAtTrace() {
        attach(RandomBetStrategy.class.getName(), Level.DEBUG);
        new RandomBetStrategy().chooseBet(slotCtx());
        assertThat(chooseBetEvents()).as("no chooseBet line at DEBUG").isEmpty();
        tearDown();
        touched.clear();
        prevLevels.clear();

        attach(RandomBetStrategy.class.getName(), Level.TRACE);
        new RandomBetStrategy().chooseBet(slotCtx());
        List<LogEvent> traceEvents = chooseBetEvents();
        assertThat(traceEvents).hasSize(1);
        assertThat(traceEvents.get(0).getLevel()).isEqualTo(Level.TRACE);
    }

    private List<LogEvent> chooseBetEvents() {
        return appender.events().stream()
                .filter(e -> e.getMessage().getFormattedMessage().contains("chooseBet: amount="))
                .toList();
    }

    // ---- Structural guard over the enumerated decision / preserved lines. ----

    @Test
    @DisplayName("structural: every enumerated per-bet decision phrase is on a log.trace line")
    void decisionPhrases_areTrace() {
        assertPhrasesUse("domain/bot/core/BettingMiniGameBot.java", "log.trace",
                "sending bet option=", "strategy parked decision", "strategy skipped tick");
        assertPhrasesUse("domain/bot/core/SlotMachineBot.java", "log.trace",
                "parked spin bet=", "sending spin gid=");
        assertPhrasesUse("domain/bot/strategy/RandomBehaviorStrategy.java", "log.trace",
                ".decide: bet option=", ".decide: skip", "onRoundEnd: sessionId=");
        assertPhrasesUse("domain/bot/strategy/martingale/MartingaleStrategySupport.java", "log.trace",
                ".decide: bet option=", ".decide: skip", "cap hit", ".onRoundEnd: delta=");
        assertPhrasesUse("domain/bot/strategy/slot/FixedBetStrategy.java", "log.trace",
                "chooseBet: amount=");
        assertPhrasesUse("domain/bot/strategy/slot/RandomBetStrategy.java", "log.trace",
                "chooseBet: amount=");
    }

    @Test
    @DisplayName("structural: balance / status / race-fallback lines stay on log.debug (not over-demoted)")
    void preservedPhrases_stayDebug() {
        assertPhrasesUse("domain/bot/core/BettingMiniGameBot.java", "log.debug",
                "session balance {}", "found no parked decision");
        assertPhrasesUse("domain/bot/core/SlotMachineBot.java", "log.debug",
                "session balance {}", "below spin cost", "spin result b=", "found no parked bet");
    }

    /**
     * Assert that every line in the given source file containing one of the phrases
     * also contains {@code expectedCall} (e.g. {@code log.trace}). Fails loudly if a
     * phrase is missing (source drift) or on the wrong level.
     */
    private static void assertPhrasesUse(String relativePath, String expectedCall, String... phrases) {
        Path file = Path.of(System.getProperty("user.dir"), "src/main/java/com/vingame/bot", relativePath);
        String source;
        try {
            source = Files.readString(file);
        } catch (Exception e) {
            throw new AssertionError("cannot read source " + file, e);
        }
        for (String phrase : phrases) {
            List<String> matches = source.lines()
                    .filter(l -> l.contains(phrase))
                    .toList();
            assertThat(matches)
                    .as("phrase '%s' present in %s", phrase, relativePath)
                    .isNotEmpty();
            assertThat(matches)
                    .as("phrase '%s' in %s is on a %s line", phrase, relativePath, expectedCall)
                    .allMatch(l -> l.contains(expectedCall));
        }
    }

    /** Minimal in-memory log4j2 appender for asserting emitted events + levels. */
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
