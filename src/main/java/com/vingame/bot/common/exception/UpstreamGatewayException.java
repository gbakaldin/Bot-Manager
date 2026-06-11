package com.vingame.bot.common.exception;

/**
 * Base class for failures caused by an upstream system (auth gateway, game
 * server, etc.) returning an unrecoverable response. Mapped to HTTP 502 by
 * {@link RestExceptionHandler}.
 * <p>
 * Carries a short {@code type} discriminator (copied verbatim into the response
 * body's {@code type} field) so the frontend can render different upstream
 * failure categories without parsing the message string.
 * <p>
 * Abstract — callers must pick a leaf subclass that names the specific upstream
 * interaction (registration / login / etc.).
 */
public abstract class UpstreamGatewayException extends RuntimeException {

    private final String type;

    protected UpstreamGatewayException(String type, String message) {
        super(message);
        this.type = type;
    }

    protected UpstreamGatewayException(String type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    /**
     * Short discriminator for the response body's {@code type} field. Keep it
     * stable across releases — frontend may key UI rendering off this value.
     */
    public final String getType() {
        return type;
    }
}
