package com.vingame.bot.domain.botgroup.model;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Pure activation decision function (TIMED_ACTIVATION AD-11 piece #2). Given a
 * group's activation mode, window, current running/dead state and the current
 * instant, it returns the reconcile action {@link ActivationDecision} without any
 * Spring, persistence, or side effects. The later Fleet abstraction reuses this as
 * is — it is deliberately agnostic to whether the target is a group or a fleet.
 */
public final class ActivationEvaluator {

    private ActivationEvaluator() {}

    /**
     * Decide the reconcile action for a scheduled target (AD-2/AD-3/AD-9).
     *
     * <ul>
     *   <li>Not {@link ActivationMode#SCHEDULED} or a {@code null} window ⇒
     *       {@link ActivationDecision#NONE} (AD-3): only opted-in scheduled groups
     *       are driven; {@code MANUAL_ON}/{@code MANUAL_OFF}/legacy {@code null}
     *       are left alone.</li>
     *   <li>{@code dead} ⇒ {@link ActivationDecision#NONE} (AD-9): a DEAD group is
     *       never auto-resurrected.</li>
     *   <li>Otherwise compare {@code window.isActiveAt(now, zone)} to
     *       {@code running} (AD-2): active-but-not-running ⇒ {@code START},
     *       running-but-inactive ⇒ {@code STOP}, already converged ⇒ {@code NONE}.</li>
     * </ul>
     */
    public static ActivationDecision decide(ActivationMode mode, ActivationWindow window,
                                            boolean running, boolean dead,
                                            Instant now, ZoneId zone) {
        if (mode != ActivationMode.SCHEDULED || window == null) {
            return ActivationDecision.NONE;
        }
        if (dead) {
            return ActivationDecision.NONE;
        }
        boolean shouldRun = window.isActiveAt(now, zone);
        if (shouldRun && !running) {
            return ActivationDecision.START;
        }
        if (!shouldRun && running) {
            return ActivationDecision.STOP;
        }
        return ActivationDecision.NONE;
    }
}
