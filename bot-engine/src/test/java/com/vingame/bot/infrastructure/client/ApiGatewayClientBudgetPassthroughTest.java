package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.exception.GatewayBudgetException;
import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.RecordingGatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>A request the JVM declined to send must not be reported as one the gateway refused</b>
 * (GATEWAY_REQUEST_BUDGET A4, A20.2).
 * <p>
 * This is the test for the defect the compliance pass found, and the reason it is worth a file
 * of its own is that it guards three separate consequences of one missing {@code catch} arm.
 * {@link GatewayBudgetException} is a {@link RuntimeException}, and
 * {@link ApiGatewayClient#authenticate} has a pre-existing {@code catch (RuntimeException e)}
 * arm <em>outside</em> the budget funnel that rewraps everything as
 * {@link UpstreamLoginException}. From the moment Phase 3 starts throwing, on the login path
 * only:
 * <ul>
 *   <li>{@code Bot.performReauth} sees {@code UpstreamLoginException}, never
 *       {@code GatewayBudgetException}, so AD-9's "a budget outcome must not mark the bot
 *       DEAD" can never fire — a paced re-auth kills the bot;</li>
 *   <li>{@code classifyCreationFailure} takes the {@code UpstreamLoginException} arm and tags
 *       {@code "auth"}, defeating the {@code "budget"} arm Phase 1 shipped for exactly this —
 *       and since {@code /start} no longer has an HTTP response, that tag is one of only three
 *       places a budget outcome during a build is visible at all;</li>
 *   <li>{@code bot_login_total{outcome="failure"}} — the per-brand regression gate and the
 *       input to {@code EnvironmentLoginFailing} — counts our own throttling as upstream login
 *       failures.</li>
 * </ul>
 * {@code getBalance} has the milder twin: it rethrows the type unwrapped (good) but used to
 * increment {@code bot_verify_token_total{outcome="failure"}} on the way past, which is what
 * {@code EnvironmentAuthDown} fires on.
 * <p>
 * No socket, no gateway, no Spring: the budget is a fake that throws before the call is ever
 * invoked, which is exactly what enforcement does.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient — a budget outcome passes through, untagged as a gateway failure")
class ApiGatewayClientBudgetPassthroughTest {

    private MeterRegistry meters;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        metrics = new BotMetrics(meters);
    }

    /**
     * A budget that records and then refuses, the way an exhausted window or an open circuit
     * does under {@code enforce} — no queue, no clock, no socket, and the recorded call is
     * never invoked.
     */
    private static GatewayBudget refusing(GatewayBudgetException failure) {
        return RecordingGatewayBudget.refusing(failure);
    }

    private ApiGatewayClient client(GatewayBudget budget) {
        ApiGatewayClient client = new ApiGatewayClient(new DisplayNameService(), metrics);
        client.init("http://127.0.0.1:1", "app-1",
                new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "x-tok", ctx -> null),
                budget);
        // registrationParallelism is a @Value field, so a client built with `new` gets 0.
        // Unused by these tests, set for the same reason ApiGatewayClientTierTest sets it.
        ReflectionTestUtils.setField(client, "registrationParallelism", 2);
        // bot.ip is a @Value field too, and deposit() puts it in the request body — null would
        // NPE inside HttpRequest.Builder before the budget was ever consulted, which would make
        // the deposit assertion below pass for the wrong reason.
        ReflectionTestUtils.setField(client, "botIp", "127.0.0.1");
        return client;
    }

    private static BotCredentials credentials() {
        return BotCredentials.builder()
                .username("authtestws1")
                .password("123123a")
                .fingerprint("fp")
                .build();
    }

    private double counterTotal(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    @DisplayName("authenticate rethrows the budget exception rather than UpstreamLoginException")
    void authenticateRethrowsTheBudgetException() {
        GatewayBudgetExhaustedException refused = new GatewayBudgetExhaustedException(
                RequestTier.ESSENTIAL, "env-1", Duration.ofSeconds(12));
        ApiGatewayClient client = client(refusing(refused));

        assertThatThrownBy(() -> client.authenticate(credentials(), RequestTier.ESSENTIAL,
                GatewayRequestScope.forBot("group-1", "authtestws1", () -> false)))
                .as("the type is what Bot.performReauth and classifyCreationFailure key off")
                .isSameAs(refused)
                .isNotInstanceOf(UpstreamLoginException.class);
    }

    @Test
    @DisplayName("an open circuit passes through the login path the same way")
    void authenticateRethrowsACircuitOpenException() {
        // Phase 5 is what opens a circuit, but the passthrough has to be written for the whole
        // hierarchy now: the arm catches GatewayBudgetException, not one subclass, so the two
        // Phase-5 outcomes inherit it instead of needing the same fix found twice.
        GatewayCircuitOpenException blocked =
                new GatewayCircuitOpenException("env-1", "9a2c3-HKG", Duration.ofMinutes(60));
        ApiGatewayClient client = client(refusing(blocked));

        assertThatThrownBy(() -> client.authenticate(credentials(), RequestTier.PRIORITIZED,
                GatewayRequestScope.forBot("group-1", "authtestws1", () -> false)))
                .isSameAs(blocked);
    }

    @Test
    @DisplayName("a refused login moves no bot_login_total series at all")
    void aRefusedLoginIsNotALoginFailure() {
        ApiGatewayClient client = client(refusing(new GatewayBudgetExhaustedException(
                RequestTier.ESSENTIAL, "env-1", Duration.ofSeconds(3))));

        assertThatThrownBy(() -> client.authenticate(credentials(), RequestTier.ESSENTIAL,
                GatewayRequestScope.forBot("group-1", "authtestws1", () -> false)))
                .isInstanceOf(GatewayBudgetException.class);

        assertThat(counterTotal("bot_login_total"))
                .as("nothing was sent, so there was no login to succeed OR fail. This counter is "
                        + "the per-brand regression gate in V4c and the input to "
                        + "EnvironmentLoginFailing — our own pacing must not appear in it.")
                .isZero();
    }

    @Test
    @DisplayName("a refused balance read moves no bot_verify_token_total series at all")
    void aRefusedBalanceReadIsNotAnAuthOutage() {
        ApiGatewayClient client = client(refusing(new GatewayBudgetExhaustedException(
                RequestTier.DEFAULT, "env-1", Duration.ofSeconds(7))));

        assertThatThrownBy(() -> client.getBalance("session-1", "fp", "authtestws1",
                RequestTier.DEFAULT, GatewayRequestScope.forBot("group-1", "authtestws1", () -> false)))
                .isInstanceOf(GatewayBudgetException.class);

        assertThat(counterTotal("bot_verify_token_total"))
                .as("EnvironmentAuthDown fires on this counter; a window we paced ourselves is "
                        + "not an auth outage and must not be able to page anyone")
                .isZero();
    }

    @Test
    @DisplayName("deposit needs no arm of its own — it only catches IOException/InterruptedException")
    void depositAlreadyPassesTheTypeThrough() {
        ApiGatewayClient client = client(refusing(new GatewayBudgetExhaustedException(
                RequestTier.PRIORITIZED, "env-1", Duration.ofSeconds(4))));

        assertThatThrownBy(() -> client.deposit("authtestws1", 1_000_000L, RequestTier.PRIORITIZED,
                GatewayRequestScope.forBot("group-1", "authtestws1", () -> false)))
                .as("asserted rather than assumed: A4 says deposit is already clean, and this is "
                        + "what makes that claim break the build if a catch arm is ever widened")
                .isInstanceOf(GatewayBudgetException.class);

        assertThat(List.of(counterTotal("bot_login_total"), counterTotal("bot_verify_token_total")))
                .containsExactly(0.0, 0.0);
    }
}
