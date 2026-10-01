package com.vingame.bot.domain.bot.core;

/**
 * Thrown by {@link Bot#start()} when the bot's session setup failed and the bot has been handed
 * to its reconnect loop (GATEWAY_REQUEST_BUDGET A33).
 * <p>
 * The start did fail, so it still throws. But the bot is now RECONNECTING and may well recover,
 * so this is not a final failure, and callers that log one at ERROR
 * ({@code BotGroupRuntime.startBot}, the periodic logout) catch this type first and log it at
 * DEBUG. The reconnect hand-off has already logged its own WARN. The message and cause are the
 * original failure's, unchanged.
 */
public class SessionSetupHandedOffException extends RuntimeException {

    public SessionSetupHandedOffException(RuntimeException cause) {
        super(cause.getMessage(), cause);
    }
}
