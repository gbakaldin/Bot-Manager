package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.rik.RikZicZacGameMessageTypes;
import com.vingame.bot.domain.bot.message.request.GameRequestFactory;
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
 * Who may fork the outbound bet frame — asserted over the <b>real component scan</b>
 * (RIK_114_BETTING_MINI AD-20/AD-21), the same way
 * {@code GameMessageTypesForGameContractTest} asserts {@code forGame}.
 *
 * <p>{@code BettingMiniGameBot.buildRequest} tests its injected provider with
 * {@code instanceof GameRequestFactory} and falls back to the shared {@code Request}
 * for every provider that is not one. So "does this product's bet frame change?" is
 * answered entirely by which providers implement the interface — and the answer must be
 * <b>exactly the two 114 providers</b>: {@link RikGameMessageTypes}, the registry bean,
 * which itself hands back the shared {@code Request} for every 114 game except
 * {@code stockPlugin}; and {@link RikZicZacGameMessageTypes}, reachable only as
 * {@code forGame}'s result for {@code ziczacPlugin}, which hands back a
 * {@code ZicZacRequest} (RIK_114_ZICZAC Phase 2, Amendment A1 item 3).
 *
 * <p><b>The set was widened from one to two deliberately</b> when ziczac's body shipped.
 * Until then this class asserted that {@code RikZicZacGameMessageTypes} did <i>not</i>
 * implement the capability (RIK_114_BETTING_MINI AD-24); that assertion was the
 * placeholder for this phase, and flipping it is the phase.
 *
 * <p>A hand-written list of the five other providers would be a statement about the day
 * it was written; it would not cover the seventh provider the next brand brings, and the
 * blast radius of a stray implementation is a product staking with a key its server does
 * not read — the silent, healthy-looking zero this whole feature exists to end.
 */
@DisplayName("GameRequestFactory — exactly the two 114 providers may fork the bet frame")
class GameRequestFactoryCapabilityTest {

    /** The package the registry and every annotated provider live under. */
    private static final String SCAN_BASE = "com.vingame.bot.domain.bot.message";

    /**
     * The only providers in the codebase allowed to implement the capability. Adding a
     * name here is a design decision, deliberately requiring an edit to this comment.
     * <ul>
     *   <li>{@link RikGameMessageTypes} — the registered 114 bean; allowlists
     *       {@code stockPlugin} inside {@code requestFor}.</li>
     *   <li>{@link RikZicZacGameMessageTypes} — not a bean; the {@code forGame}
     *       resolution result for {@code ziczacPlugin} and nothing else.</li>
     * </ul>
     */
    private static final Set<String> CAPABLE_PROVIDERS = Set.of(
            RikGameMessageTypes.class.getName(),
            RikZicZacGameMessageTypes.class.getName());

    private static AnnotationConfigApplicationContext context;
    private static MessageTypesRegistry registry;

    @BeforeAll
    static void bootRealContext() {
        context = new AnnotationConfigApplicationContext();
        context.scan(SCAN_BASE);
        context.refresh();
        registry = context.getBean(MessageTypesRegistry.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

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
    @DisplayName("RikGameMessageTypes is the ONLY registered provider implementing GameRequestFactory")
    void onlyRikImplementsTheCapability() {
        // The registry holds beans only; RikZicZacGameMessageTypes is not one (it is
        // reached through forGame), so among REGISTERED providers the answer is still
        // exactly one. The per-game test below is where the second name appears.
        registeredProviders().forEach((product, provider) ->
                assertThat(provider instanceof GameRequestFactory)
                        .as("product %s (%s) implements GameRequestFactory", product,
                                provider.getClass().getSimpleName())
                        .isEqualTo(CAPABLE_PROVIDERS.contains(provider.getClass().getName())));
    }

    @Test
    @DisplayName("and the 114 provider really does implement it — the assertion above is not vacuous")
    void rikImplementsIt() {
        assertThat(registry.bettingMini("114")).isInstanceOf(GameRequestFactory.class);
    }

    @Test
    @DisplayName("resolved per-game: the ziczac specialisation implements it too (Phase 2), and NOTHING else does")
    void resolvedProvidersAreAlsoCovered() {
        String[] pluginNames = {
                "stockPlugin", "taixiuMd5Plugin", "ziczacPlugin", "BauCua", "MiniPoker", null
        };

        registeredProviders().forEach((product, provider) -> {
            for (String pluginName : pluginNames) {
                GameMessageTypes resolved = provider.forGame(Game.builder()
                        .gameType(GameType.BETTING_MINI)
                        .pluginName(pluginName)
                        .offset(10000)
                        .md5(false)
                        .build());

                // The only objects that may answer true are the RIK registry provider
                // and the ziczac specialisation it resolves to. Every other product's
                // forGame returns itself (GameMessageTypesForGameContractTest), so a
                // stray implementation anywhere else shows up on this matrix.
                assertThat(resolved instanceof GameRequestFactory)
                        .as("product %s, plugin %s resolved to %s", product, pluginName,
                                resolved.getClass().getSimpleName())
                        .isEqualTo(CAPABLE_PROVIDERS.contains(resolved.getClass().getName()));
            }
        });
    }

    @Test
    @DisplayName("and the ziczac specialisation really does implement it — the widening is not vacuous")
    void ziczacImplementsIt() {
        GameMessageTypes resolved = registry.bettingMini("114").forGame(Game.builder()
                .gameType(GameType.BETTING_MINI)
                .pluginName("ziczacPlugin")
                .offset(9000)
                .md5(false)
                .build());

        assertThat(resolved).isInstanceOf(RikZicZacGameMessageTypes.class);
        assertThat(resolved).isInstanceOf(GameRequestFactory.class);
    }
}
