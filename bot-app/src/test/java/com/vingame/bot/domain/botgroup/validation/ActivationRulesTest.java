package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.ActivationWindow;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ActivationRules")
class ActivationRulesTest {

    private static ActivationWindow window(LocalTime from, LocalTime to) {
        return ActivationWindow.builder().from(from).to(to).build();
    }

    @Nested
    @DisplayName("SCHEDULED mode")
    class Scheduled {

        @Test
        @DisplayName("valid window (from != to) passes")
        void validWindowPasses() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(window(LocalTime.of(18, 0), LocalTime.of(23, 59)))
                    .build();

            assertThatCode(() -> ActivationRules.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("valid wrapping window with days set passes")
        void validWrappingWindowWithDaysPasses() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(ActivationWindow.builder()
                            .from(LocalTime.of(22, 0))
                            .to(LocalTime.of(2, 0))
                            .days(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
                            .build())
                    .build();

            assertThatCode(() -> ActivationRules.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null window throws BadRequestException")
        void nullWindowThrows() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(null)
                    .build();

            assertThatThrownBy(() -> ActivationRules.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("activationWindow is required");
        }

        @Test
        @DisplayName("null from throws BadRequestException")
        void nullFromThrows() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(window(null, LocalTime.of(23, 59)))
                    .build();

            assertThatThrownBy(() -> ActivationRules.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("activationWindow.from is required");
        }

        @Test
        @DisplayName("null to throws BadRequestException")
        void nullToThrows() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(window(LocalTime.of(18, 0), null))
                    .build();

            assertThatThrownBy(() -> ActivationRules.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("activationWindow.to is required");
        }

        @Test
        @DisplayName("from == to throws BadRequestException (zero-length window)")
        void fromEqualsToThrows() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(window(LocalTime.of(10, 0), LocalTime.of(10, 0)))
                    .build();

            assertThatThrownBy(() -> ActivationRules.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("must differ from");
        }
    }

    @Nested
    @DisplayName("non-scheduled modes require no window")
    class NonScheduled {

        @Test
        @DisplayName("null mode with no window passes (legacy)")
        void nullModeNoWindowPasses() {
            BotGroup group = BotGroup.builder()
                    .activationMode(null)
                    .activationWindow(null)
                    .build();

            assertThatCode(() -> ActivationRules.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MANUAL_ON with no window passes")
        void manualOnNoWindowPasses() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.MANUAL_ON)
                    .activationWindow(null)
                    .build();

            assertThatCode(() -> ActivationRules.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MANUAL_OFF with no window passes")
        void manualOffNoWindowPasses() {
            BotGroup group = BotGroup.builder()
                    .activationMode(ActivationMode.MANUAL_OFF)
                    .activationWindow(null)
                    .build();

            assertThatCode(() -> ActivationRules.validate(group)).doesNotThrowAnyException();
        }
    }
}
