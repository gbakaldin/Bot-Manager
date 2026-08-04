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

@DisplayName("ActivationEvaluator.decide (AD-2/AD-3/AD-9)")
class ActivationEvaluatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate WED = LocalDate.of(2026, 1, 7);

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(WED, LocalTime.of(hour, minute), ZONE).toInstant();
    }

    /** A window open 09:00–17:00 every day. */
    private static ActivationWindow openWindow() {
        return ActivationWindow.builder()
                .from(LocalTime.of(9, 0))
                .to(LocalTime.of(17, 0))
                .days(Set.of())
                .build();
    }

    @Test
    @DisplayName("SCHEDULED, window active, not running → START")
    void activeNotRunningStarts() {
        var d = ActivationEvaluator.decide(ActivationMode.SCHEDULED, openWindow(),
                false, false, at(12, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.START);
    }

    @Test
    @DisplayName("SCHEDULED, window active, already running → NONE")
    void activeRunningNone() {
        var d = ActivationEvaluator.decide(ActivationMode.SCHEDULED, openWindow(),
                true, false, at(12, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.NONE);
    }

    @Test
    @DisplayName("SCHEDULED, window closed, running → STOP")
    void closedRunningStops() {
        var d = ActivationEvaluator.decide(ActivationMode.SCHEDULED, openWindow(),
                true, false, at(20, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.STOP);
    }

    @Test
    @DisplayName("SCHEDULED, window closed, not running → NONE")
    void closedNotRunningNone() {
        var d = ActivationEvaluator.decide(ActivationMode.SCHEDULED, openWindow(),
                false, false, at(20, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.NONE);
    }

    @Test
    @DisplayName("DEAD group is never auto-resurrected (AD-9) → NONE even when active")
    void deadNeverStarts() {
        var d = ActivationEvaluator.decide(ActivationMode.SCHEDULED, openWindow(),
                false, true, at(12, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.NONE);
    }

    @Test
    @DisplayName("MANUAL_ON is left alone (AD-3) → NONE even when window closed")
    void manualOnSkipped() {
        var d = ActivationEvaluator.decide(ActivationMode.MANUAL_ON, openWindow(),
                true, false, at(20, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.NONE);
    }

    @Test
    @DisplayName("MANUAL_OFF is left alone (AD-3) → NONE even when window open")
    void manualOffSkipped() {
        var d = ActivationEvaluator.decide(ActivationMode.MANUAL_OFF, openWindow(),
                false, false, at(12, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.NONE);
    }

    @Test
    @DisplayName("null mode (legacy) is left alone (AD-3) → NONE")
    void nullModeSkipped() {
        var d = ActivationEvaluator.decide(null, openWindow(),
                false, false, at(12, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.NONE);
    }

    @Test
    @DisplayName("SCHEDULED with null window → NONE (AD-3)")
    void scheduledNullWindowSkipped() {
        var d = ActivationEvaluator.decide(ActivationMode.SCHEDULED, null,
                false, false, at(12, 0), ZONE);
        assertThat(d).isEqualTo(ActivationDecision.NONE);
    }
}
