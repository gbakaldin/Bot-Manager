package com.vingame.bot.infrastructure.plugin;

import com.fasterxml.jackson.databind.type.TypeFactory;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;

import java.util.Objects;

/**
 * The three registries of one plugin bundle, built together and never mutated
 * (PLUGIN_HOT_RELOAD_3_4 D-9).
 * <p>
 * <b>Why one record.</b> A bot group started while a bundle is swapped must never get a
 * strategy factory from version N and a message provider from N+1 — a mixed wiring that
 * leaves no trace in the logs. Publishing the three registries as one value, through
 * {@link PluginRuntime#current()}, makes that window unexpressible: a consumer reads
 * {@code current()} once per operation and takes everything from the same record.
 * <p>
 * <b>Keys get no version dimension</b> (D-9, resolving Amendment A8). Two versions
 * coexist (step 5) as two records, each with its own registries, not as one registry
 * keyed by {@code (key, version)}.
 *
 * @param bundle            the bundle the registries were built from.
 * @param bettingStrategies the bundle's betting strategies.
 * @param slotStrategies    the bundle's slot strategies.
 * @param messageTypes      the bundle's message-types providers.
 */
public record PluginRegistries(PluginBundle bundle,
                               BettingStrategyFactory bettingStrategies,
                               SlotStrategyFactory slotStrategies,
                               MessageTypesRegistry messageTypes) {

    public PluginRegistries {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(bettingStrategies, "bettingStrategies");
        Objects.requireNonNull(slotStrategies, "slotStrategies");
        Objects.requireNonNull(messageTypes, "messageTypes");
    }

    /**
     * The bundle's Jackson type factory, with its private cache. {@code BotFactory} hands
     * it to every bot, and every per-bot mapper is built on it (D-9, L-8).
     * <p>
     * <b>Derived, not a component</b> (review-4a). It used to be a fifth record component
     * that {@link #build} filled from {@code bundle.typeFactory()}, but the canonical
     * constructor accepted any factory, so a hand-built record could pair one bundle's
     * registries with another bundle's type cache — or with
     * {@code TypeFactory.defaultInstance()}, the exact pin D-9 exists to prevent. Reading
     * it through the bundle makes that pairing unexpressible.
     */
    public TypeFactory typeFactory() {
        return bundle.typeFactory();
    }

    /**
     * Build all three registries from one bundle. <b>The single D-10 validation
     * point</b>: a bundle is accepted or rejected as a whole, and any registry
     * misconfiguration rejects it — a duplicate key in any registry, a missing
     * {@code @MessageTypesImpl}, a gameType/contract mismatch, a product-keyed provider
     * with no products. A strategy bean with no annotation stays a WARN-and-skip, as it
     * always was.
     * <p>
     * The registries are built in the order the boot log has always printed them —
     * betting, slot, message types — because the releaser diffs those three lines
     * against the previous deploy, in order.
     *
     * @throws IllegalStateException if the bundle is rejected. In classpath mode this
     *                               propagates out of context refresh, exactly as the
     *                               registries' own throws did when they were
     *                               {@code @Component}s; in isolated mode (4b) the loader
     *                               logs it and tries the next candidate.
     */
    public static PluginRegistries build(PluginBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        BettingStrategyFactory betting = new BettingStrategyFactory(bundle);
        SlotStrategyFactory slot = new SlotStrategyFactory(bundle);
        MessageTypesRegistry messageTypes = new MessageTypesRegistry(bundle);
        return new PluginRegistries(bundle, betting, slot, messageTypes);
    }
}
