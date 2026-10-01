package com.vingame.bot.infrastructure.gateway;

import java.io.IOException;

/**
 * The one anonymous request an open circuit makes per {@code block-probe-interval} to learn
 * whether a Cloudflare edge block has been lifted (GATEWAY_REQUEST_BUDGET AD-13, A16.1).
 * <p>
 * {@link GatewayBudgetRegistry} builds it for each environment from its {@code apiGateway} base
 * URL ({@code GET <apiGateway>/gwms/v1/verifytoken.aspx?token=probe&fg=probe}) and binds it into
 * the budget; tests bind a lambda. It touches no account and carries no token — a probe that could
 * authenticate would be a bot login spent to learn what an anonymous GET learns for free, and the
 * bot would be the one to fail.
 */
@FunctionalInterface
public interface CircuitProbe {

    /**
     * Issue the probe and classify the answer.
     *
     * @throws IOException          if no response arrived at all — which is <b>not</b> evidence
     *                              that the block is lifted, so the circuit stays open
     * @throws InterruptedException if the probing thread is interrupted (shutdown)
     */
    Answer probe() throws IOException, InterruptedException;

    /**
     * What the edge said: the HTTP status for the log line, and the detector's verdict.
     * <p>
     * <b>Any response that is not an edge block closes the circuit</b> — including the gateway's
     * own JSON error envelope for an unknown token, which is precisely the expected answer: it
     * proves the origin is being reached again.
     */
    record Answer(int status, CloudflareBlockDetector.Verdict verdict) {
    }
}
