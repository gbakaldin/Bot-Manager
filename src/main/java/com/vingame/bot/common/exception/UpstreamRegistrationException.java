package com.vingame.bot.common.exception;

/**
 * Thrown when the auth gateway rejected the bulk user registration that
 * accompanies bot-group creation. Maps to HTTP 502.
 * <p>
 * Message text is forwarded verbatim from the upstream envelope (e.g. the Tip
 * gateway's Vietnamese reasons) so the operator can act without grepping logs.
 */
public class UpstreamRegistrationException extends UpstreamGatewayException {

    public UpstreamRegistrationException(String message) {
        super("Game server error", message);
    }

    public UpstreamRegistrationException(String message, Throwable cause) {
        super("Game server error", message, cause);
    }
}
