package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.auth.RikLoginRequest;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.stub.H2cStreamLimitStub;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GATEWAY_REQUEST_BUDGET A33 — the staging-release anomaly A1, reproduced on loopback.
 * <p>
 * On staging, every fleet start lost 4-8 bots per 100-bot RIK group to
 * {@code java.io.IOException: too many concurrent streams}: thrown by the JDK 21 HTTP/2 client
 * itself ({@code Http2Connection.reserveStream0}) when a request would exceed the server's
 * {@code SETTINGS_MAX_CONCURRENT_STREAMS} on the one connection a per-environment
 * {@link HttpClient} keeps to the gateway. The client neither queues nor opens a second
 * connection; the request just fails, and nothing reaches the gateway.
 * <p>
 * The stub advertises a limit <b>above</b> {@link ApiGatewayClient#MAX_IN_FLIGHT_REQUESTS} and
 * holds every answer for a while, so a group-start-shaped burst — first balance reads and logins
 * together, on one client — overlaps far past the limit. The first test proves that shape breaks
 * a bare JDK client (so the second cannot pass vacuously); the second proves that
 * {@code ApiGatewayClient} gets every request through it.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient bounds in-flight requests below the HTTP/2 stream limit (A33)")
class ApiGatewayClientStreamLimitTest {

    /** Above the client's bound, below the burst. */
    private static final int SERVER_STREAM_LIMIT = ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS + 8;
    private static final int BURST = 100;
    private static final Duration ANSWER_DELAY = Duration.ofMillis(600);
    private static final String TOO_MANY = "too many concurrent streams";

    private H2cStreamLimitStub stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    @DisplayName("control: an unbounded burst on one JDK HTTP/2 client fails with 'too many concurrent streams'")
    void unboundedBurstReproducesA1() throws Exception {
        stub = H2cStreamLimitStub.start(SERVER_STREAM_LIMIT, ANSWER_DELAY);
        HttpClient raw = HttpClient.newHttpClient();
        URI uri = URI.create(stub.baseUrl() + "/gwms/v1/verifytoken.aspx?token=t&fg=f");
        // Warm: the h2c upgrade happens on this one, and pools the connection for the burst.
        assertThat(raw.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
                .version()).isEqualTo(HttpClient.Version.HTTP_2);

        List<Throwable> failures = burst(i -> raw.send(HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()));

        assertThat(failures)
                .as("anti-vacuity: the stub must reproduce A1 for an unbounded client, or the test "
                        + "below proves nothing")
                .isNotEmpty()
                .allMatch(t -> chainContains(t, TOO_MANY));
    }

    @Test
    @DisplayName("a group-start burst of first balance reads and logins all succeed, and never exceed the bound")
    void boundedBurstSucceeds() throws Exception {
        stub = H2cStreamLimitStub.start(SERVER_STREAM_LIMIT, ANSWER_DELAY);
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(),
                new BotMetrics(new SimpleMeterRegistry()));
        client.init(stub.baseUrl(), "rik.vip",
                new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "admin-x-token",
                        ctx -> new RikLoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), "203.0.113.7")),
                GatewayBudget.UNLIMITED);

        // Warm: a GET, so the h2c upgrade happens and the connection is pooled (the stub's javadoc).
        client.getBalance("auth", "fg", "warm", RequestTier.ESSENTIAL, scope("warm"));

        // Shaped like the staging start: most of the burst is first balance reads, the rest is the
        // next group's logins on the same environment client.
        List<Throwable> failures = burst(i -> {
            String user = "rikzz" + i;
            if (i % 4 == 0) {
                return client.authenticate(BotCredentials.builder().username(user).password("p")
                        .fingerprint("fg").build(), RequestTier.ESSENTIAL, scope(user));
            }
            return client.getBalance("auth-" + i, "fg", user, RequestTier.ESSENTIAL, scope(user));
        });

        assertThat(failures).as("no request in the burst may fail").isEmpty();
        assertThat(stub.answered()).isEqualTo(BURST + 1);
        assertThat(stub.maxConcurrentStreamsObserved())
                .as("the server's own count of concurrently open streams")
                .isLessThanOrEqualTo(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS);
    }

    @Test
    @DisplayName("guard: the bound stays positive and below the RFC 9113 recommended stream floor of 100")
    void boundIsBelowTheRfcFloor() {
        // A future "let's raise it to 128" would pass the loopback test above (which advertises the
        // bound + 8) and fail on any real server at the RFC's recommended 100.
        assertThat(ApiGatewayClient.MAX_IN_FLIGHT_REQUESTS).isBetween(1, 99);
    }

    @FunctionalInterface
    private interface Call {
        Object run(int i) throws Exception;
    }

    /** Fire {@link #BURST} calls at once on virtual threads; return what each one threw. */
    private static List<Throwable> burst(Call call) throws InterruptedException {
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < BURST; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    try {
                        call.run(n);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }));
            }
        }
        assertThat(futures).allMatch(Future::isDone);
        return new ArrayList<>(failures);
    }

    private static boolean chainContains(Throwable t, String needle) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static GatewayRequestScope scope(String user) {
        return GatewayRequestScope.forBot("group-a1", user, () -> false);
    }
}
