package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy;
import com.vingame.bot.domain.bot.strategy.slot.FixedBetStrategy;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 Phase 4b (review-4a): {@link PluginRegistries#build} is silent and
 * {@link PluginRegistries#logInitialized()} prints the three registry lines — betting,
 * slot, message types, in that order — so the caller can print them for the accepted
 * bundle only. Before 4b the registry constructors printed them, and an isolated candidate
 * rejected by its message types left a "registered 9 strategies" line in the boot log next
 * to the bundle that actually ran; the releaser's C-2 diff would flag the duplicate.
 */
@DisplayName("PluginRegistries — build is silent, logInitialized prints the three lines in order (4b)")
class PluginRegistriesLoggingTest {

    private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private AbstractAppender appender;
    private LoggerConfig loggerConfig;
    private boolean created;

    @BeforeEach
    void capture() {
        Configuration config = context.getConfiguration();
        LoggerConfig existing = config.getLoggerConfig("com.vingame.bot");
        created = !existing.getName().equals("com.vingame.bot");
        loggerConfig = created ? new LoggerConfig("com.vingame.bot", Level.INFO, false) : existing;
        if (created) {
            config.addLogger("com.vingame.bot", loggerConfig);
        }
        appender = new AbstractAppender("registries-capture", null, PatternLayout.createDefaultLayout(), false, null) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        loggerConfig.addAppender(appender, Level.INFO, null);
        context.updateLoggers();
    }

    @AfterEach
    void release() {
        loggerConfig.removeAppender(appender.getName());
        if (created) {
            context.getConfiguration().removeLogger("com.vingame.bot");
        }
        context.updateLoggers();
    }

    private static StubPluginBundle bundle() {
        return StubPluginBundle.of(null, List.of(new RandomBehaviorStrategy(), new FixedBetStrategy(),
                new BomGameMessageTypes(), new SlotMessageTypesImpl()));
    }

    @Test
    @DisplayName("building the registries prints nothing")
    void buildIsSilent() {
        PluginRegistries.build(bundle());

        assertThat(infoLines()).isEmpty();
    }

    @Test
    @DisplayName("logInitialized prints betting, slot, message types — byte-identical to the 4a lines, in order")
    void logInitializedPrintsTheThreeLinesInOrder() {
        PluginRegistries registries = PluginRegistries.build(bundle());

        registries.logInitialized();

        assertThat(infoLines()).containsExactly(
                "BettingStrategyFactory initialized: registered 1 strategies — [RANDOM]",
                "SlotStrategyFactory initialized: registered 1 strategies — [FIXED]",
                "MessageTypesRegistry initialized: BETTING_MINI 2 products [097, 098], TAI_XIU 0 products [], "
                        + "SLOT provider SlotMessageTypesImpl, CASHOUT 0 products [], CRASH 0 products []");
    }

    private List<String> infoLines() {
        return events.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }
}
