package com.vingame.bot.common.exception;

/**
 * Root of the application's own domain exception hierarchy. All exceptions the
 * Bot Manager raises deliberately (as opposed to third-party or JDK exceptions)
 * extend this type, giving callers and {@link RestExceptionHandler} a single
 * base to reason about.
 * <p>
 * This base carries no state beyond {@code message}/{@code cause}; any
 * additional discriminators (e.g. the upstream {@code type}) live on the
 * relevant subtype. {@link RestExceptionHandler} maps the concrete subtypes to
 * HTTP statuses via per-type handler arms (404 for
 * {@link ResourceNotFoundException}, 400 for {@link BadRequestException}, 502
 * for {@link UpstreamGatewayException}); it does not catch this base type
 * directly, so most-specific-handler dispatch is unaffected by the reparenting.
 * <p>
 * Abstract — instantiate a concrete subtype that names the specific failure.
 */
public abstract class BotManagerException extends RuntimeException {

    protected BotManagerException(String message) {
        super(message);
    }

    protected BotManagerException(String message, Throwable cause) {
        super(message, cause);
    }
}
