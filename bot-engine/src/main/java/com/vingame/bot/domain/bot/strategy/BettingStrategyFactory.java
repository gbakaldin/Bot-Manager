package com.vingame.bot.domain.bot.strategy;

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
 * Registry of one plugin bundle's {@link BettingStrategy} implementations; produces
 * fresh instances per bot.
 *
 * <p><b>Per bundle, immutable, not a Spring bean</b> (PLUGIN_HOT_RELOAD_3_4 D-9). This
 * was a root-context {@code @Component} until Phase 4a. It is now built by
 * {@code PluginRegistries.build} from one {@link PluginBundle}, reached only through
 * {@code PluginRuntime.current()}, and handed by {@code BotFactory} to each bot, which
 * keeps it for life. Nothing writes {@link #registry} after the constructor.
 *
 * <p>Discovery happens once, in the constructor: every {@link BettingStrategy} bean of
 * the bundle ({@link PluginBundle#beansOfType}), the factory reads the
 * {@link StrategyImpl} annotation off each class to determine its <b>key</b>, and
 * {@link #create(String)} asks the bundle for a fresh prototype-scoped instance
 * ({@link PluginBundle#newInstance}). Strategy beans MUST be marked
 * {@code @Scope("prototype")} (Architecture Decision 12) — singleton-scoped strategies
 * would share mutable state across bots and silently corrupt decisions.
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
public class BettingStrategyFactory {

    /** The bundle the strategies came from, and the one {@link #create} instantiates from. */
    private final PluginBundle bundle;
    /**
     * Key → implementation class, in discovery order.
     *
     * <p>{@link LinkedHashMap} and not a {@link java.util.HashMap} so that
     * iteration is deterministic <em>within a JVM run</em>. That is the whole of
     * the property: it keeps a diagnostic from reshuffling between two reads on
     * one box. It is <b>not</b> stability across runs, machines or packagings —
     * the order is Spring's classpath-scan order, which on a directory scan is
     * derived from filesystem listing order and can legitimately differ between
     * an exploded {@code target/classes} run and a jar run. Amendment A4
     * measured what it actually is today (alphabetical by <em>class file name</em>
     * within package, so {@code RandomBehaviorStrategy} sorts ahead of the whole
     * {@code martingale/} subdirectory) and that is an observation, not a
     * contract: renaming a class or moving it to another package silently
     * reorders it and nothing in the build would notice.
     *
     * <p>It is emphatically not {@code StrategyId.values()} order — the two agree
     * on {@code RANDOM} alone and differ in <b>all eight</b> of the remaining
     * positions ({@code PAROLI_*} and {@code DALEMBERT_*} swap as blocks and each
     * pair swaps within its block, so no position after the first survives). No
     * consumer may treat this map's order as a display order: AD-21's ordering is
     * an explicit sort from {@code StrategyId.values()} that does not consult it
     * at all. Anything an operator reads — the boot line, the
     * {@code strategies present: [...]} tail — is sorted at render time instead,
     * so it does not depend on this at all.
     */
    private final Map<String, Class<? extends BettingStrategy>> registry =
            new LinkedHashMap<>();

    /**
     * Discover the bundle's strategies and log the one-per-bundle INFO line.
     *
     * @throws IllegalStateException on a duplicate {@link StrategyImpl} key, which rejects
     *                               the whole bundle (D-10).
     */
    public BettingStrategyFactory(PluginBundle bundle) {
        this.bundle = bundle;
        List<BettingStrategy> discoveredStrategies = bundle.beansOfType(BettingStrategy.class);
        for (BettingStrategy bean : discoveredStrategies) {
            // Resolve the target class once and use it for both the annotation and
            // the registered value. Not bean.getClass(): that is whatever object
            // Spring handed us, and a proxied bean's getClass() is $ProxyN — which
            // carries no annotation (a silent skip) and is not a bean definition
            // (getBean would fail later). Nothing proxies these beans today; this
            // is the cheap guard against the day something does, and against the
            // plugin classloaders this whole effort ends in. Kept identical to
            // MessageTypesRegistry.annotationOf, which has the same exposure.
            Class<? extends BettingStrategy> implClass =
                    AopUtils.getTargetClass(bean).asSubclass(BettingStrategy.class);
            StrategyImpl annotation = AnnotationUtils.findAnnotation(implClass, StrategyImpl.class);
            if (annotation == null) {
                // A BettingStrategy bean without @StrategyImpl is a programming
                // error — the registry has no key for it. Surface loudly.
                //
                // Deliberately still a WARN-and-skip, unlike MessageTypesRegistry,
                // which hard-fails the same mistake (review-2c F2): that class has
                // three sibling misconfigurations that already fail context refresh
                // and all of them read the annotation, so a soft fourth was the odd
                // one out. This class has no such siblings to be inconsistent with.
                log.warn("BettingStrategy bean {} is missing @StrategyImpl — skipping registration",
                        implClass.getName());
                continue;
            }
            String id = annotation.value();
            Class<? extends BettingStrategy> existing = registry.put(id, implClass);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate @StrategyImpl(" + id + ") on " + existing.getName()
                                + " and " + implClass.getName());
            }
        }
    }

    /**
     * The one-per-bundle INFO line, {@code BettingStrategyFactory initialized: registered N
     * strategies — [...]}, byte-identical to what the constructor used to print.
     * <p>
     * <b>Not printed by the constructor any more</b> (PLUGIN_HOT_RELOAD_3_4 Phase 4b,
     * review-4a). An isolated-mode candidate can build this factory and then be rejected by
     * its message types; printed at construction, a fallback-accepted boot logged the line
     * once per candidate tried, and the releaser's C-2 diff against the previous deploy
     * flagged a duplicate. {@code PluginRegistries.logInitialized()} calls this for the
     * accepted bundle only.
     */
    public void logInitialized() {
        // Sorted, not registry.keySet(): the map's order is Spring's scan order and
        // is not stable across packagings, so an unsorted list makes a boot log
        // pointlessly hard to diff between an IDE run and the box. Sorting at
        // startup costs nothing and this line fires once per JVM.
        log.info("BettingStrategyFactory initialized: registered {} strategies — {}",
                registry.size(), sortedKeys());
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
            // The key is quoted because it may be blank. Since Phase 2b the type is
            // String, so "" and "   " are representable — unreachable through the API
            // (AD-15 rejects them as unknown keys) but reachable by a direct Mongo
            // write, and unquoted they rendered as "registered for  — strategies
            // present:", a lookup error with an invisible key.
            throw new IllegalArgumentException("No BettingStrategy registered for '" + id
                    + "' — strategies present: " + sortedKeys());
        }
        // getBean(class) on a prototype-scoped @Component returns a fresh instance; the
        // bundle asks its own context, so the instance comes from the bundle's loader.
        return bundle.newInstance(clazz);
    }

    /**
     * @return the set of registered strategy keys, in discovery order. Read by
     *         {@code BotGroupConfigValidationService} to reject a
     *         {@code strategyMix} naming a key no bean claims (AD-15) — which
     *         sorts it itself before rendering it into a 400 body — and, since
     *         Phase 2d, by {@code StrategyCatalog} on every
     *         {@code GET /api/v1/strategy/}.
     *
     *         <p><b>A snapshot, deliberately, not a view</b> (review-2d finding 3).
     *         This used to be {@code unmodifiableSet(registry.keySet())}, which is
     *         an unmodifiable <em>view</em> over {@link #registry}: safe only while
     *         the map is written once at construction and never touched again.
     *         Phase 2d put an HTTP request thread on this method. Since Phase 4a
     *         the registry is immutable and a reload swaps whole registries through
     *         {@code PluginRuntime} instead of mutating this one (PLUGIN_HOT_RELOAD_3_4
     *         D-9), so a view would in fact be safe; the snapshot stays because it
     *         costs nine strings per call and keeps this method correct whatever a
     *         later step does to the map.
     *
     *         <p>The copy is a {@link java.util.LinkedHashSet} and <b>not</b>
     *         {@code Set.copyOf}: {@code Set.copyOf}'s iteration order is
     *         unspecified and salted per JVM run, which would both discard the
     *         discovery order this javadoc promises and make
     *         {@code BettingStrategyFactoryTest.lookupFailureTailIsSorted} — whose
     *         premise is that this method reproduces the unsorted scan order, so
     *         that the sorted exception tail proves something — flake.
     */
    public Set<String> registeredKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(registry.keySet()));
    }

    /**
     * The keys as an operator should read them. {@link #registry} iterates in
     * Spring's scan order, which is arbitrary across packagings (Amendment A4);
     * every message a human sees renders from this instead, so the same fault
     * produces the same string in an IDE and on the box.
     */
    private Set<String> sortedKeys() {
        return new TreeSet<>(registry.keySet());
    }
}
