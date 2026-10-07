package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.plugin.PluginBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registry that resolves the message-types provider for a
 * {@code (GameType, product-code string)} pair (PLUGIN_HOT_RELOAD Phase 2c, AD-17).
 *
 * <p><b>Per bundle, immutable, not a Spring bean</b> (PLUGIN_HOT_RELOAD_3_4 D-9). This
 * was a root-context {@code @Component} until Phase 4a. It is now built by
 * {@code PluginRegistries.build} from one {@link PluginBundle}, together with the two
 * strategy factories, and reached only through {@code PluginRuntime.current()}.
 *
 * <p><b>What this replaced and why.</b> {@code GameMessageTypesResolver} was a
 * static class holding three hardcoded {@code switch (productCode)} statements, so
 * shipping a brand's message layer meant editing shared engine code. Discovery here
 * is Spring bean discovery (AD-18): a provider carries {@code @Component} plus
 * {@link MessageTypesImpl}, and adding a product is a <b>pure addition</b> — one new
 * annotated class, no central file edited. The single remaining central edit is the
 * "not yet implemented" inventory inside {@code MessageTypesCoverageTest}, which
 * AD-19 puts there deliberately.
 *
 * <p><b>The lookups stay disjoint on purpose.</b> (A fourth,
 * {@link #cashout(String)}, joined them for CASHOUT_BOT AD-3 — product-keyed, offset
 * CMDs from code 0, its own contract — and a fifth, {@link #crash(String)}, for
 * AVIATOR_BOT AD-5, the same shape again.) {@link #bettingMini(String)},
 * {@link #slot()} and {@link #taiXiu(String)} return three unrelated interfaces
 * because the provider shapes genuinely differ (SLOT_MACHINE_BOT AD-4,
 * TAI_XIU_BOT AD-3/AD-4): betting-mini registers {@code CODE + offset} CMDs, slot and
 * Tai Xiu register fixed CMDs, and slot is product-neutral so it takes no product at
 * all. Collapsing them into one {@code Object resolve(...)} would move the cast to
 * every call site — {@code BotFactory}'s three branches type-check against
 * {@code setMessageTypes} on two different bot classes plus
 * {@code setTaiXiuMessageTypes}.
 *
 * <p><b>Providers are singletons.</b> They were {@code new}-ed per bot before; every
 * implementation is a stateless table of class literals (no instance fields at all),
 * so one shared instance per product is safe and is what Spring hands out.
 *
 * <p><b>Lookup failures keep the operator-facing text byte-for-byte</b> (AD-20). The
 * "not yet implemented for product code" string is what an operator greps when a new
 * brand's group fails to start; {@code MessageTypesErrorTextTest} pins it as a
 * literal. Note it renders no key set: there is no {@code providers present:} tail
 * here, unlike {@code BettingStrategyFactory.create}'s message.
 *
 * <p><b>Every misconfiguration fails the context refresh — all four of them.</b> A
 * provider that claims the wrong {@code gameType}, a product-keyed provider claiming
 * no products, a SLOT provider claiming products, and a provider carrying no
 * {@link MessageTypesImpl} at all are one posture, not three-plus-one. The last was a
 * WARN-and-skip copied from {@code BettingStrategyFactory}, and the analogy did not
 * hold: that class has no hard branch to be inconsistent with, this one has three, and
 * they all read the annotation the soft branch tolerated. See {@link #annotationOf}
 * for why the soft form was the worst of the four to keep.
 *
 * <p>See {@code docs/plans/PLUGIN_HOT_RELOAD.md} AD-16 through AD-20.
 */
@Slf4j
public class MessageTypesRegistry {

    /**
     * The whole wiring, as one immutable value.
     *
     * <p><b>Why a carrier and not three fields.</b> Three separate {@code final}
     * fields are correct today — they are assigned once, in the constructor, and
     * {@code final}-field semantics safely publish them to the bot-creation virtual
     * threads that read them lock-free. They stop being correct the moment step 5
     * lets a plugin version be swapped in at runtime: assigning three fields one at a
     * time publishes an interleaved state where {@code bettingMini} is v2 while
     * {@code taiXiu} is still v1, so a bot group started inside that window gets a
     * mixed-version wiring — the exact failure a versioned plugin system exists to
     * prevent, and one that leaves no trace in the logs.
     *
     * <p>Folding them into one carrier made that window unexpressible inside this
     * class. <b>The step-5 plan this javadoc used to record — make this field
     * {@code volatile} and swap a new {@code Tables} into it on reload — is superseded</b>
     * (PLUGIN_HOT_RELOAD_3_4 D-9). A registry never mutates: one is built per plugin
     * bundle, and the swap point is {@code PluginRuntime}, which publishes this registry
     * and both strategy factories as one {@code PluginRegistries} value. The same
     * mixed-version argument therefore holds one level up, across all three registries,
     * and this field stays {@code final}. The carrier is kept because it costs nothing
     * and still keeps the five tables one value.
     *
     * <p>Both maps are {@link LinkedHashMap}s wrapped unmodifiable. That keeps
     * iteration deterministic within a JVM run, so the startup line does not shuffle
     * between runs — but the order is Spring's classpath-scan order and is <b>not</b>
     * a contract; nothing may consume it as if it were (PLUGIN_HOT_RELOAD Amendment
     * A4, learned on the strategy registries). Note there is no
     * {@code providers present:} tail on a lookup failure to keep stable — AD-20 pins
     * that message byte-for-byte and it renders no key set.
     *
     * @param bettingMini product-code string → betting-mini provider.
     * @param taiXiu      product-code string → Tai Xiu provider.
     * @param slot        the single product-neutral SLOT provider, or {@code null} if
     *                    none was discovered.
     * @param cashout     product-code string → CASHOUT provider (CASHOUT_BOT AD-3).
     * @param crash       product-code string → CRASH provider (AVIATOR_BOT AD-5).
     */
    private record Tables(Map<String, GameMessageTypes> bettingMini,
                          Map<String, TaiXiuMessageTypes> taiXiu,
                          SlotMessageTypes slot,
                          Map<String, CashoutMessageTypes> cashout,
                          Map<String, CrashMessageTypes> crash) {
    }

    private final Tables tables;

    public MessageTypesRegistry(List<GameMessageTypes> bettingMiniProviders,
                                List<SlotMessageTypes> slotProviders,
                                List<TaiXiuMessageTypes> taiXiuProviders,
                                List<CashoutMessageTypes> cashoutProviders,
                                List<CrashMessageTypes> crashProviders) {
        this.tables = new Tables(
                indexByProduct(bettingMiniProviders, GameType.BETTING_MINI),
                indexByProduct(taiXiuProviders, GameType.TAI_XIU),
                resolveProductNeutral(slotProviders),
                indexByProduct(cashoutProviders, GameType.CASHOUT),
                indexByProduct(crashProviders, GameType.CRASH));
    }

    /**
     * The one-per-bundle INFO line, {@code MessageTypesRegistry initialized: ...},
     * byte-identical to what the constructor used to print. Printed by
     * {@code PluginRegistries.logInitialized()} for the accepted bundle only: an
     * isolated-mode candidate rejected by a later check must not leave its catalogue in the
     * boot log next to the bundle that actually runs (PLUGIN_HOT_RELOAD_3_4 Phase 4b,
     * review-4a).
     */
    public void logInitialized() {
        // Tier-1 INFO: one line per JVM at application startup, the same shape and
        // justification as (Betting|Slot)StrategyFactory's "registered N strategies".
        //
        // The product lists are sorted, for the same reason as those two: the map's
        // own order is Spring's classpath-scan order, and this line is what the
        // releaser greps to confirm the catalogue came up whole. Unsorted it is not
        // stable — the TAI_XIU pair really does render as both [114, 116] and
        // [116, 114] depending on which context built the registry, which makes the
        // smoke string un-diffable against a previous deploy for no benefit.
        //
        // The CASHOUT segment is appended LAST (CASHOUT_BOT AD-3): releasers diff the
        // existing segments against the previous deploy, so they stay byte-identical
        // and in order, and the line stays a single line. The CRASH segment follows
        // it, last again, for the same reason (AVIATOR_BOT AD-5).
        log.info("MessageTypesRegistry initialized: BETTING_MINI {} products {}, "
                        + "TAI_XIU {} products {}, SLOT provider {}, CASHOUT {} products {}, "
                        + "CRASH {} products {}",
                tables.bettingMini().size(), new TreeSet<>(tables.bettingMini().keySet()),
                tables.taiXiu().size(), new TreeSet<>(tables.taiXiu().keySet()),
                tables.slot() == null ? "none" : tables.slot().getClass().getSimpleName(),
                tables.cashout().size(), new TreeSet<>(tables.cashout().keySet()),
                tables.crash().size(), new TreeSet<>(tables.crash().keySet()));
    }

    /**
     * Build the registry from one plugin bundle's providers (PLUGIN_HOT_RELOAD_3_4 D-9).
     * Production's only constructor call, from {@code PluginRegistries.build}; the
     * list-taking constructor above is what it delegates to, and what tests use.
     *
     * @throws IllegalStateException on any misconfiguration, which rejects the whole
     *                               bundle (D-10).
     */
    public MessageTypesRegistry(PluginBundle bundle) {
        this(bundle.beansOfType(GameMessageTypes.class),
                bundle.beansOfType(SlotMessageTypes.class),
                bundle.beansOfType(TaiXiuMessageTypes.class),
                bundle.beansOfType(CashoutMessageTypes.class),
                bundle.beansOfType(CrashMessageTypes.class));
    }

    /**
     * Resolve the betting-mini {@link GameMessageTypes} for a product code.
     *
     * @param productCode {@code ProductCode.getCode()} of the bot's environment, e.g.
     *                    {@code "116"}.
     * @return the provider claiming that product.
     * @throws IllegalArgumentException if {@code productCode} is null, or no provider
     *                                  claims it (AD-20 text).
     */
    public GameMessageTypes bettingMini(String productCode) {
        return lookup(tables.bettingMini(), productCode, "GameMessageTypes");
    }

    /**
     * Resolve the product-neutral slot {@link SlotMessageTypes}. Slot message classes,
     * CMDs and protocol are identical across every brand (SLOT_MACHINE_BOT AD-4), so
     * this takes no product code.
     *
     * @return the single slot provider.
     * @throws IllegalStateException if no slot provider is registered — a deploy bug,
     *                               not a runtime fallback case. The build guards it:
     *                               {@code MessageTypesCoverageTest} asserts exactly
     *                               one is discovered by a real component scan.
     */
    public SlotMessageTypes slot() {
        SlotMessageTypes provider = tables.slot();
        if (provider == null) {
            throw new IllegalStateException(
                    "No SlotMessageTypes provider is registered — expected exactly one "
                            + "@MessageTypesImpl(gameType = SLOT, products = {}) bean.");
        }
        return provider;
    }

    /**
     * Resolve the Tai Xiu {@link TaiXiuMessageTypes} for a product code.
     *
     * @param productCode {@code ProductCode.getCode()} of the bot's environment.
     * @return the provider claiming that product.
     * @throws IllegalArgumentException if {@code productCode} is null, or no provider
     *                                  claims it (AD-20 text).
     */
    public TaiXiuMessageTypes taiXiu(String productCode) {
        return lookup(tables.taiXiu(), productCode, "TaiXiuMessageTypes");
    }

    /**
     * Resolve the {@link CashoutMessageTypes} for a product code (CASHOUT_BOT AD-3).
     * Product-keyed like {@link #taiXiu(String)}: 119 is the only brand known to run
     * the cash-out plugins, and the bet body looks brand-specific.
     *
     * @param productCode {@code ProductCode.getCode()} of the bot's environment.
     * @return the provider claiming that product.
     * @throws IllegalArgumentException if {@code productCode} is null, or no provider
     *                                  claims it (AD-20 text, contract
     *                                  {@code CashoutMessageTypes}).
     */
    public CashoutMessageTypes cashout(String productCode) {
        return lookup(tables.cashout(), productCode, "CashoutMessageTypes");
    }

    /**
     * Resolve the {@link CrashMessageTypes} for a product code (AVIATOR_BOT AD-5).
     * Product-keyed like {@link #cashout(String)}: 119 Avatar is the first crash brand.
     *
     * @param productCode {@code ProductCode.getCode()} of the bot's environment.
     * @return the provider claiming that product.
     * @throws IllegalArgumentException if {@code productCode} is null, or no provider
     *                                  claims it (AD-20 text, contract
     *                                  {@code CrashMessageTypes}).
     */
    public CrashMessageTypes crash(String productCode) {
        return lookup(tables.crash(), productCode, "CrashMessageTypes");
    }

    /**
     * @return the product codes with a betting-mini provider, in discovery order.
     *         Used by {@code MessageTypesCoverageTest} (AD-19) for its inventory.
     */
    public Set<String> registeredBettingMiniProducts() {
        return tables.bettingMini().keySet();
    }

    /**
     * @return the product codes with a Tai Xiu provider, in discovery order.
     */
    public Set<String> registeredTaiXiuProducts() {
        return tables.taiXiu().keySet();
    }

    /**
     * @return the product codes with a CASHOUT provider, in discovery order.
     */
    public Set<String> registeredCashoutProducts() {
        return tables.cashout().keySet();
    }

    /**
     * @return the product codes with a CRASH provider, in discovery order.
     */
    public Set<String> registeredCrashProducts() {
        return tables.crash().keySet();
    }

    /**
     * @return whether the product-neutral slot provider was discovered. Lets a test
     *         assert presence without tripping {@link #slot()}'s throw.
     */
    public boolean hasSlotProvider() {
        return tables.slot() != null;
    }

    /**
     * The shared lookup. {@code contract} names the provider interface so the message
     * reads exactly as the pre-2c resolver's did, per contract:
     * {@code "GameMessageTypes not yet implemented for product code: 066. …"} /
     * {@code "TaiXiuMessageTypes not yet implemented for product code: 097. …"} (AD-20).
     */
    private static <T> T lookup(Map<String, T> registry, String productCode, String contract) {
        if (productCode == null) {
            // Byte-for-byte the pre-2c message. The engine passes
            // ProductCode.getCode(), so a null here means the environment carries no
            // product code at all — the same input that used to reach
            // GameMessageTypesResolver as a null ProductCode.
            throw new IllegalArgumentException("ProductCode cannot be null");
        }
        T provider = registry.get(productCode);
        if (provider == null) {
            throw new IllegalArgumentException(
                    contract + " not yet implemented for product code: " + productCode +
                            ". Please create a " + contract + " implementation for this product.");
        }
        return provider;
    }

    private static <T> Map<String, T> indexByProduct(List<T> providers, GameType expected) {
        Map<String, T> registry = new LinkedHashMap<>();
        for (T provider : providers) {
            MessageTypesImpl annotation = annotationOf(provider);
            requireGameType(annotation, provider, expected);
            if (annotation.products().length == 0) {
                throw new IllegalStateException(
                        "@MessageTypesImpl on " + provider.getClass().getName()
                                + " declares no products — only " + GameType.SLOT
                                + " providers may be product-neutral (AD-17).");
            }
            for (String product : annotation.products()) {
                // Two step-5 constraints were recorded on this line (review-2c F4).
                // PLUGIN_HOT_RELOAD_3_4 resolved both, for all three registries alike:
                //
                // 1. Two live plugin versions under one (gameType, product) key — D-9:
                //    the key gets no version dimension; instead there is one registry
                //    instance per plugin bundle, and step 5 selects the bundle in
                //    PluginRuntime. v1 and v2 both claiming "116" is then two
                //    registries, not a duplicate in one.
                // 2. Whether a duplicate stops the app — D-10: a duplicate rejects the
                //    whole bundle. In classpath mode that is still a context-refresh
                //    failure (two of our own providers claiming "116" must not produce
                //    a startable artifact); in isolated mode the loader logs it and
                //    falls back to the next candidate bundle, so a bad mounted bundle
                //    never stops the other brands.
                T existing = registry.put(product, provider);
                if (existing != null) {
                    throw new IllegalStateException(
                            "Duplicate @MessageTypesImpl(gameType = " + expected
                                    + ", products = {… \"" + product + "\" …}) on "
                                    + existing.getClass().getName() + " and "
                                    + provider.getClass().getName());
                }
            }
        }
        return Collections.unmodifiableMap(registry);
    }

    private static SlotMessageTypes resolveProductNeutral(List<SlotMessageTypes> providers) {
        SlotMessageTypes resolved = null;
        for (SlotMessageTypes provider : providers) {
            MessageTypesImpl annotation = annotationOf(provider);
            requireGameType(annotation, provider, GameType.SLOT);
            if (annotation.products().length != 0) {
                throw new IllegalStateException(
                        "@MessageTypesImpl on " + provider.getClass().getName()
                                + " declares products, but SLOT providers are "
                                + "product-neutral (AD-17) — declare products = {}.");
            }
            if (resolved != null) {
                throw new IllegalStateException(
                        "Duplicate product-neutral SlotMessageTypes: "
                                + resolved.getClass().getName() + " and "
                                + provider.getClass().getName());
            }
            resolved = provider;
        }
        return resolved;
    }

    /**
     * Read {@link MessageTypesImpl} off a discovered bean.
     *
     * <p><b>Why not {@code provider.getClass().getAnnotation(...)}.</b> That reads the
     * annotation off whatever object Spring handed us, which is not necessarily the
     * class that carries it:
     * <ul>
     *   <li><b>Proxies.</b> All six providers implement an interface, so the first
     *       piece of advice applied to any of them — a {@code @Timed}, a
     *       {@code @Validated}, an {@code @EnableAspectJAutoProxy} added for something
     *       else entirely — yields a proxy whose {@code getClass()} is
     *       {@code $Proxy42}, which carries no annotation.
     *       {@link AopUtils#getTargetClass} unwraps it. Nothing proxies these beans
     *       today; this is the cheap guard against the day something does.</li>
     *   <li><b>Class hierarchy.</b> {@code @MessageTypesImpl} is not
     *       {@code @Inherited}, so {@code getAnnotation} would also miss a provider
     *       that inherits its declaration from a base class.
     *       {@link AnnotationUtils#findAnnotation} searches the hierarchy, which is
     *       the behaviour a reader expects and the one the failure branch below
     *       assumes.</li>
     * </ul>
     *
     * <p>What this cannot fix is the step-5 classloader case: if a plugin classloader
     * loads its own copy of {@code MessageTypesImpl}, a lookup against the engine's
     * copy finds nothing however it is spelled. That is a parent-first delegation
     * requirement on the plugin classloader —
     * {@code com.vingame.bot.domain.bot.message.MessageTypesImpl} must resolve to the
     * engine's copy — recorded here because the reason is easier to write down now
     * than to rediscover later.
     *
     * @throws IllegalStateException if the bean carries no {@link MessageTypesImpl}
     *                               anywhere in its hierarchy.
     */
    private static MessageTypesImpl annotationOf(Object provider) {
        Class<?> targetClass = AopUtils.getTargetClass(provider);
        MessageTypesImpl annotation =
                AnnotationUtils.findAnnotation(targetClass, MessageTypesImpl.class);
        if (annotation == null) {
            // Hard failure, deliberately, and deliberately unlike BettingStrategyFactory
            // — which warns and skips, and which this class used to copy.
            //
            // Three sibling misconfigurations in this class already fail context
            // refresh (wrong gameType, a product-keyed provider with no products, a
            // SLOT provider with products) and all three read from this annotation.
            // A missing annotation is both the likeliest of the four (copy a provider,
            // remember @Component, forget the second annotation) and the one with the
            // worst symptom: the app starts clean, and nothing is wrong until someone
            // starts a group for that brand hours or days later and reads
            // "GameMessageTypes not yet implemented for product code: 116" about a
            // brand that has been in production for months. That message points the
            // reader away from the cause, and the one WARN that would explain it
            // scrolled past at boot.
            //
            // Failing here costs nothing in practice because MessageTypesCoverageTest
            // catches the same mistake at build time — which is the point: the build
            // stops it, so this throw is the backstop for the deploy that skipped the
            // build, not a routine outcome.
            throw new IllegalStateException(
                    provider.getClass().getName() + " is a discovered message-types bean but "
                            + "carries no @MessageTypesImpl — the registry has no key for it.");
        }
        return annotation;
    }

    private static void requireGameType(MessageTypesImpl annotation, Object provider, GameType expected) {
        if (annotation.gameType() != expected) {
            throw new IllegalStateException(
                    "@MessageTypesImpl on " + provider.getClass().getName() + " declares gameType = "
                            + annotation.gameType() + " but the bean implements the " + expected
                            + " contract — the two must agree.");
        }
    }
}
