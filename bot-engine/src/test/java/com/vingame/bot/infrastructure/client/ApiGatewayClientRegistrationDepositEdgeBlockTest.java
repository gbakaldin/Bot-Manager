package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.dto.DepositOutcome;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code depositForRegistration} against the <b>real</b> {@link SlidingWindowGatewayBudget} and a
 * Cloudflare block page (BOT_PROVISIONING AD-7, row "Cloudflare block page → REFUSED") — QA,
 * Phase 2. The dev test covers the row with a 403 and a recording budget, which never throws the
 * post-send {@code GatewayCircuitOpenException}; the "after the marker, a budget exception"
 * branch was therefore untested in both directions.
 * <p>
 * Loopback {@link StubGateway} only.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient.depositForRegistration — a Cloudflare block page is a definite REFUSED")
class ApiGatewayClientRegistrationDepositEdgeBlockTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.registration("group-1", "dep", () -> false);

    private StubGateway stub;
    private SimpleMeterRegistry meters;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() throws IOException {
        stub = StubGateway.start();
        stub.block();
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        if (budget != null) {
            budget.shutdown();
        }
        stub.close();
    }

    private ApiGatewayClient client(GatewayBudgetMode mode) {
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "097",
                GatewayBudgetSettings.defaults().withMode(mode), meters, System::nanoTime);
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(meters));
        client.init(stub.baseUrl(), "app-1",
                new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "x-tok", ctx -> null),
                budget);
        ReflectionTestUtils.setField(client, "botIp", "203.0.113.7");
        return client;
    }

    @Test
    @DisplayName("ENFORCE: the block page opens the circuit after the marker ran — REFUSED (retryable), not UNKNOWN")
    void enforceEdgeBlockIsRefused() {
        AtomicInteger marks = new AtomicInteger();

        DepositOutcome.Result result = client(GatewayBudgetMode.ENFORCE)
                .depositForRegistration("dep1", 1_000_000L, SCOPE, Duration.ofSeconds(5), marks::incrementAndGet);

        assertThat(marks.get()).as("the request was admitted and marked before it went out").isEqualTo(1);
        assertThat(stub.countFor("deposit")).isEqualTo(1);
        assertThat(result.outcome())
                .as("the edge refused it and the origin never saw it — a definite non-credit")
                .isEqualTo(DepositOutcome.REFUSED);
        assertThat(result.detail()).contains("Cloudflare");
    }

    @Test
    @DisplayName("OBSERVE: the same page, with no circuit exception, is still REFUSED via its 4xx status")
    void observeEdgeBlockIsRefused() {
        DepositOutcome.Result result = client(GatewayBudgetMode.OBSERVE)
                .depositForRegistration("dep1", 1_000_000L, SCOPE, Duration.ofSeconds(5), () -> { });

        assertThat(result.outcome()).isEqualTo(DepositOutcome.REFUSED);
    }

    @Test
    @DisplayName("a refusal at admission (circuit already open) never runs the marker and is rethrown")
    void openCircuitBeforeAdmissionIsRethrown() {
        ApiGatewayClient client = client(GatewayBudgetMode.ENFORCE);
        // Open the circuit with one blocked deposit.
        client.depositForRegistration("dep1", 1L, SCOPE, Duration.ofSeconds(5), () -> { });
        int before = stub.countFor("deposit");
        AtomicInteger marks = new AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.depositForRegistration(
                        "dep2", 1L, SCOPE, Duration.ofSeconds(1), marks::incrementAndGet))
                .isInstanceOf(com.vingame.bot.common.exception.GatewayBudgetException.class);
        assertThat(marks.get()).as("no admission, no marker").isZero();
        assertThat(stub.countFor("deposit")).as("nothing sent").isEqualTo(before);
    }
}
