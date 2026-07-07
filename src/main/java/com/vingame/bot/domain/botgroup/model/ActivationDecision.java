package com.vingame.bot.domain.botgroup.model;

/**
 * Outcome of a single activation reconcile evaluation (TIMED_ACTIVATION AD-11).
 *
 * <ul>
 *   <li>{@link #START} — the group should be running but is not; call {@code start()}.</li>
 *   <li>{@link #STOP} — the group should be stopped but is running; call {@code stop()}.</li>
 *   <li>{@link #NONE} — no action: already converged, not scheduled, no window, or DEAD.</li>
 * </ul>
 *
 * This is the target-agnostic decision unit the later Fleet abstraction reuses
 * verbatim; it says nothing about <em>what</em> is being started or stopped.
 */
public enum ActivationDecision {
    START,
    STOP,
    NONE
}
