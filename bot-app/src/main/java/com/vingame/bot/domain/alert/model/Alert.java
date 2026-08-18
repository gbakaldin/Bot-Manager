package com.vingame.bot.domain.alert.model;

import com.vingame.bot.domain.brand.model.ProductCode;

/**
 * One message to publish to VipTalk, before routing and formatting.
 *
 * @param severity      presentation severity; never {@code null}.
 * @param title         one-line headline (e.g. {@code "Bot group DEAD"}). Required.
 * @param body          optional detail, may be multi-line. {@code null}/blank ⇒ omitted.
 * @param product       the product this concerns, which decides the room. {@code null} ⇒
 *                      not product-specific (fleet-wide announcement, or an alert whose
 *                      product could not be resolved).
 * @param source        optional origin marker (e.g. {@code "prometheus"}, {@code "operator"}),
 *                      rendered as a trailer so a reader can tell an automated alert from a
 *                      hand-sent announcement.
 * @param audience      who this is for, which decides the room set (VIPTALK_ALERTING_V2
 *                      AD-V3). {@code null} ⇒ {@link AlertAudience#INTERNAL} (AD-V4).
 * @param publicSummary customer-facing copy for the {@link AlertAudience#BOTH} product-room
 *                      register, taken verbatim from the rule's {@code public_summary}
 *                      annotation. {@code null}/blank ⇒ the customer copy is suppressed
 *                      rather than synthesised from the technical text (AD-V6).
 * @param publicResolvedSummary customer-facing copy for the <b>recovery</b> half of the same
 *                      incident, from the rule's {@code public_resolved_summary} annotation.
 *                      Sourced exactly like {@link #publicSummary} and never synthesised from
 *                      it: replaying the outage wording under a {@code ✅} marker would tell a
 *                      product room "we are having issues" at the moment the issue ended.
 *                      {@code null}/blank ⇒ the configured default recovery text is used, and
 *                      if that is blank too the customer copy is suppressed.
 * @param customerFiringDeliveredOutOfBand whether the <b>firing</b> half of this incident's
 *                      customer copy is published by a delivery path other than this
 *                      application — today, {@code viptalk-shim} for {@code BotManagerDown}.
 *                      From the rule's {@code customer_firing_delivered_out_of_band}
 *                      annotation. When true the app publishes only the recovery half, so a
 *                      product room cannot be told "we are having issues" twice, the second
 *                      time <em>after</em> the issue ended (see below).
 */
public record Alert(AlertSeverity severity,
                    String title,
                    String body,
                    ProductCode product,
                    String source,
                    AlertAudience audience,
                    String publicSummary,
                    String publicResolvedSummary,
                    boolean customerFiringDeliveredOutOfBand) {

    public Alert {
        if (severity == null) {
            severity = AlertSeverity.INFO;
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Alert title must not be blank");
        }
        if (audience == null) {
            audience = AlertAudience.INTERNAL;
        }
    }

    /**
     * The shape every call site uses that has no out-of-band delivery to declare — which
     * is all of them except the Alertmanager webhook. Defaults to {@code false}: the app
     * owns both halves of an incident unless a rule says otherwise.
     */
    public Alert(AlertSeverity severity, String title, String body, ProductCode product,
                 String source, AlertAudience audience, String publicSummary,
                 String publicResolvedSummary) {
        this(severity, title, body, product, source, audience, publicSummary,
                publicResolvedSummary, false);
    }

    /**
     * The pre-{@code public_resolved_summary} shape, kept so every call site that only
     * has firing copy (the webhook before an operator adds the annotation, and every
     * existing test) constructs the same alert as before.
     */
    public Alert(AlertSeverity severity, String title, String body, ProductCode product,
                 String source, AlertAudience audience, String publicSummary) {
        this(severity, title, body, product, source, audience, publicSummary, null, false);
    }

    /**
     * Hand-authored, product-scoped alert — the operator path
     * ({@code POST /api/v1/alerts/product/{product}}) and the shape every pre-audience
     * call site used. Defaults to {@link AlertAudience#PRODUCT}: this form is always
     * about one product. The Alertmanager path always passes an audience explicitly.
     */
    public Alert(AlertSeverity severity, String title, String body, ProductCode product, String source) {
        this(severity, title, body, product, source, AlertAudience.PRODUCT, null, null, false);
    }

    /** Product-scoped alert with detail. */
    public static Alert forProduct(ProductCode product, AlertSeverity severity, String title, String body, String source) {
        return new Alert(severity, title, body, product, source);
    }

    /** Fleet-wide announcement — no product, routed to every room by the broadcast path. */
    public static Alert announcement(AlertSeverity severity, String title, String body, String source) {
        return new Alert(severity, title, body, null, source);
    }

    /** This alert with a different product, used when routing resolves one after construction. */
    public Alert withProduct(ProductCode newProduct) {
        return new Alert(severity, title, body, newProduct, source, audience,
                publicSummary, publicResolvedSummary, customerFiringDeliveredOutOfBand);
    }

    /** Whether a customer-facing copy can be rendered at all (AD-V6 fail-closed gate). */
    public boolean hasPublicSummary() {
        return publicSummary != null && !publicSummary.isBlank();
    }

    /** Whether the rule declared its own recovery wording. */
    public boolean hasPublicResolvedSummary() {
        return publicResolvedSummary != null && !publicResolvedSummary.isBlank();
    }

    /** Whether this is the recovery half of an incident rather than the outage half. */
    public boolean isResolved() {
        return severity == AlertSeverity.RESOLVED;
    }
}
