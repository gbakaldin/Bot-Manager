package com.vingame.bot.domain.bot.message;

import com.vingame.bot.infrastructure.plugin.ClasspathPluginBundle;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.g2.b52.B52GameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomStartGameMd5Message;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.JackpotTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD Phase 2c — the port of {@code GameMessageTypesResolverTest} onto
 * the registry that replaced the static {@code switch (productCode)}.
 *
 * <p><b>Discovery here is a real Spring component scan, not a hand-built provider
 * list.</b> That distinction is the whole point of the test: a list assembled in the
 * test proves nothing about what the production scan finds, which is how Phase 1's
 * blocking defect survived review (green against a fake registry, broken in prod). If
 * a provider loses its {@code @Component} or its {@code @MessageTypesImpl}, this test
 * goes red. {@code ApplicationContextLoadsTest.messageTypesRegistryIsFullyPopulated}
 * in bot-app then pins the same thing against {@code Starter}'s own scan, which this
 * one cannot see.
 */
@DisplayName("MessageTypesRegistry — resolution under a real component scan")
class MessageTypesRegistryTest {

    /** The package the registry and all six annotated providers live under. */
    private static final String SCAN_BASE = "com.vingame.bot.domain.bot.message";

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

    /* ---- betting-mini: the four products the switch used to handle ---- */

    @Test
    @DisplayName("Should resolve 097 to BomGameMessageTypes")
    void shouldResolveP097ToBom() {
        GameMessageTypes result = registry.bettingMini(ProductCode.P_097.getCode());

        assertThat(result).isInstanceOf(BomGameMessageTypes.class);
    }

    @Test
    @DisplayName("Should resolve 098 to BomGameMessageTypes — one provider, two products")
    void shouldResolveP098ToBom() {
        GameMessageTypes result = registry.bettingMini(ProductCode.P_098.getCode());

        assertThat(result).isInstanceOf(BomGameMessageTypes.class);
        // The @MessageTypesImpl products list is what keeps 098 alive: a
        // single-valued member would have silently dropped it (AD-17). Pin that both
        // codes land on the *same* singleton, which is also the AD-17 behaviour
        // change from new-ing a provider per bot.
        assertThat(result).isSameAs(registry.bettingMini(ProductCode.P_097.getCode()));
    }

    @Test
    @DisplayName("Should resolve 118 to NohuGameMessageTypes")
    void shouldResolveP118ToNohu() {
        GameMessageTypes result = registry.bettingMini(ProductCode.P_118.getCode());

        assertThat(result).isInstanceOf(NohuGameMessageTypes.class);
    }

