package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.JackpotTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD Phase 2c — <b>the registry's fail-loud branches</b>, which the
 * shipped 2c suite exercises only through providers that are all correct.
 *
 * <p>Why these matter more than they look. Before 2c a malformed provider was not
 * expressible: {@code GameMessageTypesResolver}'s {@code switch} named a class
 * literal, so "claims the wrong game type", "claims no product", and "two classes
 * claim one product" were all compile errors or simply unwritable. After 2c they are
 * annotation values on a bean, and this phase exists precisely so that a <em>new</em>
 * provider can be added without touching central code — i.e. by someone who is not
 * looking at {@code MessageTypesRegistry}. Every one of these branches is therefore
 * on the path a future contributor will actually walk, and each is the difference
 * between a context refresh that dies naming the offending class and a fleet that
 * silently parses a brand's frames with another brand's classes.
 *
 * <p>The registry is constructed directly rather than scanned, because the subject is
 * a provider list that a component scan cannot currently produce. Discovery is covered
 * by {@link MessageTypesRegistryTest} and {@link MessageTypesCoverageTest}.
 */
@DisplayName("MessageTypesRegistry — malformed providers fail loud at construction")
class MessageTypesRegistryValidationTest {

    /* ------------------------------------------------------------------ *
     * Fixtures. Each subclasses a real provider so it inherits a complete
     * implementation, and re-declares @MessageTypesImpl with the defect under
     * test. None carries @Component, and @Component is not @Inherited, so none
     * of these is a candidate for the sibling tests' component scan.
     * ------------------------------------------------------------------ */

    @MessageTypesImpl(gameType = GameType.SLOT, products = "097")
    static class BettingMiniClaimingSlot extends BomGameMessageTypes {
    }

    @MessageTypesImpl(gameType = GameType.BETTING_MINI, products = {})
    static class BettingMiniWithNoProducts extends BomGameMessageTypes {
    }

    @MessageTypesImpl(gameType = GameType.BETTING_MINI, products = "097")
    static class SecondClaimantFor097 extends TipGameMessageTypes {
    }

    @MessageTypesImpl(gameType = GameType.BETTING_MINI, products = "116")
    static class TaiXiuClaimingBettingMini extends MiniGameTaiXiuMessageTypes {
    }

    @MessageTypesImpl(gameType = GameType.SLOT, products = "116")
    static class ProductScopedSlot extends SlotMessageTypesImpl {
    }

    @MessageTypesImpl(gameType = GameType.SLOT, products = {})
    static class SecondProductNeutralSlot extends SlotMessageTypesImpl {
    }

    /** No {@code @MessageTypesImpl} at all — the WARN-and-skip path. */
    static class UnannotatedBettingMini extends BomGameMessageTypes {
    }

    @Nested
    @DisplayName("gameType must agree with the contract interface the bean implements")
    class GameTypeCrossCheck {

