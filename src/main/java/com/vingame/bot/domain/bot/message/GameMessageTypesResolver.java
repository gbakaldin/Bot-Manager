package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.brand.model.ProductCode;

/**
 * Resolves the appropriate message-types provider.
 * <p>
 * The provider shape depends on the game type first, product code second
 * (SLOT_MACHINE_BOT plan AD-4):
 * <ul>
 *   <li>Betting-mini games resolve a {@link GameMessageTypes} keyed on
 *       {@link ProductCode} — each product may have different message structures
 *       for the same game type — via {@link #resolveBettingMini(ProductCode)}.</li>
 *   <li>Slot games resolve a product-neutral {@link SlotMessageTypes} via
 *       {@link #resolveSlot()} — slot message classes are identical across all
 *       brands.</li>
 * </ul>
 * Because the two providers have disjoint shapes, each game type gets its own
 * statically typed resolve method rather than a common {@code Object} return.
 */
public class GameMessageTypesResolver {

    /**
     * Resolve betting-mini {@link GameMessageTypes} for a given product code.
     *
     * @param productCode The product code from the environment
     * @return The appropriate GameMessageTypes implementation
     * @throws IllegalArgumentException if the product code is not yet supported
     */
    public static GameMessageTypes resolveBettingMini(ProductCode productCode) {
        if (productCode == null) {
            throw new IllegalArgumentException("ProductCode cannot be null");
        }

        return switch (productCode) {
            case P_097 -> new BomGameMessageTypes();
            case P_098 -> new BomGameMessageTypes();
            case P_116 -> new TipGameMessageTypes();
            case P_118 -> new NohuGameMessageTypes();
            case P_066, P_103, P_105, P_114, P_119, P_222 ->
                throw new IllegalArgumentException(
                    "GameMessageTypes not yet implemented for product code: " + productCode.getCode() +
                    ". Please create a GameMessageTypes implementation for this product.");
        };
    }

    /**
     * Resolve the product-neutral slot {@link SlotMessageTypes} (AD-4). Slot
     * message classes, CMDs, and protocol are identical across all brands, so
     * this takes no {@link ProductCode}.
     *
     * @return the single slot message-types provider
     */
    public static SlotMessageTypes resolveSlot() {
        return new SlotMessageTypesImpl();
    }
}
