package com.vingame.bot.common.exception;

/**
 * Root of the outcomes in which <b>this JVM declined to send</b> a gateway request
 * (GATEWAY_REQUEST_BUDGET AD-11).
 * <p>
 * Deliberately <b>not</b> an {@link UpstreamGatewayException}. That type means "the upstream
 * answered and the answer was unusable", and {@code RestExceptionHandler} maps it to
 * <b>502</b>. Every subclass here means the opposite: the gateway was never asked, because
 * asking would have taken us over the Cloudflare rate limit or into an edge block that is
 * already refusing us. Reporting that as 502 would send an operator to look at a gateway
 * that is working.
 * <p>
 * The REST mapping (429 / 503 with {@code Retry-After}) lands with enforcement in Phase 3;
 * until then nothing in production throws these, because the facade ships in
 * {@code mode=observe} and never refuses anything.
 * <p>
 * <b>A budget outcome is never terminal for a bot</b> (AD-9): {@code Bot.performReauth} must
 * count one of these as a failed attempt of its existing backoff loop rather than marking
 * the bot DEAD, and {@code Bot.deposit} must skip the round rather than fail. A request the
 * JVM chose not to send must not be reported as one the gateway refused.
 * <p>
 * Abstract — throw a subclass that names which of the two outcomes happened.
 */
public abstract class GatewayBudgetException extends BotManagerException {

    private final String environmentId;

    protected GatewayBudgetException(String environmentId, String message) {
        super(message);
        this.environmentId = environmentId;
    }

    /** The environment whose budget or circuit declined the request. May be {@code null}. */
    public final String getEnvironmentId() {
        return environmentId;
    }

    /**
     * Short discriminator for the response body's {@code type} field, mirroring
     * {@link UpstreamGatewayException#getType()}. Stable across releases — the frontend may
     * key off it.
     */
    public abstract String getType();
}
