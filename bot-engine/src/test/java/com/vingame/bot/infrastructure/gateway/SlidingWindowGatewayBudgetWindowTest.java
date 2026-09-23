package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sliding window itself, on a <b>manual clock</b> (GATEWAY_REQUEST_BUDGET AD-21).
 * <p>
 * Nothing here sleeps and nothing here touches a network. That is not only a speed
 * preference: a test of this class that used real time would be a test that occasionally
 * fails on a loaded laptop, and a test of this class that made a real request could get a
 * whole brand blocked at the Cloudflare edge for over an hour.
 * <p>
 * The window's exact boundary is the assertion that matters most. {@code W} is the count of
 * stamps <em>younger</em> than one window, so a stamp taken exactly one window ago is gone —
 * off by one in the other direction and the budget would permanently believe it has one more
 * request outstanding than it does, which at the ceiling is a request that never gets sent.
 */
@DisplayName("SlidingWindowGatewayBudget — the window")
class SlidingWindowGatewayBudgetWindowTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

    private AtomicLong clock;
    private SimpleMeterRegistry meters;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        meters = new SimpleMeterRegistry();
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults(), meters, clock::get);
    }

    private void advance(Duration by) {
        clock.addAndGet(by.toNanos());
    }

    private double counter(RequestTier tier, String outcome) {
        return meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name(), "outcome", outcome)
                .counter().count();
    }

    private double windowGauge() {
        return meters.get(SlidingWindowGatewayBudget.WINDOW_REQUESTS)
                .tags("environmentId", "env-1", "product", "116")
                .gauge().value();
    }

    @Test
    @DisplayName("every admitted request is one stamp, and the gauge follows the window")
    void executeStampsTheWindow() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> "ok")).isEqualTo("ok");
        }

        assertThat(budget.windowRequests()).isEqualTo(3);
        assertThat(windowGauge()).isEqualTo(3.0);
        assertThat(counter(RequestTier.ESSENTIAL, SlidingWindowGatewayBudget.OUTCOME_ADMITTED))
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("stamps expire at exactly one window, not a nanosecond later")
    void stampsExpireAtExactlyOneWindow() throws Exception {
        budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");

        advance(WINDOW.minusNanos(1));
        assertThat(budget.windowRequests())
                .as("one nanosecond short of the window, the request is still outstanding")
                .isEqualTo(1);

        advance(Duration.ofNanos(1));
        assertThat(budget.windowRequests())
                .as("at exactly one window the stamp is outside it — Cloudflare's boundary, and "
                        + "the difference between a full window and one permanently-lost request")
                .isZero();
    }

    @Test
    @DisplayName("a request in every tier counts against the same one window")
    void allTiersShareTheWindow() throws Exception {
        budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> "a");
        budget.execute(RequestTier.PRIORITIZED, SCOPE, () -> "b");
        budget.execute(RequestTier.DEFAULT, SCOPE, () -> "c");

        // The tiers decide who gets the window, never whether a request is in it. The edge
        // counts requests, not our opinion of their importance.
        assertThat(budget.windowRequests()).isEqualTo(3);
    }

    @Test
    @DisplayName("count() stamps the window without asking for admission")
    void countStampsTheWindow() {
        budget.count("ws-probe");
        budget.count("circuit-probe");

        assertThat(budget.windowRequests())
                .as("a probe costs the edge a request even though it never queues")
                .isEqualTo(2);
        // A probe has no tier, so it must not move any tier's admission counter — a probe
        // counted as an ESSENTIAL admission would make the start-path series unreadable.
        for (RequestTier tier : RequestTier.values()) {
            assertThat(counter(tier, SlidingWindowGatewayBudget.OUTCOME_ADMITTED)).isZero();
        }
    }

    @Test
    @DisplayName("a failing call still stamped: it cost the edge a request")
    void aFailedCallIsStillStamped() {
        assertThatThrownBy(() -> budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> {
            throw new IllegalStateException("upstream said no");
        })).isInstanceOf(IllegalStateException.class).hasMessage("upstream said no");

        assertThat(budget.windowRequests())
                .as("stamped on admission, not on completion — the request left the JVM")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the call's own exception is rethrown unwrapped")
    void theCallsExceptionIsNotWrapped() {
        // The funnel must not change the exception a caller already handles: ApiGatewayClient
        // catches IOException/InterruptedException by type, and Bot's reconnect loop keys off
        // what authenticate() throws.
        assertThatThrownBy(() -> budget.execute(RequestTier.DEFAULT, SCOPE, () -> {
            throw new java.io.IOException("connection reset");
        })).isInstanceOf(java.io.IOException.class).hasMessage("connection reset");
    }

    @Test
    @DisplayName("observe mode never parks: the wait timer records zero")
    void observeModeNeverWaits() throws Exception {
        budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> "ok");

        assertThat(meters.get(SlidingWindowGatewayBudget.WAIT_TIMER)
                .tags("environmentId", "env-1", "product", "116", "tier", "ESSENTIAL")
                .timer().max(java.util.concurrent.TimeUnit.SECONDS))
                .as("the release check is gateway_budget_wait_seconds_max < 0.01 in observe mode")
                .isZero();
    }

    @Test
    @DisplayName("a burst far past the hard cap is admitted in observe mode — only the gauge moves")
    void observeModeAdmitsPastTheHardCap() throws Exception {
        for (int i = 0; i < 1_500; i++) {
            budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");
        }

        // This is the whole meaning of `observe`: no behaviour change on a production fleet,
        // and a number that says what enforcement WOULD have done. 1,500 in a window is a
        // Cloudflare block; the point is that the app can now see that before it is paced.
        assertThat(budget.windowRequests()).isEqualTo(1_500);
        assertThat(budget.snapshot().windowRequests()).isEqualTo(1_500);
        assertThat(budget.snapshot().hardCap()).isEqualTo(900);
    }

    @Test
    @DisplayName("a cancelled scope is not stamped — but only from Phase 3")
    void aCancelledScopeIsStillAdmittedInPhaseOne() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        GatewayRequestScope dead = GatewayRequestScope.forBot("group-1", "bot1", cancelled::get);

        budget.execute(RequestTier.ESSENTIAL, dead, () -> "ok");

        // Deliberately asserting today's behaviour rather than the target behaviour: in
        // observe-only there is no queue for a request to be cancelled OUT of, so a cancelled
        // scope whose request is issued anyway is honest — it really was sent, so it really
        // must be counted. Phase 3 introduces the pre-admission check, and the assertion here
        // is what will have to change with it (and will therefore be noticed).
        assertThat(budget.windowRequests()).isEqualTo(1);
        assertThat(dead.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("WS upgrades are counted, or not, exactly as configured")
    void wsUpgradesFollowTheFlag() {
        AtomicBoolean ran = new AtomicBoolean();
        budget.runWsUpgrade(RequestTier.ESSENTIAL, SCOPE, () -> ran.set(true));

        assertThat(ran).isTrue();
        assertThat(budget.windowRequests()).isEqualTo(1);
        assertThat(budget.countsWsUpgrades()).isTrue();

        SlidingWindowGatewayBudget uncounted = new SlidingWindowGatewayBudget("env-2", "Staging", "116",
                withWsUpgradesCounted(false), new SimpleMeterRegistry(), clock::get);
        AtomicBoolean ran2 = new AtomicBoolean();
        uncounted.runWsUpgrade(RequestTier.ESSENTIAL, SCOPE, () -> ran2.set(true));

        assertThat(ran2)
                .as("the upgrade always happens; the flag only decides whether it is counted")
                .isTrue();
        assertThat(uncounted.windowRequests()).isZero();
    }

    @Test
    @DisplayName("tryExecute admits in observe mode and returns the result")
    void tryExecuteAdmitsInObserveMode() throws Exception {
        Optional<String> result = budget.tryExecute(RequestTier.DEFAULT, SCOPE, () -> "ok", Duration.ZERO);

        assertThat(result).contains("ok");
        assertThat(budget.windowRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("a reservation shows up in the reserved gauge and gives itself back once")
    void reservationAccounting() {
        GatewayBudget.Reservation reservation =
                budget.reserve(RequestTier.ESSENTIAL, 150, SCOPE);

        assertThat(reservedGauge(RequestTier.ESSENTIAL)).isEqualTo(150.0);
        assertThat(reservation.remaining()).isEqualTo(150);
        assertThat(budget.windowRequests())
                .as("declaring demand is not sending anything")
                .isZero();

        reservation.release();
        reservation.release();

        assertThat(reservedGauge(RequestTier.ESSENTIAL))
                .as("release is idempotent — a double release would drive the gauge negative "
                        + "and, from Phase 3, hand the lower tiers room that was never returned")
                .isZero();
    }

    @Test
    @DisplayName("snapshot() renders the rollup fragment operators grep")
    void snapshotRendersTheRollupFragment() throws Exception {
        budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> "ok");
        budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");

        GatewayBudget.Snapshot snapshot = budget.snapshot();

        assertThat(snapshot.environmentId()).isEqualTo("env-1");
        assertThat(snapshot.environmentName()).isEqualTo("Staging");
        assertThat(snapshot.productCode()).isEqualTo("116");
        assertThat(snapshot.mode()).isEqualTo(GatewayBudgetMode.OBSERVE);
        assertThat(snapshot.circuitOpen())
                .as("the circuit lands in Phase 4; until then it is closed and says so")
                .isFalse();
        assertThat(snapshot.describeForRollup())
                .isEqualTo("gateway=2/900 queued=0/0/0 circuit=closed");
    }

    @Test
    @DisplayName("every tier/outcome series exists at zero before anything increments it")
    void countersArePreRegisteredAtZero() {
        // Same reason BotMetrics.initGroupRecoverySeries exists: a counter first seen at 1
        // makes increase() read 0 through the very first occurrence of the thing a rule is
        // watching for. Without this, a dashboard panel for `timeout` would be empty on the
        // day timeouts started.
        for (RequestTier tier : RequestTier.values()) {
            for (String outcome : new String[]{
                    SlidingWindowGatewayBudget.OUTCOME_ADMITTED,
                    SlidingWindowGatewayBudget.OUTCOME_TIMEOUT,
                    SlidingWindowGatewayBudget.OUTCOME_CANCELLED,
                    SlidingWindowGatewayBudget.OUTCOME_CIRCUIT_OPEN}) {
                assertThat(counter(tier, outcome))
                        .as("%s{tier=%s,outcome=%s} must exist at 0",
                                SlidingWindowGatewayBudget.REQUESTS_TOTAL, tier, outcome)
                        .isZero();
            }
            assertThat(reservedGauge(tier)).isZero();
            assertThat(queueGauge(tier)).isZero();
        }
    }

    @Test
    @DisplayName("UNLIMITED runs everything and counts nothing")
    void unlimitedIsAPassThrough() throws Exception {
        assertThat(GatewayBudget.UNLIMITED.execute(RequestTier.ESSENTIAL, SCOPE, () -> 42)).isEqualTo(42);
        assertThat(GatewayBudget.UNLIMITED.snapshot().hardCap())
                .as("a hard cap of 0 is a value no configured budget can hold, so a fixture "
                        + "budget can never be mistaken for a real one in a snapshot")
                .isZero();
        assertThat(GatewayBudget.UNLIMITED.countsWsUpgrades()).isFalse();
    }

    private double reservedGauge(RequestTier tier) {
        return meters.get(SlidingWindowGatewayBudget.RESERVED)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name())
                .gauge().value();
    }

    private double queueGauge(RequestTier tier) {
        return meters.get(SlidingWindowGatewayBudget.QUEUE_DEPTH)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name())
                .gauge().value();
    }

    private static GatewayBudgetSettings withWsUpgradesCounted(boolean counted) {
        GatewayBudgetSettings d = GatewayBudgetSettings.defaults();
        return new GatewayBudgetSettings(d.mode(), d.window(), d.hardCap(), d.ceilings(),
                d.maxWaits(), d.registrationMaxWait(), counted, d.blockCooldown());
    }
}
