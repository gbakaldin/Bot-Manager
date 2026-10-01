package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA, GATEWAY_REQUEST_BUDGET Phase 5: what the circuit <b>says</b>, and how often.
 * <p>
 * {@code GatewayCircuitBreakerTest} pins the state machine and the meters; nothing pinned the log
 * lines, and they are the half an operator reads. The rate rule from CLAUDE.md applies with force
 * here: during a block <em>every</em> request is a block, so a line emitted per detection is a
 * per-bot, per-request line at WARN/ERROR — on track 1, in Loki, for possibly a day.
 * <ul>
 *   <li>enforce: one ERROR per <em>open</em>, never per block — in-flight requests that land
 *       after the open are counted, not re-announced;</li>
 *   <li>observe: one WARN per environment per five minutes, carrying how many blocks it folded;</li>
 *   <li>one INFO when an answered probe closes it;</li>
 *   <li>an open circuit with no probe bound stays open, says so, and sends nothing.</li>
 * </ul>
 * Plus the detector conjunct no test exercised alone: {@code server: cloudflare} without a
 * {@code cf-ray} still proves the response came from the edge.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("QA — circuit log lines are per incident, never per request")
class GatewayCircuitSignalsTest {

    private static final String RAY = CapturedBlockPage.CF_RAY;
    private static final Duration INTERVAL = GatewayBudgetSettings.BLOCK_PROBE_INTERVAL_DEFAULT;

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private AbstractAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    private AtomicLong clock;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() {
        appender = new AbstractAppender("qa-circuit-signals", null, PatternLayout.createDefaultLayout(), true, null) {
            @Override
            public void append(LogEvent event) {
                if (event.getLoggerName().equals(SlidingWindowGatewayBudget.class.getName())) {
                    events.add(event.toImmutable());
                }
            }
        };
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration().getLoggerConfig(SlidingWindowGatewayBudget.class.getName());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        loggerConfig.setLevel(Level.DEBUG);
        ctx.updateLoggers();
        clock = new AtomicLong(1_000_000_000L);
    }

    @AfterEach
    void tearDown() {
        if (budget != null) {
            budget.shutdown();
        }
        loggerConfig.removeAppender("qa-circuit-signals");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    private SlidingWindowGatewayBudget budget(GatewayBudgetMode mode) {
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "119",
                GatewayBudgetSettings.defaults().withMode(mode), new SimpleMeterRegistry(), clock::get);
        return budget;
    }

    private List<String> at(Level level) {
        return events.stream()
                .filter(e -> e.getLevel() == level)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    private void block(SlidingWindowGatewayBudget b, GatewayEndpoint endpoint, String ray) {
        assertThatThrownBy(() -> b.reportEdgeBlock(endpoint, ray)).isInstanceOf(GatewayCircuitOpenException.class);
    }

    @Test
    @DisplayName("enforce: one ERROR per open, however many blocks follow, naming the cf-ray and the action")
    void oneErrorPerOpen() {
        SlidingWindowGatewayBudget b = budget(GatewayBudgetMode.ENFORCE);

        block(b, GatewayEndpoint.LOGIN, RAY);
        for (int i = 0; i < 50; i++) {
            // requests that were already in flight when it opened — a whole fleet's worth
            block(b, GatewayEndpoint.VERIFY_TOKEN, "other-" + i);
        }

        assertThat(at(Level.ERROR)).hasSize(1);
        assertThat(at(Level.ERROR).get(0))
                .contains("env-1", "login", RAY, "circuit open", "SA/back-office ticket");
        assertThat(at(Level.WARN)).as("in-flight blocks are counted, not re-announced").isEmpty();
    }

    @Test
    @DisplayName("observe: one WARN per five minutes per environment, folding the blocks in between")
    void observeWarnIsThrottled() {
        SlidingWindowGatewayBudget b = budget(GatewayBudgetMode.OBSERVE);

        for (int i = 0; i < 5; i++) {
            b.reportEdgeBlock(GatewayEndpoint.DEPOSIT, RAY);
        }
        assertThat(at(Level.WARN)).hasSize(1);
        assertThat(at(Level.WARN).get(0)).contains("mode=observe", RAY, "(1 block(s)");

        clock.addAndGet(Duration.ofMinutes(5).toNanos());
        for (int i = 0; i < 3; i++) {
            b.reportEdgeBlock(GatewayEndpoint.DEPOSIT, RAY);
        }

        assertThat(at(Level.WARN)).hasSize(2);
        assertThat(at(Level.WARN).get(1))
                .as("the four swallowed blocks plus the one that re-armed the line")
                .contains("(5 block(s)");
        assertThat(at(Level.ERROR)).as("observe never opens, so never announces an open").isEmpty();
    }

    @Test
    @DisplayName("an answered probe closes it with exactly one INFO line")
    void oneInfoOnClose() {
        SlidingWindowGatewayBudget b = budget(GatewayBudgetMode.ENFORCE);
        b.bindCircuitProbe(() -> new CircuitProbe.Answer(200, CloudflareBlockDetector.Verdict.NOT_A_BLOCK));
        block(b, GatewayEndpoint.LOGIN, RAY);
        clock.addAndGet(INTERVAL.toNanos());

        b.runCircuitProbe();

        assertThat(b.snapshot().circuitOpen()).isFalse();
        assertThat(at(Level.INFO)).hasSize(1);
        assertThat(at(Level.INFO).get(0)).contains("cleared", "HTTP 200", "circuit closed after");
    }

    @Test
    @DisplayName("an open circuit with no probe bound stays open, says so once per interval, and sends nothing")
    void noProbeBoundStaysOpen() {
        SlidingWindowGatewayBudget b = budget(GatewayBudgetMode.ENFORCE);
        block(b, GatewayEndpoint.WS_UPGRADE, RAY);

        clock.addAndGet(INTERVAL.toNanos());
        b.runCircuitProbe();
        b.runCircuitProbe();

        assertThat(b.snapshot().circuitOpen()).as("'we could not ask' is not 'it cleared'").isTrue();
        assertThat(b.windowRequests()).as("no probe was sent, so none is stamped").isZero();
        assertThat(at(Level.WARN))
                .as("once for the interval that came due; the second call is inside the next one")
                .hasSize(1)
                .allMatch(line -> line.contains("no clearance probe is bound"));
    }

    @Test
    @DisplayName("server: cloudflare without a cf-ray still comes from the edge — a block, with no ray to quote")
    void serverHeaderAloneProvesTheEdge() {
        Map<String, String> headers = Map.of("server", "Cloudflare", "content-type", "text/html");

        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classify(403,
                name -> Optional.ofNullable(headers.get(name)), "<html>Sorry, you have been blocked</html>");

        assertThat(verdict.edgeBlock()).isTrue();
        assertThat(verdict.cfRay()).isNull();

        SlidingWindowGatewayBudget b = budget(GatewayBudgetMode.ENFORCE);
        assertThatThrownBy(() -> b.reportEdgeBlock(GatewayEndpoint.LOGIN, verdict.cfRay()))
                .isInstanceOfSatisfying(GatewayCircuitOpenException.class, e -> assertThat(e.getCfRay()).isNull());
        assertThat(at(Level.ERROR)).singleElement().asString().contains("cf-ray -");
    }
}
