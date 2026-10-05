package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessage;
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

    /** CASHOUT is product-keyed (CASHOUT_BOT AD-3), so the no-products rule applies to it too. */
    @MessageTypesImpl(gameType = GameType.CASHOUT, products = {})
    static class CashoutWithNoProducts extends Win79CashoutMessageTypes {
    }

    @MessageTypesImpl(gameType = GameType.TAI_XIU, products = "119")
    static class CashoutClaimingTaiXiu extends Win79CashoutMessageTypes {
    }

    /** CRASH is product-keyed too (AVIATOR_BOT AD-5). */
    @MessageTypesImpl(gameType = GameType.CRASH, products = {})
    static class CrashWithNoProducts extends Win79CrashMessageTypes {
    }

    /** The likeliest copy-paste slip: a crash provider still annotated as the cash-out one. */
    @MessageTypesImpl(gameType = GameType.CASHOUT, products = "119")
    static class CrashClaimingCashout extends Win79CrashMessageTypes {
    }

    @MessageTypesImpl(gameType = GameType.SLOT, products = "116")
    static class ProductScopedSlot extends SlotMessageTypesImpl {
    }

    @MessageTypesImpl(gameType = GameType.SLOT, products = {})
    static class SecondProductNeutralSlot extends SlotMessageTypesImpl {
    }

    /**
     * No {@code @MessageTypesImpl} <em>anywhere in its hierarchy</em>.
     *
     * <p>It implements the contract directly rather than subclassing a real provider,
     * which matters: the registry resolves the annotation with
     * {@code AnnotationUtils.findAnnotation}, which searches superclasses, so
     * {@code extends BomGameMessageTypes} would silently inherit 097/098 and this
     * fixture would not be testing what it says it tests. See
     * {@link AnnotationInheritedFromASuperclass} for the other half of that pair.
     */
    static class UnannotatedBettingMini implements GameMessageTypes {
        @Override public Class<? extends SubscribeMessage> subscribeType() { return null; }
        @Override public Class<? extends StartGameMessage> startGameType() { return null; }
        @Override public Class<? extends StartGameMd5Message> startGameMd5Type() { return null; }
        @Override public Class<? extends UpdateBetMessage> updateBetType() { return null; }
        @Override public Class<? extends EndGameMessage> endGameType() { return null; }
    }

    /**
     * Carries no annotation of its own but inherits one from a real provider. Pins the
     * hierarchy search, which is a behaviour change from {@code getAnnotation}.
     */
    static class InheritsBomAnnotation extends BomGameMessageTypes {
    }

    @Nested
    @DisplayName("gameType must agree with the contract interface the bean implements")
    class GameTypeCrossCheck {

        @Test
        @DisplayName("a GameMessageTypes declaring SLOT is rejected, naming the class")
        void bettingMiniDeclaringSlotIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(new BettingMiniClaimingSlot()), List.of(), List.of(), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BettingMiniClaimingSlot.class.getName())
                    .hasMessageContaining("declares gameType = SLOT")
                    .hasMessageContaining("BETTING_MINI");
        }

        @Test
        @DisplayName("a TaiXiuMessageTypes declaring BETTING_MINI is rejected")
        void taiXiuDeclaringBettingMiniIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(), List.of(new TaiXiuClaimingBettingMini()), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TaiXiuClaimingBettingMini.class.getName())
                    .hasMessageContaining("TAI_XIU");
        }

        @Test
        @DisplayName("a SlotMessageTypes declaring products is rejected — SLOT is product-neutral")
        void productScopedSlotIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(new ProductScopedSlot()), List.of(), List.of(), List.of()))
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
                    List.of(new BettingMiniWithNoProducts()), List.of(), List.of(), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BettingMiniWithNoProducts.class.getName())
                    .hasMessageContaining("declares no products");
        }

        @Test
        @DisplayName("products = {} on a CASHOUT provider is rejected — it is not product-neutral")
        void emptyProductsOnCashoutIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(), List.of(), List.of(new CashoutWithNoProducts()), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CashoutWithNoProducts.class.getName())
                    .hasMessageContaining("declares no products");
        }

        @Test
        @DisplayName("a CashoutMessageTypes declaring TAI_XIU is rejected, naming the class")
        void cashoutDeclaringTaiXiuIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(), List.of(), List.of(new CashoutClaimingTaiXiu()), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CashoutClaimingTaiXiu.class.getName())
                    .hasMessageContaining("declares gameType = TAI_XIU")
                    .hasMessageContaining("CASHOUT");
        }

        @Test
        @DisplayName("products = {} on a CRASH provider is rejected — it is not product-neutral")
        void emptyProductsOnCrashIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(), List.of(), List.of(), List.of(new CrashWithNoProducts())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CrashWithNoProducts.class.getName())
                    .hasMessageContaining("declares no products");
        }

        @Test
        @DisplayName("a CrashMessageTypes declaring CASHOUT is rejected, naming the class")
        void crashDeclaringCashoutIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(), List.of(), List.of(), List.of(new CrashClaimingCashout())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CrashClaimingCashout.class.getName())
                    .hasMessageContaining("declares gameType = CASHOUT")
                    .hasMessageContaining("CRASH");
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
                    List.of(), List.of(), List.of(), List.of()))
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
                    List.of(), List.of(), List.of()))
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
                    List.of(new MiniGameTaiXiuMessageTypes(), new JackpotTaiXiuMessageTypes()), List.of(), List.of());

            assertThat(registry.bettingMini("116")).isInstanceOf(TipGameMessageTypes.class);
            assertThat(registry.taiXiu("116")).isInstanceOf(MiniGameTaiXiuMessageTypes.class);
        }
    }

    @Nested
    @DisplayName("a provider bean with no @MessageTypesImpl is fatal, like its three siblings")
    class MissingAnnotation {

        /**
         * <b>This reverses the WARN-and-skip this test previously pinned</b> (review-2c
         * F2). The old posture was copied from {@code BettingStrategyFactory}, and the
         * analogy did not survive contact: that class has no hard branch to be
         * inconsistent with, whereas this one fails refresh for the three sibling
         * mistakes — wrong {@code gameType}, no products, SLOT-with-products — all of
         * which read the very annotation the soft branch tolerated.
         *
         * <p>The old justification was that "a context that refuses to start over one
         * unkeyed bean would be a worse failure than a product that resolves nothing".
         * That weighs the wrong two things. Skipping does not give you "a product that
         * resolves nothing" at a moment anyone is watching; it gives you a clean
         * startup and then, hours or days later,
         * {@code "GameMessageTypes not yet implemented for product code: 116"} about a
         * brand that has been live for months — a message that actively points away
         * from the cause, with the one explanatory WARN long since scrolled past.
         *
         * <p>The old safety net named here still exists and is why the throw is cheap:
         * {@link MessageTypesCoverageTest} turns red for the affected product at build
         * time (verified by mutation — removing {@code @Component} from
         * {@code TipGameMessageTypes} fails it for 116). The build is what stops this
         * mistake; the throw is the backstop for the deploy that skipped the build.
         */
        @Test
        @DisplayName("construction throws, naming the class and what it is missing")
        void unannotatedProviderIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(new UnannotatedBettingMini()), List.of(), List.of(), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(UnannotatedBettingMini.class.getName())
                    .hasMessageContaining("carries no @MessageTypesImpl");
        }

        /**
         * The same posture on the SLOT arm, which resolves through a different method
         * ({@code resolveProductNeutral}) and had its own copy of the skip.
         */
        @Test
        @DisplayName("an unannotated SLOT provider is rejected too")
        void unannotatedSlotProviderIsRejected() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(), List.of(new UnannotatedSlot()), List.of(), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(UnannotatedSlot.class.getName())
                    .hasMessageContaining("carries no @MessageTypesImpl");
        }
    }

    /** No {@code @MessageTypesImpl} anywhere in its hierarchy, on the SLOT contract. */
    static class UnannotatedSlot implements SlotMessageTypes {
        @Override public Class<? extends SlotMessage> subscribeResponseType() { return null; }
        @Override public Class<? extends SlotMessage> spinResultType() { return null; }
    }

    @Nested
    @DisplayName("the annotation is resolved through the class hierarchy, not off getClass()")
    class AnnotationInheritedFromASuperclass {

        /**
         * {@code AnnotationUtils.findAnnotation} replaced
         * {@code provider.getClass().getAnnotation(...)} to survive a proxied bean
         * (review-2c F3) — nothing proxies these today, but the whole feature ends in
         * child classloaders and a classloader-fragile reflective lookup is the wrong
         * thing to leave lying around.
         *
         * <p>It brings one behaviour change worth pinning rather than discovering:
         * {@code findAnnotation} searches superclasses, so a subclass of an annotated
         * provider now inherits the claim even though {@code @MessageTypesImpl} is not
         * {@code @Inherited}. That is the reading a reader expects, and it is what
         * makes {@link MissingAnnotation}'s fixture have to implement the contract
         * directly instead of extending a real provider.
         */
        @Test
        @DisplayName("a subclass of an annotated provider inherits its products")
        void subclassInheritsTheAnnotation() {
            MessageTypesRegistry registry = new MessageTypesRegistry(
                    List.of(new InheritsBomAnnotation()), List.of(), List.of(), List.of(), List.of());

            assertThat(registry.registeredBettingMiniProducts()).containsExactly("097", "098");
            assertThat(registry.bettingMini("097")).isInstanceOf(InheritsBomAnnotation.class);
        }

        /**
         * The consequence that makes the inheritance safe: a subclass registered
         * alongside its own superclass is a duplicate claim, which is already fatal.
         * So the widened lookup cannot silently shadow a brand's provider.
         */
        @Test
        @DisplayName("a subclass registered next to its superclass is a duplicate, not a shadow")
        void subclassAlongsideItsSuperclassIsADuplicate() {
            assertThatThrownBy(() -> new MessageTypesRegistry(
                    List.of(new BomGameMessageTypes(), new InheritsBomAnnotation()),
                    List.of(), List.of(), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate");
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
                    List.of(new BomGameMessageTypes()), List.of(), List.of(), List.of(), List.of());

            assertThat(registry.hasSlotProvider()).isFalse();
            assertThatThrownBy(registry::slot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No SlotMessageTypes provider is registered")
                    .hasMessageContaining("@MessageTypesImpl(gameType = SLOT, products = {})");
        }

        @Test
        @DisplayName("an empty registry still constructs — the throw is at lookup, not at refresh")
        void emptyRegistryConstructs() {
            assertThatCode(() -> new MessageTypesRegistry(List.of(), List.of(), List.of(), List.of(), List.of()))
                    .doesNotThrowAnyException();
        }
    }
}
