package com.vingame.bot.domain.botgroup.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ActivationWindow.isActiveAt (AD-7)")
class ActivationWindowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Instant for a given local date/time in the test zone. */
    private static Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), ZONE).toInstant();
    }

    // A plain Wednesday (2026-01-07) for day-agnostic cases.
    private static final LocalDate WED = LocalDate.of(2026, 1, 7);

    @Nested
    @DisplayName("non-wrapping window (from < to)")
    class NonWrapping {

        private final ActivationWindow window = ActivationWindow.builder()
                .from(LocalTime.of(18, 0))
                .to(LocalTime.of(22, 0))
                .build();

        @Test
        @DisplayName("active inside the window")
        void activeInside() {
            assertThat(window.isActiveAt(at(WED, 20, 0), ZONE)).isTrue();
        }

        @Test
        @DisplayName("active at the opening edge (inclusive)")
        void activeAtOpen() {
            assertThat(window.isActiveAt(at(WED, 18, 0), ZONE)).isTrue();
        }

        @Test
        @DisplayName("inactive at the closing edge (exclusive)")
        void inactiveAtClose() {
            assertThat(window.isActiveAt(at(WED, 22, 0), ZONE)).isFalse();
        }

        @Test
        @DisplayName("inactive before the window")
        void inactiveBefore() {
            assertThat(window.isActiveAt(at(WED, 17, 59), ZONE)).isFalse();
        }

        @Test
        @DisplayName("inactive after the window")
        void inactiveAfter() {
            assertThat(window.isActiveAt(at(WED, 22, 1), ZONE)).isFalse();
        }
    }

    @Nested
    @DisplayName("wrapping window (from > to, crosses midnight)")
    class Wrapping {

        // 22:00 -> 02:00
        private final ActivationWindow window = ActivationWindow.builder()
                .from(LocalTime.of(22, 0))
                .to(LocalTime.of(2, 0))
                .build();

        @Test
        @DisplayName("active in the pre-midnight half")
        void activePreMidnight() {
            assertThat(window.isActiveAt(at(WED, 23, 0), ZONE)).isTrue();
        }

        @Test
        @DisplayName("active in the post-midnight half")
        void activePostMidnight() {
            // Thursday 01:00 falls in the post-midnight half.
            assertThat(window.isActiveAt(at(WED.plusDays(1), 1, 0), ZONE)).isTrue();
        }

        @Test
        @DisplayName("inactive in the closed gap")
        void inactiveGap() {
            assertThat(window.isActiveAt(at(WED, 12, 0), ZONE)).isFalse();
        }

        @Test
        @DisplayName("inactive at the closing edge (exclusive)")
        void inactiveAtClose() {
            assertThat(window.isActiveAt(at(WED.plusDays(1), 2, 0), ZONE)).isFalse();
        }
    }

    @Nested
    @DisplayName("day gate")
    class DayGate {

        @Test
        @DisplayName("empty day set = active every day")
        void emptyDaysAllDays() {
            ActivationWindow window = ActivationWindow.builder()
                    .from(LocalTime.of(0, 0))
                    .to(LocalTime.of(23, 59))
                    .days(Set.of())
                    .build();
            // WED is a Wednesday; any day should be active.
            assertThat(window.isActiveAt(at(WED, 12, 0), ZONE)).isTrue();
        }

        @Test
        @DisplayName("null day set = active every day")
        void nullDaysAllDays() {
            ActivationWindow window = ActivationWindow.builder()
                    .from(LocalTime.of(0, 0))
                    .to(LocalTime.of(23, 59))
                    .days(null)
                    .build();
            assertThat(window.isActiveAt(at(WED, 12, 0), ZONE)).isTrue();
        }

        @Test
        @DisplayName("non-wrapping window inactive on a day outside the set")
        void nonWrappingDayNotInSet() {
            // WED is Wednesday; restrict to weekends.
            ActivationWindow window = ActivationWindow.builder()
                    .from(LocalTime.of(18, 0))
                    .to(LocalTime.of(22, 0))
                    .days(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                    .build();
            assertThat(window.isActiveAt(at(WED, 20, 0), ZONE)).isFalse();
        }

        @Test
        @DisplayName("post-midnight half is anchored to the opening day, not the current day")
        void wrappingAnchoredToOpeningDay() {
            // Friday 22:00 -> Saturday 02:00, days = {FRIDAY}.
            ActivationWindow window = ActivationWindow.builder()
                    .from(LocalTime.of(22, 0))
                    .to(LocalTime.of(2, 0))
                    .days(Set.of(DayOfWeek.FRIDAY))
                    .build();

            LocalDate friday = LocalDate.of(2026, 1, 9); // 2026-01-09 is a Friday
            LocalDate saturday = friday.plusDays(1);

            // Saturday 01:00: current day is Saturday (not in set) but the window
            // opened Friday (in set) -> active.
            assertThat(window.isActiveAt(at(saturday, 1, 0), ZONE)).isTrue();

            // Friday 23:00: pre-midnight half on the opening day -> active.
            assertThat(window.isActiveAt(at(friday, 23, 0), ZONE)).isTrue();

            // Saturday 23:00: window would open on Saturday (not in set) -> inactive.
            assertThat(window.isActiveAt(at(saturday, 23, 0), ZONE)).isFalse();

            // Sunday 01:00: opening day was Saturday (not in set) -> inactive.
            assertThat(window.isActiveAt(at(saturday.plusDays(1), 1, 0), ZONE)).isFalse();
        }
    }

    @Test
    @DisplayName("from == to is never active (zero-length; validation rejects it in Phase 2)")
    void fromEqualsToNeverActive() {
        ActivationWindow window = ActivationWindow.builder()
                .from(LocalTime.of(10, 0))
                .to(LocalTime.of(10, 0))
                .build();
        assertThat(window.isActiveAt(at(WED, 10, 0), ZONE)).isFalse();
        assertThat(window.isActiveAt(at(WED, 12, 0), ZONE)).isFalse();
    }
}
