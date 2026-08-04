package com.vingame.bot.domain.metrics.controller;

import com.vingame.bot.common.exception.BadRequestException;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Resolved, validated time window for a {@code timeseries} query — the result of
 * applying the METRICS_API AD-5 rules to the raw request params (OI-2: support
 * BOTH raw {@code from}/{@code to}/{@code step} AND convenience {@code range}
 * presets).
 *
 * @param start resolved window start.
 * @param end   resolved window end.
 * @param step  resolved resolution step.
 */
public record TimeWindow(Instant start, Instant end, Duration step) {

    /** Retention cap (AD-1): the window may not exceed 30 days. */
    static final Duration MAX_RANGE = Duration.ofDays(30);

    /** Step floor (AD-5): Prometheus scrape is 10s; sub-scrape steps waste points. */
    static final Duration MIN_STEP = Duration.ofSeconds(5);

    /** Prometheus' own max-resolution guard: a range query may yield at most this many points. */
    static final long MAX_POINTS = 11_000;

    /** Default window when neither {@code range} nor {@code from}/{@code to} is supplied (AD-5). */
    static final Duration DEFAULT_RANGE = Duration.ofHours(1);

    /**
     * Named convenience presets (OI-2). {@code range=<name>} expands to
     * {@code from=now-duration, to=now}; the step is then computed like any other
     * window so charts stay ~720 points.
     */
    private static final Map<String, Duration> PRESETS = Map.of(
            "1h", Duration.ofHours(1),
            "6h", Duration.ofHours(6),
            "24h", Duration.ofHours(24),
            "7d", Duration.ofDays(7),
            "30d", Duration.ofDays(30));

    /**
     * Resolve and validate a window from the raw request params.
     * <p>
     * Precedence (documented): {@code range} and explicit {@code from}/{@code to}
     * are <b>mutually exclusive</b>. If {@code range} is supplied alongside either
     * {@code from} or {@code to}, that is a conflict → 400. If only {@code range}
     * is given, it wins and {@code from}/{@code to} are derived from it. If neither
     * is given, the default 1h window applies. {@code step} is honoured (and
     * independently validated) in every case; when omitted it is computed as
     * {@code max(MIN_STEP, range/720)}.
     *
     * @param range preset name (e.g. {@code 24h}) or {@code null}.
     * @param from  raw start (epoch seconds or ISO-8601) or {@code null}.
     * @param to    raw end (epoch seconds or ISO-8601) or {@code null}.
     * @param step  raw step (e.g. {@code 60}, {@code 60s}, {@code 5m}, ISO-8601 {@code PT1M}) or {@code null}.
     * @throws BadRequestException on any AD-5 / OI-2 violation (mapped to HTTP 400).
     */
    public static TimeWindow resolve(String range, String from, String to, String step) {
        Instant now = Instant.now();
        Instant start;
        Instant end;

        boolean hasRange = range != null && !range.isBlank();
        boolean hasExplicit = (from != null && !from.isBlank()) || (to != null && !to.isBlank());

        if (hasRange && hasExplicit) {
            throw new BadRequestException(
                    "Provide either 'range' (a preset) or explicit 'from'/'to', not both.");
        }

        if (hasRange) {
            Duration preset = PRESETS.get(range.toLowerCase(Locale.ROOT));
            if (preset == null) {
                throw new BadRequestException(
                        "Unknown range preset '" + range + "'. Valid presets: 1h, 6h, 24h, 7d, 30d.");
            }
            end = now;
            start = now.minus(preset);
        } else {
            end = (to == null || to.isBlank()) ? now : parseInstant(to, "to");
            start = (from == null || from.isBlank()) ? end.minus(DEFAULT_RANGE) : parseInstant(from, "from");
        }

        Duration windowSpan = Duration.between(start, end);
        if (windowSpan.isZero() || windowSpan.isNegative()) {
            throw new BadRequestException("'to' must be strictly after 'from'.");
        }
        if (windowSpan.compareTo(MAX_RANGE) > 0) {
            throw new BadRequestException(
                    "Requested range exceeds the 30d retention cap (requested "
                            + windowSpan.toDays() + "d).");
        }

        Duration resolvedStep = (step == null || step.isBlank())
                ? defaultStep(windowSpan)
                : parseStep(step);

        if (resolvedStep.compareTo(MIN_STEP) < 0) {
            throw new BadRequestException(
                    "'step' must be at least " + MIN_STEP.toSeconds() + "s (Prometheus scrape resolution).");
        }
        long points = windowSpan.toSeconds() / resolvedStep.toSeconds();
        if (points > MAX_POINTS) {
            throw new BadRequestException(
                    "Resolution too high: " + points + " points exceeds the " + MAX_POINTS
                            + "-point cap. Increase 'step' or shrink the range.");
        }

        return new TimeWindow(start, end, resolvedStep);
    }

    /** Default step keeps a chart to ~720 points, floored at {@link #MIN_STEP}. */
    private static Duration defaultStep(Duration windowSpan) {
        long stepSeconds = Math.max(MIN_STEP.toSeconds(), windowSpan.toSeconds() / 720);
        return Duration.ofSeconds(stepSeconds);
    }

    /** Parse epoch-seconds or ISO-8601 instant. */
    private static Instant parseInstant(String raw, String param) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(raw.trim()));
        } catch (NumberFormatException notEpoch) {
            try {
                return Instant.parse(raw.trim());
            } catch (DateTimeParseException notIso) {
                throw new BadRequestException(
                        "Invalid '" + param + "': '" + raw + "' is neither epoch-seconds nor ISO-8601.");
            }
        }
    }

    /**
     * Parse a step: plain seconds ({@code 60}), a short duration suffix
     * ({@code 60s}/{@code 5m}/{@code 1h}/{@code 1d}), or ISO-8601 ({@code PT1M}).
     */
    private static Duration parseStep(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException ignored) {
            // fall through to suffix / ISO parsing
        }
        try {
            if (s.endsWith("ms")) {
                throw new BadRequestException("'step' must be expressed in whole seconds or coarser.");
            }
            char unit = s.charAt(s.length() - 1);
            String numberPart = s.substring(0, s.length() - 1);
            long n = Long.parseLong(numberPart);
            return switch (unit) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default -> throw new BadRequestException("Unknown step unit in '" + raw + "'.");
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException notSuffix) {
            try {
                return Duration.parse(raw.trim().toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException notIso) {
                throw new BadRequestException(
                        "Invalid 'step': '" + raw + "'. Use seconds (60), a suffix (60s/5m/1h), or ISO-8601 (PT1M).");
            }
        }
    }
}
