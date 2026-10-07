package com.vingame.bot.domain.bot.strategy.slot;

import com.vingame.bot.infrastructure.plugin.PluginBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registry of one plugin bundle's {@link SlotStrategy} implementations; produces
 * fresh instances per bot. Mirrors the betting {@code BettingStrategyFactory} (AD-9 of
 * {@code docs/plans/SLOT_MACHINE_BOT.md}).
 *
 * <p><b>Per bundle, immutable, not a Spring bean</b> (PLUGIN_HOT_RELOAD_3_4 D-9) — see
 * the betting twin. Built by {@code PluginRegistries.build}, reached only through
 * {@code PluginRuntime.current()}, kept for life by each {@code SlotMachineBot}.
 *
 * <p>Discovery happens once, in the constructor: every {@link SlotStrategy} bean of the
 * bundle, the factory reads the {@link SlotStrategyImpl} annotation off each class to
 * determine its <b>key</b>, and stores the class for prototype-scoped instantiation via
 * {@link PluginBundle#newInstance(Class)}. Strategy beans MUST be marked
 * {@code @Scope("prototype")} — singleton-scoped strategies would share mutable
 * state across bots.
 *
 * <p><b>Keys are {@code String}s, not {@link SlotStrategyId}s</b>
 * (PLUGIN_HOT_RELOAD Phase 2a/2b, AD-12), for the same reason as the betting twin:
 * a plugin-supplied strategy cannot name an enum constant the engine does not
 * declare. {@link SlotStrategyId} survives as the catalogue of the built-in
 * keys, pinned to registered beans by {@code StrategyCatalogParityTest}.
 *
 * <p>{@link #create(String)} returns a new instance every call. An
 * unknown key throws {@link IllegalArgumentException} — a strategy referenced
 * from config without a corresponding bean is a deploy bug, not a runtime
 * fallback.
 *
 * <p>{@code BotFactory} reads this from {@code PluginRuntime.current()} and wires it
 * onto each {@code SlotMachineBot}.
 */
@Slf4j
public class SlotStrategyFactory {

    /** The bundle the strategies came from, and the one {@link #create} instantiates from. */
    private final PluginBundle bundle;
    /** Key → implementation class, in Spring's bean-discovery order. */
    private final Map<String, Class<? extends SlotStrategy>> registry =
            new LinkedHashMap<>();

    /**
     * Discover the bundle's slot strategies. The INFO line is {@link #logInitialized()}'s.
     *
     * @throws IllegalStateException on a duplicate {@link SlotStrategyImpl} key, which
     *                               rejects the whole bundle (D-10).
     */
    public SlotStrategyFactory(PluginBundle bundle) {
        this.bundle = bundle;
        List<SlotStrategy> discoveredStrategies = bundle.beansOfType(SlotStrategy.class);
        for (SlotStrategy bean : discoveredStrategies) {
            // See BettingStrategyFactory's constructor for why this is the target class
            // and not bean.getClass().
            Class<? extends SlotStrategy> implClass =
                    AopUtils.getTargetClass(bean).asSubclass(SlotStrategy.class);
            SlotStrategyImpl annotation =
                    AnnotationUtils.findAnnotation(implClass, SlotStrategyImpl.class);
            if (annotation == null) {
                // A SlotStrategy bean without @SlotStrategyImpl is a programming
                // error — the registry has no key for it. Surface loudly.
                log.warn("SlotStrategy bean {} is missing @SlotStrategyImpl — skipping registration",
                        implClass.getName());
                continue;
            }
            String id = annotation.value();
            Class<? extends SlotStrategy> existing = registry.put(id, implClass);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate @SlotStrategyImpl(" + id + ") on " + existing.getName()
                                + " and " + implClass.getName());
            }
        }
    }

    /**
     * The one-per-bundle INFO line, byte-identical to what the constructor used to print.
     * Printed by {@code PluginRegistries.logInitialized()} for the accepted bundle only —
     * see {@code BettingStrategyFactory.logInitialized()} for why (review-4a).
     */
    public void logInitialized() {
        log.info("SlotStrategyFactory initialized: registered {} strategies — {}",
                registry.size(), sortedKeys());
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
            // Quoted so a blank key is visible — see the betting twin.
            throw new IllegalArgumentException("No SlotStrategy registered for '" + id
                    + "' — strategies present: " + sortedKeys());
        }
        return bundle.newInstance(clazz);
    }

    /**
     * @return the set of registered slot strategy keys, in discovery order.
     *         Read by {@code BotGroupConfigValidationService} to reject a
     *         {@code slotStrategyId} no bean claims (AD-15) — which sorts it
     *         itself before rendering it into a 400 body.
     *
     *         <p><b>A snapshot, not a view</b> (PLUGIN_HOT_RELOAD_3_4 D-17), for the
     *         reason given on the betting twin's {@code registeredKeys()}: a view over
     *         {@link #registry} is safe only while the map is never written after
     *         construction, and a request thread iterating it would throw
     *         {@link java.util.ConcurrentModificationException} the first time that
     *         stops being true. A {@link LinkedHashSet} copy and not
     *         {@code Set.copyOf}, whose iteration order is unspecified and salted per
     *         JVM run, so the discovery order promised above survives.
     */
    public Set<String> registeredKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(registry.keySet()));
    }

    /** The keys as an operator should read them — see the betting twin. */
    private Set<String> sortedKeys() {
        return new TreeSet<>(registry.keySet());
    }
}
