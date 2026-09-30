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
        // A16.3, and review F7: the BODY is where the truth goes. `retryAfter` is the interval at
        // which we will next ASK — not a deadline — and the message used to render it as
        // "circuit open for another 3600s", which is exactly the promise the Retry-After header was
        // excused for making, in the one place that had room to qualify it. A block may require
        // operator action and may outlive a day; a message that implies an hour is a lie the UI
        // repeats.
        super(environmentId, "Gateway edge block on environment " + environmentId
                + (cfRay == null ? "" : " (cf-ray " + cfRay + ")")
                + " — this host is being refused by the edge and there is no automatic recovery: "
                + "it may require operator action and may last a day or more. Next clearance probe "
                + "in " + (retryAfter == null ? "an unknown interval" : retryAfter.toSeconds() + "s")
                + "; the circuit only closes when a probe is answered.");
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

    /**
     * Time until the next clearance probe — <b>not</b> a deadline by which the block clears.
     * <p>
     * The distinction is A16.3's and it is load-bearing: the circuit only ever closes because a
     * probe was answered, never because an interval elapsed. This value is what the
     * {@code Retry-After} header carries (an HTTP client needs a number) and the message above is
     * what says so.
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
