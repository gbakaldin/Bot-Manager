package com.vingame.bot.domain.logging.dto;

import java.util.List;

/**
 * The scoped-DEBUG surface as a whole (LOG_VOLUME_TIERING Phase 2): whether the mechanism
 * is armed on this instance, the TTL policy it enforces, and the windows currently open.
 *
 * @param enabled        master switch ({@code bot.logging.scoped-debug.enabled}) AND the
 *                       filter actually being attached to the {@code com.vingame.bot}
 *                       logger. False here means a POST would raise verbosity for nobody
 * @param defaultMinutes TTL applied when the caller names none
 * @param maxMinutes     ceiling on any single TTL (AD-11)
 * @param maxScopes      ceiling on simultaneously scoped groups — scoped DEBUG must not be
 *                       reconstructible into fleet-wide DEBUG one call at a time
 * @param scopes         the active windows, soonest expiry first
 */
public record ScopedDebugStatusDTO(boolean enabled,
                                   int defaultMinutes,
                                   int maxMinutes,
                                   int maxScopes,
                                   List<ScopedDebugDTO> scopes) {
}
