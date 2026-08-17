package com.vingame.bot.domain.alert.model;

/**
 * Which voice one alert is rendered in (VIPTALK_ALERTING_V2 AD-V6).
 * <p>
 * An {@link AlertAudience#BOTH} alert is the same condition told twice: operators get
 * the metric, the environment id and the runbook hint; a product room gets one
 * non-technical sentence. The two texts are never derived from each other — the
 * customer copy comes from the rule's own {@code public_summary} annotation, and is
 * suppressed outright when that annotation is missing, so internal wording can never
 * leak into a product room.
 */
public enum AlertRegister {

    /** Operator-facing: severity, product, instance, title, per-alert detail, source trailer. */
    TECHNICAL,

    /** Customer-facing: marker, product, instance, and the rule's {@code public_summary} verbatim. */
    CUSTOMER
}
