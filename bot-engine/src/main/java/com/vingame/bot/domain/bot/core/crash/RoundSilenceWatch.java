package com.vingame.bot.domain.bot.core.crash;

import com.vingame.bot.domain.bot.core.support.ReconnectLadder;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The crash bot's only watchdog: "has this socket gone quiet?" ({@code docs/plans/AVIATOR_BOT.md}
 * AD-9). Pure — no scheduler, no logging, time from an injected clock. The bot owns the one
 * scheduled task that calls {@link #check()} at the deadline and acts on the answer.
 *
 * <h2>How it counts</h2>
 * <ul>
 *   <li>Every inbound crash frame calls {@link #onFrame()}: <b>one volatile write</b> of the
 *       clock, nothing else — ticks arrive at 2/s/bot and must not reschedule anything. The
 *       count reset that a frame implies is applied lazily, by the next {@link #check()}
 *       (and reflected immediately by {@link #silentWindows()}).</li>
 *   <li>{@link #check()} answers {@link Check.Remaining} with the exact time left when a
 *       frame has arrived within the window, measured from that frame; otherwise it
 *       increments the silent-window count, starts a new full window and answers
 *       {@link Check.Silent}.</li>
 *   <li><b>A reconnect does not reset the count.</b> {@link #beginWindow()} — called when
 *       the bot (re)starts — opens a fresh window but keeps the count, so a reconnect that
 *       lands on the same silent server keeps climbing the ladder instead of re-logging in
 *       every window. Only a frame resets it.</li>
 *   <li>A reconnect is due at the {@link ReconnectLadder} rungs with {@code r = 1,
 *       topShift = 5}: windows 1, 2, 4, 8, 16, 32, then every 32.</li>
 * </ul>
 *
 * <p>{@link #markSubscribed()} / {@link #clearSubscribed()} track whether the current
 * connection has had its subscribe answered, purely so the first silent window's WARN can
 * say {@code subscribed=false} — the signature of a refused subscribe (CASHOUT release
 * finding 2), which is exactly the case a watch armed only after the subscribe reply misses.
 *
 * <p>{@link #check()} and {@link #beginWindow()} are synchronized; {@link #onFrame()} is
 * lock-free and safe from any number of inbound workers.
 */
public final class RoundSilenceWatch {

    /** First rung of the reconnect ladder, in silent windows. */
    static final int LADDER_R = 1;

    /** Highest doubling step: rungs 1..32, then every 32 windows. */
    static final int LADDER_TOP_SHIFT = 5;

    /** What the bot must do when the watch task fires. */
    public sealed interface Check permits Check.Remaining, Check.Silent {

        /** A frame arrived within the window: re-arm for {@code millis} (always {@code > 0}). */
        record Remaining(long millis) implements Check {
        }

        /**
         * A whole window passed with no frame. Re-arm for a full window.
         *
         * @param silentWindows consecutive silent windows, including this one
         * @param reconnect     whether this count is a reconnect rung
         * @param subscribed    whether the current connection's subscribe was answered
         */
        record Silent(int silentWindows, boolean reconnect, boolean subscribed) implements Check {

            /** @return whether this is the first silent window of a silence episode. */
            public boolean first() {
                return silentWindows == 1;
            }
        }
    }

    private static final long NEVER = Long.MIN_VALUE;

    private final long windowMillis;
    private final LongSupplier clock;

    /** Written by every inbound frame; the only field the hot path touches. */
    private volatile long lastFrameAt = NEVER;

    private volatile boolean subscribed;

    // Guarded by this.
    private long seenFrameAt = NEVER;
    private long windowStart;
    private int silentWindows;

    /**
     * @param windowMillis the silence window ({@code bot.watchdog.timeout.seconds}, in ms)
     * @param clock        milliseconds, monotonic enough for deltas
     */
    public RoundSilenceWatch(long windowMillis, LongSupplier clock) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be > 0, got " + windowMillis);
        }
        this.windowMillis = windowMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.windowStart = clock.getAsLong();
    }

    /** An inbound crash frame arrived. One volatile write. */
    public void onFrame() {
        lastFrameAt = clock.getAsLong();
    }

    /**
     * Open a fresh full window from now, keeping the silent-window count. Called when the
     * bot (re)starts and arms its watch task.
     *
     * @return the window length, i.e. the delay to arm the task for
     */
    public synchronized long beginWindow() {
        absorbFrame();
        windowStart = Math.max(windowStart, clock.getAsLong());
        return windowMillis;
    }

    /**
     * The watch task fired.
     *
     * @return {@link Check.Remaining} if a frame arrived within the window, else
     *         {@link Check.Silent} with the new count and the ladder decision
     */
    public synchronized Check check() {
        absorbFrame();
        long now = clock.getAsLong();
        long remaining = windowStart + windowMillis - now;
        if (remaining > 0) {
            return new Check.Remaining(remaining);
        }
        silentWindows++;
        windowStart = now;
        return new Check.Silent(silentWindows,
                ReconnectLadder.isRung(silentWindows, LADDER_R, LADDER_TOP_SHIFT), subscribed);
    }

    /** Applies a frame recorded since the last check: count back to 0, window from the frame. */
    private void absorbFrame() {
        long last = lastFrameAt;
        if (last != NEVER && last > seenFrameAt) {
            seenFrameAt = last;
            silentWindows = 0;
            windowStart = Math.max(windowStart, last);
        }
    }

    /** The current connection's subscribe was answered. */
    public void markSubscribed() {
        subscribed = true;
    }

    /** A new connection is being made; its subscribe has not been answered yet. */
    public void clearSubscribed() {
        subscribed = false;
    }

    /** @return whether the current connection's subscribe was answered. */
    public boolean isSubscribed() {
        return subscribed;
    }

    /** @return consecutive silent windows; {@code 0} as soon as a frame has arrived. */
    public synchronized int silentWindows() {
        long last = lastFrameAt;
        return last != NEVER && last > seenFrameAt ? 0 : silentWindows;
    }

    /** @return the window length in milliseconds. */
    public long windowMillis() {
        return windowMillis;
    }
}
