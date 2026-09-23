package com.vingame.bot.common.exception;

import com.vingame.bot.common.gateway.RequestTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The typed budget outcomes (GATEWAY_REQUEST_BUDGET AD-11): what they say, and — the part
 * that actually matters — <b>what they are not</b>.
 * <p>
 * Nothing throws these in Phase 1; the facade ships in {@code mode=observe} and never
 * refuses anything. They are asserted now because the hierarchy is the decision, and it is
 * the kind of decision that is quietly undone later by making one of them extend
 * {@link UpstreamGatewayException} "so it gets a proper status code". That would map a
 * request <em>this JVM chose not to send</em> to <b>502 Bad Gateway</b> and send an operator
 * to look at a gateway that is working perfectly — the inverse of the diagnosis this whole
 * feature exists to provide. The 429/503 arms land in Phase 3; until they do, these fall to
 * {@code RestExceptionHandler.handleAny} and nothing renders their {@code type}.
 */
@DisplayName("Gateway budget exceptions — typed, and deliberately not upstream failures")
class GatewayBudgetExceptionTest {

    @Test
    @DisplayName("none of them is an UpstreamGatewayException, so none of them can become a 502")
    void noneOfThemIsAnUpstreamFailure() {
        for (GatewayBudgetException e : all()) {
            assertThat(e)
                    .as("%s must not be an UpstreamGatewayException: 502 means the gateway "
                            + "answered badly, and in every one of these cases it was never asked",
                            e.getClass().getSimpleName())
                    .isNotInstanceOf(UpstreamGatewayException.class)
                    .isInstanceOf(BotManagerException.class)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @DisplayName("the body `type` strings are the frontend contract and are all distinct")
    void theTypeStringsAreDistinctAndStable() {
        assertThat(GatewayBudgetExhaustedException.TYPE).isEqualTo("Gateway budget exhausted");
        assertThat(GatewayCircuitOpenException.TYPE).isEqualTo("Gateway edge block");
        assertThat(GatewayRequestCancelledException.TYPE).isEqualTo("Gateway request cancelled");
        assertThat(all().stream().map(GatewayBudgetException::getType).distinct().count())
                .as("two outcomes sharing a `type` would be indistinguishable to the UI, and the "
                        + "remedies are different: 429 clears itself in seconds, 503 does not")
                .isEqualTo(all().size());
    }

    @Test
    @DisplayName("exhaustion names the tier, the environment and when a retry could work")
    void exhaustionCarriesTheRetryAdvice() {
        GatewayBudgetExhaustedException e = new GatewayBudgetExhaustedException(
                RequestTier.DEFAULT, "env-1", Duration.ofSeconds(42));

        assertThat(e.getTier()).isEqualTo(RequestTier.DEFAULT);
        assertThat(e.getEnvironmentId()).isEqualTo("env-1");
        // Retry-After is rendered from this; seconds, because that is what the header takes.
        assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(42));
        assertThat(e.getMessage())
                .contains("env-1")
                .contains("DEFAULT")
                .contains("42s");
    }

    @Test
    @DisplayName("the circuit-open exception carries the cf-ray the SA ticket needs")
    void circuitOpenCarriesTheCfRay() {
        GatewayCircuitOpenException e = new GatewayCircuitOpenException(
                "env-1", "a3c7e4004acc850e-HKG", Duration.ofMinutes(15));

        assertThat(e.getCfRay()).isEqualTo("a3c7e4004acc850e-HKG");
        assertThat(e.getRetryAfter()).isEqualTo(Duration.ofMinutes(15));
        assertThat(e.getMessage()).contains("a3c7e4004acc850e-HKG").contains("900s");
    }

    @Test
    @DisplayName("a null retryAfter or cf-ray degrades to words, never to a NullPointerException")
    void nullsAreTolerated() {
        // These are constructed on a failure path, sometimes from a best-effort classification
        // of a WebSocket handshake failure where no cf-ray survived. A message builder that
        // threw there would replace a diagnosable outage with a stack trace about nothing.
        assertThat(new GatewayBudgetExhaustedException(RequestTier.ESSENTIAL, null, null).getMessage())
                .contains("unknown");
        assertThat(new GatewayCircuitOpenException(null, null, null).getMessage())
                .contains("unknown")
                .doesNotContain("cf-ray");
    }

    private static java.util.List<GatewayBudgetException> all() {
        return java.util.List.of(
                new GatewayBudgetExhaustedException(RequestTier.DEFAULT, "env-1", Duration.ofSeconds(5)),
                new GatewayCircuitOpenException("env-1", "ray", Duration.ofMinutes(15)),
                new GatewayRequestCancelledException("env-1", "group-1/bot1"));
    }
}
