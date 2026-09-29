package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>Declared demand shrinks the tiers below it, and gives itself back exactly once</b>
 * (GATEWAY_REQUEST_BUDGET AD-7).
 * <p>
 * The reservation is <em>purely pre-emptive</em>: it stops DEFAULT and PRIORITIZED filling the
 * window in the minutes before an ESSENTIAL flood arrives. Once the window is actually at the
 * ceilings the admission rules alone do the work. What it buys is the case the rules cannot see —
 * a 300-bot group whose login storm is about to start, against a registration or a drift-read
 * cadence that would otherwise have consumed the 500 DEFAULT slots first and made the start
 * queue behind its own housekeeping.
 * <p>
 * Two failure modes are asserted rather than reasoned about, because both are silent. A
 * <b>double release</b> would drive the gauge negative and hand the lower tiers ceiling room that
 * was never returned. A <b>leaked</b> reservation is worse: it shrinks the lower ceilings for the
 * life of the JVM, so registration and drift reads would be starved by a start that finished
 * hours ago.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — enforce: declared demand")
class SlidingWindowGatewayBudgetReservationTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final GatewayRequestScope GROUP_1 =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);
    private static final GatewayRequestScope GROUP_2 =
            GatewayRequestScope.forBot("group-2", "othergroup1", () -> false);

    private AtomicLong clock;
    private SimpleMeterRegistry meters;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        meters = new SimpleMeterRegistry();
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE),
                meters, clock::get);
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
    }

    private double reservedGauge(RequestTier tier) {
        return meters.get(SlidingWindowGatewayBudget.RESERVED)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name())
                .gauge().value();
    }

    /** Admit exactly {@code n} requests of {@code tier}, failing the test if one is refused. */
    private void fill(RequestTier tier, GatewayRequestScope scope, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            budget.execute(tier, scope, () -> "ok", Duration.ZERO);
        }
    }

    @Test
    @DisplayName("reserve(ESSENTIAL, 300) shrinks DEFAULT to 200 and PRIORITIZED to 450")
    void aReservationShrinksTheTiersBelowIt() throws Exception {
        try (GatewayBudget.Reservation reservation =
                     budget.reserve(RequestTier.ESSENTIAL, 300, GROUP_1)) {
            assertThat(reservedGauge(RequestTier.ESSENTIAL)).isEqualTo(300.0);

            // DEFAULT: 500 − 300 = 200.
            fill(RequestTier.DEFAULT, GROUP_2, 200);
            assertThatThrownBy(() -> budget.execute(RequestTier.DEFAULT, GROUP_2, () -> "ok",
                    Duration.ZERO))
                    .as("the 201st DEFAULT must be refused even though the window is only 200/900")
                    .isInstanceOf(GatewayBudgetExhaustedException.class);

            // PRIORITIZED: 750 − 300 = 450, of which 200 are already spent, so 250 more.
            fill(RequestTier.PRIORITIZED, GROUP_2, 250);
            assertThatThrownBy(() -> budget.execute(RequestTier.PRIORITIZED, GROUP_2, () -> "ok",
                    Duration.ZERO))
                    .isInstanceOf(GatewayBudgetExhaustedException.class);

            // ESSENTIAL is not shrunk by its own reservation — there is no tier above it.
            assertThat(budget.execute(RequestTier.ESSENTIAL, GROUP_1, () -> "start"))
                    .isEqualTo("start");
        }
    }

    @Test
    @DisplayName("the reserving group's own admissions consume the reservation")
    void aGroupsOwnRequestsDrawDownItsReservation() throws Exception {
        GatewayBudget.Reservation reservation = budget.reserve(RequestTier.ESSENTIAL, 10, GROUP_1);

        // Matched on (tier, botGroupId) — AD-7's rule — so the group's own flood does not also
        // pay the pre-emptive price it declared.
        budget.execute(RequestTier.ESSENTIAL, GROUP_1, () -> "ok");
        budget.execute(RequestTier.ESSENTIAL, GROUP_1, () -> "ok");
        assertThat(reservation.remaining()).isEqualTo(8);
        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isEqualTo(8.0);

        // Another group's ESSENTIAL request does NOT consume it: the declared demand belongs to
        // the group that declared it, or two simultaneous starts would each spend the other's.
        budget.execute(RequestTier.ESSENTIAL, GROUP_2, () -> "ok");
        assertThat(reservation.remaining()).isEqualTo(8);

        // Nor does a different tier of the same group — a group's drift reads are not its start.
        budget.execute(RequestTier.DEFAULT, GROUP_1, () -> "ok");
        assertThat(reservation.remaining()).isEqualTo(8);

        reservation.release();
        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isZero();
    }

    @Test
    @DisplayName("release returns the remainder and is idempotent")
    void releaseIsIdempotent() {
        GatewayBudget.Reservation reservation = budget.reserve(RequestTier.ESSENTIAL, 300, GROUP_1);

        reservation.release();
        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isZero();
        assertThat(reservation.remaining()).isZero();

        // startLocked's finally releases it, and a try-with-resources form would release it
        // again. A double release would drive the gauge NEGATIVE, which hands the lower tiers
        // ceiling room that was never returned — i.e. it raises the effective cap.
        reservation.release();
        reservation.close();
        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isZero();
    }

    @Test
    @DisplayName("releasing the remainder immediately wakes the waiters it was holding back")
    void releasingAReservationAdmitsQueuedLowerTiers() throws Exception {
        GatewayBudget.Reservation reservation = budget.reserve(RequestTier.ESSENTIAL, 500, GROUP_1);
        // DEFAULT's effective ceiling is 500 − 500 = 0, floored at zero: nothing at all.
        assertThatThrownBy(() -> budget.execute(RequestTier.DEFAULT, GROUP_2, () -> "ok",
                Duration.ZERO))
                .isInstanceOf(GatewayBudgetExhaustedException.class);

        java.util.concurrent.CountDownLatch admitted = new java.util.concurrent.CountDownLatch(1);
        Thread.ofVirtual().name("default-waiter").start(() -> {
            try {
                budget.execute(RequestTier.DEFAULT, GROUP_2, () -> {
                    admitted.countDown();
                    return "ok";
                }, Duration.ofSeconds(20));
            } catch (Exception e) {
                // asserted through the latch
            }
        });
        for (int i = 0; i < 300 && budget.snapshot().queuedDefault() == 0; i++) {
            Thread.sleep(10);
        }
        assertThat(budget.snapshot().queuedDefault()).isEqualTo(1);

        reservation.release();

        assertThat(admitted.await(10, TimeUnit.SECONDS))
                .as("release raises the lower tiers' effective ceilings, so it must walk the "
                        + "queues before it returns — otherwise a DEFAULT waiter sits until the "
                        + "next stamp expiry for room that already exists")
                .isTrue();
    }

    @Test
    @DisplayName("a leaked reservation is retired after 2 x window, with a WARN")
    void aLeakedReservationIsRetired() throws Exception {
        // No release, ever — the shape of a build whose finally did not run.
        budget.reserve(RequestTier.ESSENTIAL, 500, GROUP_1);
        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isEqualTo(500.0);

        clock.addAndGet(WINDOW.toNanos() * 2 - 1);
        budget.admitWaiters();
        assertThat(reservedGauge(RequestTier.ESSENTIAL))
                .as("one nanosecond short of the TTL it is still live — a legitimately slow "
                        + "3,000-bot start takes ~10 windows and must not lose its reservation")
                .isEqualTo(500.0);

        clock.addAndGet(2);
        budget.admitWaiters();
        assertThat(reservedGauge(RequestTier.ESSENTIAL))
                .as("retired. A leak here would starve registration and drift reads for the life "
                        + "of the JVM, on behalf of a start that finished hours ago.")
                .isZero();
        // And the tier it was shrinking is usable again.
        assertThat(budget.execute(RequestTier.DEFAULT, GROUP_2, () -> "ok", Duration.ZERO))
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("declared demand is botCount x 3, and an oversized one floors the lower tiers at zero")
    void anOversizedReservationFloorsTheLowerTiersAtZero() {
        // A15.1: demand is botCount x 3 unconditionally (login + WS upgrade + first balance
        // read), because Open Item 1 is closed — the WS hosts are behind the same rule. For a
        // 3,000-bot group that is 9,000 against a 900 cap, which zeroes DEFAULT and PRIORITIZED
        // until it is consumed or released. That is the intended strict priority, not a bug.
        budget.reserve(RequestTier.ESSENTIAL, 3_000 * 3, GROUP_1);

        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isEqualTo(9_000.0);
        assertThatThrownBy(() -> budget.execute(RequestTier.DEFAULT, GROUP_2, () -> "ok",
                Duration.ZERO))
                .isInstanceOf(GatewayBudgetExhaustedException.class);
        assertThatThrownBy(() -> budget.execute(RequestTier.PRIORITIZED, GROUP_2, () -> "ok",
                Duration.ZERO))
                .isInstanceOf(GatewayBudgetExhaustedException.class);
    }

    @Test
    @DisplayName("a reservation with no group is accounted but consumed by nobody")
    void aGrouplessReservationIsStillAccounted() throws Exception {
        // reserve() is called with the group's scope in production, but a null group id must not
        // NPE or silently become a wildcard that every bot's admission draws down.
        GatewayBudget.Reservation reservation = budget.reserve(RequestTier.ESSENTIAL, 5,
                GatewayRequestScope.internal("fixture"));

        budget.execute(RequestTier.ESSENTIAL, GROUP_1, () -> "ok");

        assertThat(reservation.remaining()).isEqualTo(5);
        reservation.release();
        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isZero();
    }
}
