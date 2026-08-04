package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.domain.bot.strategy.martingale.ClassicMartingaleAggressive;
import com.vingame.bot.domain.bot.strategy.martingale.ClassicMartingaleCautious;
import com.vingame.bot.domain.bot.strategy.martingale.DAlembertAggressive;
import com.vingame.bot.domain.bot.strategy.martingale.DAlembertCautious;
import com.vingame.bot.domain.bot.strategy.martingale.FibonacciAggressive;
import com.vingame.bot.domain.bot.strategy.martingale.FibonacciCautious;
import com.vingame.bot.domain.bot.strategy.martingale.ParoliAggressive;
import com.vingame.bot.domain.bot.strategy.martingale.ParoliCautious;
import com.vingame.bot.domain.bot.strategy.martingale.RiskProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phases 2 and 3 of {@code docs/plans/MARTINGALE_STRATEGIES.md}.
 *
 * <p>Pins the factory wiring for the eight Martingale-family strategies.
 * Mirrors the mocked-context harness from {@link BettingStrategyFactoryTest}
 * (the registry / lookup contract is pure Java — Spring's prototype semantics
 * are stubbed by returning fresh instances from the mocked
 * {@link ApplicationContext#getBean(Class)} calls). Lives in the same package
 * as {@link BettingStrategyFactory} because {@code init()} is package-private.
 *
 * <p>What's pinned here:
 * <ul>
 *   <li>{@code BettingStrategyFactory.create(<id>)} returns the right concrete
 *       class for each of the eight Martingale ids.</li>
 *   <li>Two consecutive {@code create(...)} calls produce distinct instances —
 *       proves the factory is delegating to {@code context.getBean(Class)}
 *       rather than caching, so the prototype scope on the concrete classes
 *       wires through end-to-end.</li>
 *   <li>{@code registeredIds()} contains exactly the nine ids that are
 *       implemented at the end of Phase 3 (RANDOM + the eight Martingales).</li>
 *   <li>Each concrete class carries the correct {@link RiskProfile} — the
 *       constructor-arg pattern in Architecture Decision A4 wires through.</li>
 * </ul>
 */
@DisplayName("Martingale strategy factory wiring (Phases 2-3)")
class MartingaleStrategyFactoryWiringTest {

    /**
     * Build a factory pre-populated with discovery for all nine beans: RANDOM
     * + the eight Martingale strategies (Classic, Paroli, D'Alembert, Fibonacci
     * × Cautious / Aggressive). The mocked context is configured to return a
     * fresh instance on every {@code getBean(Class)} call so the prototype-
     * scope assertion has something concrete to compare.
     */
    private static BettingStrategyFactory wiredFactory() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(RandomBehaviorStrategy.class))
                .thenAnswer(inv -> new RandomBehaviorStrategy());
        when(context.getBean(ClassicMartingaleCautious.class))
                .thenAnswer(inv -> new ClassicMartingaleCautious());
        when(context.getBean(ClassicMartingaleAggressive.class))
                .thenAnswer(inv -> new ClassicMartingaleAggressive());
        when(context.getBean(ParoliCautious.class))
                .thenAnswer(inv -> new ParoliCautious());
        when(context.getBean(ParoliAggressive.class))
                .thenAnswer(inv -> new ParoliAggressive());
        when(context.getBean(DAlembertCautious.class))
                .thenAnswer(inv -> new DAlembertCautious());
        when(context.getBean(DAlembertAggressive.class))
                .thenAnswer(inv -> new DAlembertAggressive());
        when(context.getBean(FibonacciCautious.class))
                .thenAnswer(inv -> new FibonacciCautious());
        when(context.getBean(FibonacciAggressive.class))
                .thenAnswer(inv -> new FibonacciAggressive());

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(
                new RandomBehaviorStrategy(),
                new ClassicMartingaleCautious(),
                new ClassicMartingaleAggressive(),
                new ParoliCautious(),
                new ParoliAggressive(),
                new DAlembertCautious(),
                new DAlembertAggressive(),
                new FibonacciCautious(),
                new FibonacciAggressive()));
        factory.init();
        return factory;
    }

    @Test
    @DisplayName("create(MARTINGALE_CLASSIC_CAUTIOUS) returns ClassicMartingaleCautious with CAUTIOUS profile")
    void classicCautiousResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.MARTINGALE_CLASSIC_CAUTIOUS);
        assertThat(s).isInstanceOf(ClassicMartingaleCautious.class);
        assertThat(((ClassicMartingaleCautious) s).getProfile()).isEqualTo(RiskProfile.CAUTIOUS);
    }

    @Test
    @DisplayName("create(MARTINGALE_CLASSIC_AGGRESSIVE) returns ClassicMartingaleAggressive with AGGRESSIVE profile")
    void classicAggressiveResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.MARTINGALE_CLASSIC_AGGRESSIVE);
        assertThat(s).isInstanceOf(ClassicMartingaleAggressive.class);
        assertThat(((ClassicMartingaleAggressive) s).getProfile()).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @Test
    @DisplayName("create(PAROLI_CAUTIOUS) returns ParoliCautious with CAUTIOUS profile")
    void paroliCautiousResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.PAROLI_CAUTIOUS);
        assertThat(s).isInstanceOf(ParoliCautious.class);
        assertThat(((ParoliCautious) s).getProfile()).isEqualTo(RiskProfile.CAUTIOUS);
    }

    @Test
    @DisplayName("create(PAROLI_AGGRESSIVE) returns ParoliAggressive with AGGRESSIVE profile")
    void paroliAggressiveResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.PAROLI_AGGRESSIVE);
        assertThat(s).isInstanceOf(ParoliAggressive.class);
        assertThat(((ParoliAggressive) s).getProfile()).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @Test
    @DisplayName("create(DALEMBERT_CAUTIOUS) returns DAlembertCautious with CAUTIOUS profile")
    void dalembertCautiousResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.DALEMBERT_CAUTIOUS);
        assertThat(s).isInstanceOf(DAlembertCautious.class);
        assertThat(((DAlembertCautious) s).getProfile()).isEqualTo(RiskProfile.CAUTIOUS);
    }

    @Test
    @DisplayName("create(DALEMBERT_AGGRESSIVE) returns DAlembertAggressive with AGGRESSIVE profile")
    void dalembertAggressiveResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.DALEMBERT_AGGRESSIVE);
        assertThat(s).isInstanceOf(DAlembertAggressive.class);
        assertThat(((DAlembertAggressive) s).getProfile()).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @Test
    @DisplayName("create(FIBONACCI_CAUTIOUS) returns FibonacciCautious with CAUTIOUS profile")
    void fibonacciCautiousResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.FIBONACCI_CAUTIOUS);
        assertThat(s).isInstanceOf(FibonacciCautious.class);
        assertThat(((FibonacciCautious) s).getProfile()).isEqualTo(RiskProfile.CAUTIOUS);
    }

    @Test
    @DisplayName("create(FIBONACCI_AGGRESSIVE) returns FibonacciAggressive with AGGRESSIVE profile")
    void fibonacciAggressiveResolves() {
        BettingStrategy s = wiredFactory().create(StrategyId.FIBONACCI_AGGRESSIVE);
        assertThat(s).isInstanceOf(FibonacciAggressive.class);
        assertThat(((FibonacciAggressive) s).getProfile()).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @Test
    @DisplayName("Each Martingale strategy is prototype-scoped — two creates → distinct instances")
    void martingaleStrategiesArePrototypeScoped() {
        BettingStrategyFactory factory = wiredFactory();
        StrategyId[] martingaleIds = {
                StrategyId.MARTINGALE_CLASSIC_CAUTIOUS,
                StrategyId.MARTINGALE_CLASSIC_AGGRESSIVE,
                StrategyId.PAROLI_CAUTIOUS,
                StrategyId.PAROLI_AGGRESSIVE,
                StrategyId.DALEMBERT_CAUTIOUS,
                StrategyId.DALEMBERT_AGGRESSIVE,
                StrategyId.FIBONACCI_CAUTIOUS,
                StrategyId.FIBONACCI_AGGRESSIVE,
        };
        for (StrategyId id : martingaleIds) {
            BettingStrategy first = factory.create(id);
            BettingStrategy second = factory.create(id);
            assertThat(first)
                    .as("two create(%s) calls must return distinct instances", id)
                    .isNotSameAs(second);
            assertThat(first.getClass())
                    .as("two create(%s) calls must return the same concrete class", id)
                    .isEqualTo(second.getClass());
        }
    }

    @Test
    @DisplayName("registeredIds contains RANDOM + all eight Martingale ids at the end of Phase 3")
    void registeredIdsForPhase3() {
        assertThat(wiredFactory().registeredIds()).containsExactlyInAnyOrder(
                StrategyId.RANDOM,
                StrategyId.MARTINGALE_CLASSIC_CAUTIOUS,
                StrategyId.MARTINGALE_CLASSIC_AGGRESSIVE,
                StrategyId.PAROLI_CAUTIOUS,
                StrategyId.PAROLI_AGGRESSIVE,
                StrategyId.DALEMBERT_CAUTIOUS,
                StrategyId.DALEMBERT_AGGRESSIVE,
                StrategyId.FIBONACCI_CAUTIOUS,
                StrategyId.FIBONACCI_AGGRESSIVE);
    }

    /**
     * Phase 4 sanity check: every {@link StrategyId} value declared in the enum
     * must have a registered backing bean, and {@code create(id)} must return
     * an instance of the expected concrete class. This catches the failure mode
     * where someone adds a new enum entry without wiring a corresponding
     * {@code @StrategyImpl}-annotated bean — the listing endpoint
     * ({@code GET /api/v1/strategy/}) would surface the id while
     * {@code BotGroup}'s {@code /start} would throw
     * {@link IllegalArgumentException} at runtime.
     *
     * <p>Walks {@link StrategyId#values()} explicitly rather than asserting a
     * hard-coded set, so the test fails fast on any future enum addition that
     * forgets the bean side of the contract.
     */
    @Test
    @DisplayName("Phase 4: every StrategyId resolves to its expected concrete class — no missing wiring")
    void everyStrategyIdResolvesEndToEnd() {
        BettingStrategyFactory factory = wiredFactory();

        // The full id → expected concrete class map. Keep in lockstep with
        // StrategyId.values() — the assertion below fails if any enum value is
        // missing here OR if the resolved bean is the wrong class.
        java.util.Map<StrategyId, Class<? extends BettingStrategy>> expected =
                new java.util.EnumMap<>(StrategyId.class);
        expected.put(StrategyId.RANDOM, RandomBehaviorStrategy.class);
        expected.put(StrategyId.MARTINGALE_CLASSIC_CAUTIOUS, ClassicMartingaleCautious.class);
        expected.put(StrategyId.MARTINGALE_CLASSIC_AGGRESSIVE, ClassicMartingaleAggressive.class);
        expected.put(StrategyId.PAROLI_CAUTIOUS, ParoliCautious.class);
        expected.put(StrategyId.PAROLI_AGGRESSIVE, ParoliAggressive.class);
        expected.put(StrategyId.DALEMBERT_CAUTIOUS, DAlembertCautious.class);
        expected.put(StrategyId.DALEMBERT_AGGRESSIVE, DAlembertAggressive.class);
        expected.put(StrategyId.FIBONACCI_CAUTIOUS, FibonacciCautious.class);
        expected.put(StrategyId.FIBONACCI_AGGRESSIVE, FibonacciAggressive.class);

        // Coverage check — every declared StrategyId is in the expectations
        // table. Without this guard a new enum entry would slip past the
        // per-id assertion below (we'd never iterate over the new id).
        assertThat(expected.keySet())
                .as("expectations table must cover every StrategyId.values()")
                .containsExactlyInAnyOrder(StrategyId.values());

        // Registration coverage — every declared StrategyId is also actually
        // registered by the factory. The hard-coded test in registeredIdsForPhase3
        // pins the same shape, but iterating StrategyId.values() here lets
        // this test self-update on future enum additions (the test above must
        // be hand-edited).
        assertThat(factory.registeredIds())
                .as("factory must register every declared StrategyId")
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.asList(StrategyId.values()));

        // Resolution coverage — create() returns the right concrete class for
        // every id. Prototype-scope is already pinned by
        // martingaleStrategiesArePrototypeScoped; here we only confirm the
        // class wiring is correct end-to-end.
        for (java.util.Map.Entry<StrategyId, Class<? extends BettingStrategy>> e : expected.entrySet()) {
            BettingStrategy resolved = factory.create(e.getKey());
            assertThat(resolved)
                    .as("create(%s) must return an instance of %s", e.getKey(), e.getValue().getSimpleName())
                    .isInstanceOf(e.getValue());
        }
    }
}
