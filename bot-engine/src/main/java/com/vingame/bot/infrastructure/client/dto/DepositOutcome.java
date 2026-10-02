package com.vingame.bot.infrastructure.client.dto;

/**
 * What one registration-time deposit did, as far as can be known (BOT_PROVISIONING AD-7).
 * <p>
 * The existing {@code ApiGatewayClient.deposit} folds every outcome into a {@code boolean}, which
 * cannot tell "refused" from "sent, answer lost". For money that difference is the whole problem:
 * the first may be retried, the second must never be.
 */
public enum DepositOutcome {

    /** HTTP 200. The index is funded. */
    CREDITED,

    /** HTTP 4xx (incl. a Cloudflare block page). A definite non-credit; retryable. */
    REFUSED,

    /**
     * Never reached the gateway: no free stream on our connection, or the connection was refused
     * before the request went out. A definite non-credit.
     */
    NOT_SENT,

    /**
     * Sent, and the outcome cannot be known: 5xx, a timeout or another I/O failure after the
     * send, an interrupt. Must never be re-sent automatically.
     */
    UNKNOWN;

    /** An outcome plus the operator-safe detail behind it, for logs and {@code registrationError}. */
    public record Result(DepositOutcome outcome, String detail) {
    }
}
