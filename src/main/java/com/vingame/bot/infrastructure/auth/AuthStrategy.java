package com.vingame.bot.infrastructure.auth;

/**
 * Identifies which {@link com.vingame.websocketparser.auth.LoginRequest} implementation
 * to use when authenticating bots for a given environment.
 * <p>
 * Each value maps to a factory in {@link AuthStrategyFactory}.
 * Add a new enum constant and a corresponding case in the factory
 * whenever a new environment requires a different login payload.
 */
public enum AuthStrategy {
    DEFAULT
}
