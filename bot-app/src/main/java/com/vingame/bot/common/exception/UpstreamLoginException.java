package com.vingame.bot.common.exception;

/**
 * Thrown when the auth gateway rejected a bot's login. Maps to HTTP 502.
 * <p>
 * The underlying {@code websocket-parser} library currently throws a bare
 * {@link RuntimeException} ("No data in response") that swallows the upstream
 * envelope; this wrapper preserves whatever the library exposed and tags the
 * failure with a stable type for the response body. Once the library learns to
 * surface the upstream {@code {status, code, message}} envelope, the message
 * here will get correspondingly richer with no caller changes.
 */
public class UpstreamLoginException extends UpstreamGatewayException {

    public UpstreamLoginException(String message) {
        super("Game server error", message);
    }

    public UpstreamLoginException(String message, Throwable cause) {
        super("Game server error", message, cause);
    }
}
