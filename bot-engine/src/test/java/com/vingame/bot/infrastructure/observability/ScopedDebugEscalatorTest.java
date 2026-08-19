package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.ScopedDebugRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-12 — detail is armed on the early-warning signals, and armed at
 * most once per group per {@code ttl + cooldown}.
 * <p>
 * The two failure modes this pins are opposites of each other. Escalating <em>too late</em>
 * (on DEAD) produces logs of a group that has stopped doing anything, which is why all three
 * triggers here fire while the group is still running. Escalating <em>too eagerly</em> — a
 * group flapping a watchdog every three minutes, or 200 bots reconnecting at once — would
 * hold DEBUG open indefinitely and reconstruct the fleet-wide DEBUG the plan exists to
 * remove, which is what the cooldown and the registry cap prevent.
 * <p>
 * <b>The cooldown assertion is a duty-cycle assertion.</b> An earlier version of this class
 * asserted only that a re-arm <em>eventually succeeds</em> past the cooldown, which is true
 * of the broken implementation too — with the cooldown measured from the escalation and equal
 * to the TTL, "eventually" was 30 seconds after every expiry, i.e. a ~96% duty cycle. So
 * {@link #unattendedGroupCannotExceedItsDutyCycle()} drives 4 simulated hours of a
 * permanently sick group and measures the fraction of that time the scope was open.
 */
@DisplayName("ScopedDebugEscalator (AD-12)")
class ScopedDebugEscalatorTest {

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration COOLDOWN = Duration.ofMinutes(45);

    private final AtomicLong now = new AtomicLong(1_000_000L);
    // Same simulated clock as the escalator, so "the TTL lapsed" is a real event here and
    // not something the test has to fake with disable().
    private final ScopedDebugRegistry registry = ScopedDebugRegistry.withClock(10, now::get);

    private ScopedDebugEscalator escalator(boolean enabled) {
        return new ScopedDebugEscalator(registry, enabled, TTL, COOLDOWN,
                5, Duration.ofMinutes(5), 0.80, now::get);
    }

    @Test
    @DisplayName("the FIRST watchdog expiry arms scoped DEBUG for that group only")
    void watchdogExpiryEscalates() {
        ScopedDebugEscalator escalator = escalator(true);

        escalator.onWatchdogExpiry("g1");

        assertThat(registry.isEnabled("g1")).isTrue();
        assertThat(registry.isEnabled("g2")).isFalse();
    }

    @Test
    @DisplayName("the cooldown runs from scope EXPIRY, so an expired scope does not re-arm at once")
    void escalationIsRateLimited() {
        ScopedDebugEscalator escalator = escalator(true);
        escalator.onWatchdogExpiry("g1");

        // The scope has lapsed on its own TTL. This is the exact moment the broken version
        // re-armed: cooldown == TTL, so both deadlines passed together.
        now.addAndGet(TTL.toMillis() + 30_000L);
        assertThat(registry.isEnabled("g1")).as("the TTL really has lapsed").isFalse();
        escalator.onWatchdogExpiry("g1");
        assertThat(registry.isEnabled("g1"))
                .as("the cooldown is a QUIET PERIOD after expiry — no immediate re-arm")
                .isFalse();

        // Still inside the quiet period.
        now.addAndGet(COOLDOWN.toMillis() - 60_000L);
        escalator.onWatchdogExpiry("g1");
        assertThat(registry.isEnabled("g1")).as("inside the quiet period").isFalse();

        // Past ttl + cooldown from the escalation.
        now.addAndGet(120_000L);
        escalator.onWatchdogExpiry("g1");
        assertThat(registry.isEnabled("g1")).as("past ttl + cooldown it may re-arm").isTrue();
    }

    @Test
    @DisplayName("an unattended, permanently sick group cannot exceed ttl/(ttl+cooldown) of any window")
    void unattendedGroupCannotExceedItsDutyCycle() {
        ScopedDebugEscalator escalator = escalator(true);

        // A group parked at dead/total = 0.5: inside the [0.40, 0.80) escalation band on
        // EVERY 30 s health tick and never declared DEAD. Four hours of that.
        long tickMillis = 30_000L;
        long ticks = Duration.ofHours(4).toMillis() / tickMillis;
        long ticksEnabled = 0;
        for (long i = 0; i < ticks; i++) {
            escalator.onGroupHealth("g1", 5, 10);
            if (registry.isEnabled("g1")) {
                ticksEnabled++;
            }
            now.addAndGet(tickMillis);
        }

        double dutyCycle = (double) ticksEnabled / ticks;
        double bound = (double) TTL.toMillis() / escalator.reArmIntervalMillis();
        assertThat(bound).as("the configured bound is 15/(15+45)").isEqualTo(0.25);
        assertThat(dutyCycle)
                .as("scoped DEBUG must not become de-facto permanent DEBUG for a sick group; "
                        + "the broken version measured ~0.96 here")
                .isLessThanOrEqualTo(bound + 0.01);
        assertThat(dutyCycle).as("and it must still fire at all").isGreaterThan(0.0);
    }

    @Nested
    @DisplayName("reconnect-rate trigger")
    class ReconnectRate {

        @Test
        @DisplayName("escalates only once the group crosses the threshold inside the window")
        void escalatesAtThreshold() {
            ScopedDebugEscalator escalator = escalator(true);

            for (int i = 0; i < 4; i++) {
                escalator.onReconnect("g1");
            }
            assertThat(registry.isEnabled("g1")).as("4 reconnects is under the threshold of 5").isFalse();

            escalator.onReconnect("g1");
            assertThat(registry.isEnabled("g1")).isTrue();
        }

        @Test
        @DisplayName("reconnects spread beyond the window never accumulate to the threshold")
        void windowLapses() {
            ScopedDebugEscalator escalator = escalator(true);

            for (int i = 0; i < 20; i++) {
                escalator.onReconnect("g1");
                // One reconnect every 6 minutes: real periodic-logout churn, not a fault.
                now.addAndGet(Duration.ofMinutes(6).toMillis());
            }

            assertThat(registry.isEnabled("g1"))
                    .as("a sliding window is what separates churn from a burst")
                    .isFalse();
        }

        @Test
        @DisplayName("each group is counted separately")
        void perGroupCounters() {
            ScopedDebugEscalator escalator = escalator(true);

            for (int i = 0; i < 4; i++) {
                escalator.onReconnect("g1");
                escalator.onReconnect("g2");
            }

            assertThat(registry.isAnyEnabled())
                    .as("8 reconnects across two groups is 4 each, under the threshold")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("dead-ratio trigger")
    class DeadRatio {

        @Test
        @DisplayName("escalates in the band between half the dead threshold and the threshold")
        void escalatesWhileDeteriorating() {
            ScopedDebugEscalator escalator = escalator(true);

            escalator.onGroupHealth("g1", 3, 10); // 0.30, under half of 0.80
            assertThat(registry.isEnabled("g1")).isFalse();

            escalator.onGroupHealth("g1", 5, 10); // 0.50, in the band
            assertThat(registry.isEnabled("g1")).isTrue();
        }

        @Test
        @DisplayName("does not escalate once the group is over the dead threshold")
        void silentAboveTheThreshold() {
            ScopedDebugEscalator escalator = escalator(true);

            // At/above 0.80 the group is about to be declared DEAD; DEBUG from here is a
            // log of a group that has stopped doing anything.
            escalator.onGroupHealth("g1", 8, 10);

            assertThat(registry.isEnabled("g1")).isFalse();
        }

        @Test
        @DisplayName("a healthy sample is free — no dead bots, no work, no escalation")
        void healthyIsANoop() {
            ScopedDebugEscalator escalator = escalator(true);

            escalator.onGroupHealth("g1", 0, 50);
            escalator.onGroupHealth("g2", 0, 0);

            assertThat(registry.isAnyEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("the master switch off makes every trigger a no-op")
    void disabledEscalatesNothing() {
        ScopedDebugEscalator escalator = escalator(false);

        escalator.onWatchdogExpiry("g1");
        escalator.onGroupHealth("g1", 5, 10);
        for (int i = 0; i < 50; i++) {
            escalator.onReconnect("g1");
        }

        assertThat(registry.isAnyEnabled()).isFalse();
    }

    @Test
    @DisplayName("a fleet-wide incident is bounded by the registry cap, not by this class")
    void registryCapBoundsAFleetWideIncident() {
        ScopedDebugRegistry small = ScopedDebugRegistry.withClock(2, now::get);
        ScopedDebugEscalator escalator = new ScopedDebugEscalator(small, true,
                TTL, COOLDOWN, 5, Duration.ofMinutes(5), 0.80, now::get);

        for (int i = 0; i < 100; i++) {
            escalator.onWatchdogExpiry("group-" + i);
        }

        assertThat(small.activeScopes()).hasSize(2);
    }

    @Test
    @DisplayName("evictGroup drops a stopped group's bookkeeping")
    void evictClearsCounters() {
        ScopedDebugEscalator escalator = escalator(true);
        escalator.onWatchdogExpiry("g1");
        registry.disable("g1");

        escalator.evictGroup("g1");
        // The cooldown went with the group: a restarted group is a new incident.
        escalator.onWatchdogExpiry("g1");

        assertThat(registry.isEnabled("g1")).isTrue();
    }

    @Test
    @DisplayName("null group ids are tolerated everywhere")
    void nullSafe() {
        ScopedDebugEscalator escalator = escalator(true);

        escalator.onWatchdogExpiry(null);
        escalator.onReconnect(null);
        escalator.onGroupHealth(null, 5, 10);
        escalator.evictGroup(null);

        assertThat(registry.isAnyEnabled()).isFalse();
    }
}
