package com.vingame.bot.domain.alert.model;

/**
 * Who an alert is for — the routing contract between a Prometheus rule and the app
 * (VIPTALK_ALERTING_V2 AD-V3, superseding VIPTALK_ALERTING AD-5).
 * <p>
 * Every rule declares its audience with an {@code audience} label:
 * <table>
 *   <caption>Audience → rooms</caption>
 *   <tr><th>audience</th><th>rooms</th><th>register</th></tr>
 *   <tr><td>{@code internal}</td><td>ops room only</td><td>technical</td></tr>
 *   <tr><td>{@code product}</td><td>that product's room only</td><td>technical</td></tr>
 *   <tr><td>{@code both}</td><td>ops room <b>and</b> the product room</td><td>technical <b>and</b> customer</td></tr>
 * </table>
 * The ops room is no longer a catch-all: it takes fundamental infrastructure only
 * (CPU, RAM, storage, app down / restarted), which is what makes the product rooms
 * usable at all.
 */
public enum AlertAudience {

    /** Operators only. The ops room. */
    INTERNAL,

    /** The product's own room. Never the ops room, except via the AD-V5 misroute fallback. */
    PRODUCT,

    /** Both, in two different registers — technical to ops, customer-facing to the product. */
    BOTH;

    /**
     * Parses the {@code audience} rule label.
     * <p>
     * {@code null}, blank and unrecognised values all resolve to {@link #INTERNAL}
     * (AD-V4): an unlabelled rule must not silently vanish, so it defaults to the one
     * room that is guaranteed to exist. A unit test over {@code prometheus/alerts.yml}
     * keeps that default from becoming a loophole for our own rules.
     *
     * @param label the raw label value; case-insensitive, surrounding space tolerated.
     * @return the audience; never {@code null}.
     */
    public static AlertAudience fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return INTERNAL;
        }
        return switch (label.strip().toLowerCase()) {
            case "product" -> PRODUCT;
            case "both" -> BOTH;
            default -> INTERNAL;
        };
    }
}
