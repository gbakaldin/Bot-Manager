package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.gateway.RecordingGatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every request {@link ApiGatewayClient} makes goes through the environment's budget, carrying
 * the tier and scope its caller declared (GATEWAY_REQUEST_BUDGET AD-3).
 * <p>
 * <b>No request is ever made.</b> The budget is a {@link RecordingGatewayBudget} in
 * {@code BLOCK} mode, so the funnel's {@code httpClient.send(...)} lambda is recorded and then
 * <em>never invoked</em>; a sentinel comes back instead. That is the only safe way to test this
 * class — the gateway host in these fixtures is fictional, but a mistake that pointed it at a
 * real one and actually sent would cost the brand an hour-long Cloudflare block.
 * <p>
 * What this proves, per method, is exactly what a reviewer cannot see by reading: that the tier
 * a caller passes is the tier that reaches the budget, and that registration requests carry a
 * registration scope (no {@code botGroupId}) rather than a bot's.
 */
@DisplayName("ApiGatewayClient — tier and scope of every request")
class ApiGatewayClientTierTest {

    private static final GatewayRequestScope BOT_SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

    private RecordingGatewayBudget budget;
    private ApiGatewayClient client;

    @BeforeEach
    void setUp() {
        budget = new RecordingGatewayBudget(RecordingGatewayBudget.Mode.BLOCK);
        client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(new SimpleMeterRegistry()));
        client.init("http://127.0.0.1:1/never-reached", "bc114097",
                new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "x-tok", ctx -> null),
                budget);
        // registrationParallelism is a @Value field, so a client built with `new` gets 0 —
        // and registerUsers' Semaphore(0) then parks every registration thread forever.
        // Pre-existing, unrelated to the budget, and this is the first test to exercise
        // registerUsers outside Spring; set it the way the container would.
        ReflectionTestUtils.setField(client, "registrationParallelism", 2);
    }

    @Test
    @DisplayName("authenticate submits the caller's tier and scope")
    void authenticateSubmitsTheCallersTier() {
        BotCredentials credentials = BotCredentials.builder()
                .username("authtestws1").password("pw").fingerprint("fp").build();

        // The library's AuthClient call is wrapped by the funnel's non-HTTP twin until Phase 4
        // moves login in-repo. The sentinel therefore surfaces as UpstreamLoginException, which
        // is what BotFactory's failure classification already keys off.
        assertThatThrownBy(() ->
                client.authenticate(credentials, RequestTier.PRIORITIZED, BOT_SCOPE))
                .isInstanceOf(com.vingame.bot.common.exception.UpstreamLoginException.class)
                .hasRootCauseInstanceOf(RecordingGatewayBudget.Sentinel.class);

        assertThat(budget.only().tier()).isEqualTo(RequestTier.PRIORITIZED);
        assertThat(budget.only().scope()).isSameAs(BOT_SCOPE);
        assertThat(budget.only().wsUpgrade()).isFalse();
    }

    @Test
    @DisplayName("getBalance submits the caller's tier — ESSENTIAL first read, DEFAULT drift")
    void getBalanceSubmitsTheCallersTier() {
        // The sentinel is unchecked, so getBalance's own catch re-increments the failure
        // counter and rethrows it as-is — no wrapping, no cause.
        assertThatThrownBy(() -> client.getBalance("tok", "fp", "authtestws1",
                RequestTier.ESSENTIAL, BOT_SCOPE))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);
        assertThat(budget.only().tier()).isEqualTo(RequestTier.ESSENTIAL);

        budget.clear();
        assertThatThrownBy(() -> client.getBalance("tok", "fp", "authtestws1",
                RequestTier.DEFAULT, BOT_SCOPE))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);
        assertThat(budget.only().tier()).isEqualTo(RequestTier.DEFAULT);
    }

    @Test
    @DisplayName("deposit submits the caller's tier")
    void depositSubmitsTheCallersTier() {
        // deposit() swallows IOException/InterruptedException and returns false; the sentinel is
        // unchecked, so it escapes — which is correct, because a budget refusal is not a failed
        // deposit and must not be reported as one (AD-9).
        assertThatThrownBy(() -> client.deposit("authtestws1", 1_000_000L,
                RequestTier.PRIORITIZED, BOT_SCOPE))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);

        assertThat(budget.only().tier()).isEqualTo(RequestTier.PRIORITIZED);
        assertThat(budget.only().scope()).isSameAs(BOT_SCOPE);
    }

    @Test
    @DisplayName("setDisplayName is DEFAULT with a registration scope carrying the username")
    void setDisplayNameIsDefaultWithARegistrationScope() {
        assertThatThrownBy(() -> client.setDisplayName("authtestws1", "session", "Gấu Bự"))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);

        assertThat(budget.only().tier()).isEqualTo(RequestTier.DEFAULT);
        assertThat(budget.only().scope().botGroupId())
                .as("registration runs before any bot of the group exists, so there is no group "
                        + "to cancel it by and claiming one would be a lie")
                .isNull();
        assertThat(budget.only().scope().botId()).isEqualTo("authtestws1");
    }

    @Test
    @DisplayName("registerUsers routes every request as DEFAULT with a registration scope")
    void registerUsersIsDefaultWithARegistrationScope() {
        // registerUsers catches per-user failures and reports them in its result, so the
        // sentinel becomes a recorded failure rather than a throw. Two users, so this also
        // proves the fan-out does not accidentally share one scope object with a bot's.
        var result = client.registerUsers("authtestws", "pw", 2);

        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailureCount()).isEqualTo(2);
        assertThat(budget.submissions()).hasSize(2);
        assertThat(budget.tiers()).containsExactly(RequestTier.DEFAULT, RequestTier.DEFAULT);
        assertThat(budget.submissions())
                .allSatisfy(submission -> {
                    assertThat(submission.scope().botGroupId()).isNull();
                    assertThat(submission.scope().botId()).isEqualTo("authtestws");
                    assertThat(submission.wsUpgrade()).isFalse();
                });
    }

    @Test
    @DisplayName("a client initialised without a budget still works — it just counts nothing")
    void theFixtureSeamFallsBackToUnlimited() {
        ApiGatewayClient unbudgeted = new ApiGatewayClient(
                new DisplayNameService(), new BotMetrics(new SimpleMeterRegistry()));
        unbudgeted.init("http://127.0.0.1:1/never-reached", "bc114097",
                new AuthProfile("/l", "/r", "/u", "x", ctx -> null), null);

        // A null budget must not become a NullPointerException on a bot thread: an uncounted
        // request is bad, an unstarted fleet is worse.
        assertThat(unbudgeted.getApiGateway()).isEqualTo("http://127.0.0.1:1/never-reached");
    }
}
