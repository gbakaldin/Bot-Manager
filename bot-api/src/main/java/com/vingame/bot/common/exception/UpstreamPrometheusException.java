package com.vingame.bot.common.exception;

/**
 * Thrown when bot-manager's metrics API fails to obtain a usable response from
 * the internal Prometheus HTTP API — a non-2xx status, a Prometheus
 * {@code {"status":"error", ...}} envelope, a transport error (timeout /
 * connection refused), or an unparseable body. Maps to HTTP 502 via
 * {@link RestExceptionHandler}.
 * <p>
 * Prometheus is internal-only; this surfaces an upstream-observability failure
 * to the caller without leaking the internal endpoint. The message preserves
 * Prometheus' own {@code error} string when present.
 */
public class UpstreamPrometheusException extends UpstreamGatewayException {

    public UpstreamPrometheusException(String message) {
        super("Game server error", message);
    }

    public UpstreamPrometheusException(String message, Throwable cause) {
        super("Game server error", message, cause);
    }
}
