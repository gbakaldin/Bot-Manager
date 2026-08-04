package com.vingame.bot.domain.bot.strategy.slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 6 of {@code docs/plans/SLOT_MACHINE_BOT.md} (AD-9).
 *
 * <p>Pins the slot strategy registry / lookup contract, mirroring
 * {@code BettingStrategyFactoryTest}:
 * <ul>
 *   <li>Beans annotated {@link SlotStrategyImpl} discovered at startup, keyed
 *       by {@link SlotStrategyId}.</li>
 *   <li>{@code create(id)} returns a fresh instance each call (prototype
 *       semantics).</li>
 *   <li>Unknown {@link SlotStrategyId} throws {@link IllegalArgumentException}.</li>
 *   <li>Duplicate {@code @SlotStrategyImpl} keys throw at startup.</li>
 *   <li>A {@link SlotStrategy} bean missing {@link SlotStrategyImpl} is skipped
 *       (not registered, no crash).</li>
 * </ul>
 */
@DisplayName("SlotStrategyFactory")
class SlotStrategyFactoryTest {

    @Test
    @DisplayName("init() registers all @SlotStrategyImpl beans — {FIXED, RANDOM}")
    void initRegistersAnnotated() {
        ApplicationContext context = mock(ApplicationContext.class);

        SlotStrategyFactory factory = new SlotStrategyFactory(
                context, List.of(new FixedBetStrategy(), new RandomBetStrategy()));
        factory.init();

        assertThat(factory.registeredIds())
                .containsExactlyInAnyOrder(SlotStrategyId.FIXED, SlotStrategyId.RANDOM);
    }

    @Test
    @DisplayName("create(RANDOM) returns a fresh instance per call")
    void createReturnsFreshInstances() {
        ApplicationContext context = mock(ApplicationContext.class);
        RandomBetStrategy a = new RandomBetStrategy();
        RandomBetStrategy b = new RandomBetStrategy();
        when(context.getBean(RandomBetStrategy.class)).thenReturn(a, b);

        SlotStrategyFactory factory = new SlotStrategyFactory(context, List.of(a));
        factory.init();

        SlotStrategy s1 = factory.create(SlotStrategyId.RANDOM);
        SlotStrategy s2 = factory.create(SlotStrategyId.RANDOM);

        assertThat(s1).isNotSameAs(s2);
        assertThat(s1).isInstanceOf(RandomBetStrategy.class);
        assertThat(s2).isInstanceOf(RandomBetStrategy.class);
    }

    @Test
    @DisplayName("create with unknown SlotStrategyId throws IllegalArgumentException")
    void unknownIdThrows() {
        ApplicationContext context = mock(ApplicationContext.class);

        SlotStrategyFactory factory = new SlotStrategyFactory(context, List.of());
        factory.init();

        assertThatThrownBy(() -> factory.create(SlotStrategyId.FIXED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FIXED");
    }

    @Test
    @DisplayName("Duplicate @SlotStrategyImpl on two beans throws at init")
    void duplicateImplThrows() {
        ApplicationContext context = mock(ApplicationContext.class);

        SlotStrategyFactory factory = new SlotStrategyFactory(
                context, List.of(new FixedBetStrategy(), new FakeFixedDuplicate()));
        assertThatThrownBy(factory::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("SlotStrategy bean without @SlotStrategyImpl is skipped (not registered)")
    void unannotatedBeanSkipped() {
        ApplicationContext context = mock(ApplicationContext.class);

        SlotStrategyFactory factory = new SlotStrategyFactory(context, List.of(new UnannotatedStrategy()));
        factory.init();

        assertThat(factory.registeredIds()).isEmpty();
    }

    /**
     * Standalone strategy duplicating {@link SlotStrategyId#FIXED} to test the
     * duplicate-registration guard. Kept inside the test class so the production
     * scan never picks it up.
     */
    @SlotStrategyImpl(SlotStrategyId.FIXED)
    private static final class FakeFixedDuplicate implements SlotStrategy {
        @Override public long chooseBet(SlotBetContext ctx) { return 0L; }
    }

    /** Strategy without {@code @SlotStrategyImpl} — should be skipped, not crash. */
    private static final class UnannotatedStrategy implements SlotStrategy {
        @Override public long chooseBet(SlotBetContext ctx) { return 0L; }
    }
}
