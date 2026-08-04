package com.vingame.bot.domain.metrics.controller;

import com.vingame.bot.common.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Focused boundary coverage for {@link TimeWindow#resolve} — the highest-surface
 * logic in the metrics API (METRICS_API AD-5 / OI-2). The MockMvc controller
 * tests exercise the common paths through HTTP; this unit test isolates the
 * resolver and probes the exact edges (the 30d cap boundary, the 5s step floor
 * boundary, the 11000-point cap boundary, step format parsing, epoch-vs-ISO, and
 * precedence/mutual-exclusion rules) directly.
 */
@DisplayName("TimeWindow.resolve")
class TimeWindowTest {

    // ---- defaults ----

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("no params → 1h window ending ~now")
        void defaultWindow() {
            TimeWindow w = TimeWindow.resolve(null, null, null, null);
            Instant now = Instant.now();
            assertThat(Duration.between(w.start(), w.end())).isEqualTo(Duration.ofHours(1));
            assertThat(w.end()).isCloseTo(now, within(5, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("blank params are treated as absent → 1h default")
        void blankParamsAreAbsent() {
            TimeWindow w = TimeWindow.resolve("  ", " ", "", "");
            assertThat(Duration.between(w.start(), w.end())).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("default step keeps a 1h window to ~720 points, floored at 5s")
        void defaultStepFor1h() {
            TimeWindow w = TimeWindow.resolve(null, null, null, null);
            // 3600 / 720 = 5s, exactly the floor.
            assertThat(w.step()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("default step on a tiny window is floored to 5s (would otherwise be 0)")
        void defaultStepFlooredForTinyWindow() {
            long now = Instant.now().getEpochSecond();
            // 60s window: 60/720 = 0 → floored to MIN_STEP (5s).
            TimeWindow w = TimeWindow.resolve(null, String.valueOf(now - 60), String.valueOf(now), null);
            assertThat(w.step()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("default step on a 24h window ≈ 120s (~720 points)")
        void defaultStepFor24h() {
            TimeWindow w = TimeWindow.resolve("24h", null, null, null);
            assertThat(w.step()).isEqualTo(Duration.ofSeconds(120));
        }
    }

    // ---- presets vs explicit ----

    @Nested
    @DisplayName("range presets vs explicit from/to")
    class Precedence {

        @Test
        @DisplayName("each known preset resolves to its duration")
        void knownPresets() {
            assertThat(span(TimeWindow.resolve("1h", null, null, null))).isEqualTo(Duration.ofHours(1));
            assertThat(span(TimeWindow.resolve("6h", null, null, null))).isEqualTo(Duration.ofHours(6));
            assertThat(span(TimeWindow.resolve("24h", null, null, null))).isEqualTo(Duration.ofHours(24));
            assertThat(span(TimeWindow.resolve("7d", null, null, null))).isEqualTo(Duration.ofDays(7));
            assertThat(span(TimeWindow.resolve("30d", null, null, null))).isEqualTo(Duration.ofDays(30));
        }

        @Test
        @DisplayName("preset is case-insensitive")
        void presetCaseInsensitive() {
            assertThat(span(TimeWindow.resolve("24H", null, null, null))).isEqualTo(Duration.ofHours(24));
        }

        @Test
        @DisplayName("unknown preset → 400")
        void unknownPreset() {
            assertThatThrownBy(() -> TimeWindow.resolve("13h", null, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("13h");
        }

        @Test
        @DisplayName("range + from → 400 (mutually exclusive)")
        void rangeAndFromConflict() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", "1700000000", null, "60"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not both");
        }

        @Test
        @DisplayName("range + to → 400 (mutually exclusive)")
        void rangeAndToConflict() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", null, "1700000000", "60"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("only 'from' given → 'to' defaults to now")
        void onlyFrom() {
            long from = Instant.now().getEpochSecond() - 1800;
            TimeWindow w = TimeWindow.resolve(null, String.valueOf(from), null, "60");
            assertThat(w.start().getEpochSecond()).isEqualTo(from);
            assertThat(w.end()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("only 'to' given → 'from' defaults to to-1h")
        void onlyTo() {
            long to = 1700003600L;
            TimeWindow w = TimeWindow.resolve(null, null, String.valueOf(to), "60");
            assertThat(w.end().getEpochSecond()).isEqualTo(to);
            assertThat(w.start().getEpochSecond()).isEqualTo(to - 3600);
        }
    }

    // ---- 30d cap boundary ----

    @Nested
    @DisplayName("30d retention cap")
    class RangeCap {

        @Test
        @DisplayName("exactly 30d is allowed (boundary inclusive)")
        void exactly30dAllowed() {
            long to = 1700000000L;
            long from = to - Duration.ofDays(30).toSeconds();
            TimeWindow w = TimeWindow.resolve(null, String.valueOf(from), String.valueOf(to), "3600");
            assertThat(span(w)).isEqualTo(Duration.ofDays(30));
        }

        @Test
        @DisplayName("30d + 1s → 400")
        void justOver30d() {
            long to = 1700000000L;
            long from = to - Duration.ofDays(30).toSeconds() - 1;
            assertThatThrownBy(() -> TimeWindow.resolve(null, String.valueOf(from), String.valueOf(to), "3600"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("30d");
        }
    }

    // ---- inverted / zero window ----

    @Nested
    @DisplayName("inverted / zero window")
    class Inverted {

        @Test
        @DisplayName("to == from → 400")
        void zeroWindow() {
            assertThatThrownBy(() -> TimeWindow.resolve(null, "1700000000", "1700000000", "60"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("strictly after");
        }

        @Test
        @DisplayName("to < from → 400")
        void negativeWindow() {
            assertThatThrownBy(() -> TimeWindow.resolve(null, "1700003600", "1700000000", "60"))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ---- step floor boundary ----

    @Nested
    @DisplayName("step floor (5s)")
    class StepFloor {

        @Test
        @DisplayName("step exactly 5s is allowed (boundary inclusive)")
        void stepExactlyFloor() {
            TimeWindow w = TimeWindow.resolve("1h", null, null, "5");
            assertThat(w.step()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("step 4s → 400")
        void stepBelowFloor() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", null, null, "4"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("at least 5s");
        }

        @Test
        @DisplayName("step 0 → 400")
        void stepZero() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", null, null, "0"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("negative step → 400")
        void stepNegative() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", null, null, "-5"))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ---- point-count cap boundary ----

    @Nested
    @DisplayName("11000-point cap")
    class PointCap {

        @Test
        @DisplayName("exactly 11000 points is allowed")
        void exactly11000() {
            long to = 1700000000L;
            // 11000 points at step=10s → 110000s window.
            long from = to - 11_000 * 10L;
            TimeWindow w = TimeWindow.resolve(null, String.valueOf(from), String.valueOf(to), "10");
            assertThat(span(w).toSeconds() / w.step().toSeconds()).isEqualTo(11_000);
        }

        @Test
        @DisplayName("11001 points → 400")
        void over11000() {
            long to = 1700000000L;
            long from = to - (11_000 * 10L + 10); // one step more
            assertThatThrownBy(() -> TimeWindow.resolve(null, String.valueOf(from), String.valueOf(to), "10"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("point");
        }

        @Test
        @DisplayName("the default step never overflows the point cap, even for the full 30d window")
        void defaultStepNeverOverflows() {
            // No explicit step: default = max(5s, range/720) keeps points ~720.
            TimeWindow w = TimeWindow.resolve("30d", null, null, null);
            long points = span(w).toSeconds() / w.step().toSeconds();
            assertThat(points).isLessThanOrEqualTo(11_000);
        }
    }

    // ---- step parsing formats ----

    @Nested
    @DisplayName("step parsing formats")
    class StepFormats {

        @Test
        @DisplayName("plain seconds")
        void plainSeconds() {
            assertThat(TimeWindow.resolve("1h", null, null, "60").step()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("'30s' suffix")
        void secondsSuffix() {
            assertThat(TimeWindow.resolve("1h", null, null, "30s").step()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("'5m' suffix")
        void minutesSuffix() {
            assertThat(TimeWindow.resolve("6h", null, null, "5m").step()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("'1h' suffix")
        void hoursSuffix() {
            assertThat(TimeWindow.resolve("7d", null, null, "1h").step()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("'1d' suffix")
        void daysSuffix() {
            assertThat(TimeWindow.resolve("30d", null, null, "1d").step()).isEqualTo(Duration.ofDays(1));
        }

        @Test
        @DisplayName("ISO-8601 'PT1M'")
        void isoDuration() {
            assertThat(TimeWindow.resolve("6h", null, null, "PT1M").step()).isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        @DisplayName("ISO-8601 lower-case 'pt5m' is accepted")
        void isoDurationLowerCase() {
            assertThat(TimeWindow.resolve("6h", null, null, "pt5m").step()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("millisecond step is rejected (whole-seconds or coarser only)")
        void millisRejected() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", null, null, "500ms"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("whole seconds");
        }

        @Test
        @DisplayName("unknown step unit → 400")
        void unknownUnit() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", null, null, "5w"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("garbage step → 400")
        void garbageStep() {
            assertThatThrownBy(() -> TimeWindow.resolve("1h", null, null, "abc"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid 'step'");
        }
    }

    // ---- from/to parsing formats ----

    @Nested
    @DisplayName("from/to parsing formats")
    class InstantFormats {

        @Test
        @DisplayName("epoch seconds")
        void epochSeconds() {
            TimeWindow w = TimeWindow.resolve(null, "1700000000", "1700003600", "60");
            assertThat(w.start()).isEqualTo(Instant.ofEpochSecond(1700000000L));
            assertThat(w.end()).isEqualTo(Instant.ofEpochSecond(1700003600L));
        }

        @Test
        @DisplayName("ISO-8601 instants")
        void isoInstants() {
            TimeWindow w = TimeWindow.resolve(null,
                    "2023-11-14T22:13:20Z", "2023-11-14T23:13:20Z", "60");
            assertThat(span(w)).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("epoch and ISO can be mixed across from/to")
        void mixedFormats() {
            // from epoch, to ISO (same instants as above).
            TimeWindow w = TimeWindow.resolve(null, "1700000000", "2023-11-14T23:13:20Z", "60");
            assertThat(span(w)).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("malformed 'from' → 400")
        void malformedFrom() {
            assertThatThrownBy(() -> TimeWindow.resolve(null, "not-a-time", "1700003600", "60"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("from");
        }

        @Test
        @DisplayName("malformed ISO 'to' → 400")
        void malformedIsoTo() {
            assertThatThrownBy(() -> TimeWindow.resolve(null, "1700000000", "2023-13-99T99:99Z", "60"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("to");
        }
    }

    private static Duration span(TimeWindow w) {
        return Duration.between(w.start(), w.end());
    }
}
