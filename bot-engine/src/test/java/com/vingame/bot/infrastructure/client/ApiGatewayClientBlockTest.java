package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import com.vingame.bot.infrastructure.gateway.CapturedBlockPage;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Cloudflare block page on <b>any</b> of the four non-login gateway requests opens the circuit
 * — the classifier sits in the one place every response passes, before anything parses it
 * (GATEWAY_REQUEST_BUDGET A29.4, Implementation Note 11) — and the page itself never reaches a log
 * line at INFO or above (plan Phase 5 tests).
 * <p>
 * The login's half is {@code ApiGatewayClientLoginTest}. Loopback {@link StubGateway} only.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient — a block page on any request opens the circuit, and is never logged at INFO+")
class ApiGatewayClientBlockTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.registration("group-1", "authtestws1", () -> false);
    private static final Duration WAIT = Duration.ofSeconds(5);

    private StubGateway stub;
    private SimpleMeterRegistry meters;
    private SlidingWindowGatewayBudget budget;

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;
    private LoggerContext ctx;

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("api-gateway-block-capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        stub = StubGateway.start();
        stub.block();
        meters = new SimpleMeterRegistry();

        appender = new CapturingAppender();
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration().getLoggerConfig(ApiGatewayClient.class.getName());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        loggerConfig.setLevel(Level.DEBUG);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        if (budget != null) {
            budget.shutdown();
        }
        stub.close();
        loggerConfig.removeAppender("api-gateway-block-capture");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    private ApiGatewayClient client(GatewayBudgetMode mode) {
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "119",
                GatewayBudgetSettings.defaults().withMode(mode), meters, System::nanoTime);
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(meters));
        client.init(stub.baseUrl(), "w79.club",
                new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "admin-x-token", ctx -> null),
                budget);
        ReflectionTestUtils.setField(client, "botIp", "203.0.113.7");
        return client;
    }

    private double edgeBlocks(String endpoint) {
        return meters.get(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL)
                .tags("endpoint", endpoint).counter().count();
    }

    /** Every line at INFO or above, from any logger, as rendered. */
    private List<String> infoAndAbove() {
        return appender.events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(Level.INFO))
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    @Test
    @DisplayName("under enforce, each of verifytoken / deposit / register / update-fullname opens the circuit")
    void everyRequestKindOpensTheCircuit() {
        Map<String, Executable> calls = new java.util.LinkedHashMap<>();
        calls.put("verifytoken", () -> client(GatewayBudgetMode.ENFORCE)
                .getBalance("tok", "fp", "authtestws1", RequestTier.ESSENTIAL, SCOPE));
        calls.put("deposit", () -> client(GatewayBudgetMode.ENFORCE)
                .deposit("authtestws1", 1_000_000L, RequestTier.PRIORITIZED, SCOPE));
        calls.put("register", () -> client(GatewayBudgetMode.ENFORCE)
                .registerOne("authtestws", "123123a", 1, SCOPE, WAIT));
        calls.put("update-fullname", () -> client(GatewayBudgetMode.ENFORCE)
                .setDisplayName("authtestws1", "Gấu Bự", SCOPE, WAIT));

        calls.forEach((endpoint, call) -> {
            stub.reset();
            stub.block();
            if (budget != null) {
                budget.shutdown();
            }
            meters.clear();

            assertThatThrownBy(call::execute)
                    .as("%s: the block is a budget outcome, never a parse error about HTML", endpoint)
                    .isInstanceOfSatisfying(GatewayCircuitOpenException.class,
                            e -> assertThat(e.getCfRay()).isEqualTo(CapturedBlockPage.CF_RAY));
            assertThat(budget.snapshot().circuitOpen()).as(endpoint).isTrue();
            assertThat(edgeBlocks(endpoint)).as("labelled by request kind, not by URL").isEqualTo(1.0);
            assertThat(stub.totalReceived()).as("%s: exactly the one request that met the block", endpoint)
                    .isEqualTo(1);
        });

        assertThat(infoAndAbove())
                .as("the page body never reaches INFO+ — five kilobytes of HTML per bot per request")
                .noneMatch(line -> line.contains("<!DOCTYPE") || line.contains("cf-wrapper"));
    }

    @Test
    @DisplayName("under observe, a blocked deposit WARNs with the verdict and the cf-ray — not the page")
    void anObservedBlockedDepositLogsTheVerdictNotThePage() {
        ApiGatewayClient client = client(GatewayBudgetMode.OBSERVE);

        boolean deposited = client.deposit("authtestws1", 1_000_000L, RequestTier.PRIORITIZED, SCOPE);

        assertThat(deposited).as("observe changes nothing about the outcome: a 403 is not a deposit").isFalse();
        assertThat(budget.snapshot().circuitOpen()).isFalse();
        assertThat(edgeBlocks("deposit")).isEqualTo(1.0);
        List<String> lines = infoAndAbove();
        assertThat(lines)
                .anyMatch(line -> line.contains("[BotDeposit] non-200")
                        && line.contains("Cloudflare edge block page, cf-ray " + CapturedBlockPage.CF_RAY));
        assertThat(lines).noneMatch(line -> line.contains("<!DOCTYPE") || line.contains("cf-wrapper"));
    }
}
