package com.vingame.bot.domain.botgroup.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * A recurring time-of-day activation window (TIMED_ACTIVATION AD-1), optionally
 * restricted to a set of days-of-week. This is a pure value object — no Spring,
 * no persistence logic beyond the plain JSR-310 fields that Spring Data / Jackson
 * serialize out of the box.
 *
 * <p>The whole activation predicate lives in {@link #isActiveAt(Instant, ZoneId)}
 * (AD-7). It is deliberately Spring-free so the later Fleet abstraction can reuse
 * it verbatim (AD-11).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivationWindow {

    /** Window opening time-of-day. Required when the window is present. */
    private LocalTime from;

    /** Window closing time-of-day. Required when the window is present. */
    private LocalTime to;

    /**
     * Days-of-week the window applies to. {@code null} or empty means every day
     * (AD-1). For a midnight-crossing window the set is anchored to the day the
     * window <em>opened</em> (AD-7).
     */
    private Set<DayOfWeek> days;

    /**
     * The whole activation predicate (AD-7). Evaluates whether this window is
     * open at {@code now}, interpreted in wall-clock terms of the configured
     * {@code zone}.
     *
     * <ul>
     *   <li><b>Non-wrapping</b> ({@code from < to}): active iff
     *       {@code from <= t < to} and the day gate holds for the current day.</li>
     *   <li><b>Wrapping</b> ({@code from > to}, e.g. 22:00→02:00): active iff
     *       ({@code t >= from} and the day gate holds for the current day) OR
     *       ({@code t < to} and the day gate holds for the <em>previous</em> day)
     *       — the post-midnight half is anchored to the opening day.</li>
     * </ul>
     *
     * {@code from == to} is rejected by validation (Phase 2), not here — this
     * predicate treats it as never active.
     */
    public boolean isActiveAt(Instant now, ZoneId zone) {
        var zdt = now.atZone(zone);
        LocalTime t = zdt.toLocalTime();
        DayOfWeek d = zdt.getDayOfWeek();

        if (from.isBefore(to)) {
            // Non-wrapping window.
            return !t.isBefore(from) && t.isBefore(to) && dayGate(d);
        }
        if (from.isAfter(to)) {
            // Midnight-crossing window: pre-midnight half anchored to today,
            // post-midnight half anchored to the opening (previous) day.
            boolean preMidnight = !t.isBefore(from) && dayGate(d);
            boolean postMidnight = t.isBefore(to) && dayGate(d.minus(1));
            return preMidnight || postMidnight;
        }
        // from == to: ambiguous zero-length, rejected by validation.
        return false;
    }

    /**
     * Day-of-week gate: {@code days} null/empty means always true; otherwise the
     * gate holds only for days contained in the set.
     */
    boolean dayGate(DayOfWeek d) {
        return days == null || days.isEmpty() || days.contains(d);
    }
}
