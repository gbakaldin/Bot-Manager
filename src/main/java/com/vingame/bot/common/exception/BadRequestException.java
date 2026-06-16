package com.vingame.bot.common.exception;

/**
 * Indicates that the client's request is invalid (semantically or syntactically)
 * and the service refuses to process it. Mapped to HTTP 400 by
 * {@link RestExceptionHandler}.
 * <p>
 * Use this for service-layer validation failures where the operator can correct
 * the request and retry (e.g. missing required fields, malformed identifiers,
 * pre-flight constraint violations).
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
