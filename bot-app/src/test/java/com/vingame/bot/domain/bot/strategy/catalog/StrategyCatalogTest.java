package com.vingame.bot.domain.bot.strategy.catalog;

import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.dto.StrategyInfoDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
        return new StrategyCatalog(factory);
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
            // order and differs from this in six of nine positions, so relying on
            // it would be a live defect, not a hypothetical one.
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
}
