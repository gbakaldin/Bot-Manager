package com.vingame.bot.domain.botgroup.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive cover of the pure recovery predicate (DEAD_GROUP_AUTO_RECOVERY AD-3),
 * mirroring {@code ActivationEvaluatorTest}. Conditions 1 (the global flag) and 7
 * (the attempt budget) are scheduler state and deliberately not expressible here.
 */
@DisplayName("RecoveryEligibility.isCandidate (AD-3)")
class RecoveryEligibilityTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate WED = LocalDate.of(2026, 1, 7);

    private static Instant at(int hour) {
        return ZonedDateTime.of(WED, LocalTime.of(hour, 0), ZONE).toInstant();
    }

    /** Open 09:00–17:00, every day. */
    private static ActivationWindow window() {
        return ActivationWindow.builder()
                .from(LocalTime.of(9, 0))
                .to(LocalTime.of(17, 0))
                .days(Set.of())
                .build();
    }

    private static boolean candidate(BotGroupStatus persisted, ActivationMode mode,
                                     ActivationWindow window, int botCount,
                                     BotGroupStatus runtimeStatus, boolean runtimeDead,
                                     int hour) {
        return RecoveryEligibility.isCandidate(persisted, mode, window, botCount,
                runtimeStatus, runtimeDead, at(hour), ZONE);
    }

    @Test
    @DisplayName("legacy group, persisted DEAD, no runtime → candidate")
    void persistedDeadLegacyIsCandidate() {
        assertThat(candidate(BotGroupStatus.DEAD, null, null, 20, null, false, 12)).isTrue();
    }

    @Test
    @DisplayName("persisted ACTIVE but the in-memory runtime is DEAD → candidate (the swallowed-save case)")
    void runtimeDeadIsCandidate() {
        assertThat(candidate(BotGroupStatus.ACTIVE, null, null, 20,
                BotGroupStatus.DEAD, true, 12)).isTrue();
    }

    @Test
    @DisplayName("persisted DEAD but the runtime is ACTIVE → not a candidate (the health-monitor race)")
    void activeRuntimeBeatsPersistedDead() {
        assertThat(candidate(BotGroupStatus.DEAD, null, null, 20,
                BotGroupStatus.ACTIVE, false, 12)).isFalse();
    }

    @Test
    @DisplayName("persisted STOPPED → not a candidate (STOPPED is the opt-out, AD-5)")
    void stoppedIsNeverACandidate() {
        assertThat(candidate(BotGroupStatus.STOPPED, null, null, 20, null, false, 12)).isFalse();
    }

    @Test
    @DisplayName("persisted ACTIVE with no runtime → not a candidate")
    void activeIsNotACandidate() {
        assertThat(candidate(BotGroupStatus.ACTIVE, null, null, 20, null, false, 12)).isFalse();
    }

    @Test
    @DisplayName("null target status → not a candidate")
    void nullTargetIsNotACandidate() {
        assertThat(candidate(null, null, null, 20, null, false, 12)).isFalse();
    }

    @Test
    @DisplayName("MANUAL_OFF → not a candidate even when DEAD")
    void manualOffIsNeverACandidate() {
        assertThat(candidate(BotGroupStatus.DEAD, ActivationMode.MANUAL_OFF, null, 20,
                null, false, 12)).isFalse();
    }

    @Test
    @DisplayName("MANUAL_ON + DEAD → candidate")
    void manualOnIsACandidate() {
        assertThat(candidate(BotGroupStatus.DEAD, ActivationMode.MANUAL_ON, null, 20,
                null, false, 12)).isTrue();
    }

    @Test
    @DisplayName("SCHEDULED + DEAD inside the window → candidate")
    void scheduledInsideWindowIsACandidate() {
        assertThat(candidate(BotGroupStatus.DEAD, ActivationMode.SCHEDULED, window(), 20,
                null, false, 12)).isTrue();
    }

    @Test
    @DisplayName("SCHEDULED + DEAD outside the window → not a candidate (never resurrect into a closed window)")
    void scheduledOutsideWindowIsNotACandidate() {
        assertThat(candidate(BotGroupStatus.DEAD, ActivationMode.SCHEDULED, window(), 20,
                null, false, 20)).isFalse();
    }

    @Test
    @DisplayName("SCHEDULED + DEAD with a null window → not a candidate")
    void scheduledWithoutWindowIsNotACandidate() {
        assertThat(candidate(BotGroupStatus.DEAD, ActivationMode.SCHEDULED, null, 20,
                null, false, 12)).isFalse();
    }

    /**
     * The shape the explicit condition-2a veto exists for. Condition 2 on its own is
     * a disjunction — it asks "not DEAD", never "not STOPPED" — so a row an operator
     * parked {@code STOPPED} would qualify through the second disjunct for as long as
     * a DEAD in-memory runtime for it survives. A lingering DEAD runtime is the
     * ordinary post-death state (both {@code handleBotGroupDeath} and the zero-bot
     * start guard leave it in {@code runningGroups} deliberately), and the pair is
     * reachable through a {@code PATCH {"targetStatus":"STOPPED"}} and through a lost
     * Mongo write in the zero-bot guard.
     * <p>
     * This is the AD-5 invariant an operator's intent depends on, so it is asserted
     * against the predicate rather than against a reachability argument.
     * {@code RecoveryCandidateSelectorTest} and {@code DeadGroupRecoverySchedulerTest}
     * drive the same shape through the real selector and the real reconciler.
     */
    @Test
    @DisplayName("persisted STOPPED + a DEAD runtime → still not a candidate (AD-5 is absolute)")
    void stoppedWithADeadRuntimeIsStillNeverACandidate() {
        assertThat(candidate(BotGroupStatus.STOPPED, null, null, 20,
                BotGroupStatus.DEAD, true, 12))
                .as("STOPPED vetoes unconditionally, whichever disjunct of condition 2 holds")
                .isFalse();
    }

    @Test
    @DisplayName("the veto does not swallow condition 2's purpose: ACTIVE + a DEAD runtime is still a candidate")
    void theStoppedVetoDoesNotBreakTheSwallowedSaveCase() {
        // The disjunct exists for handleBotGroupDeath's swallowed save — persisted
        // ACTIVE, runtime DEAD. Vetoing STOPPED must leave that fully covered.
        assertThat(candidate(BotGroupStatus.ACTIVE, null, null, 20,
                BotGroupStatus.DEAD, true, 12)).isTrue();
    }

    @Test
    @DisplayName("MANUAL_OFF + a DEAD runtime → still not a candidate (condition 4 is not a disjunction)")
    void manualOffWithDeadRuntimeIsNotACandidate() {
        assertThat(candidate(BotGroupStatus.STOPPED, ActivationMode.MANUAL_OFF, null, 20,
                BotGroupStatus.DEAD, true, 12)).isFalse();
    }

    @Test
    @DisplayName("an ACTIVE runtime wins even when the dead-runtime flag is set — condition 3 is evaluated first")
    void activeRuntimeStatusWinsOverTheDeadFlag() {
        assertThat(candidate(BotGroupStatus.DEAD, null, null, 20,
                BotGroupStatus.ACTIVE, true, 12)).isFalse();
    }

    @Test
    @DisplayName("botCount 0 → not a candidate (nothing to rebuild)")
    void zeroBotsIsNotACandidate() {
        assertThat(candidate(BotGroupStatus.DEAD, null, null, 0, null, false, 12)).isFalse();
    }
}
