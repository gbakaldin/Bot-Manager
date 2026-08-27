package com.vingame.bot.domain.bot.strategy.catalog;

import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.dto.StrategyInfoDTO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins the registered strategy <em>keys</em> to the display metadata the UI
 * picker renders (PLUGIN_HOT_RELOAD Phase 2d, AD-21).
 *
 * <p><b>What changed.</b> {@code StrategyController} used to enumerate
 * {@code StrategyId.values()} directly, so the catalogue was the enum. It is now
 * {@link BettingStrategyFactory#registeredKeys()} — the set of keys that a bean
 * actually claims — and the enum is demoted to a lookup table for
 * {@code displayName}/{@code description} (AD-12). That is the whole point of the
 * phase: a plugin-supplied strategy registers a key the engine does not declare,
 * and the picker has to be able to offer it.
 *
 * <h2>Where the human text comes from</h2>
 * <ul>
 *   <li><b>Built-in key</b> (its name matches a {@link StrategyId} constant) —
 *       the enum constant's {@code displayName} and {@code description},
 *       byte-for-byte what the endpoint served before this phase.</li>
 *   <li><b>Any other key</b> — there is nowhere to read copy from, so the key
 *       itself is the {@code displayName} and the description is the
 *       <b>empty string</b>, not {@code null}: the DTO is serialised as-is and a
 *       null would put {@code "description":null} into the picker's tooltip.
 *       Supplying real copy from a plugin is a later step's problem
 *       (a plugin descriptor, not this class).</li>
 * </ul>
 *
 * <h2>Ordering — the trap</h2>
 * AD-21 requires built-ins in {@link StrategyId} declaration order, because that
 * order is a de-facto UI contract nobody wrote down, and the picker must not
 * reshuffle on a deploy. Non-built-in keys follow, alphabetically.
 *
 * <p>The sort is <b>explicit and total</b>, and it deliberately consults nothing
 * about the registry's own iteration order. Amendment A4 measured that order: it
 * is Spring's bean-discovery order — alphabetical by <em>class file name</em>
 * within package — which agrees with {@code StrategyId.values()} on {@code RANDOM}
 * alone and differs in <b>all eight</b> of the remaining positions, and which
 * silently changes if a strategy class is renamed or moved to another package. Do
 * not "simplify" this by trusting a container's natural order.
 *
 * <h2>The set, not just the order</h2>
 * The response lists the <b>registry</b>. A {@link StrategyId} constant whose bean
 * has gone missing is therefore absent from the picker, where before this phase it
 * would have been offered and then rejected by
 * {@code BotGroupConfigValidationService}'s AD-15 key check on POST. Offering only
 * keys that will actually validate is the correct behaviour.
 *
 * <p>The guard that keeps this "the same nine entries" for a well-formed artifact
 * is <b>{@code ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated}</b>
 * (bot-app): it asserts exact set equality in both directions between
 * {@code registeredKeys()} and {@code StrategyId.values()}, under the real
 * {@code Starter} component scan. That last part is what makes it the load-bearing
 * one. {@code StrategyCatalogParityTest} (bot-strategies) proves only the enum ⊆
 * registry direction and does it under a bare
 * {@code AnnotationConfigApplicationContext} package scan, so it cannot see the
 * divergence mode this class introduces — a strategy bean that stays reachable
 * from that scan while dropping out of {@code Starter}'s (module excluded from the
 * assembly, {@code @ComponentScan} narrowed, a jar not shipped) leaves it green and
 * shortens the picker in production. {@code StrategyCatalogResponseContractTest}
 * pins the serialised body byte-for-byte against the pre-change expression over a
 * real component scan, which is the response-contract guard rather than the set
 * guard. At runtime the same divergence is reported by {@link #warnOnMissingBuiltins()}.
 *
 * <h2>Why the slot registry is not joined here</h2>
 * {@code GET /api/v1/strategy/?gameType=SLOT} returns {@code []} and AD-21
 * preserves the response contract exactly, so there is no reader for
 * {@code SlotStrategyId}'s metadata to serve. Slots always run {@code FIXED} and
 * the strategy is not selectable in the UI. If that ever becomes selectable, this
 * class gains a {@code slotStrategies()} in the same shape — it is not omitted
 * because it is hard.
 */
@Slf4j
@Component
public class StrategyCatalog {

    /**
     * Built-in key → its enum constant. Keyed by {@code String}, never by the
     * enum (AD-12 forbids the enum as a runtime map key); the enum is the value,
     * which is what makes it a catalogue lookup rather than an identity.
     */
    private static final Map<String, StrategyId> BUILTINS = builtinsByKey();

    /**
     * AD-21's display order: built-ins in declaration order, then everything else
     * alphabetically. {@link StrategyId#ordinal()} is read for exactly one reason
     * — it <em>is</em> declaration order, which is the property AD-21 names.
     */
    private static final Comparator<String> DISPLAY_ORDER =
            Comparator.<String>comparingInt(StrategyCatalog::builtinRank)
                    .thenComparing(Comparator.naturalOrder());

    private final BettingStrategyFactory bettingStrategyFactory;

    public StrategyCatalog(BettingStrategyFactory bettingStrategyFactory) {
        this.bettingStrategyFactory = bettingStrategyFactory;
    }

    /**
     * Names the built-in keys that no bean claims, once, at startup (review-2d
     * finding 2).
     *
     * <p>Since this phase lists the registry rather than the enum, a built-in whose
     * bean stops being discovered is simply <em>absent</em> — which is the right
     * behaviour and, on its own, an entirely silent one. What an operator would see
     * instead is three symptoms that sit far apart and none of which names the
     * cause: a picker with eight entries instead of nine, a 400 on <em>every</em>
     * PATCH of any group still holding that key (Amendment A5 — {@code validate}
     * runs post-merge over the whole entity), and that group's bots dying inside
     * {@code BettingStrategyFactory.create} on a bot thread at start. This class is
     * the one place that knows both sets, so this is the one place that can say it.
     *
     * <p>Fires <b>at most once per JVM</b> and only when the sets disagree, which is
     * what makes WARN admissible under the CLAUDE.md rule that no default-visible
     * level may carry a rate that is a function of bot or round count. It rides
     * track 1, so it reaches Loki and is alertable.
     */
    @PostConstruct
    void warnOnMissingBuiltins() {
        Set<String> registered = bettingStrategyFactory.registeredKeys();
        List<String> missing = BUILTINS.keySet().stream()
                .filter(key -> !registered.contains(key))
                .toList();
        if (!missing.isEmpty()) {
            log.warn("built-in strategies with no registered bean: {} — they will not appear "
                            + "in the strategy picker and groups holding them will fail to start",
                    missing);
        }
    }

    /**
     * The betting-strategy catalogue as the picker renders it.
     *
     * <p><b>This must stay a per-request read of the registry and must not be
     * memoised.</b> Re-reading {@code registeredKeys()} on every call is the
     * property step 5 depends on: a reloaded plugin's keys have to show up on the
     * next request without a restart. Hoisting this list into a
     * {@code @PostConstruct}-computed field is the one plausible "optimisation"
     * here, it would look harmless for as long as the registry is write-once, and
     * it would quietly break plugin reload. {@link #BUILTINS} is the only thing
     * cached, and only because it is derived from a compile-time enum.
     *
     * @return one entry per registered betting key, built-ins first in
     *         {@link StrategyId} declaration order and any other key after them
     *         alphabetically. Never null; empty only if no strategy bean was
     *         discovered at all.
     */
    public List<StrategyInfoDTO> bettingStrategies() {
        return bettingStrategyFactory.registeredKeys().stream()
                .sorted(DISPLAY_ORDER)
                .map(StrategyCatalog::describe)
                .toList();
    }

    private static StrategyInfoDTO describe(String key) {
        StrategyId builtin = BUILTINS.get(key);
        return builtin != null
                ? StrategyInfoDTO.of(builtin)
                : new StrategyInfoDTO(key, key, "");
    }

    private static int builtinRank(String key) {
        StrategyId builtin = BUILTINS.get(key);
        return builtin == null ? Integer.MAX_VALUE : builtin.ordinal();
    }

    private static Map<String, StrategyId> builtinsByKey() {
        Map<String, StrategyId> byKey = new LinkedHashMap<>();
        for (StrategyId id : StrategyId.values()) {
            byKey.put(id.name(), id);
        }
        return Collections.unmodifiableMap(byKey);
    }
}
