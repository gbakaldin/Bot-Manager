package com.vingame.bot.common.dto;

/**
 * Structured error body for non-2xx responses. Lets the frontend display the
 * underlying failure (especially upstream game-server / auth-server messages)
 * instead of a bare HTTP status with empty body.
 */
public record ErrorResponse(String type, String msg) {
}
