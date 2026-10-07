package com.vingame.bot.domain.bot.message;

import com.vingame.bot.infrastructure.plugin.ClasspathPluginBundle;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GameMessageTypes#forGame(Game)} as a <b>contract over every registered
 * provider</b>, discovered by the same real component scan
 * {@code MessageTypesRegistryTest} uses.
 *
 * <p><b>Why this is not the same test as {@code RikGameMessageTypesRoutingTest}.</b>
 * That one instantiates five named providers by hand, which is a statement about the
 * five that existed the day it was written. {@code forGame} is a <b>default method on
 * a bot-api interface</b> invoked from {@code BotFactory} for every betting-mini bot of
 * every product, so the blast radius of a bad override is products that have nothing to
 * do with P_114 — and the seventh provider, added by whoever brings up the next brand,
 * is not in any hand-maintained list. This enumerates the registry instead, so a new
 * provider is covered on the day it is registered.
 *
 * <p>The claim, in full: for <b>every</b> registered betting-mini provider and for
 * every plugin name including {@code null} and including {@code ziczacPlugin} itself,
 * {@code forGame} returns <b>the identical instance</b> the registry handed back — with
 * exactly one specialisation, {@code RikGameMessageTypes} on {@code ziczacPlugin}. If
 * that exception list ever needs a second entry, this test is where the decision gets
 * made explicitly rather than discovered in production.
 *
 * <p>Note what {@code forGame} must <b>not</b> do, which the identity assertion also
 * covers: it must not allocate a fresh provider per call. {@code BotFactory} invokes it
 * once per bot, so a per-call {@code new} on a 3 000-bot fleet is 3 000 objects and, for
 * an implementation that caches Jackson registrations, 3 000 caches.
 */
@DisplayName("GameMessageTypes.forGame — the cross-product default, over the real scan")
class GameMessageTypesForGameContractTest {

    /** The package the registry and every annotated provider live under. */
    private static final String SCAN_BASE = "com.vingame.bot.domain.bot.message";

    /**
     * The only (provider class, plugin name) pair in the codebase allowed to return
     * something other than {@code this}. Adding a row here is a design decision; it is
     * deliberately a literal so that making one requires editing this comment.
     */
    private static final String SPECIALISING_PROVIDER = RikGameMessageTypes.class.getName();
    private static final String SPECIALISING_PLUGIN = "ziczacPlugin";

    /**
     * Plugin names every provider must pass through unchanged. Includes the ziczac name
     * (no other product may claim a P_114 plugin), a null, and the shapes a typo takes.
     */
    private static final String[] PLUGIN_NAMES = {
            "stockPlugin", "taixiuMd5Plugin", "BauCua", "TaiXiuSeven", "MiniPoker",
            "ziczacPlugin", "ZICZACPLUGIN", "ziczac", "ziczacPlugin ", "", null
    };

    private static AnnotationConfigApplicationContext context;
    private static MessageTypesRegistry registry;

    @BeforeAll
    static void bootRealContext() {
        context = new AnnotationConfigApplicationContext();
        context.scan(SCAN_BASE);
        context.refresh();
        // PLUGIN_HOT_RELOAD_3_4 Phase 4a: the registry is a per-bundle object, not a bean.
        registry = new MessageTypesRegistry(new ClasspathPluginBundle(context));
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    private static Game game(String pluginName) {
        return Game.builder()
                .gameType(GameType.BETTING_MINI)
                .pluginName(pluginName)
                .offset(9000)
                .md5(false)
                .build();
    }

    /** Provider per product, keyed by product code, straight off the scanned registry. */
    private static Map<String, GameMessageTypes> registeredProviders() {
        Map<String, GameMessageTypes> byProduct = new TreeMap<>();
        for (String product : registry.registeredBettingMiniProducts()) {
            byProduct.put(product, registry.bettingMini(product));
        }
        return byProduct;
    }

    @Test
    @DisplayName("the scan actually found providers — otherwise every assertion below is vacuous")
    void theRegistryIsPopulated() {
        assertThat(registeredProviders())
                .as("betting-mini providers discovered by the scan")
                .hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("every registered provider returns THIS for every plugin name — except RIK on ziczacPlugin")
    void everyProviderKeepsTheDefaultExceptTheOneSpecialisation() {
        registeredProviders().forEach((product, provider) -> {
            for (String pluginName : PLUGIN_NAMES) {
                GameMessageTypes resolved = provider.forGame(game(pluginName));

                boolean expectSpecialisation =
                        provider.getClass().getName().equals(SPECIALISING_PROVIDER)
                                && SPECIALISING_PLUGIN.equalsIgnoreCase(pluginName);

                if (expectSpecialisation) {
                    assertThat(resolved)
                            .as("product %s / plugin %s must specialise", product, pluginName)
                            .isNotSameAs(provider);
                } else {
                    assertThat(resolved)
                            .as("product %s (%s) / plugin '%s' must return the SAME instance "
                                            + "the registry answered with — a provider that "
                                            + "specialises here changes message classes for a "
                                            + "product that has nothing to do with P_114",
                                    product, provider.getClass().getSimpleName(), pluginName)
                            .isSameAs(provider);
                }
            }
        });
    }

    @Test
    @DisplayName("forGame(null) never throws for any registered provider")
    void nullGameIsToleratedEverywhere() {
        registeredProviders().forEach((product, provider) ->
                assertThat(provider.forGame(null))
                        .as("product %s must tolerate a null Game rather than NPE on a bot thread",
                                product)
                        .isSameAs(provider));
    }

    @Test
    @DisplayName("forGame is idempotent and allocation-free: two calls give the same instance")
    void resolutionIsStableAcrossCalls() {
        registeredProviders().forEach((product, provider) -> {
            for (String pluginName : PLUGIN_NAMES) {
                assertThat(provider.forGame(game(pluginName)))
                        .as("product %s / plugin '%s': forGame is called once per bot, so it "
                                        + "must hand back a shared instance, not allocate",
                                product, pluginName)
                        .isSameAs(provider.forGame(game(pluginName)));
            }
        });
    }

    @Test
    @DisplayName("resolving a game does not perturb the registry's inventory or the boot line")
    void resolutionDoesNotRegisterAnything() {
        // AD-3's entire premise: the ziczac provider is a resolution RESULT, never a
        // bean. V-2 greps the boot line for this exact product set after deploy, so a
        // change here is a change an operator is told to look for.
        var before = Set.copyOf(registry.registeredBettingMiniProducts());

        registeredProviders().forEach((product, provider) -> {
            provider.forGame(game("ziczacPlugin"));
            provider.forGame(game("stockPlugin"));
        });

        assertThat(registry.registeredBettingMiniProducts())
                .containsExactlyInAnyOrderElementsOf(before);
        assertThat(registry.registeredTaiXiuProducts())
                .containsExactlyInAnyOrder("114", "116", "119");
    }
}
