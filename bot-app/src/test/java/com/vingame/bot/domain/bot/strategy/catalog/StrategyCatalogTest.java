package com.vingame.bot.domain.bot.strategy.catalog;

import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.infrastructure.plugin.TestPluginRuntimes;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.dto.StrategyInfoDTO;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PLUGIN_HOT_RELOAD Phase 2d — the join itself, away from HTTP.
 *
 * <p>{@code StrategyCatalogResponseContractTest} pins the wire contract against
 * the real nine-strategy registry, which is the case that ships. This class pins
 * the cases the real registry <em>cannot</em> produce today and which the whole
 * phase exists for: a key with no enum constant behind it, and a registry whose
 * iteration order is nothing like the display order.
 *
 * <p>The factory is mocked here on purpose. Everywhere else in this feature a
 * mocked registry would be a hazard (a stub that agrees with itself proves
 * nothing about production); here the stub is the only way to express a
 * plugin-supplied key at all, since no plugin can register one until step 5.
 */
@DisplayName("StrategyCatalog — registry keys joined to display metadata")
class StrategyCatalogTest {

    /** Declaration order of {@link StrategyId}, i.e. AD-21's display order. */
    private static final List<String> BUILTIN_ORDER =
            Arrays.stream(StrategyId.values()).map(StrategyId::name).toList();

    private static StrategyCatalog catalogOver(String... registeredKeys) {
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        // LinkedHashSet: the stub hands the keys back in the order written, which
        // is what lets these tests prove the sort ignores it.
        Set<String> keys = new LinkedHashSet<>(Arrays.asList(registeredKeys));
        when(factory.registeredKeys()).thenReturn(keys);
        return new StrategyCatalog(TestPluginRuntimes.of(factory,
                mock(SlotStrategyFactory.class), mock(MessageTypesRegistry.class)));
    }

    @Nested
    @DisplayName("Built-in keys")
    class Builtins {

        @Test
        @DisplayName("carry the enum's displayName and description verbatim")
        void builtinsCarryEnumCopy() {
            List<StrategyInfoDTO> listed = catalogOver("RANDOM").bettingStrategies();

            assertThat(listed).containsExactly(new StrategyInfoDTO(
                    "RANDOM",
                    StrategyId.RANDOM.getDisplayName(),
                    StrategyId.RANDOM.getDescription()));
        }

        @Test
        @DisplayName("come back in StrategyId declaration order whatever order the registry yields")
        void builtinsAreSortedIntoDeclarationOrder() {
            // Reversed, so a pass-through implementation cannot accidentally
            // agree. Amendment A4: the real registry's order is bean-discovery
            // order and agrees with this on RANDOM alone, differing in all eight
            // remaining positions, so relying on it would be a live defect, not a
            // hypothetical one.
            String[] reversed = BUILTIN_ORDER.reversed().toArray(String[]::new);

            assertThat(catalogOver(reversed).bettingStrategies())
                    .extracting(StrategyInfoDTO::id)
                    .containsExactlyElementsOf(BUILTIN_ORDER);
        }

        @Test
        @DisplayName("a built-in with no registered bean is absent — the response lists the registry")
        void unregisteredBuiltinIsNotListed() {
            List<StrategyInfoDTO> listed =
                    catalogOver("RANDOM", "FIBONACCI_CAUTIOUS").bettingStrategies();

            assertThat(listed)
                    .extracting(StrategyInfoDTO::id)
                    .as("the picker must only offer keys that a bean claims — the others "
                            + "would be rejected by the AD-15 key check on POST anyway")
                    .containsExactly("RANDOM", "FIBONACCI_CAUTIOUS");
        }
    }

    @Nested
    @DisplayName("Keys with no enum constant (plugin-supplied, step 5+)")
    class NonBuiltins {

        @Test
        @DisplayName("fall back to the key as displayName and an empty — not null — description")
        void fallBackToTheKeyItself() {
            List<StrategyInfoDTO> listed = catalogOver("ACME_GRID").bettingStrategies();

            assertThat(listed).containsExactly(
                    new StrategyInfoDTO("ACME_GRID", "ACME_GRID", ""));
            assertThat(listed.getFirst().description())
                    .as("null would serialise as \"description\":null into the picker tooltip")
                    .isNotNull();
        }

        @Test
        @DisplayName("sort after every built-in, alphabetically among themselves")
        void nonBuiltinsSortAfterBuiltinsAlphabetically() {
            List<StrategyInfoDTO> listed = catalogOver(
                    "ZETA_PLUGIN", "RANDOM", "ACME_GRID", "FIBONACCI_AGGRESSIVE")
                    .bettingStrategies();

            assertThat(listed)
                    .extracting(StrategyInfoDTO::id)
                    .containsExactly("RANDOM", "FIBONACCI_AGGRESSIVE", "ACME_GRID", "ZETA_PLUGIN");
        }
    }

