package com.vingame.bot.domain.bot.core.crash;

import com.vingame.bot.domain.bot.core.crash.RoundSilenceWatch.Check;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVIATOR_BOT QA: {@link RoundSilenceWatch} boundaries {@code RoundSilenceWatchTest} does
 * not pin — the exact deadline, a frame landing on the deadline, a late-firing task, a
 * wall-clock step backwards, and the ladder past 100 windows.
 */
@DisplayName("RoundSilenceWatch edges (QA)")
class RoundSilenceWatchEdgeTest {

    private static final long WINDOW = 180_000L;

    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final RoundSilenceWatch watch = new RoundSilenceWatch(WINDOW, now::get);

    @Test
    @DisplayName("no frame: exactly at the deadline is Silent, one millisecond before is Remaining(1)")
    void exactDeadline() {
        watch.beginWindow();
        now.addAndGet(WINDOW - 1);
        assertThat(watch.check()).isEqualTo(new Check.Remaining(1L));

        now.addAndGet(1L);
        assertThat(watch.check()).isInstanceOf(Check.Silent.class);
    }

    @Test
    @DisplayName("a frame on the deadline instant keeps the window alive for a full window from that frame")
    void frameOnTheDeadline() {
        watch.beginWindow();
        now.addAndGet(WINDOW);
        watch.onFrame();

        assertThat(watch.check()).isEqualTo(new Check.Remaining(WINDOW));
        assertThat(watch.silentWindows()).isZero();
    }

    @Test
    @DisplayName("a task that fires 2.5 windows late counts one silent window, never three — late undercounts, never overcounts")
    void lateFire() {
        watch.beginWindow();
        now.addAndGet(WINDOW * 5 / 2);

        Check check = watch.check();

        assertThat(check).isEqualTo(new Check.Silent(1, true, false));
        now.addAndGet(WINDOW - 1);
        assertThat(watch.check()).as("the new window starts at the late check").isEqualTo(new Check.Remaining(1L));
    }

    @Test
    @DisplayName("a wall-clock step backwards never produces a false Silent while frames flow")
    void clockStepsBack() {
        watch.beginWindow();
        now.addAndGet(60_000L);
        watch.onFrame();
        now.addAndGet(-3_600_000L); // NTP step back an hour
        for (int i = 0; i < 20; i++) {
            now.addAndGet(30_000L);
            watch.onFrame();
            Check check = watch.check();
            assertThat(check).as("check %d", i).isInstanceOf(Check.Remaining.class);
        }
        assertThat(watch.silentWindows()).isZero();
    }

    @Test
    @DisplayName("silence with no frames: reconnect rungs over 200 windows are 1,2,4,8,16,32 and every 32 after")
    void ladderPast100() {
        long delay = watch.beginWindow();
        List<Integer> rungs = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            now.addAndGet(delay);
            Check.Silent s = (Check.Silent) watch.check();
            if (s.reconnect()) {
                rungs.add(s.silentWindows());
            }
            delay = WINDOW;
        }
        assertThat(rungs).containsExactly(1, 2, 4, 8, 16, 32, 64, 96, 128, 160, 192);
    }

    @Test
    @DisplayName("beginWindow mid-window never shortens the window a recent frame earned")
    void beginWindowDoesNotShorten() {
        watch.beginWindow();
        now.addAndGet(100_000L);
        watch.onFrame();
        now.addAndGet(10_000L);

        assertThat(watch.beginWindow()).isEqualTo(WINDOW);
        now.addAndGet(WINDOW - 1);
        assertThat(watch.check()).isEqualTo(new Check.Remaining(1L));
    }

    @Test
    @DisplayName("subscribed is reported as it was at the check; clearSubscribed (reconnect) flips the next Silent back to false")
    void subscribedFollowsTheConnection() {
        watch.beginWindow();
        watch.markSubscribed();
        now.addAndGet(WINDOW);
        assertThat(((Check.Silent) watch.check()).subscribed()).isTrue();

        watch.clearSubscribed();
        now.addAndGet(WINDOW);
        Check.Silent s = (Check.Silent) watch.check();
        assertThat(s.subscribed()).isFalse();
        assertThat(s.silentWindows()).isEqualTo(2);
    }
}
