package com.vingame.bot.common.exception;

import com.vingame.bot.common.gateway.RequestTier;

import java.time.Duration;

/**
 * The per-environment request window had no room for this request within the tier's
 * maximum wait (GATEWAY_REQUEST_BUDGET AD-11). Mapped to <b>429</b> with a
 * {@code Retry-After} header by {@code RestExceptionHandler} from Phase 3.
 * <p>
 * {@link #getRetryAfter()} is the time until the earliest stamp in the sliding window
 * expires — i.e. the soonest moment at which a retry could possibly be admitted. It is
 * advice, not a promise: a higher tier may consume the freed slot first.
 */
public class GatewayBudgetExhaustedException extends GatewayBudgetException {

    /** Body {@code type} for this outcome. Frontend-visible; do not reword casually. */
    public static final String TYPE = "Gateway budget exhausted";

    private final RequestTier tier;
    private final Duration retryAfter;

    public GatewayBudgetExhaustedException(RequestTier tier, String environmentId, Duration retryAfter) {
        super(environmentId, "Gateway request budget exhausted for environment " + environmentId
                + " (tier " + tier + "); retry in " + (retryAfter == null ? "unknown" : retryAfter.toSeconds() + "s"));
        this.tier = tier;
        this.retryAfter = retryAfter;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /** The tier whose ceiling or wait was hit. */
    public RequestTier getTier() {
        return tier;
    }

    /** Seconds until the earliest window slot frees up. May be {@code null} if unknown. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
