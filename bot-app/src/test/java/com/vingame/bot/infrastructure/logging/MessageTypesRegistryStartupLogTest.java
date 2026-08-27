package com.vingame.bot.infrastructure.logging;

import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.JackpotTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD Phase 2c adds exactly one log line, and this pins the two
 * properties {@code CLAUDE.md}'s Logging Guidelines make binding for it: it is
 * <b>INFO</b>, and its rate is <b>not a function of anything that scales</b>.
 *
 * <p>The rule that decides every level question in this repo is:
 *
 * <blockquote>INFO must not contain anything whose rate is a function of bot count or
 * round rate. If a line fires once per bot, once per round, or once per message, it is
 * DEBUG or TRACE, and its group-level aggregate is what goes to INFO.</blockquote>
 *
 * <p>{@code MessageTypesRegistry}'s line is emitted from the constructor of a Spring
 * singleton, so it fires once per application context — the same shape and the same
 * justification as {@code (Betting|Slot)StrategyFactory}'s "registered N strategies",
 * which {@code PerBotInfoLogGuardTest} exempts by name for exactly this reason.
 *
 * <p><b>Why a test and not a code review.</b> {@code PerBotInfoLogGuardTest} scans
 * {@code bot-engine/.../domain/bot/core} and {@code bot-strategies/.../domain/bot/strategy}
 * — it does <b>not</b> scan {@code bot-messages}, which before this phase had no Spring
 * dependency and no logger at all. 2c is the commit that gives that module both, so it
 * is also the commit after which a per-product or per-bot INFO line can be added there
 * with nothing failing. The second test below is the durable half: it constructs the
 * registry with one provider and with six, and asserts the line count does not move.
 */
@DisplayName("Tier-1 invariant: the message-types registry logs once, at INFO, per context")
class MessageTypesRegistryStartupLogTest {

    private static final String REGISTRY_LOGGER = MessageTypesRegistry.class.getName();

    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        appender = new CapturingAppender("CapturingAppender-message-types-registry");
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration().getLoggerConfig("com.vingame.bot");
        previousLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        // TRACE, so a line that was quietly demoted (or promoted) is still captured and
        // the level is asserted rather than inferred from presence.
        loggerConfig.setLevel(Level.TRACE);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(previousLevel);
        ctx.updateLoggers();
    }

    @Test
    @DisplayName("constructing the registry emits exactly one INFO line naming the catalogue")
    void oneInfoLinePerConstruction() {
        newFullRegistry();

        List<LogEvent> events = registryEvents();

        assertThat(events)
                .as("one line per application context — tier 1. More than one means the "
                        + "catalogue summary has been split per product or per game type, "
                        + "which is the shape this repo's INFO tier forbids.")
                .hasSize(1);
        assertThat(events.get(0).getLevel())
                .as("tier-1 group-level lifecycle: application startup. DEBUG would hide it "
                        + "from Loki entirely, since only track 1 is shipped and it is "
                        + "INFO-capped by a ThresholdFilter.")
                .isEqualTo(Level.INFO);

        String message = events.get(0).getMessage().getFormattedMessage();
        assertThat(message)
                .as("the line is the catalogue: it must name what was discovered, or it is "
                        + "not worth an INFO slot")
                .contains("MessageTypesRegistry initialized")
                .contains("097", "098", "116", "118")   // betting-mini products
                .contains("114")                        // Tai Xiu jackpot product
                .contains("SlotMessageTypesImpl");      // the product-neutral slot provider
    }

    /**
     * The product lists are sorted, so the line is byte-identical between two deploys
     * that discovered the same catalogue.
     *
     * <p>This is not hypothetical tidiness. The map is a {@code LinkedHashMap} in
     * Spring's classpath-scan order, and the TAI_XIU pair was observed rendering as
     * <em>both</em> {@code [114, 116]} and {@code [116, 114]} within a single build,
     * depending on which context constructed the registry. review-2c's merge guidance
     * makes this line the releaser's smoke check — {@code docker logs bot-manager |
     * grep "MessageTypesRegistry initialized"} — so an unstable order makes the one
     * artefact a human diffs against the previous deploy un-diffable, for nothing.
     * Same treatment, same reason, as {@code (Betting|Slot)StrategyFactory}'s
     * "registered N strategies" (review-2a).
     */
    @Test
    @DisplayName("the product lists are sorted, so the line is stable across scans")
    void productListsAreSorted() {
        newFullRegistry();

        String message = registryEvents().get(0).getMessage().getFormattedMessage();

        assertThat(message)
                .as("sorted, not scan order — this is the releaser's smoke string")
                .contains("BETTING_MINI 4 products [097, 098, 116, 118]")
                .contains("TAI_XIU 2 products [114, 116]");
    }

    /**
     * The load-bearing half. A future contributor adding a per-product line inside the
     * registration loop would still leave the test above green (it constructs one
     * registry and would just see more events — caught) — but more importantly, this
     * states the invariant in the form the guideline states it: the count is independent
     * of the catalogue size.
     */
    @Test
    @DisplayName("the line count does not scale with the number of providers")
    void lineCountIsIndependentOfCatalogueSize() {
        newMinimalRegistry();
        int withOneProvider = registryEvents().size();

        appender.clear();

        newFullRegistry();
        int withSixProviders = registryEvents().size();

        assertThat(withOneProvider).isEqualTo(1);
        assertThat(withSixProviders)
                .as("six providers must cost the same one line as one provider — a count "
                        + "that tracks the catalogue is a count that will track the fleet")
                .isEqualTo(withOneProvider);
    }

    private static MessageTypesRegistry newFullRegistry() {
        return new MessageTypesRegistry(
                List.of(new BomGameMessageTypes(), new TipGameMessageTypes(),
                        new NohuGameMessageTypes()),
                List.of(new SlotMessageTypesImpl()),
                List.of(new MiniGameTaiXiuMessageTypes(), new JackpotTaiXiuMessageTypes()));
    }

    private static MessageTypesRegistry newMinimalRegistry() {
        return new MessageTypesRegistry(
                List.of(new BomGameMessageTypes()), List.of(), List.of());
    }

    private List<LogEvent> registryEvents() {
        return appender.events().stream()
                .filter(e -> REGISTRY_LOGGER.equals(e.getLoggerName()))
                .toList();
    }

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

        void clear() {
            events.clear();
        }
    }
}
