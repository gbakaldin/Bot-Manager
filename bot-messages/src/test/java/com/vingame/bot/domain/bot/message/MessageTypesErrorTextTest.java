package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD Phase 2c, AD-20 — <b>the operator-facing error text is preserved
 * byte-for-byte.</b>
 *
 * <p>This is the string an operator greps when a new brand's group fails to start, and
 * verification step P2-7 greps it out of {@code docker logs bot-manager} verbatim. It
 * is therefore spelled here as a whole literal rather than assembled from the same
 * expression the production code uses — an assembled expectation would happily follow
 * a reformat of the message and assert nothing.
 *
 * <p>Both contracts are pinned. AD-20 quotes only the {@code GameMessageTypes} form,
 * but {@code GameMessageTypesResolver.resolveTaiXiu} threw its own
 * {@code TaiXiuMessageTypes} variant, and AD-23 ("Phase 2 changes no behaviour")
 * covers that one too.
 *
 * <p>The registry is built directly here, from the same provider instances the scan
 * would produce. That is deliberate and is not the anti-pattern the sibling tests
 * avoid: the subject is the <em>text of a miss</em>, and a miss needs a registry with
 * a known, small set of hits — discovery is covered by
 * {@link MessageTypesRegistryTest} and {@link MessageTypesCoverageTest}.
 */
@DisplayName("MessageTypesRegistry — the not-yet-implemented text is unchanged")
class MessageTypesErrorTextTest {

    private static final MessageTypesRegistry REGISTRY = new MessageTypesRegistry(
            List.of(new BomGameMessageTypes()),
            List.of(new SlotMessageTypesImpl()),
            List.of(new MiniGameTaiXiuMessageTypes()), List.of());

    @Test
    @DisplayName("betting-mini miss reads exactly as GameMessageTypesResolver's did")
    void bettingMiniMissTextIsUnchanged() {
        assertThatThrownBy(() -> REGISTRY.bettingMini("066"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GameMessageTypes not yet implemented for product code: 066."
                        + " Please create a GameMessageTypes implementation for this product.");
    }

    @Test
    @DisplayName("Tai Xiu miss reads exactly as GameMessageTypesResolver's did")
    void taiXiuMissTextIsUnchanged() {
        assertThatThrownBy(() -> REGISTRY.taiXiu("097"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TaiXiuMessageTypes not yet implemented for product code: 097."
                        + " Please create a TaiXiuMessageTypes implementation for this product.");
    }

    /** CASHOUT_BOT AD-3: the fourth lookup misses in the same AD-20 shape, naming its own contract. */
    @Test
    @DisplayName("CASHOUT miss reads in the same AD-20 shape, naming CashoutMessageTypes")
    void cashoutMissText() {
        assertThatThrownBy(() -> REGISTRY.cashout("116"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CashoutMessageTypes not yet implemented for product code: 116."
                        + " Please create a CashoutMessageTypes implementation for this product.");
        assertThatThrownBy(() -> REGISTRY.cashout(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
    }

    @Test
    @DisplayName("A null product code still reads 'ProductCode cannot be null', on both lookups")
    void nullTextIsUnchanged() {
        assertThatThrownBy(() -> REGISTRY.bettingMini(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
        assertThatThrownBy(() -> REGISTRY.taiXiu(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
    }

    /**
     * An unknown string — not a {@code ProductCode} at all — takes the same path. It
     * cannot arrive from {@code BotFactory}, which always passes
     * {@code ProductCode.getCode()}, but it is reachable from a plugin-supplied key and
     * the message must still name the thing that was asked for.
     */
    @Test
    @DisplayName("An unknown key echoes the key it was given")
    void unknownKeyEchoesTheKey() {
        assertThatThrownBy(() -> REGISTRY.bettingMini("nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GameMessageTypes not yet implemented for product code: nonsense."
                        + " Please create a GameMessageTypes implementation for this product.");
    }

    @Test
    @DisplayName("A hit still resolves — the miss text is not the only thing this registry does")
    void hitsStillResolve() {
        assertThat(REGISTRY.bettingMini("097")).isInstanceOf(BomGameMessageTypes.class);
        assertThat(REGISTRY.bettingMini("098")).isInstanceOf(BomGameMessageTypes.class);
        assertThat(REGISTRY.taiXiu("116")).isInstanceOf(MiniGameTaiXiuMessageTypes.class);
        assertThat(REGISTRY.slot()).isInstanceOf(SlotMessageTypesImpl.class);
    }
}
