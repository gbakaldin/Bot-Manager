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
}
