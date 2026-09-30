package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GatewayBudget.observeModePacing()} — the one number the only self-paced caller in the
 * system reads (GATEWAY_REQUEST_BUDGET A2.3 / A28.1).
 *
 * <p><b>Why the method exists at all.</b> {@code RegistrationWorker} is a serial loop at
 * ~100-300 ms per call, i.e. ~200-600 requests in a 5-minute window entirely on its own. In
 * {@code enforce} the budget holds it under the DEFAULT ceiling like everything else; in
 * {@code observe} nothing does, so it has to pace itself. The two numbers it needs to do that —
 * the window and the DEFAULT ceiling — are on {@code GatewayBudgetSettings}, which is reachable
 * only through {@code SlidingWindowGatewayBudget.settings()}. A28.1 explicitly forbids getting
 * there by downcast, because every fixture in the build carries {@code GatewayBudget.UNLIMITED}
 * and would throw {@code ClassCastException} on a bot thread.
 */
@DisplayName("GatewayBudget.observeModePacing")
class GatewayBudgetObserveModePacingTest {

    private static SlidingWindowGatewayBudget budget(GatewayBudgetMode mode) {
        return new SlidingWindowGatewayBudget("env-1", "Env", "116",
                GatewayBudgetSettings.defaults().withMode(mode), new SimpleMeterRegistry(),
                System::nanoTime);
    }

    @Test
    @DisplayName("observe mode paces at window / default.ceiling — 600 ms on the shipped policy")
    void observeModePacesAtTheDefaultTiersShare() {
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.OBSERVE);
        try {
            // 300 s / 500 = 600 ms. Derived from the configured ceiling rather than declared as a
            // property of its own, so the pacing cannot drift away from the ceiling it is a
            // restatement of.
            assertThat(budget.observeModePacing()).isEqualTo(Duration.ofMillis(600));
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("enforce mode paces at zero — the budget is already doing it")
    void enforceModeDoesNotAskTheCallerToSleep() {
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.ENFORCE);
        try {
            // A caller that also slept would be paced twice, and would present as the worker
            // being inexplicably slow on the one box where the feature is actually switched on.
            assertThat(budget.observeModePacing()).isZero();
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("it tracks the configured ceiling rather than a compiled constant")
    void itIsDerivedFromTheConfiguredCeiling() {
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Env", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.OBSERVE)
                        .withCeiling(RequestTier.DEFAULT, 300),
                new SimpleMeterRegistry(), System::nanoTime);
        try {
            // 300 s / 300 = 1 s. Halving the DEFAULT ceiling must halve the worker's rate; a
            // hard-coded 600 ms would keep it registering at the old rate against a ceiling an
            // operator deliberately lowered, which is the exact shape of a silent breach.
            assertThat(budget.observeModePacing()).isEqualTo(Duration.ofSeconds(1));
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("UNLIMITED never paces — a fixture must not sleep 600 ms per account")
    void unlimitedNeverPaces() {
        assertThat(GatewayBudget.UNLIMITED.observeModePacing())
                .as("this is the budget every fixture carries; a non-zero answer here would add "
                        + "five minutes to a five-hundred-account test")
                .isZero();
    }
}
