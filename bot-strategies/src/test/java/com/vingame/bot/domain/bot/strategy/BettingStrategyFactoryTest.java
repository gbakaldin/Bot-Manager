package com.vingame.bot.domain.bot.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 of {@code docs/plans/BETTING_STRATEGIES.md}.
 *
 * <p>Pins the registry / lookup contract:
 * <ul>
 *   <li>Beans annotated {@link StrategyImpl} discovered at startup, keyed by
 *       {@link StrategyId}.</li>
 *   <li>{@code create(id, seed)} returns a fresh instance each call (prototype
 *       semantics — delegated to {@link ApplicationContext#getBean(Class)}).</li>
 *   <li>Unknown {@link StrategyId} throws {@link IllegalArgumentException}.</li>
 *   <li>Duplicate {@code @StrategyImpl} keys on two beans throw at startup.</li>
 *   <li>A {@link BettingStrategy} bean missing {@link StrategyImpl} is skipped
 *       with a WARN — neither registers nor crashes init.</li>
 * </ul>
 */
@DisplayName("BettingStrategyFactory")
class BettingStrategyFactoryTest {

    @Test
    @DisplayName("init() registers all @StrategyImpl-annotated beans")
    void initRegistersAnnotated() {
        RandomBehaviorStrategy random = new RandomBehaviorStrategy();
        ApplicationContext context = mock(ApplicationContext.class);

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(random));
        factory.init();

        assertThat(factory.registeredKeys()).containsExactly("RANDOM");
    }

    @Test
    @DisplayName("create(RANDOM, seed) returns a fresh instance per call")
    void createReturnsFreshInstances() {
        ApplicationContext context = mock(ApplicationContext.class);
        RandomBehaviorStrategy a = new RandomBehaviorStrategy();
        RandomBehaviorStrategy b = new RandomBehaviorStrategy();
        when(context.getBean(RandomBehaviorStrategy.class)).thenReturn(a, b);

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(a));
        factory.init();

        BettingStrategy s1 = factory.create(StrategyId.RANDOM.name());
        BettingStrategy s2 = factory.create(StrategyId.RANDOM.name());

        assertThat(s1).isNotSameAs(s2);
        assertThat(s1).isInstanceOf(RandomBehaviorStrategy.class);
        assertThat(s2).isInstanceOf(RandomBehaviorStrategy.class);
    }

    @Test
    @DisplayName("create with an unregistered key throws IllegalArgumentException")
    void unknownIdThrows() {
        ApplicationContext context = mock(ApplicationContext.class);

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of());
        factory.init();

        assertThatThrownBy(() -> factory.create(StrategyId.RANDOM.name()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RANDOM");
    }

    @Test
    @DisplayName("a null key is rejected with IllegalArgumentException, not NPE")
    void nullKeyRejectedWithIllegalArgumentException() {
        // AD-23 (Phase 2 changes no behaviour). Before Phase 2a the registry was
        // an EnumMap and EnumMap.get(null) returns null, so create(null) threw
        // IllegalArgumentException rather than NPE. Phase 2a preserved that with
        // an `id == null` guard on the deprecated enum overload; Phase 2b deletes
        // that overload, and the property now belongs to create(String) alone —
        // LinkedHashMap.get(null) is also null-tolerant, so the message is the
        // same. Pinned here because it is one `Objects.requireNonNull` away from
        // silently changing.
        ApplicationContext context = mock(ApplicationContext.class);

        BettingStrategyFactory factory =
                new BettingStrategyFactory(context, List.of(new RandomBehaviorStrategy()));
        factory.init();

        assertThatThrownBy(() -> factory.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Duplicate @StrategyImpl on two beans throws at init")
    void duplicateImplThrows() {
        ApplicationContext context = mock(ApplicationContext.class);
        RandomBehaviorStrategy a = new RandomBehaviorStrategy();
        // A second bean class with the same @StrategyImpl(RANDOM) annotation —
        // simulates a deploy bug where two strategies claim the same id.
        FakeRandomDuplicate b = new FakeRandomDuplicate();

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(a, b));
        assertThatThrownBy(factory::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("BettingStrategy bean without @StrategyImpl is skipped (not registered)")
    void unannotatedBeanSkipped() {
        ApplicationContext context = mock(ApplicationContext.class);
        UnannotatedStrategy stray = new UnannotatedStrategy();

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(stray));
        factory.init();

        assertThat(factory.registeredKeys()).isEmpty();
    }

    /**
     * Standalone strategy that duplicates {@link StrategyId#RANDOM} to test
     * the duplicate-registration guard. Kept inside the test class so the
     * production scan never picks it up.
     */
    @StrategyImpl("RANDOM")
    private static final class FakeRandomDuplicate implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }

    /**
     * Strategy without {@code @StrategyImpl} — the factory should skip it
     * with a WARN, not crash.
     */
    private static final class UnannotatedStrategy implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }
}
