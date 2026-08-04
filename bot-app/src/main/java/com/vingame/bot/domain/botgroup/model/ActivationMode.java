package com.vingame.bot.domain.botgroup.model;

/**
 * How a bot group's run/stop lifecycle is governed relative to its
 * {@link ActivationWindow} (TIMED_ACTIVATION AD-1).
 *
 * <ul>
 *   <li>{@link #SCHEDULED} — the reconciler drives start/stop from the window
 *       predicate every tick (requires a non-null {@link ActivationWindow}).</li>
 *   <li>{@link #MANUAL_ON} — parked "up" by an operator; the reconciler leaves
 *       it alone (AD-4).</li>
 *   <li>{@link #MANUAL_OFF} — parked "down" by an operator; the reconciler
 *       leaves it alone (AD-4).</li>
 * </ul>
 *
 * A {@code null} mode on a {@link BotGroup} is the legacy, non-timed group:
 * governed solely by its persisted {@code targetStatus}, exactly as before this
 * feature existed.
 */
public enum ActivationMode {
    SCHEDULED,
    MANUAL_ON,
    MANUAL_OFF
}
