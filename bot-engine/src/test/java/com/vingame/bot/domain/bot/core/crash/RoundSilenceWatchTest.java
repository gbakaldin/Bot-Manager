package com.vingame.bot.domain.bot.core.crash;

import com.vingame.bot.domain.bot.core.crash.RoundSilenceWatch.Check;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AVIATOR_BOT Phase 2: {@link RoundSilenceWatch} with a fake clock (AD-9). The bot's
 * scheduled task is simulated by calling {@link RoundSilenceWatch#check()} at the delay
 * each answer asks for.
 */
@DisplayName("RoundSilenceWatch")
class RoundSilenceWatchTest {

    private static final long WINDOW = 180_000L;

    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final RoundSilenceWatch watch = new RoundSilenceWatch(WINDOW, now::get);

    /** Advance to the next deadline and check, as the bot's task would. */
    private Check fireAfter(long delay) {
        now.addAndGet(delay);
        return watch.check();
    }

    @Test
    @DisplayName("no frames at all: reconnect at windows 1, 2, 4, 8, 16, 32, 64, 96 and no other count <= 100")
    void ladderWithNoFrames() {
        long delay = watch.beginWindow();
        List<Integer> reconnects = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Check check = fireAfter(delay);
            assertThat(check).isInstanceOf(Check.Silent.class);
            Check.Silent silent = (Check.Silent) check;
            assertThat(silent.silentWindows()).isEqualTo(i);
            assertThat(silent.first()).isEqualTo(i == 1);
            if (silent.reconnect()) {
                reconnects.add(i);
            }
            delay = WINDOW;
        }
        assertThat(reconnects).containsExactly(1, 2, 4, 8, 16, 32, 64, 96);
    }

    @Test
    @DisplayName("a frame resets the count; the next silence starts over at window 1")
    void frameResetsCount() {
        watch.beginWindow();
        fireAfter(WINDOW);
        fireAfter(WINDOW);
        assertThat(watch.silentWindows()).isEqualTo(2);

        now.addAndGet(1_000L);
        watch.onFrame();
        assertThat(watch.silentWindows()).as("visible immediately").isZero();

        Check check = fireAfter(WINDOW - 1_000L);
        assertThat(check).as("frame 1 s into the window").isEqualTo(new Check.Remaining(1_000L));
        Check.Silent silent = (Check.Silent) fireAfter(1_000L);
        assertThat(silent.silentWindows()).isEqualTo(1);
        assertThat(silent.reconnect()).isTrue();
        assertThat(silent.first()).isTrue();
    }

    @Test
    @DisplayName("a reconnect (beginWindow) does not reset the count")
    void reconnectKeepsCount() {
        watch.beginWindow();
        Check.Silent first = (Check.Silent) fireAfter(WINDOW);
        assertThat(first.reconnect()).isTrue();

        // the bot reconnects and re-arms a few seconds later
        now.addAndGet(3_000L);
        long delay = watch.beginWindow();
        assertThat(delay).isEqualTo(WINDOW);
        assertThat(watch.silentWindows()).isEqualTo(1);

        Check.Silent second = (Check.Silent) fireAfter(delay);
        assertThat(second.silentWindows()).isEqualTo(2);
        assertThat(second.reconnect()).isTrue();
        Check.Silent third = (Check.Silent) fireAfter(WINDOW);
        assertThat(third.silentWindows()).isEqualTo(3);
        assertThat(third.reconnect()).isFalse();
    }

    @Test
    @DisplayName("Remaining is exact after a frame mid-window, measured from the frame")
    void remainingExact() {
        watch.beginWindow();
        now.addAndGet(60_000L);
        watch.onFrame();
        assertThat(fireAfter(WINDOW - 60_000L)).isEqualTo(new Check.Remaining(60_000L));
        // another frame 10 s later: the window is now measured from it (deadline +190 s)
        now.addAndGet(10_000L);
        watch.onFrame();
        assertThat(fireAfter(60_000L - 10_000L)).isEqualTo(new Check.Remaining(WINDOW - 50_000L));
        assertThat(fireAfter(WINDOW - 50_000L)).isInstanceOf(Check.Silent.class);
    }

    @Test
    @DisplayName("an early fire (before the deadline, no frame) answers the exact remainder")
    void earlyFire() {
        watch.beginWindow();
        assertThat(fireAfter(100_000L)).isEqualTo(new Check.Remaining(WINDOW - 100_000L));
        assertThat(watch.silentWindows()).isZero();
    }

    @Test
    @DisplayName("subscribed is carried on Silent; clearSubscribed on reconnect")
    void subscribedTracking() {
        watch.beginWindow();
        Check.Silent refused = (Check.Silent) fireAfter(WINDOW);
        assertThat(refused.subscribed()).as("refused subscribe").isFalse();

        watch.markSubscribed();
        assertThat(watch.isSubscribed()).isTrue();
        assertThat(((Check.Silent) fireAfter(WINDOW)).subscribed()).isTrue();

        watch.clearSubscribed();
        assertThat(((Check.Silent) fireAfter(WINDOW)).subscribed()).isFalse();
    }

    @Test
    @DisplayName("a frame between a Silent and the re-arm still resets the count")
    void frameBeforeRearm() {
        watch.beginWindow();
        fireAfter(WINDOW);
        now.addAndGet(500L);
        watch.onFrame();
        now.addAndGet(500L);
        watch.beginWindow();
        assertThat(watch.silentWindows()).isZero();
        assertThat(fireAfter(WINDOW - 1L)).isEqualTo(new Check.Remaining(1L));
        assertThat(((Check.Silent) fireAfter(1L)).silentWindows()).isEqualTo(1);
    }

    @Test
    @DisplayName("window must be positive")
    void badWindow() {
        assertThatThrownBy(() -> new RoundSilenceWatch(0, now::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
