package com.vingame.bot.domain.bot.message;

import com.vingame.bot.infrastructure.plugin.ClasspathPluginBundle;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * PLUGIN_HOT_RELOAD Phase 2c, AD-19 — <b>the replacement for the {@code switch}'s
 * compile-time exhaustiveness.</b>
 *
 * <p>Before Phase 2c, adding an eleventh {@code ProductCode} constant broke the build
 * until someone handled it in three {@code switch} arms; {@code javac} did that for
 * free. A registry cannot: an unhandled product is simply a map miss at runtime, on
 * the box, when a group fails to start. This test is what fails instead, and it is
 * deliberately shaped as an <b>inventory</b> rather than a bare "everything resolves"
 * assertion: a product with no provider is fine, but it must be <em>written down</em>
 * as such.
 *
 * <p>Three things make it red, and all three were verified by mutation:
 * <ul>
 *   <li>a new {@code ProductCode} constant appears in neither {@link #BETTING_MINI_NOT_YET_IMPLEMENTED}
 *       nor the resolvable set — {@link #everyProductIsAccountedForPerGameType()};</li>
 *   <li>a provider stops being discovered (lost {@code @Component}, lost
 *       {@code @MessageTypesImpl}, moved out of the scanned package) — the product it
 *       claimed is not on the not-yet list, so its resolve throws;</li>
 *   <li>a not-yet list entry goes stale because a provider shipped for it — it is
 *       asserted to actually throw, so a silently-resolving entry fails.</li>
 * </ul>
 *
 * <p>Discovery is a real component scan for the same reason as
 * {@link MessageTypesRegistryTest}: a hand-assembled provider list would make the
 * first two of those three undetectable.
 */
@DisplayName("Message-types coverage — every ProductCode is accounted for, per GameType")
class MessageTypesCoverageTest {

    private static final String SCAN_BASE = "com.vingame.bot.domain.bot.message";

    /**
     * Products with <b>no</b> {@code BETTING_MINI} provider — the exact set that used
     * to sit in {@code GameMessageTypesResolver}'s throw arm. Shipping a provider for
     * one of them is a pure addition (a new annotated {@code @Component}) plus deleting
     * its line here; nothing else in the codebase changes. That single central edit is
     * AD-19's deliberate price for turning a compile error into a readable inventory.
     */
    private static final Set<String> BETTING_MINI_NOT_YET_IMPLEMENTED = Set.of(
            "066",  // P_066 KCLUB
            "103",  // P_103 HIT
            "105",  // P_105 IWIN
            "222"); // P_222 BKK WIN

    /** Products with no {@code TAI_XIU} provider — the resolver's other throw arm. */
    private static final Set<String> TAI_XIU_NOT_YET_IMPLEMENTED = Set.of(
            "066", "097", "098", "103", "105", "118", "222");

    /**
     * Products with no {@code CASHOUT} provider — every product except 119, the only
     * brand known to run {@code balloonPlugin} / {@code soccerPlugin}
     * (CASHOUT_BOT AD-3).
     */
    private static final Set<String> CASHOUT_NOT_YET_IMPLEMENTED = Set.of(
            "066", "097", "098", "103", "105", "114", "116", "118", "222");

    /**
     * Products with no {@code CRASH} provider — every product except 119, whose Avatar
     * ({@code aviatorPlugin}) is the first crash game (AVIATOR_BOT AD-5).
     */
    private static final Set<String> CRASH_NOT_YET_IMPLEMENTED = Set.of(
            "066", "097", "098", "103", "105", "114", "116", "118", "222");

    /**
     * Game types that have no message-types lookup at all. {@code BotFactory}'s own
     * {@code switch (game.getGameType())} — which selects a <em>bot class</em>, not a
     * product implementation, and therefore stays a switch — rejects these with
     * "Game type not yet implemented". Listed here so that adding a new
     * {@link GameType} fails {@link #everyGameTypeIsClassified()} and forces the
     * decision rather than defaulting to silence.
     */
    private static final Set<GameType> GAME_TYPES_WITHOUT_A_LOOKUP =
            Set.of(GameType.CARD_GAME, GameType.UP_DOWN);

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

    @Test
    @DisplayName("Every ProductCode either resolves a provider or is on the not-yet-implemented inventory")
    void everyProductIsAccountedForPerGameType() {
        for (ProductCode product : ProductCode.values()) {
            String code = product.getCode();

            assertResolvesOrIsListed(product, GameType.BETTING_MINI,
                    BETTING_MINI_NOT_YET_IMPLEMENTED.contains(code),
                    () -> registry.bettingMini(code),
                    "GameMessageTypes");

            assertResolvesOrIsListed(product, GameType.TAI_XIU,
                    TAI_XIU_NOT_YET_IMPLEMENTED.contains(code),
                    () -> registry.taiXiu(code),
                    "TaiXiuMessageTypes");

            assertResolvesOrIsListed(product, GameType.CASHOUT,
                    CASHOUT_NOT_YET_IMPLEMENTED.contains(code),
                    () -> registry.cashout(code),
                    "CashoutMessageTypes");

            assertResolvesOrIsListed(product, GameType.CRASH,
                    CRASH_NOT_YET_IMPLEMENTED.contains(code),
                    () -> registry.crash(code),
                    "CrashMessageTypes");

            // SLOT is product-neutral (AD-17): one provider serves every product, so
            // there is no per-product arm to be missing and no inventory to keep.
            assertThat(registry.slot())
                    .as("SLOT provider for product %s", code)
                    .isNotNull();
        }
    }

    /**
     * The inventories must describe reality in both directions. The test above proves
     * "listed ⇒ throws"; this proves "listed ⇒ is a real product code", so a typo'd or
     * deleted constant cannot sit in the list masking a genuine hole.
     */
    @Test
    @DisplayName("Every not-yet-implemented entry is a real ProductCode, and none is also registered")
    void inventoriesAreConsistentWithProductCode() {
        Set<String> allCodes = Arrays.stream(ProductCode.values())
                .map(ProductCode::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(BETTING_MINI_NOT_YET_IMPLEMENTED).isSubsetOf(allCodes);
        assertThat(TAI_XIU_NOT_YET_IMPLEMENTED).isSubsetOf(allCodes);
        assertThat(CASHOUT_NOT_YET_IMPLEMENTED).isSubsetOf(allCodes);
        assertThat(CRASH_NOT_YET_IMPLEMENTED).isSubsetOf(allCodes);

        assertThat(registry.registeredBettingMiniProducts())
                .as("a registered product must not also be listed as not-yet-implemented")
                .doesNotContainAnyElementsOf(BETTING_MINI_NOT_YET_IMPLEMENTED);
        assertThat(registry.registeredTaiXiuProducts())
                .doesNotContainAnyElementsOf(TAI_XIU_NOT_YET_IMPLEMENTED);
        assertThat(registry.registeredCashoutProducts())
                .doesNotContainAnyElementsOf(CASHOUT_NOT_YET_IMPLEMENTED);
        assertThat(registry.registeredCrashProducts())
                .doesNotContainAnyElementsOf(CRASH_NOT_YET_IMPLEMENTED);

        // Union covers everything: registered ∪ not-yet == every ProductCode.
        Set<String> bettingMiniCovered = new LinkedHashSet<>(registry.registeredBettingMiniProducts());
        bettingMiniCovered.addAll(BETTING_MINI_NOT_YET_IMPLEMENTED);
        assertThat(bettingMiniCovered)
                .as("BETTING_MINI coverage — a new ProductCode must be given a provider "
                        + "or added to BETTING_MINI_NOT_YET_IMPLEMENTED")
                .containsExactlyInAnyOrderElementsOf(allCodes);

        Set<String> taiXiuCovered = new LinkedHashSet<>(registry.registeredTaiXiuProducts());
        taiXiuCovered.addAll(TAI_XIU_NOT_YET_IMPLEMENTED);
        assertThat(taiXiuCovered)
                .as("TAI_XIU coverage — a new ProductCode must be given a provider "
                        + "or added to TAI_XIU_NOT_YET_IMPLEMENTED")
                .containsExactlyInAnyOrderElementsOf(allCodes);

        Set<String> cashoutCovered = new LinkedHashSet<>(registry.registeredCashoutProducts());
        cashoutCovered.addAll(CASHOUT_NOT_YET_IMPLEMENTED);
        assertThat(cashoutCovered)
                .as("CASHOUT coverage — a new ProductCode must be given a provider "
                        + "or added to CASHOUT_NOT_YET_IMPLEMENTED")
                .containsExactlyInAnyOrderElementsOf(allCodes);

        Set<String> crashCovered = new LinkedHashSet<>(registry.registeredCrashProducts());
        crashCovered.addAll(CRASH_NOT_YET_IMPLEMENTED);
        assertThat(crashCovered)
                .as("CRASH coverage — a new ProductCode must be given a provider "
                        + "or added to CRASH_NOT_YET_IMPLEMENTED")
                .containsExactlyInAnyOrderElementsOf(allCodes);
    }

    /**
     * The other half of the lost exhaustiveness: a new {@link GameType} constant.
     * Five of the seven have a registry lookup (CASHOUT joined for CASHOUT_BOT AD-3 and
     * CRASH for AVIATOR_BOT AD-5 — each lookup exists before its bot does); the other two
     * are named in {@link #GAME_TYPES_WITHOUT_A_LOOKUP}. An eighth belongs in one column
     * or the other.
     */
    @Test
    @DisplayName("Every GameType either has a registry lookup or is explicitly listed as having none")
    void everyGameTypeIsClassified() {
        Set<GameType> withLookup = Set.of(GameType.BETTING_MINI, GameType.SLOT, GameType.TAI_XIU,
                GameType.CASHOUT, GameType.CRASH);

        assertThat(withLookup).doesNotContainAnyElementsOf(GAME_TYPES_WITHOUT_A_LOOKUP);

        Set<GameType> covered = new LinkedHashSet<>(withLookup);
        covered.addAll(GAME_TYPES_WITHOUT_A_LOOKUP);
        assertThat(covered)
                .as("a new GameType needs either a MessageTypesRegistry lookup or a line "
                        + "in GAME_TYPES_WITHOUT_A_LOOKUP")
                .containsExactlyInAnyOrderElementsOf(Set.of(GameType.values()));
    }

    /**
     * The counts the boot log prints, asserted at build time so a lost bean fails here
     * rather than on the box — the same role as
     * {@code StrategyCatalogParityTest.scanDiscoversTheWholeCatalogue}.
     * <p>
     * The expected numbers are <b>derived</b> from the inventories above rather than
     * written as literals, on purpose: a hardcoded 4 and 2 would be a third copy of the
     * same fact, and every duplicated copy is another file that shipping a brand has to
     * touch. As arithmetic it is a cross-check — registry size plus inventory size must
     * equal {@code ProductCode.values().length} — not a duplicate.
     */
    @Test
    @DisplayName("A real component scan discovers every product not on an inventory, plus the slot provider")
    void scanDiscoversTheWholeCatalogue() {
        int products = ProductCode.values().length;
        assertThat(registry.registeredBettingMiniProducts())
                .hasSize(products - BETTING_MINI_NOT_YET_IMPLEMENTED.size());
        assertThat(registry.registeredTaiXiuProducts())
                .hasSize(products - TAI_XIU_NOT_YET_IMPLEMENTED.size());
        assertThat(registry.registeredCashoutProducts())
                .hasSize(products - CASHOUT_NOT_YET_IMPLEMENTED.size());
        assertThat(registry.registeredCrashProducts())
                .hasSize(products - CRASH_NOT_YET_IMPLEMENTED.size());
        assertThat(registry.hasSlotProvider()).isTrue();
    }

    private static void assertResolvesOrIsListed(ProductCode product,
                                                 GameType gameType,
                                                 boolean listedAsNotYetImplemented,
                                                 ThrowingSupplier lookup,
                                                 String contract) {
        if (listedAsNotYetImplemented) {
            assertThatThrownBy(lookup::get)
                    .as("%s is on the %s not-yet-implemented inventory, so the lookup must "
                            + "still throw — if a provider shipped, delete the inventory line",
                            product, gameType)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(contract + " not yet implemented for product code: "
                            + product.getCode());
            return;
        }
        try {
            assertThat(lookup.get())
                    .as("%s resolves a %s provider", product, gameType)
                    .isNotNull();
        } catch (Exception e) {
            fail("%s (%s) has no %s provider and is not on the not-yet-implemented "
                    + "inventory — either register a provider or add \"%s\" to the list. "
                    + "Lookup said: %s",
                    product, gameType, contract, product.getCode(), e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get();
    }
}