    @Test
    @DisplayName("An empty registry yields an empty list, not a failure")
    void emptyRegistryYieldsEmptyList() {
        assertThat(catalogOver().bettingStrategies()).isEmpty();
    }

    /**
     * review-2d finding 2. Absence is the right response to a built-in whose bean
     * went missing; silence is not. The operator-visible symptoms are three and are
     * far apart (a shorter dropdown, a 400 on every PATCH of an affected group, bots
     * dying inside {@code BettingStrategyFactory.create} on a bot thread), and none
     * of them names the cause. This is the one place that knows both sets.
     */
    @Nested
    @DisplayName("The startup warning for a shrinking registry")
    class MissingBuiltinWarning {

        private CapturingAppender appender;
        private LoggerContext ctx;
        private LoggerConfig loggerConfig;
        private Level previousLevel;

        @BeforeEach
        void attachAppender() {
            appender = new CapturingAppender("CapturingAppender-strategy-catalog");
            appender.start();
            ctx = (LoggerContext) LogManager.getContext(false);
            loggerConfig = ctx.getConfiguration().getLoggerConfig("com.vingame.bot");
            previousLevel = loggerConfig.getLevel();
            loggerConfig.addAppender(appender, Level.ALL, null);
            // TRACE, so a line quietly demoted out of WARN is still captured and the
            // level is asserted rather than inferred from presence.
            loggerConfig.setLevel(Level.TRACE);
            ctx.updateLoggers();
        }

        @AfterEach
        void detachAppender() {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(previousLevel);
            ctx.updateLoggers();
        }

        @Test
        @DisplayName("silent when every built-in has a bean — the shipped case")
        void silentWhenTheSetsAgree() {
            catalogOver(BUILTIN_ORDER.toArray(String[]::new)).warnOnMissingBuiltins();

            assertThat(catalogEvents())
                    .as("a warning on every healthy boot is a warning nobody reads")
                    .isEmpty();
        }

        @Test
        @DisplayName("silent when the registry carries extra plugin keys as well")
        void silentWhenTheRegistryIsAStrictSuperset() {
            String[] keys = Stream.concat(BUILTIN_ORDER.stream(), Stream.of("ACME_GRID"))
                    .toArray(String[]::new);

            catalogOver(keys).warnOnMissingBuiltins();

            assertThat(catalogEvents())
                    .as("the check is BUILTINS - registered, not set equality — a "
                            + "plugin-supplied key is the point of the phase, not a fault")
                    .isEmpty();
        }

        @Test
        @DisplayName("one WARN naming exactly the built-ins with no bean")
        void warnsNamingTheMissingKeys() {
            String[] allButFibonacciCautious = BUILTIN_ORDER.stream()
                    .filter(key -> !key.equals(StrategyId.FIBONACCI_CAUTIOUS.name()))
                    .toArray(String[]::new);

            catalogOver(allButFibonacciCautious).warnOnMissingBuiltins();

            List<LogEvent> events = catalogEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getLevel())
                    .as("WARN: recoverable-but-investigate. ERROR would page for what is "
                            + "often a deliberately trimmed assembly; INFO would be lost "
                            + "in the boot noise")
                    .isEqualTo(Level.WARN);
            assertThat(events.getFirst().getMessage().getFormattedMessage())
                    .as("the line has to name the key, or it sends the reader back to "
                            + "diffing the picker against the enum by hand")
                    .contains("FIBONACCI_CAUTIOUS")
                    .doesNotContain("RANDOM");
        }

        /**
         * The tier-1 property, stated the way CLAUDE.md states it: the count must not
         * be a function of anything that scales. It fires from a {@code @PostConstruct}
         * on a singleton, so it is once per JVM — and the number of missing keys must
         * not turn it into one line each.
         */
        @Test
        @DisplayName("one line however many built-ins are missing — never one per key")
        void lineCountIsIndependentOfHowManyAreMissing() {
            catalogOver("RANDOM").warnOnMissingBuiltins();

            assertThat(catalogEvents())
                    .as("eight missing keys, one line")
                    .hasSize(1);
            assertThat(catalogEvents().getFirst().getMessage().getFormattedMessage())
                    .contains("FIBONACCI_CAUTIOUS", "MARTINGALE_CLASSIC_CAUTIOUS");
        }

        @Test
        @DisplayName("an empty registry warns rather than throwing at context refresh")
        void emptyRegistryWarns() {
            StrategyCatalog catalog = catalogOver();

            assertThatCode(catalog::warnOnMissingBuiltins)
                    .as("a @PostConstruct that throws fails context refresh — the app must "
                            + "still come up, so an operator can read the reason")
                    .doesNotThrowAnyException();
            assertThat(catalogEvents()).hasSize(1);
        }

        private List<LogEvent> catalogEvents() {
            return appender.events().stream()
                    .filter(e -> StrategyCatalog.class.getName().equals(e.getLoggerName()))
                    .toList();
        }
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
    }
}
