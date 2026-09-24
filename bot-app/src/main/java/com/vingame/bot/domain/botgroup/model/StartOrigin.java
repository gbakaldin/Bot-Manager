package com.vingame.bot.domain.botgroup.model;

/**
 * Who asked for a bot-group start (GATEWAY_REQUEST_BUDGET AD-15). Carried on the in-flight
 * {@code StartAttempt} so an operator watching a group that has been {@code STARTING} for
 * twenty minutes can tell whether they started it, the activation reconciler did, the
 * startup daisy-chain did, or dead-group auto-recovery did.
 * <p>
 * Bounded on purpose: it is a log field, and it is the natural label if a counter is ever
 * put on start outcomes. Every constant has exactly one call site.
 */
public enum StartOrigin {

    /** {@code POST /api/v1/bot-group/{id}/start} or {@code /restart}. */
    REST,

    /** The application-ready daisy-chain (AD-14). */
    STARTUP,

    /** {@code ActivationScheduler}'s START decision for a scheduled window. */
    SCHEDULE,

    /** {@code DeadGroupRecoveryScheduler} via {@code startForRecovery} — synchronous. */
    RECOVERY,

    /** A restart previously booked through {@code POST /{id}/schedule-restart}. */
    SCHEDULED_RESTART
}
