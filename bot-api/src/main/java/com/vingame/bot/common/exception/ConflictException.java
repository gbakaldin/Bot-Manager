package com.vingame.bot.common.exception;

/**
 * The request is valid but conflicts with an operation already in progress on the same
 * resource, and the service refuses it rather than silently doing nothing. Mapped to HTTP 409 by
 * {@code RestExceptionHandler}.
 * <p>
 * First use: {@code POST /{id}/restart} while an attach of newly registered accounts is in flight
 * (BOT_PROVISIONING review). A 200 there used to mean "accepted" and do nothing at all.
 */
public class ConflictException extends BotManagerException {

    public ConflictException(String message) {
        super(message);
    }
}
