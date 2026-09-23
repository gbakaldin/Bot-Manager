package com.vingame.bot.common.exception;

import java.time.Duration;

/**
 * The environment's gateway is behind an <b>open circuit</b>: a Cloudflare edge block was
 * detected and nothing is being sent to that host until the cooldown ends
 * (GATEWAY_REQUEST_BUDGET AD-13). Mapped to <b>503</b> with a {@code Retry-After} header by
 * {@code RestExceptionHandler} from Phase 3.
 * <p>
 * Distinct from {@link GatewayBudgetExhaustedException} because the remedy is different: a
 * 429 means "we are pacing ourselves, this will clear on its own in seconds"; this means
 * "the edge is refusing this host, and retrying into it is what keeps the block alive".
 * {@link #getCfRay()} is the {@code cf-ray} id from the block page — the one value the SA
 * ticket needs, and the reason the detector captures it rather than logging a generic 403.
 * <p>
 * The detector and the circuit land in Phase 4; this type exists from Phase 1 so the
 * exception hierarchy is decided in one place.
 */
public class GatewayCircuitOpenException extends GatewayBudgetException {

    /** Body {@code type} for this outcome. Frontend-visible; do not reword casually. */
    public static final String TYPE = "Gateway edge block";

    private final String cfRay;
    private final Duration retryAfter;

    public GatewayCircuitOpenException(String environmentId, String cfRay, Duration retryAfter) {
        super(environmentId, "Gateway edge block on environment " + environmentId
                + (cfRay == null ? "" : " (cf-ray " + cfRay + ")")
                + "; circuit open for another "
                + (retryAfter == null ? "unknown" : retryAfter.toSeconds() + "s"));
        this.cfRay = cfRay;
        this.retryAfter = retryAfter;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /** {@code cf-ray} of the block page, for the SA ticket. May be {@code null}. */
    public String getCfRay() {
        return cfRay;
    }

    /** Time until the cooldown ends and one clearance probe is issued. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
