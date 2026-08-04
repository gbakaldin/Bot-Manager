package com.vingame.bot.domain.bot.strategy.slot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring-managed registry that produces fresh {@link SlotStrategy} instances
 * per bot. Mirrors the betting {@code BettingStrategyFactory} (AD-9 of
 * {@code docs/plans/SLOT_MACHINE_BOT.md}).
 *
 * <p>Discovery happens once at startup in {@link #init()}: Spring injects every
 * {@link SlotStrategy} bean, the factory reads the {@link SlotStrategyImpl}
 * annotation off each class to determine its {@link SlotStrategyId}, and stores
 * the class for prototype-scoped instantiation via
 * {@link ApplicationContext#getBean(Class)}. Strategy beans MUST be marked
 * {@code @Scope("prototype")} — singleton-scoped strategies would share mutable
 * state across bots.
 *
 * <p>{@link #create(SlotStrategyId)} returns a new instance every call. An
 * unknown id throws {@link IllegalArgumentException} — a strategy referenced
 * from config without a corresponding bean is a deploy bug, not a runtime
 * fallback.
 *
 * <p>{@code BotFactory} injects this and wires it onto each {@code SlotMachineBot}
 * (Phase 5).
 */
@Slf4j
@Component
public class SlotStrategyFactory {

    private final ApplicationContext context;
    private final List<SlotStrategy> discoveredStrategies;
    private final Map<SlotStrategyId, Class<? extends SlotStrategy>> registry =
            new EnumMap<>(SlotStrategyId.class);

    public SlotStrategyFactory(ApplicationContext context,
                               List<SlotStrategy> discoveredStrategies) {
        this.context = context;
        this.discoveredStrategies = discoveredStrategies;
    }

    @PostConstruct
    void init() {
        for (SlotStrategy bean : discoveredStrategies) {
            SlotStrategyImpl annotation = bean.getClass().getAnnotation(SlotStrategyImpl.class);
            if (annotation == null) {
                // A SlotStrategy bean without @SlotStrategyImpl is a programming
                // error — the registry has no key for it. Surface loudly.
                log.warn("SlotStrategy bean {} is missing @SlotStrategyImpl — skipping registration",
                        bean.getClass().getName());
                continue;
            }
            SlotStrategyId id = annotation.value();
            Class<? extends SlotStrategy> existing = registry.put(id, bean.getClass());
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate @SlotStrategyImpl(" + id + ") on " + existing.getName()
                                + " and " + bean.getClass().getName());
            }
        }
        log.info("SlotStrategyFactory initialized: registered {} strategies — {}",
                registry.size(), registry.keySet());
    }

    /**
     * Build a fresh strategy instance for a single bot.
     *
     * @param id {@link SlotStrategyId} assigned to the bot (from
     *           {@code BotConfiguration.slotStrategyId}, defaulting to
     *           {@link SlotStrategyId#FIXED}).
     * @return a new {@link SlotStrategy} instance (prototype-scoped).
     * @throws IllegalArgumentException if {@code id} has no registered bean.
     */
    public SlotStrategy create(SlotStrategyId id) {
        Class<? extends SlotStrategy> clazz = registry.get(id);
        if (clazz == null) {
            throw new IllegalArgumentException("No SlotStrategy registered for " + id
                    + " — strategies present: " + registry.keySet());
        }
        return context.getBean(clazz);
    }

    /**
     * @return the set of registered slot strategy ids.
     */
    public Set<SlotStrategyId> registeredIds() {
        return Collections.unmodifiableSet(registry.keySet());
    }
}
