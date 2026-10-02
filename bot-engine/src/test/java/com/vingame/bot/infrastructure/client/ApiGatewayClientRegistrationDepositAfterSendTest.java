package com.vingame.bot.infrastructure.client;

import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code depositForRegistration}: failures <b>after</b> the request went out are UNKNOWN, never
 * NOT_SENT (BOT_PROVISIONING AD-7) — QA, Phase 2. The dev test covers the interrupt and the 5xx;
 * the generic I/O failure after the send (the server read the request and dropped the connection)
 * and an unexpected runtime failure after the send were not covered, and classifying either as
 * NOT_SENT would let the worker clear the marker and send the same deposit again.
 * <p>
 * Loopback {@link HttpServer} only.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient.depositForRegistration — a failure after the send is UNKNOWN")
class ApiGatewayClientRegistrationDepositAfterSendTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.registration("group-1", "dep", () -> false);
    private static final Duration WAIT = Duration.ofMinutes(15);

    private HttpServer server;
    private final AtomicInteger arrivals = new AtomicInteger();
    private volatile boolean dropConnection;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            arrivals.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (dropConnection) {
                // Read the request — it may have been acted on — and hang up without an answer.
                exchange.close();
                return;
            }
            byte[] bytes = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
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

    private ApiGatewayClient client(GatewayBudget budget) {
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(),
                new BotMetrics(new SimpleMeterRegistry()));
        client.init("http://127.0.0.1:" + server.getAddress().getPort(), "app-1",
                new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "x-tok", ctx -> null),
                budget);
        ReflectionTestUtils.setField(client, "botIp", "127.0.0.1");
        return client;
    }

    @Test
    @DisplayName("the server read the request and dropped the connection: UNKNOWN, after the marker")
    void ioFailureAfterSendIsUnknown() {
        dropConnection = true;
        AtomicBoolean marked = new AtomicBoolean();

        DepositOutcome.Result result = client(new RecordingGatewayBudget())
                .depositForRegistration("dep1", 1_000_000L, SCOPE, WAIT, () -> marked.set(true));

        assertThat(marked).isTrue();
        assertThat(arrivals.get()).as("the request reached the server").isGreaterThanOrEqualTo(1);
        assertThat(result.outcome())
                .as("its effect cannot be known — must never be classified as a re-sendable NOT_SENT")
                .isEqualTo(DepositOutcome.UNKNOWN);
    }

    @Test
    @DisplayName("an unexpected runtime failure after the send is UNKNOWN too")
    void runtimeFailureAfterSendIsUnknown() {
        RecordingGatewayBudget throwingAfterCall = new RecordingGatewayBudget() {
            @Override
            public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call,
                                 Duration maxWait) throws Exception {
                super.execute(tier, scope, call, maxWait);
                throw new IllegalStateException("bookkeeping failed after the call returned");
            }
        };

        DepositOutcome.Result result = client(throwingAfterCall)
                .depositForRegistration("dep1", 1_000_000L, SCOPE, WAIT, () -> { });

        assertThat(arrivals.get()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(DepositOutcome.UNKNOWN);
    }

    @Test
    @DisplayName("submitted at DEFAULT with the caller's registration wait (AD-10)")
    void defaultTierWithTheRegistrationWait() {
        RecordingGatewayBudget budget = new RecordingGatewayBudget();

        client(budget).depositForRegistration("dep1", 1L, SCOPE, WAIT, () -> { });

        RecordingGatewayBudget.Submission only = budget.only();
        assertThat(only.tier()).isEqualTo(RequestTier.DEFAULT);
        assertThat(only.maxWait()).isEqualTo(WAIT);
        assertThat(only.scope()).isSameAs(SCOPE);
    }
}
