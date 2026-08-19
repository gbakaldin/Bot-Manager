package com.vingame.bot.domain.logging.dto;

import java.time.Instant;

/**
 * One active scoped-DEBUG window (LOG_VOLUME_TIERING Phase 2).
 *
 * @param botGroupId the group whose lines are being surfaced at DEBUG
 * @param expiresAt  when it reverts to the configured level — never null, because the TTL
 *                   is mandatory (AD-11): there is no open-ended form of this window
 */
public record ScopedDebugDTO(String botGroupId, Instant expiresAt) {
}
