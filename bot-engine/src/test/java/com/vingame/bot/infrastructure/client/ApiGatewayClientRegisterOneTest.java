package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.dto.RegistrationOutcome;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code registerOne} against a real HTTP exchange, on loopback
 * (GATEWAY_REQUEST_BUDGET A28.3).
 *
 * <p><b>Never a real gateway.</b> {@link StubGateway} binds {@code 127.0.0.1} on an ephemeral
 * port and nothing in this class can name anything else. That is not tidiness: a Cloudflare block
 * has no tolerable cooldown — possibly ~24 hours, possibly until someone clears it by hand — so a
 * test that fired real registration traffic could take a brand's staging down for a day.
 *
 * <p>What is worth an HTTP exchange rather than a mock is precisely the classification: both the
 * fresh and the already-exists envelope come back as <b>HTTP 200</b>, so a parser that read the
 * status line instead of the body would pass every mock-based test and then, in production, count
 * every resumed index as a fresh account — which is the one reading that makes an interrupted
 * 500-account registration unresumable.
 */
@DisplayName("ApiGatewayClient.registerOne — one account, classified on the body")
class ApiGatewayClientRegisterOneTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.registration("group-1", "regtest", () -> false);
    private static final Duration WAIT = Duration.ofMinutes(15);

    private StubGateway gateway;
    private ApiGatewayClient client;

    @BeforeEach
    void setUp() throws Exception {
        gateway = StubGateway.start();
        client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(new SimpleMeterRegistry()));
        client.init(gateway.baseUrl(), "bc114097", new AuthProfile(
                "/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                "/gwms/v1/bot/update-fullname.aspx", "x-tok", ctx -> null));
        // bot.ip is a @Value field and goes in the request body; null would NPE before the
        // request was ever built.
        ReflectionTestUtils.setField(client, "botIp", "127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        gateway.close();
    }

    @Test
    @DisplayName("a fresh username is CREATED, and costs exactly one request")
    void freshUsernameIsCreated() throws Exception {
        RegistrationOutcome outcome = client.registerOne("regtest", "pw", 1, SCOPE, WAIT);

        assertThat(outcome).isEqualTo(RegistrationOutcome.CREATED);
        assertThat(gateway.countFor("register.aspx")).isEqualTo(1);
    }

    @Test
    @DisplayName("re-registering the same index is ALREADY_EXISTED, not a failure")
    void reRegisteringIsAlreadyExisted() throws Exception {
        client.registerOne("regtest", "pw", 1, SCOPE, WAIT);

        RegistrationOutcome outcome = client.registerOne("regtest", "pw", 1, SCOPE, WAIT);

        // This is the whole of Open Item 13. The envelope is {"status":"EXISTED","code":409} at
        // HTTP 200, so `isSuccess()` is false and the generic failure throw would fire — which
        // would make a JVM restart mid-registration fail the group on the very index it was in
        // the middle of, permanently, on every retry.
        assertThat(outcome).isEqualTo(RegistrationOutcome.ALREADY_EXISTED);
        assertThat(gateway.countFor("register.aspx")).isEqualTo(2);
    }

    @Test
    @DisplayName("the resume path never asks for a login — no token is needed to name an account")
    void resumingDoesNotLogIn() throws Exception {
        // The interrupted state this reconstructs: index 1 registered, and the JVM died before
        // its display name landed. `gateway.reset()` is deliberately NOT called — it clears the
        // stub's memory of registered usernames, which would turn the resume below into a fresh
        // registration and quietly test nothing.
        client.registerOne("regtest", "pw", 1, SCOPE, WAIT);

        // The plan (A17.3 / A28.3) budgets a resumed index at register + login + update-fullname,
        // on the reasoning that a re-register returns no session_id and setDisplayName needs one.
        // The first half is true; the second is not in this codebase — update-fullname
        // authenticates with the per-environment admin X-TOKEN and identifies the account by the
        // username in the body. A30 records the correction, and this is the assertion behind it:
        // the calls a resumed index actually makes, and no login among them.
        RegistrationOutcome resumed = client.registerOne("regtest", "pw", 1, SCOPE, WAIT);
        client.setDisplayName("regtest1", "Gấu Bự", SCOPE, WAIT);

        assertThat(resumed).isEqualTo(RegistrationOutcome.ALREADY_EXISTED);
        assertThat(gateway.countFor("update-fullname.aspx")).isEqualTo(1);
        assertThat(gateway.countFor("login.aspx"))
                .as("a resumed index costs two gateway requests, not three — do not add a login "
                        + "back on the strength of the plan's arithmetic")
                .isZero();
        // Three in total across the whole test: the original registration, plus the two the
        // resume itself costs.
        assertThat(gateway.totalReceived()).isEqualTo(3);
    }

    @Test
    @DisplayName("a username the gateway refuses is a throw, so the index costs an attempt")
    void anUnrecognisedEnvelopeFailsClosed() {
        // StubGateway answers a 400 ERROR envelope for the reserved username "reject" — the
        // "fail closed on anything else" half of A28.3. It must not be optimistically believed:
        // a silent success here would advance registeredCount past an account that does not
        // exist, and every bot built on it would fail to authenticate at group start, which
        // presents as an auth outage rather than as a registration bug.
        assertThatThrownBy(() -> client.registerOne("reject", "pw", 1, SCOPE, WAIT))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Registration failed for reject1");
    }
}