    @Test
    @DisplayName("Should resolve 116 to TipGameMessageTypes")
    void shouldResolveP116ToTip() {
        GameMessageTypes result = registry.bettingMini(ProductCode.P_116.getCode());

        assertThat(result).isInstanceOf(TipGameMessageTypes.class);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when productCode is null")
    void shouldThrowWhenNull() {
        assertThatThrownBy(() -> registry.bettingMini(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
    }

    /**
     * One representative unimplemented product. The pre-2c version of this test spelled
     * all six as an {@code @EnumSource} list; keeping that here would make it a
     * <b>second copy</b> of {@code MessageTypesCoverageTest}'s inventory, and a
     * duplicated inventory is a tax on the exact thing this phase exists to make cheap —
     * shipping a brand. AD-19 puts the exhaustive list in one place; this keeps only the
     * "a miss throws" shape.
     */
    @Test
    @DisplayName("Should throw IllegalArgumentException for an unimplemented product code")
    void shouldThrowForUnimplementedProductCode() {
        assertThatThrownBy(() -> registry.bettingMini(ProductCode.P_066.getCode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ProductCode.P_066.getCode())
                .hasMessageContaining("not yet implemented");
    }

    /* ---- slot and Tai Xiu, the other two disjoint lookups ---- */

    @Test
    @DisplayName("slot() resolves the single product-neutral provider")
    void slotResolvesProductNeutralProvider() {
        assertThat(registry.hasSlotProvider()).isTrue();
        assertThat(registry.slot()).isInstanceOf(SlotMessageTypesImpl.class);
        assertThat(registry.slot()).isSameAs(registry.slot());
    }

    @Test
    @DisplayName("taiXiu() keys the same 116 string as bettingMini(), into a different table")
    void taiXiuAndBettingMiniShareAProductCodeWithoutColliding() {
        assertThat(registry.taiXiu(ProductCode.P_116.getCode()))
                .isInstanceOf(MiniGameTaiXiuMessageTypes.class);
        assertThat(registry.bettingMini(ProductCode.P_116.getCode()))
                .isInstanceOf(TipGameMessageTypes.class);
        assertThat(registry.taiXiu(ProductCode.P_114.getCode()))
                .isInstanceOf(JackpotTaiXiuMessageTypes.class);
    }

    /**
     * CASHOUT_BOT AD-3: a fourth, product-keyed table. 119 resolves the Win79 provider;
     * 116 — a live brand with betting-mini and Tai Xiu providers — does not, and says so
     * in the AD-20 shape with the {@code CashoutMessageTypes} contract name.
     */
    @Test
    @DisplayName("cashout(119) resolves the Win79 provider; cashout(116) throws the AD-20 text")
    void cashoutResolvesOnlyItsProduct() {
        assertThat(registry.cashout(ProductCode.P_119.getCode()))
                .isInstanceOf(Win79CashoutMessageTypes.class);
        assertThatThrownBy(() -> registry.cashout(ProductCode.P_116.getCode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CashoutMessageTypes not yet implemented for product code: 116."
                        + " Please create a CashoutMessageTypes implementation for this product.");
    }

    /**
     * AVIATOR_BOT AD-5: a fifth, product-keyed table. 119 resolves the Win79 Avatar
     * provider with its two runners; 116 does not, in the AD-20 shape with the
     * {@code CrashMessageTypes} contract name.
     */
    @Test
    @DisplayName("crash(119) resolves the Win79 provider with 2 runners; crash(116) throws the AD-20 text")
    void crashResolvesOnlyItsProduct() {
        CrashMessageTypes crash = registry.crash(ProductCode.P_119.getCode());
        assertThat(crash).isInstanceOf(Win79CrashMessageTypes.class);
        assertThat(crash.runnerCount()).isEqualTo(2);
        assertThatThrownBy(() -> registry.crash(ProductCode.P_116.getCode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CrashMessageTypes not yet implemented for product code: 116."
                        + " Please create a CrashMessageTypes implementation for this product.");
    }

    @Test
    @DisplayName("The scan registers exactly 119 for CRASH, and every CRASH bean declares gameType CRASH")
    void crashScan() {
        assertThat(registry.registeredCrashProducts())
                .as("AVIATOR_BOT AD-5 — 119 Avatar only")
                .containsExactly("119");
        assertGameType(context.getBeansOfType(CrashMessageTypes.class).values(), GameType.CRASH);
    }

    /* ---- what the scan did and did not pick up ---- */

    /**
     * The AD-23 behaviour-identity pin: the registry registers <em>exactly</em> the
     * declared set, no more and no less. Deliberately a hardcoded set — shipping a
     * brand must be a conscious edit here, because "which products have a provider"
     * is the one observable this sub-phase promises not to change by accident.
     * Together with the one inventory line in {@code MessageTypesCoverageTest} it is
     * the whole cost of a new product; nothing in {@code src/main} outside the new
     * provider itself has to change.
     * <p>
     * <b>Neither row is still exactly what {@code GameMessageTypesResolver}'s switch
     * returned</b>, and deliberately so. {@code "119"} was added to both on 2026-09-10 —
     * {@code Win79GameMessageTypes} and {@code Win79TaiXiuMessageTypes} — the first
     * providers registered after the resolver was retired. Without them a group for
     * P_119 of the corresponding type cannot be created at all: {@code BotFactory}
     * throws on the bot thread before authentication.
     */
    @Test
    @DisplayName("The scan registers exactly the declared product set")
    void scanRegistersExactlyThePreviousSwitchArms() {
        assertThat(registry.registeredBettingMiniProducts())
                .containsExactlyInAnyOrder("097", "098", "114", "116", "118", "119");
        assertThat(registry.registeredTaiXiuProducts())
                .containsExactlyInAnyOrder("114", "116", "119");
        assertThat(registry.registeredCashoutProducts())
                .as("CASHOUT_BOT AD-3 — 119 Balloon/Soccer only")
                .containsExactly("119");
    }

    /**
     * {@link B52GameMessageTypes} implements {@link GameMessageTypes} and is on the
     * scanned package, but carries no {@code @Component} — exactly as the switch never
     * returned it. Pin that, so "complete the registry" cannot quietly change which
     * classes product 098 parses with.
     */
    @Test
    @DisplayName("B52GameMessageTypes stays out of the registry, as it was out of the switch")
    void b52IsNotRegistered() {
        assertThat(context.getBeansOfType(GameMessageTypes.class).values())
                .noneMatch(B52GameMessageTypes.class::isInstance);
        for (String product : registry.registeredBettingMiniProducts()) {
            assertThat(registry.bettingMini(product)).isNotInstanceOf(B52GameMessageTypes.class);
        }
    }

    /**
     * The annotation's {@code gameType} is not decorative: the registry cross-checks it
     * against the contract interface the bean was discovered under. Assert every
     * discovered provider agrees, so the check has something to be true of.
     */
    @Test
    @DisplayName("Every discovered provider's declared gameType matches its contract interface")
    void declaredGameTypeMatchesTheContract() {
        assertGameType(context.getBeansOfType(GameMessageTypes.class).values(), GameType.BETTING_MINI);
        assertGameType(context.getBeansOfType(SlotMessageTypes.class).values(), GameType.SLOT);
        assertGameType(context.getBeansOfType(TaiXiuMessageTypes.class).values(), GameType.TAI_XIU);
        assertGameType(context.getBeansOfType(CashoutMessageTypes.class).values(), GameType.CASHOUT);
    }

    private static void assertGameType(Iterable<?> beans, GameType expected) {
        for (Object bean : beans) {
            MessageTypesImpl annotation = bean.getClass().getAnnotation(MessageTypesImpl.class);
            assertThat(annotation)
                    .as("%s is a discovered %s bean but carries no @MessageTypesImpl",
                            bean.getClass().getName(), expected)
                    .isNotNull();
            assertThat(annotation.gameType())
                    .as("%s declares gameType", bean.getClass().getName())
                    .isEqualTo(expected);
            List<String> products = Arrays.asList(annotation.products());
            assertThat(products).allSatisfy(p -> assertThat(p).isNotBlank().isEqualTo(p.trim()));
            if (expected == GameType.SLOT) {
                assertThat(products).as("SLOT providers are product-neutral").isEmpty();
            } else {
                assertThat(products).as("%s must claim at least one product",
                        bean.getClass().getName()).isNotEmpty();
            }
        }
    }

    /* ---- unchanged: the provider's own registration shape ---- */

    @Test
    @DisplayName("getTypeRegistrations produces correct CMD = CODE + OFFSET names and swaps StartGame on md5")
    void getTypeRegistrationsProducesCorrectCmdValues() {
        GameMessageTypes types = registry.bettingMini(ProductCode.P_097.getCode());

        // 4 entries: SUBSCRIBE (3000+2000), UPDATE_BET (3002+2000), START_GAME (3005+2000), END_GAME (3006+2000)
        NamedType[] regs = types.getTypeRegistrations(2000, false);
        assertThat(regs).hasSize(4);
        Set<String> names = Arrays.stream(regs).map(NamedType::getName).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("5000", "5002", "5005", "5006");

        // md5=true swaps the START_GAME entry to BomStartGameMd5Message; cmd value is unchanged.
        NamedType[] regsMd5 = types.getTypeRegistrations(2000, true);
        assertThat(regsMd5).hasSize(4);
        NamedType startGameReg = Arrays.stream(regsMd5)
                .filter(r -> "5005".equals(r.getName()))
                .findFirst().orElseThrow();
        assertThat(startGameReg.getType()).isEqualTo(BomStartGameMd5Message.class);
    }
}
