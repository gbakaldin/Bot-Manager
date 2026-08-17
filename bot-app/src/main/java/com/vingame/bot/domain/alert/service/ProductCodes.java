package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.brand.model.ProductCode;

import java.util.Optional;

/**
 * Lenient parsing of a {@link ProductCode} from free-text that came in over the wire —
 * a Prometheus label, a path variable, an operator's request body.
 * <p>
 * {@link ProductCode#fromCode} is strict (numeric code only, throws on miss), which is
 * right for API contracts but wrong for routing: an alert rule author will just as
 * plausibly write {@code product="TIP"} or {@code product="P_116"}, and an unroutable
 * alert should fall back to the ops room, not blow up the webhook.
 */
public final class ProductCodes {

    private ProductCodes() {
    }

    /**
     * Resolves a product from its numeric code ({@code "116"}), enum name
     * ({@code "P_116"}), or product name ({@code "TIP"}), case-insensitively.
     *
     * @param value the raw value; {@code null}/blank/unknown ⇒ empty.
     * @return the matching product, or empty when nothing matches.
     */
    public static Optional<ProductCode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String needle = value.strip();
        for (ProductCode product : ProductCode.values()) {
            if (product.getCode().equalsIgnoreCase(needle)
                    || product.name().equalsIgnoreCase(needle)
                    || product.getName().equalsIgnoreCase(needle)) {
                return Optional.of(product);
            }
        }
        return Optional.empty();
    }
}