        @Test
        @DisplayName("a GameMessageTypes declaring SLOT is rejected, naming the class")
        void bettingMiniDeclaringSlotIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(new BettingMiniClaimingSlot()), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BettingMiniClaimingSlot.class.getName())
                    .hasMessageContaining("declares gameType = SLOT")
                    .hasMessageContaining("BETTING_MINI");
        }

        @Test
        @DisplayName("a TaiXiuMessageTypes declaring BETTING_MINI is rejected")
        void taiXiuDeclaringBettingMiniIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(), List.of(new TaiXiuClaimingBettingMini())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TaiXiuClaimingBettingMini.class.getName())
                    .hasMessageContaining("TAI_XIU");
        }

        @Test
        @DisplayName("a SlotMessageTypes declaring products is rejected — SLOT is product-neutral")
        void productScopedSlotIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(new ProductScopedSlot()), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ProductScopedSlot.class.getName())
                    .hasMessageContaining("product-neutral");
        }
    }

    @Nested
    @DisplayName("a product-keyed provider must claim at least one product")
    class ProductsRequired {

        @Test
        @DisplayName("products = {} on a BETTING_MINI provider is rejected")
        void emptyProductsOnBettingMiniIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(new BettingMiniWithNoProducts()), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BettingMiniWithNoProducts.class.getName())
                    .hasMessageContaining("declares no products");
        }
    }

    @Nested
    @DisplayName("one product may not be claimed twice")
    class DuplicateClaims {

        /**
         * The defect this catches is silent-last-wins: a {@code Map.put} would simply
         * have replaced 097's provider with whichever bean the scan happened to visit
         * second, so product 097 would parse BOM frames with TIP classes depending on
         * classpath order. Both offending class names must appear so the operator does
         * not have to bisect the scan.
         */
        @Test
        @DisplayName("two BETTING_MINI providers claiming 097 are rejected, naming both")
        void duplicateBettingMiniProductIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(new BomGameMessageTypes(), new SecondClaimantFor097()),
                    List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate")
                    .hasMessageContaining("097")
                    .hasMessageContaining(BomGameMessageTypes.class.getName())
                    .hasMessageContaining(SecondClaimantFor097.class.getName());
        }

        @Test
        @DisplayName("two product-neutral SLOT providers are rejected, naming both")
        void duplicateSlotProviderIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(),
                    List.of(new SlotMessageTypesImpl(), new SecondProductNeutralSlot()),
                    List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate product-neutral")
                    .hasMessageContaining(SlotMessageTypesImpl.class.getName())
                    .hasMessageContaining(SecondProductNeutralSlot.class.getName());
        }

        /**
         * The converse, so the duplicate check cannot be "fixed" by widening it: the
         * two tables are keyed on {@code (gameType, product)}, and 116 legitimately
         * appears in both today.
         */
        @Test
        @DisplayName("116 in both tables is not a duplicate")
        void sameProductInBothTablesIsFine() {
            MessageTypesRegistry registry = new MessageTypesRegistry(
                    List.of(new TipGameMessageTypes()),
                    List.of(new SlotMessageTypesImpl()),
                    List.of(new MiniGameTaiXiuMessageTypes(), new JackpotTaiXiuMessageTypes()));

            assertThat(registry.bettingMini("116")).isInstanceOf(TipGameMessageTypes.class);
            assertThat(registry.taiXiu("116")).isInstanceOf(MiniGameTaiXiuMessageTypes.class);
        }
    }

    @Nested
    @DisplayName("a provider bean with no @MessageTypesImpl is skipped, not fatal")
    class MissingAnnotation {

        /**
         * Deliberately not a throw: the registry has no key for such a bean, and a
         * context that refuses to start over one unkeyed bean would be a worse failure
         * than a product that resolves nothing. The safety net is that the product it
         * <em>would</em> have claimed then fails {@link MessageTypesCoverageTest} —
         * verified by mutation, where removing {@code @Component} from
         * {@code TipGameMessageTypes} turns that test red for product 116.
         */
        @Test
        @DisplayName("construction succeeds and the unannotated bean registers nothing")
        void unannotatedProviderIsSkipped() {
            MessageTypesRegistry registry = new MessageTypesRegistry(
                    List.of(new UnannotatedBettingMini()), List.of(), List.of());

            assertThat(registry.registeredBettingMiniProducts()).isEmpty();
            assertThatThrownBy(() -> registry.bettingMini("097"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("GameMessageTypes not yet implemented for product code: 097."
                            + " Please create a GameMessageTypes implementation for this product.");
        }
    }

    @Nested
    @DisplayName("slot() with no provider is a deploy bug, and says so")
    class MissingSlotProvider {

        /**
         * This is a genuine behaviour change from {@code GameMessageTypesResolver.resolveSlot()},
         * which returned {@code new SlotMessageTypesImpl()} unconditionally and could not
         * fail. It is the right change — a missing bean must not be papered over — but it
         * is an {@code IllegalStateException}, not the AD-20 {@code IllegalArgumentException},
         * and the distinction is what tells an operator "this is a build/deploy problem,
         * not an unsupported brand". Pinned so it cannot drift into the AD-20 text.
         */
        @Test
        @DisplayName("IllegalStateException naming the annotation the deploy is missing")
        void slotWithoutProviderThrowsIllegalState() {
            MessageTypesRegistry registry = new MessageTypesRegistry(
                    List.of(new BomGameMessageTypes()), List.of(), List.of());

            assertThat(registry.hasSlotProvider()).isFalse();
            assertThatThrownBy(registry::slot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No SlotMessageTypes provider is registered")
                    .hasMessageContaining("@MessageTypesImpl(gameType = SLOT, products = {})");
        }

        @Test
        @DisplayName("an empty registry still constructs — the throw is at lookup, not at refresh")
        void emptyRegistryConstructs() {
            assertThatCode(() -> new MessageTypesRegistry(List.of(), List.of(), List.of()))
                    .doesNotThrowAnyException();
        }
    }
}
