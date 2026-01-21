package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.environment.model.ProductCode;

/**
 * Resolves the appropriate GameMessageTypes implementation based on product code.
 * Each product may have different message structures for the same game types.
 */
public class GameMessageTypesResolver {

    /**
     * Resolve GameMessageTypes for a given product code.
     *
     * @param productCode The product code from the environment
     * @return The appropriate GameMessageTypes implementation
     * @throws IllegalArgumentException if the product code is not yet supported
     */
    public static GameMessageTypes resolve(ProductCode productCode) {
        if (productCode == null) {
            throw new IllegalArgumentException("ProductCode cannot be null");
        }

        return switch (productCode) {
            case P_097 -> new BomGameMessageTypes();
            case P_118 -> new NohuGameMessageTypes();
            case P_066, P_098, P_103, P_105, P_114, P_116, P_119, P_222 ->
                throw new IllegalArgumentException(
                    "GameMessageTypes not yet implemented for product code: " + productCode.getCode() +
                    ". Please create a GameMessageTypes implementation for this product.");
        };
    }
}
