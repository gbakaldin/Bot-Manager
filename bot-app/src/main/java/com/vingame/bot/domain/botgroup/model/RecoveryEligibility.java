package com.vingame.bot.domain.botgroup.model;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Pure recovery-eligibility predicate (DEAD_GROUP_AUTO_RECOVERY AD-3). Given a
 * group's persisted target status, activation settings, bot count and live runtime
 * state, it answers the single question <em>"is this group in a shape that
 * auto-recovery may act on?"</em> — with no Spring, no persistence and no side
 * effects, exactly like its sibling {@link ActivationEvaluator}.
 *
 * <p>It is shared by the Phase 1 probe selector (which probes an environment only
 * when it has at least one candidate) and by the Phase 3 recovery reconciler
 * (which re-asserts it under the group lock before acting).
 *
 * <p><b>Two of AD-3's seven conditions are deliberately not here:</b> the global
 * {@code bot.recovery.enabled} flag (condition 1) and the per-group attempt
 * budget / backoff deadline (condition 7). Both are scheduler state, not group
 * shape, and keeping them out is what lets this class stay pure and exhaustively
 * unit-testable.
 */
public final class RecoveryEligibility {

    private RecoveryEligibility() {}

    /**
     * AD-3 conditions 2-6, in the order they are evaluated.
     *
     * <ol start="2">
     *   <li>persisted {@code targetStatus == DEAD}, <b>or</b> a live runtime that
     *       reports itself dead — the second disjunct covers the case where
     *       {@code handleBotGroupDeath}'s DB save threw and its {@code catch}
     *       swallowed the failure, leaving memory DEAD and Mongo ACTIVE;</li>
     *   <li><b>not</b> a live runtime whose {@code actualStatus} is
     *       {@link BotGroupStatus#ACTIVE} — a running group is not dead no matter
     *       what the DB says. This is evaluated <em>first</em> because it is the
     *       guard against the documented race where the health monitor persists
     *       {@code DEAD} after a concurrent start persisted {@code ACTIVE};</li>
     *   <li>{@code activationMode != MANUAL_OFF} — an operator parked it down;</li>
     *   <li>a {@link ActivationMode#SCHEDULED} group must have a non-null window
     *       that is open at {@code now}: never resurrect a group into a closed
     *       window;</li>
     *   <li>{@code botCount > 0} — there is nothing to rebuild otherwise.</li>
     * </ol>
     *
     * @param persistedTarget the group's persisted {@code targetStatus} (nullable)
     * @param mode            persisted activation mode; {@code null} = legacy group
     * @param window          persisted activation window; only read for SCHEDULED
     * @param botCount        persisted bot count
     * @param runtimeStatus   live runtime status, or {@code null} when no runtime
     *                        exists for this group
     * @param runtimeGroupDead whether a live runtime exists and reports itself DEAD
     * @param now             evaluation instant
     * @param zone            business wall-clock zone the window is interpreted in
     */
    public static boolean isCandidate(BotGroupStatus persistedTarget,
                                      ActivationMode mode,
                                      ActivationWindow window,
                                      int botCount,
                                      BotGroupStatus runtimeStatus,
                                      boolean runtimeGroupDead,
                                      Instant now,
                                      ZoneId zone) {
        // (3) a live, ACTIVE runtime beats any persisted status.
        if (runtimeStatus == BotGroupStatus.ACTIVE) {
            return false;
        }
        // (2) dead in the DB, or dead in memory.
        if (persistedTarget != BotGroupStatus.DEAD && !runtimeGroupDead) {
            return false;
        }
        // (4) parked down by an operator.
        if (mode == ActivationMode.MANUAL_OFF) {
            return false;
        }
        // (5) scheduled groups only recover inside their window.
        if (mode == ActivationMode.SCHEDULED
                && (window == null || !window.isActiveAt(now, zone))) {
            return false;
        }
        // (6) nothing to rebuild.
        return botCount > 0;
    }
}
