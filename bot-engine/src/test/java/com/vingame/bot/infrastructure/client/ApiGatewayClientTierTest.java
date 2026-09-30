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
 * a caller passes is the tier that reaches the budget, and that a registration request carries
 * the caller's registration scope — which since Phase 4 <b>does</b> name a {@code botGroupId},
 * because that is the key {@code cancelScope} uses to call one off (A28.6).
 */
@org.junit.jupiter.api.Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
@DisplayName("ApiGatewayClient — tier and scope of every request")
class ApiGatewayClientTierTest {

    private static final GatewayRequestScope BOT_SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

    /** What RegistrationWorker builds: the group, the account, and a live cancel read. */
    private static final GatewayRequestScope REGISTRATION_SCOPE =
            GatewayRequestScope.registration("group-1", "authtestws1", () -> false);

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
    @DisplayName("setDisplayName is DEFAULT and submits the caller's scope verbatim")
    void setDisplayNameIsDefaultWithARegistrationScope() {
        assertThatThrownBy(() -> client.setDisplayName("authtestws1", "Gấu Bự",
                REGISTRATION_SCOPE, java.time.Duration.ofMinutes(15)))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);

        assertThat(budget.only().tier()).isEqualTo(RequestTier.DEFAULT);
        assertThat(budget.only().scope())
                .as("the caller's scope reaches the budget unchanged — it used to be replaced "
                        + "here by a group-less one, which made a queued update-fullname "
                        + "uncancellable by the DELETE that wanted the group gone (A28.6)")
                .isSameAs(REGISTRATION_SCOPE);
        assertThat(budget.only().scope().botGroupId()).isEqualTo("group-1");
        assertThat(budget.only().scope().botId()).isEqualTo("authtestws1");
    }

    @Test
    @DisplayName("registerOne is DEFAULT and submits the caller's scope verbatim")
    void registerOneIsDefaultWithTheCallersScope() {
        assertThatThrownBy(() -> client.registerOne("authtestws", "pw", 1,
                REGISTRATION_SCOPE, java.time.Duration.ofMinutes(15)))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);

        assertThat(budget.submissions()).hasSize(1);
        assertThat(budget.only().tier()).isEqualTo(RequestTier.DEFAULT);
        assertThat(budget.only().scope()).isSameAs(REGISTRATION_SCOPE);
        assertThat(budget.only().wsUpgrade()).isFalse();
    }

    @Test
    @DisplayName("registerOne makes exactly one request, and a budget refusal is not swallowed")
    void registerOneDoesNotSwallowABudgetRefusal() {
        // The bulk predecessor caught Exception per user into a failureCount, which is how a
        // budget refusal became an UpstreamRegistrationException and then a 502 about a gateway
        // that was never asked (A25.1, review F2). One index, one request, and the refusal
        // propagates to RegistrationWorker, which is the only component that can tell "not now"
        // from "this account cannot be created" and re-queue rather than fail the group.
        assertThatThrownBy(() -> client.registerOne("authtestws", "pw", 7,
                REGISTRATION_SCOPE, java.time.Duration.ofMinutes(15)))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);

        assertThat(budget.submissions())
                .as("one index is one gateway request — no fan-out, no semaphore, nothing to "
                        + "size from a @Value field that is zero outside Spring (QA G1)")
                .hasSize(1);
    }

    @Test
    @DisplayName("a client built outside Spring registers without hanging")
    void aClientBuiltOutsideSpringDoesNotHang() {
        // The predecessor of this test pinned a KNOWN DEFECT: registerUsers sized a Semaphore
        // from the `user.registration.parallelism` @Value field, so a client the container did
        // not build carried 0 and Semaphore(0).acquire() parked every registration thread
        // forever — a HANG, not a failure, which is why the old test carried a SEPARATE_THREAD
        // timeout as a wedge guard. Deleting the fan-out deleted the defect: there is no
        // concurrency here to bound and no field to forget to set.
        ApiGatewayClient raw = new ApiGatewayClient(
                new DisplayNameService(), new BotMetrics(new SimpleMeterRegistry()));
        raw.init("http://127.0.0.1:1/never-reached", "bc114097",
                new AuthProfile("/l", "/r", "/u", "x", ctx -> null), budget);
        ReflectionTestUtils.setField(raw, "botIp", "127.0.0.1");

        assertThatThrownBy(() -> raw.registerOne("authtestws", "pw", 1,
                REGISTRATION_SCOPE, java.time.Duration.ofMinutes(15)))
                .isInstanceOf(RecordingGatewayBudget.Sentinel.class);
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
