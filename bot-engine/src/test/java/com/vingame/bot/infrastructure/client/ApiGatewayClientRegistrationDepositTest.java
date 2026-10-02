package com.vingame.bot.infrastructure.client;

import com.sun.net.httpserver.HttpServer;
import com.vingame.bot.common.exception.GatewayBudgetException;
import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.dto.DepositOutcome;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.RecordingGatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code depositForRegistration} — every AD-7 row, against a loopback HTTP server
 * (BOT_PROVISIONING Phase 2). Never a real gateway.
 * <p>
 * The property under test throughout: the write-ahead marker runs <b>after</b> admission and
 * <b>before</b> the request reaches the server, and every outcome is classified so that only a
 * definite non-credit is ever retryable.
 */
@DisplayName("ApiGatewayClient.depositForRegistration — AD-7 classification")
class ApiGatewayClientRegistrationDepositTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.registration("group-1", "dep", () -> false);
    private static final Duration WAIT = Duration.ofMinutes(15);

    private HttpServer server;
    private final AtomicInteger arrivals = new AtomicInteger();
    /** Whether the marker had run by the time each request arrived. */
    private final List<Boolean> markedAtArrival = new CopyOnWriteArrayList<>();
    private final AtomicBoolean marked = new AtomicBoolean();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            arrivals.incrementAndGet();
            markedAtArrival.add(marked.get());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // The username selects the answer: "s200", "s403", "s500", ...
            int status = Integer.parseInt(body.replaceAll(".*\"username\"\\s*:\\s*\"s(\\d{3})\".*", "$1"));
            byte[] bytes = "{\"status\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiGatewayClient client(String baseUrl, GatewayBudget budget) {
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(),
                new BotMetrics(new SimpleMeterRegistry()));
        client.init(baseUrl, "app-1", new AuthProfile("/gwms/v1/bot/login.aspx",
                "/gwms/v1/bot/register.aspx", "/gwms/v1/bot/update-fullname.aspx", "x-tok", ctx -> null),
                budget);
        ReflectionTestUtils.setField(client, "botIp", "127.0.0.1");
        return client;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private DepositOutcome.Result deposit(ApiGatewayClient client, String username) {
        return client.depositForRegistration(username, 1_000_000L, SCOPE, WAIT, () -> marked.set(true));
    }

    @Test
    @DisplayName("HTTP 200 is CREDITED, and the marker ran before the request arrived")
    void http200IsCredited() {
        RecordingGatewayBudget budget = new RecordingGatewayBudget();
        DepositOutcome.Result result = deposit(client(baseUrl(), budget), "s200");

        assertThat(result.outcome()).isEqualTo(DepositOutcome.CREDITED);
        assertThat(arrivals.get()).isEqualTo(1);
        assertThat(markedAtArrival).containsExactly(true);
        assertThat(budget.tiers())
                .as("DEFAULT with the registration wait (AD-10) — never PRIORITIZED")
                .containsExactly(RequestTier.DEFAULT);
    }

    @Test
    @DisplayName("HTTP 4xx is REFUSED — a definite non-credit")
    void http4xxIsRefused() {
        assertThat(deposit(client(baseUrl(), new RecordingGatewayBudget()), "s403").outcome())
                .isEqualTo(DepositOutcome.REFUSED);
        assertThat(deposit(client(baseUrl(), new RecordingGatewayBudget()), "s400").outcome())
                .isEqualTo(DepositOutcome.REFUSED);
    }

    @Test
    @DisplayName("HTTP 5xx is UNKNOWN, never REFUSED — nothing says a 5xx did not credit")
    void http5xxIsUnknown() {
        assertThat(deposit(client(baseUrl(), new RecordingGatewayBudget()), "s500").outcome())
                .isEqualTo(DepositOutcome.UNKNOWN);
        assertThat(deposit(client(baseUrl(), new RecordingGatewayBudget()), "s502").outcome())
                .isEqualTo(DepositOutcome.UNKNOWN);
        assertThat(deposit(client(baseUrl(), new RecordingGatewayBudget()), "s302").outcome())
                .as("anything that is neither 200 nor 4xx is not a definite answer")
                .isEqualTo(DepositOutcome.UNKNOWN);
    }

    @Test
    @DisplayName("a budget refusal before admission is rethrown, the marker never runs, nothing is sent")
    void budgetRefusalLeavesNoMarker() {
        GatewayBudget refusing = RecordingGatewayBudget.refusing(
                new GatewayBudgetExhaustedException(RequestTier.DEFAULT, "env-1", Duration.ofSeconds(30)));

        assertThatThrownBy(() -> deposit(client(baseUrl(), refusing), "s200"))
                .isInstanceOf(GatewayBudgetException.class);
        assertThat(marked).as("the marker is written only after admission").isFalse();
        assertThat(arrivals.get()).isZero();
    }

    @Test
    @DisplayName("a marker that cannot be written means the deposit is not sent")
    void markerFailureMeansNotSent() {
        assertThatThrownBy(() -> client(baseUrl(), new RecordingGatewayBudget())
                .depositForRegistration("s200", 1L, SCOPE, WAIT, () -> {
                    throw new IllegalStateException("mongo down");
                }))
                .isInstanceOf(ApiGatewayClient.DepositMarkerException.class);
        assertThat(arrivals.get()).as("no marker, no money").isZero();
    }

    @Test
    @DisplayName("a refused connection is NOT_SENT even after the marker")
    void connectionRefusedIsNotSent() throws IOException {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = s.getLocalPort();
        }
        DepositOutcome.Result result = deposit(
                client("http://127.0.0.1:" + closedPort, new RecordingGatewayBudget()), "s200");

        assertThat(marked).isTrue();
        assertThat(result.outcome()).isEqualTo(DepositOutcome.NOT_SENT);
    }

    @Test
    @DisplayName("an interrupt after the marker (request in flight) is UNKNOWN")
    void interruptClassification() throws Exception {
        // After the marker: the server stalls, the calling thread is interrupted mid-send.
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        slow.createContext("/", exchange -> {
            arrived.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.start();
        try {
            ApiGatewayClient client = client("http://127.0.0.1:" + slow.getAddress().getPort(),
                    new RecordingGatewayBudget());
            java.util.concurrent.atomic.AtomicReference<DepositOutcome.Result> out =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread t = Thread.ofVirtual().start(() -> out.set(deposit(client, "s200")));
            assertThat(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            t.interrupt();
            t.join(10_000);
            assertThat(out.get().outcome())
                    .as("the request reached the server: its effect cannot be known")
                    .isEqualTo(DepositOutcome.UNKNOWN);
        } finally {
            slow.stop(0);
        }
    }
}
