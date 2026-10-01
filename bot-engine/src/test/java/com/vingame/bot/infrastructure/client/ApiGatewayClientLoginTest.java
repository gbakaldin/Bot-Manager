package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.auth.B52LoginRequest;
import com.vingame.bot.domain.bot.auth.BomLoginRequest;
import com.vingame.bot.domain.bot.auth.RikLoginRequest;
import com.vingame.bot.domain.bot.auth.TipLoginRequest;
import com.vingame.bot.domain.bot.auth.Win79LoginRequest;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import com.vingame.bot.infrastructure.gateway.CapturedBlockPage;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.auth.AuthClient;
import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.DefaultLoginRequest;
import com.vingame.websocketparser.auth.LoginRequest;
import com.vingame.websocketparser.auth.TokensProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login moved in-repo (GATEWAY_REQUEST_BUDGET AD-12, Phase 5) — and this is the proof that it
 * is the <b>same</b> login on the wire, for every brand.
 * <p>
 * <b>The method.</b> For each {@link LoginRequest} implementation, the library's own
 * {@link AuthClient} logs in against the loopback {@link StubGateway} first, then
 * {@link ApiGatewayClient#authenticate} does, and the two requests the stub recorded are compared:
 * method, path, query, every header, and the body bytes. Recorded in the test, on the stub — no
 * network, no fixture file that could go stale. A regression here is a brand that cannot log in,
 * which V4c would otherwise only find on staging.
 * <p>
 * {@code DefaultLoginRequest} stamps {@code System.currentTimeMillis()} into its body, so the
 * factory is memoised on the {@link AuthContext} it is given — which also asserts the context the
 * library built and the one we built are equal (Implementation Note 12): a different context would
 * get a fresh object, a different {@code time}, and fail the byte comparison.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient login — byte-identical to the library's, and observable")
class ApiGatewayClientLoginTest {

    private static final String BOT_IP = "203.0.113.7";
    private static final String X_TOKEN = "admin-x-token-0123456789";
    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);
    private static final BotCredentials CREDENTIALS = BotCredentials.builder()
            .username("authtestws1").password("123123a").fingerprint("f298e7a233c88c9980a7d90dc707fbe712345")
            .build();

    /** A fixed envelope, so both logins parse the same tokens. */
    private static final String TOKENS = "{\"status\":\"OK\",\"code\":200,\"message\":\"OK\",\"data\":[{"
            + "\"session_id\":\"session-777\",\"token\":\"18-agency-777\",\"token2\":\"jwt-777\"}]}";

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

    private record Brand(String name, String loginPath, String appId, String xToken,
                         Function<AuthContext, ? extends LoginRequest> factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Every LoginRequest implementation, wired the way AuthStrategyFactory wires it. */
    static Stream<Brand> brands() {
        String bot = "/gwms/v1/bot/login.aspx";
        return Stream.of(
                new Brand("TIP/116", bot, "bc115116", X_TOKEN,
                        ctx -> new TipLoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), BOT_IP)),
                new Brand("BOM/097", bot, "bc114097", X_TOKEN,
                        ctx -> new BomLoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), BOT_IP)),
                new Brand("B52/098", bot, "bc114098", X_TOKEN,
                        ctx -> new B52LoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), BOT_IP)),
                new Brand("RIK/114", bot, "rik.vip", X_TOKEN,
                        ctx -> new RikLoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), BOT_IP)),
                new Brand("WIN79/119", bot, "w79.club", X_TOKEN,
                        ctx -> new Win79LoginRequest(ctx.userName(), ctx.password(), ctx.appId(),
                                ctx.fingerprint(), BOT_IP)),
                // The legacy arm: /user/login.aspx and no X-TOKEN header at all.
                new Brand("Default (066/103/105/118/222)", "/user/login.aspx", "bc000000", null,
                        ctx -> new DefaultLoginRequest(ctx.userName(), ctx.password(), ctx.appId(),
                                ctx.fingerprint())));
    }

    /** Remember the last context and the object built for it; rebuild only for a different context. */
    private static final class MemoisingFactory implements Function<AuthContext, LoginRequest> {
        private final Function<AuthContext, ? extends LoginRequest> delegate;
        private final List<AuthContext> seen = new java.util.ArrayList<>();
        private AuthContext lastCtx;
        private LoginRequest last;

        private MemoisingFactory(Function<AuthContext, ? extends LoginRequest> delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized LoginRequest apply(AuthContext ctx) {
            seen.add(ctx);
            if (!ctx.equals(lastCtx)) {
                lastCtx = ctx;
                last = delegate.apply(ctx);
            }
            return last;
        }
    }

    private ApiGatewayClient client(Brand brand, Function<AuthContext, ? extends LoginRequest> factory,
                                    GatewayBudget budget) {
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(meters));
        client.init(stub.baseUrl(), brand.appId(),
                new AuthProfile(brand.loginPath(), "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", brand.xToken(), factory),
                budget);
        return client;
    }

    private AuthContext context(Brand brand) {
        return new AuthContext(stub.baseUrl(), CREDENTIALS.getUsername(), CREDENTIALS.getPassword(),
                brand.appId(), CREDENTIALS.getFingerprint(), brand.loginPath(), brand.xToken());
    }

    private double logins(String outcome) {
        return meters.find("bot_login_total").tag("outcome", outcome).counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brands")
    @DisplayName("the in-repo login sends what the library sent — method, path, headers, body bytes")
    void theWireShapeIsTheLibrarys(Brand brand) {
        stub.setLoginResponse(TOKENS);
        MemoisingFactory factory = new MemoisingFactory(brand.factory());

        TokensProvider library = new AuthClient(context(brand), factory).authenticate();
        TokensProvider ours = client(brand, factory, GatewayBudget.UNLIMITED)
                .authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE);

        List<StubGateway.RecordedRequest> requests = stub.requests();
        assertThat(requests).hasSize(2);
        StubGateway.RecordedRequest theirs = requests.get(0);
        StubGateway.RecordedRequest mine = requests.get(1);

        assertThat(factory.seen).hasSize(2);
        assertThat(factory.seen.get(1))
                .as("the factory sees the same AuthContext the library built (Note 12)")
                .isEqualTo(factory.seen.get(0));
        assertThat(mine.method()).isEqualTo(theirs.method()).isEqualTo("POST");
        assertThat(mine.path()).isEqualTo(theirs.path()).isEqualTo(brand.loginPath());
        assertThat(mine.query()).isEqualTo(theirs.query());
        assertThat(mine.body()).as("body bytes").isEqualTo(theirs.body());
        assertThat(mine.headers())
                .as("every header the JDK client put on the wire, X-TOKEN and its absence included")
                .isEqualTo(theirs.headers());
        assertThat(mine.header("X-TOKEN")).isEqualTo(brand.xToken());

        assertThat(ours.getAgencyToken()).isEqualTo(library.getAgencyToken()).isEqualTo("18-agency-777");
        assertThat(ours.getAuthToken()).isEqualTo(library.getAuthToken()).isEqualTo("session-777");
        assertThat(ours.getJwtToken()).isEqualTo(library.getJwtToken()).isEqualTo("jwt-777");
    }

    @Test
    @DisplayName("the gateway's own refusal reaches the UpstreamLoginException message, body excluded")
    void anEnvelopeErrorIsNamed() {
        Brand win79 = brands().filter(b -> b.name().startsWith("WIN79")).findFirst().orElseThrow();
        stub.setLoginResponse("{\"status\":\"INVALID\",\"code\":400,"
                + "\"message\":\"Invalid data. Required {ip}, {os}, {device}, {browser}, {fg}.\"}");

        assertThatThrownBy(() -> client(win79, win79.factory(), GatewayBudget.UNLIMITED)
                .authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                .isInstanceOf(UpstreamLoginException.class)
                .hasMessageContaining("authtestws1")
                .hasMessageContaining("Data array is missing or empty")
                .hasMessageContaining("HTTP 200")
                .hasMessageContaining("status: INVALID")
                .hasMessageContaining("code: 400")
                .hasMessageContaining("Required {ip}")
                .satisfies(e -> assertThat(e.getCause())
                        .as("the library's own exception type, so a caller inspecting the cause sees "
                                + "what it always saw")
                        .isInstanceOf(com.vingame.websocketparser.exception.MessageParsingException.class));
        assertThat(logins("failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a block page on login under enforce opens the circuit and is not a login failure")
    void aBlockedLoginOpensTheCircuit() {
        Brand tip = brands().findFirst().orElseThrow();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), meters, System::nanoTime);
        try {
            stub.block();
            ApiGatewayClient client = client(tip, tip.factory(), budget);

            assertThatThrownBy(() -> client.authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                    .isInstanceOfSatisfying(GatewayCircuitOpenException.class,
                            e -> assertThat(e.getCfRay()).isEqualTo(CapturedBlockPage.CF_RAY));
            assertThat(budget.snapshot().circuitOpen()).isTrue();
            assertThat(meters.get(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL)
                    .tags("endpoint", "login").counter().count()).isEqualTo(1.0);
            assertThat(logins("failure"))
                    .as("a brand-wide edge block is not this account failing to log in — and "
                            + "bot_login_total{failure} feeds EnvironmentLoginFailing")
                    .isZero();

            // And the next login is refused inside the JVM: the stub sees nothing more.
            assertThatThrownBy(() -> client.authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                    .isInstanceOf(GatewayCircuitOpenException.class);
            assertThat(stub.countFor("login")).isEqualTo(1);
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("a block page on login under observe changes nothing but the message — no circuit")
    void aBlockedLoginInObserveMode() {
        Brand tip = brands().findFirst().orElseThrow();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Prod", "116",
                GatewayBudgetSettings.defaults(), meters, System::nanoTime);
        try {
            stub.block();
            assertThatThrownBy(() -> client(tip, tip.factory(), budget)
                    .authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                    .isInstanceOf(UpstreamLoginException.class)
                    .hasMessageContaining("not JSON (HTTP 403)")
                    .hasMessageNotContaining("<!DOCTYPE");
            assertThat(budget.snapshot().circuitOpen()).isFalse();
            assertThat(meters.get(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL)
                    .tags("endpoint", "login").counter().count()).isEqualTo(1.0);
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("the login request carries the house timeout — the one it never had under the library")
    void theLoginRequestIsBounded() throws IOException {
        Brand tip = brands().findFirst().orElseThrow();
        HttpRequest request = client(tip, tip.factory(), GatewayBudget.UNLIMITED).loginRequest(context(tip));

        assertThat(request.timeout()).contains(ApiGatewayClient.GATEWAY_REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("a gateway that never answers costs the bound and becomes an UpstreamLoginException")
    void aSilentGatewayIsBoundedNotForever() throws Exception {
        // What BoundedLoginTest proved for the wrapper, re-proved for the request it was replaced
        // by. Loopback, ephemeral port, accepts and never answers — the exact failure A19 named.
        // Takes the real ten seconds: the bound is the shared house constant on purpose.
        Brand tip = brands().findFirst().orElseThrow();
        AtomicReference<Thread> acceptor = new AtomicReference<>();
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            acceptor.set(Thread.ofVirtual().start(() -> {
                try (Socket socket = silent.accept(); InputStream in = socket.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    while (in.read(buffer) >= 0) {
                        // read, never answer
                    }
                } catch (IOException ignored) {
                    // the client gave up — that is the point
                }
            }));
            ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(meters));
            client.init("http://127.0.0.1:" + silent.getLocalPort(), tip.appId(),
                    new AuthProfile(tip.loginPath(), "/r", "/u", X_TOKEN, tip.factory()), GatewayBudget.UNLIMITED);

            long before = System.nanoTime();
            assertThatThrownBy(() -> client.authenticate(CREDENTIALS, RequestTier.ESSENTIAL, SCOPE))
                    .isInstanceOf(UpstreamLoginException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - before);

            assertThat(waited).isGreaterThanOrEqualTo(ApiGatewayClient.GATEWAY_REQUEST_TIMEOUT.minusMillis(50));
            assertThat(waited).isLessThan(Duration.ofSeconds(30));
            assertThat(logins("failure"))
                    .as("the request left and the gateway did not answer — that IS a login failure")
                    .isEqualTo(1.0);
        } finally {
            if (acceptor.get() != null) {
                acceptor.get().interrupt();
            }
        }
    }
}
