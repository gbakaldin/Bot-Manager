package com.vingame.bot.domain.bot.strategy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Spring-managed registry that produces fresh {@link BettingStrategy} instances
 * per bot.
 *
 * <p>Discovery happens once at startup in {@link #init()}: Spring injects every
 * {@link BettingStrategy} bean, the factory reads the {@link StrategyImpl}
 * annotation off each class to determine its {@link StrategyId}, and stores
 * an {@link ObjectProvider} that produces fresh prototype-scoped instances on
 * demand. Strategy beans MUST be marked {@code @Scope("prototype")} (Architecture
 * Decision 12) — singleton-scoped strategies would share mutable state across
 * bots and silently corrupt decisions.
 *
 * <p>{@link #create(StrategyId, long)} returns a new strategy instance every
 * call. The {@code seed} parameter is reserved for future strategies that hold
 * their own RNG; today's {@link RandomBehaviorStrategy} ignores it (RNG is
 * threaded through {@link BetContext#rng()}). The plumbing exists so a future
 * strategy can be wired in without touching the call site in
 * {@code BettingMiniGameBot}.
 *
 * <p>An unknown {@link StrategyId} at lookup throws
 * {@link IllegalArgumentException} — see Architecture Decision 12: a strategy
 * referenced from Mongo without a corresponding bean is a deploy bug, not a
 * runtime fallback case.
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decisions 1, 12.
 */
@Slf4j
@Component
public class BettingStrategyFactory {

    private final ApplicationContext context;
    private final List<BettingStrategy> discoveredStrategies;
    private final Map<StrategyId, Class<? extends BettingStrategy>> registry =
            new EnumMap<>(StrategyId.class);

    public BettingStrategyFactory(ApplicationContext context,
                                  List<BettingStrategy> discoveredStrategies) {
        this.context = context;
        this.discoveredStrategies = discoveredStrategies;
    }

    @PostConstruct
    void init() {
        for (BettingStrategy bean : discoveredStrategies) {
            StrategyImpl annotation = bean.getClass().getAnnotation(StrategyImpl.class);
            if (annotation == null) {
                // A BettingStrategy bean without @StrategyImpl is a programming
                // error — the registry has no key for it. Surface loudly.
                log.warn("BettingStrategy bean {} is missing @StrategyImpl — skipping registration",
                        bean.getClass().getName());
                continue;
            }
            StrategyId id = annotation.value();
            Class<? extends BettingStrategy> existing = registry.put(id, bean.getClass());
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate @StrategyImpl(" + id + ") on " + existing.getName()
                                + " and " + bean.getClass().getName());
            }
        }
        log.info("BettingStrategyFactory initialized: registered {} strategies — {}",
                registry.size(), registry.keySet());
    }

    /**
     * Build a fresh strategy instance for a single bot.
     *
     * @param id    {@link StrategyId} assigned to the bot at startup (from the
     *              {@code BotGroup.strategyMix} fill-to-target distribution).
     * @param seed  reserved for future strategies that own their own RNG.
     *              {@link RandomBehaviorStrategy} ignores this parameter — the
     *              per-bot RNG flows in via {@link BetContext#rng()} so the
     *              bot owns RNG lifecycle and seeding.
     * @return a new {@link BettingStrategy} instance (prototype-scoped).
     * @throws IllegalArgumentException if {@code id} has no registered bean.
     */
    public BettingStrategy create(StrategyId id, long seed) {
        Class<? extends BettingStrategy> clazz = registry.get(id);
        if (clazz == null) {
            throw new IllegalArgumentException("No BettingStrategy registered for " + id
                    + " — strategies present: " + registry.keySet());
        }
        // getBean(class) on a prototype-scoped @Component returns a fresh instance.
        return context.getBean(clazz);
    }

    /**
     * @return the set of registered strategy ids. Used by tests and by the
     *         assignment routine to validate a {@code strategyMix} at the API
     *         boundary.
     */
    public java.util.Set<StrategyId> registeredIds() {
        return java.util.Collections.unmodifiableSet(registry.keySet());
    }
}
