package com.vingame.bot.domain.bot.strategy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring-managed registry that produces fresh {@link BettingStrategy} instances
 * per bot.
 *
 * <p>Discovery happens once at startup in {@link #init()}: Spring injects every
 * {@link BettingStrategy} bean, the factory reads the {@link StrategyImpl}
 * annotation off each class to determine its <b>key</b>, and stores
 * an {@link ObjectProvider} that produces fresh prototype-scoped instances on
 * demand. Strategy beans MUST be marked {@code @Scope("prototype")} (Architecture
 * Decision 12) — singleton-scoped strategies would share mutable state across
 * bots and silently corrupt decisions.
 *
 * <p><b>Keys are {@code String}s, not {@link StrategyId}s</b> (PLUGIN_HOT_RELOAD
 * Phase 2a/2b, AD-12): a strategy served from a plugin classloader cannot name an
 * enum constant the engine does not declare. {@link StrategyId} survives as the
 * catalogue of the built-in keys and their UI copy, and
 * {@code StrategyCatalogParityTest} pins every one of its constant names to a
 * registered bean.
 *
 * <p>{@link #create(String)} returns a new strategy instance every call.
 * The RNG is owned by the bot and threaded through {@link BetContext#rng()} on
 * every {@code decide} call (Architecture Decision 13) — strategies never hold
 * their own RNG today. When a future strategy needs one the signature can be
 * extended with a {@code seed} parameter; until then carrying dead plumbing
 * just confuses callers about what does and doesn't seed strategy behaviour.
 *
 * <p>An unknown key at lookup throws {@link IllegalArgumentException} — see
 * Architecture Decision 12: a strategy referenced from Mongo without a
 * corresponding bean is a deploy bug, not a runtime fallback case.
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md} Architecture Decisions 1, 12,
 * and {@code docs/plans/PLUGIN_HOT_RELOAD.md} AD-12/AD-13.
 */
@Slf4j
@Component
public class BettingStrategyFactory {

    private final ApplicationContext context;
    private final List<BettingStrategy> discoveredStrategies;
    /**
     * Key → implementation class, in discovery order. {@link LinkedHashMap} and
     * not a {@link java.util.HashMap} so that iteration is deterministic within
     * a JVM run (a stable boot log, a stable {@code strategies present: [...]}
     * tail on a lookup failure). That order is Spring's bean-discovery order —
     * alphabetical by class file name within package — and it is emphatically
     * <em>not</em> {@code StrategyId.values()} order (plan Amendment A4). No
     * consumer may treat it as a display order: AD-21's ordering is an explicit
     * sort from {@code StrategyId.values()} that does not consult this map's
     * order at all.
     */
    private final Map<String, Class<? extends BettingStrategy>> registry =
            new LinkedHashMap<>();

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
            String id = annotation.value();
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
     * @param id    registry key assigned to the bot at startup (from the
     *              {@code BotGroup.strategyMix} fill-to-target distribution).
     * @return a new {@link BettingStrategy} instance (prototype-scoped).
     * @throws IllegalArgumentException if {@code id} has no registered bean.
     */
    public BettingStrategy create(String id) {
        Class<? extends BettingStrategy> clazz = registry.get(id);
        if (clazz == null) {
            throw new IllegalArgumentException("No BettingStrategy registered for " + id
                    + " — strategies present: " + registry.keySet());
        }
        // getBean(class) on a prototype-scoped @Component returns a fresh instance.
        return context.getBean(clazz);
    }

    /**
     * @return the set of registered strategy keys, in discovery order. Read by
     *         {@code BotGroupConfigValidationService} to reject a
     *         {@code strategyMix} naming a key no bean claims (AD-15).
     */
    public Set<String> registeredKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }
}
