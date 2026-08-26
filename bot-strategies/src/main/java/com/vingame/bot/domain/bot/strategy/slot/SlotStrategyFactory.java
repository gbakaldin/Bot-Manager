package com.vingame.bot.domain.bot.strategy.slot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * annotation off each class to determine its <b>key</b>, and stores
 * the class for prototype-scoped instantiation via
 * {@link ApplicationContext#getBean(Class)}. Strategy beans MUST be marked
 * {@code @Scope("prototype")} — singleton-scoped strategies would share mutable
 * state across bots.
 *
 * <p><b>Keys are {@code String}s, not {@link SlotStrategyId}s</b>
 * (PLUGIN_HOT_RELOAD Phase 2a, AD-12), for the same reason as the betting twin:
 * a plugin-supplied strategy cannot name an enum constant the engine does not
 * declare. {@link SlotStrategyId} survives as the catalogue of the built-in
 * keys, pinned to registered beans by {@code StrategyCatalogParityTest}.
 *
 * <p>{@link #create(String)} returns a new instance every call. An
 * unknown key throws {@link IllegalArgumentException} — a strategy referenced
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
    /** Key → implementation class, in Spring's bean-discovery order. */
    private final Map<String, Class<? extends SlotStrategy>> registry =
            new LinkedHashMap<>();

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
            String id = annotation.value();
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
     * @param id registry key assigned to the bot (from
     *           {@code BotConfiguration.slotStrategyId}, defaulting to
     *           {@link SlotStrategyId#FIXED}).
     * @return a new {@link SlotStrategy} instance (prototype-scoped).
     * @throws IllegalArgumentException if {@code id} has no registered bean.
     */
    public SlotStrategy create(String id) {
        Class<? extends SlotStrategy> clazz = registry.get(id);
        if (clazz == null) {
            throw new IllegalArgumentException("No SlotStrategy registered for " + id
                    + " — strategies present: " + registry.keySet());
        }
        return context.getBean(clazz);
    }

    /**
     * Enum-keyed overload kept only so that Phase 2a moves no engine call site.
     *
     * @deprecated the registry is string-keyed (AD-12). Call
     *             {@link #create(String)}; this overload is removed in
     *             PLUGIN_HOT_RELOAD Phase 2b, when
     *             {@code BotConfiguration.slotStrategyId} becomes a
     *             {@code String}.
     */
    @Deprecated
    public SlotStrategy create(SlotStrategyId id) {
        return create(id == null ? null : id.name());
    }

    /**
     * @return the set of registered slot strategy keys, in discovery order.
     */
    public Set<String> registeredKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }
}
