package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.auth.RikLoginRequest;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import com.vingame.bot.infrastructure.gateway.GatewayEndpoint;
import com.vingame.bot.infrastructure.gateway.RecordingGatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stream permit's edges (GATEWAY_REQUEST_BUDGET A33, review round): released on every failure
 * path, never waited for by the AD-10 drift read, sharing the caller's deadline on the session
 * path, and visible as meters that do not pollute the auth-failure counters.
 * <p>
 * Loopback {@link StubGateway} only, plus a closed loopback port for the connection-refused case.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient — the stream permit's edges (A33)")
class ApiGatewayClientStreamPermitTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "rikzz1", () -> false);
    private static final BotCredentials CREDENTIALS = BotCredentials.builder()
            .username("rikzz1").password("p").fingerprint("fg").build();

    private StubGateway stub;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() throws IOException {
        stub = StubGateway.start();
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    /** A budget whose edge-block report throws, as an enforce-mode budget does. */
    private static final class EnforcingBudget extends RecordingGatewayBudget {
        @Override
        public void reportEdgeBlock(GatewayEndpoint endpoint, String cfRay) {
            super.reportEdgeBlock(endpoint, cfRay);
            throw new GatewayCircuitOpenException("env-fake", cfRay, Duration.ofMinutes(2));
        }
    }

    private ApiGatewayClient client(String baseUrl, RecordingGatewayBudget budget) {
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(meters));
        client.init(baseUrl, "rik.vip",
                new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "admin-x-token",
                        ctx -> new RikLoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), "203.0.113.7")),
                budget);
        return client;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Semaphore permits(ApiGatewayClient client) {
        return (Semaphore) ReflectionTestUtils.getField(client, "inFlight");
    }

    private double counter(String name) {
        return meters.get(name).tag("environmentId", "env-fake").counter().count();
    }

    private double inFlightGauge() {
        return meters.get(BotMetrics.GATEWAY_CLIENT_INFLIGHT_REQUESTS)
                .tag("environmentId", "env-fake").gauge().value();
    }

    @Test
    @DisplayName("every failure path releases its permit: 40 refused connections and 40 edge blocks, then a request still goes straight through")
    void permitsAreReleasedOnEveryFailurePath() throws Exception {
        int n = ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS + 8;

        // 1. Transport failure: IOException out of httpClient.send.
        ApiGatewayClient refused = client("http://127.0.0.1:" + closedPort(), new RecordingGatewayBudget());
        for (int i = 0; i < n; i++) {
            assertThatThrownBy(() -> refused.authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                    .isInstanceOf(UpstreamLoginException.class);
            assertThat(refused.inFlightRequests()).as("after refused connection #%d", i + 1).isZero();
        }

        // 2. Classifier throw: the block page reaches classified(), whose reportEdgeBlock throws
        //    the circuit-open exception from inside the admitted call.
        ApiGatewayClient blocked = client(stub.baseUrl(), new EnforcingBudget());
        stub.block();
        for (int i = 0; i < n; i++) {
            assertThatThrownBy(() -> blocked.authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                    .isInstanceOf(GatewayCircuitOpenException.class);
            assertThat(blocked.inFlightRequests()).as("after edge block #%d", i + 1).isZero();
        }

        // 3. And the same client then gets a permit at once, not after a 10 s permit timeout.
        stub.unblock();
        long started = System.nanoTime();
        assertThat(blocked.authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE).getAgencyToken()).isNotBlank();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the drift read never waits for a permit: no free stream reads as a deferral, and nothing is stamped or sent")
    void theDriftReadNeverWaitsForAPermit() {
        RecordingGatewayBudget budget = new RecordingGatewayBudget();
        ApiGatewayClient client = client(stub.baseUrl(), budget);
        permits(client).acquireUninterruptibly(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS);

        long started = System.nanoTime();
        OptionalLong fresh = client.getBalanceIfAdmitted("auth", "fg", "rikzz1", SCOPE);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(fresh).isEmpty();
        // readBalance's own 500 ms sleep is pre-existing; a permit wait would be 10 s on top.
        assertThat(took).isLessThan(Duration.ofSeconds(3));
        assertThat(budget.submissions()).as("the budget was never asked, so nothing was stamped").isEmpty();
        assertThat(stub.totalReceived()).isZero();
        assertThat(counter(BotMetrics.GATEWAY_CLIENT_STREAM_WAIT_TIMEOUTS_TOTAL)).isZero();
    }

    @Test
    @DisplayName("a session-path call's permit wait is bounded by its own maxWait, and a permit timeout is not an auth failure")
    void theSessionPathPermitWaitSharesTheDeadline() {
        ApiGatewayClient client = client(stub.baseUrl(), new RecordingGatewayBudget());
        permits(client).acquireUninterruptibly(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS);
        assertThat(inFlightGauge()).isEqualTo(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.getBalance("auth", "fg", "rikzz1", RequestTier.PRIORITIZED, SCOPE,
                Duration.ofSeconds(1)))
                .hasRootCauseInstanceOf(ApiGatewayClient.StreamWaitTimeoutException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).as("500 ms sleep + at most the 1 s maxWait, not the 10 s default permit wait")
                .isLessThan(Duration.ofSeconds(4));
        assertThat(stub.totalReceived()).isZero();
        assertThat(counter(BotMetrics.GATEWAY_CLIENT_STREAM_WAIT_TIMEOUTS_TOTAL)).isEqualTo(1.0);
        assertThat(meters.find(BotMetrics.BOT_VERIFY_TOKEN_TOTAL).tag("outcome", "failure").counter())
                .as("the gateway was never asked: not an EnvironmentAuthDown input")
                .isNull();
    }

    @Test
    @DisplayName("a login that finds no free stream is a login failure to its caller but not to bot_login_total")
    void aLoginPermitTimeoutIsNotCountedAsALoginFailure() {
        ApiGatewayClient client = client(stub.baseUrl(), new RecordingGatewayBudget());
        permits(client).acquireUninterruptibly(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS);
        // The login has no caller deadline, so this waits the full default permit wait (10 s).
        assertThatThrownBy(() -> client.authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                .isInstanceOf(UpstreamLoginException.class)
                .hasRootCauseInstanceOf(ApiGatewayClient.StreamWaitTimeoutException.class);
        assertThat(meters.find(BotMetrics.BOT_LOGIN_TOTAL).tag("outcome", "failure").counter()).isNull();
        assertThat(counter(BotMetrics.GATEWAY_CLIENT_STREAM_WAIT_TIMEOUTS_TOTAL)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the stream meters exist at zero as soon as the environment's client is initialised")
    void theMetersArePreRegisteredAtZero() {
        client(stub.baseUrl(), new RecordingGatewayBudget());
        assertThat(inFlightGauge()).isZero();
        assertThat(counter(BotMetrics.GATEWAY_CLIENT_STREAM_WAIT_TIMEOUTS_TOTAL)).isZero();
    }

    @Test
    @DisplayName("permitWait: the default without a deadline, what is left of one, never negative")
    void permitWaitArithmetic() {
        long now = 1_000_000_000L;
        assertThat(ApiGatewayClient.permitWait(null, now)).isEqualTo(ApiGatewayClient.GATEWAY_REQUEST_TIMEOUT);
        assertThat(ApiGatewayClient.permitWait(now + Duration.ofSeconds(3).toNanos(), now))
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(ApiGatewayClient.permitWait(now + Duration.ofMinutes(5).toNanos(), now))
                .isEqualTo(ApiGatewayClient.GATEWAY_REQUEST_TIMEOUT);
        assertThat(ApiGatewayClient.permitWait(now - 1, now)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("FR-1: the drift read does not barge past a request already queued for a stream")
    void theDriftReadHonoursTheFairQueue() throws Exception {
        ApiGatewayClient client = client(stub.baseUrl(), new RecordingGatewayBudget());
        Semaphore permits = permits(client);
        // One permit free, and a waiter queued ahead of it that needs two: the only stable way to
        // have a queued predecessor while a permit is available. tryAcquire() would take the free
        // permit anyway; tryAcquire(0, NANOSECONDS) sees the queue and declines.
        permits.acquireUninterruptibly(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS - 1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                if (permits.tryAcquire(2, 20, TimeUnit.SECONDS)) {
                    permits.release(2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!permits.hasQueuedThreads() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(permits.hasQueuedThreads()).as("precondition: a request is queued").isTrue();

        assertThat(client.getBalanceIfAdmitted("auth", "fg", "rikzz1", SCOPE)).isEmpty();
        assertThat(stub.totalReceived()).isZero();

        waiter.interrupt();
        waiter.join();
    }

    @Test
    @DisplayName("FR-2: drift reads release their permit whether admitted or refused by the budget")
    void driftReadsReleaseTheirPermit() throws Exception {
        int n = ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS + 8;
        RecordingGatewayBudget refusing = new RecordingGatewayBudget() {
            @Override
            public <T> java.util.Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope,
                                                        java.util.concurrent.Callable<T> call, Duration maxWait) {
                return java.util.Optional.empty();   // a full window: refused, nothing sent
            }
        };
        ApiGatewayClient admitted = client(stub.baseUrl(), new RecordingGatewayBudget());
        ApiGatewayClient refused = client(stub.baseUrl(), refusing);

        for (ApiGatewayClient client : java.util.List.of(admitted, refused)) {
            java.util.List<Thread> threads = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                threads.add(Thread.ofVirtual().start(() -> client.getBalanceIfAdmitted("auth", "fg", "rikzz1", SCOPE)));
            }
            for (Thread t : threads) {
                t.join();
            }
            assertThat(client.inFlightRequests()).isZero();
        }
        assertThat(stub.countFor("verifytoken")).as("anti-vacuity: the admitted reads were sent").isPositive();

        long started = System.nanoTime();
        assertThat(refused.getBalance("auth", "fg", "rikzz1", RequestTier.ESSENTIAL, SCOPE)).isNotNegative();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("FR-3: a deposit that found no free stream is a WARN without a stack trace, not an ERROR")
    void aDepositPermitTimeoutIsAWarn() {
        Capture capture = new Capture();
        capture.start();
        org.apache.logging.log4j.core.LoggerContext ctx =
                (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.LoggerConfig own = new org.apache.logging.log4j.core.config.LoggerConfig(
                ApiGatewayClient.class.getName(), org.apache.logging.log4j.Level.INFO, false);
        own.addAppender(capture, org.apache.logging.log4j.Level.INFO, null);
        ctx.getConfiguration().addLogger(ApiGatewayClient.class.getName(), own);
        ctx.updateLoggers();
        try {
            ApiGatewayClient client = client(stub.baseUrl(), new RecordingGatewayBudget());
            permits(client).acquireUninterruptibly(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS);

            assertThat(client.deposit("rikzz1", 1_000L, RequestTier.PRIORITIZED, SCOPE, Duration.ofMillis(200)))
                    .isFalse();

            assertThat(capture.events).noneMatch(e -> e.getLevel() == org.apache.logging.log4j.Level.ERROR);
            assertThat(capture.events).filteredOn(e -> e.getLevel() == org.apache.logging.log4j.Level.WARN)
                    .hasSize(1).allSatisfy(e -> assertThat(e.getThrown()).isNull());
        } finally {
            ctx.getConfiguration().removeLogger(ApiGatewayClient.class.getName());
            ctx.updateLoggers();
            capture.stop();
        }
    }

    private static final class Capture extends org.apache.logging.log4j.core.appender.AbstractAppender {
        private final java.util.List<org.apache.logging.log4j.core.LogEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        Capture() {
            super("a33-deposit-capture", null,
                    org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
