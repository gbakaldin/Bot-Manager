package com.vingame.bot.infrastructure.client;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for
 * {@link ApiGatewayClient#setDisplayNameWithRetry(String, int, GatewayRequestScope, Duration)}.
 * <p>
 * The retry loop is the only mitigation for display-name collisions (the
 * display-names file is shared across all bot groups). A regression that
 * accidentally short-circuited the loop on first conflict, or that failed
 * to short-circuit on success, would silently degrade bot identity quality.
 * <p>
 * The HTTP-bound {@code setDisplayName(...)} is stubbed via {@code Mockito.spy}
 * to isolate the retry loop from the {@code HttpClient.send} call.
 * <p>
 * <b>Stub the exact overload the loop calls.</b> Review F2 gave the loop a {@code maxWait} so a
 * batch of registrations shares one deadline, and Phase 4 replaced the unread {@code sessionToken}
 * parameter with the caller's {@link GatewayRequestScope} (A28.6, A30): the loop calls
 * {@code setDisplayName(user, name, scope, maxWait)}. A stub on any other shape does not sit on
 * the path — the spy does not intercept, the call reaches the real {@code HttpClient}, and the
 * {@code IOException} against the fixture's unroutable host surfaces as
 * {@code "Failed to set display name: <name>"}, which looks like an assertion failure and is not.
 * {@link #retryLoopPropagatesTheBatchDeadline()} and {@link #retryLoopPropagatesTheCallersScope()}
 * are what keep the real signature on the path.
 */
@DisplayName("ApiGatewayClient.setDisplayNameWithRetry")
class ApiGatewayClientSetDisplayNameWithRetryTest {

    private static final String USERNAME = "alice";

    /** The shape RegistrationWorker passes: a group id, the username, and a live cancel read. */
    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.registration("group-1", USERNAME, () -> false);

    private DisplayNameService displayNameService;
    private BotMetrics metrics;
    private ApiGatewayClient client;

    @BeforeEach
    void setUp() {
        displayNameService = spy(new DisplayNameService());
        metrics = new BotMetrics(new SimpleMeterRegistry());
        // Real client wired with a spy DisplayNameService so we can control
        // hasDisplayNames() and getRandomDisplayName() deterministically.
        ApiGatewayClient real = new ApiGatewayClient(displayNameService, metrics);
        real.init("https://api.example.test", "bc114097", new AuthProfile(
                "/login", "/register", "/update-fullname", "x-tok", ctx -> null));
        client = spy(real);
    }

    @Test
    @DisplayName("returns null and skips setDisplayName when no display names are available")
    void setDisplayNameWithRetry_returnsNullWhenNoDisplayNamesAvailable() {
        doReturn(false).when(displayNameService).hasDisplayNames();

        String result = client.setDisplayNameWithRetry(USERNAME, 5, SCOPE, Duration.ofMinutes(15));

        assertThat(result).isNull();
        verify(client, never()).setDisplayName(anyString(), anyString(), any(GatewayRequestScope.class), any(Duration.class));
    }

    @Test
    @DisplayName("returns immediately on first success")
    void setDisplayNameWithRetry_returnsOnFirstSuccess() {
        doReturn(true).when(displayNameService).hasDisplayNames();
        when(displayNameService.getRandomDisplayName()).thenReturn("FirstName");
        doReturn(true).when(client).setDisplayName(eq(USERNAME), eq("FirstName"), eq(SCOPE), any(Duration.class));

        String result = client.setDisplayNameWithRetry(USERNAME, 5, SCOPE, Duration.ofMinutes(15));

        assertThat(result).isEqualTo("FirstName");
        verify(client, times(1)).setDisplayName(anyString(), anyString(), any(GatewayRequestScope.class), any(Duration.class));
    }

    @Test
    @DisplayName("retries on conflict and returns the name that finally succeeded")
    void setDisplayNameWithRetry_retriesOnConflict_succeedsOnSecondAttempt() {
        doReturn(true).when(displayNameService).hasDisplayNames();
        when(displayNameService.getRandomDisplayName()).thenReturn("Taken", "Available");
        doReturn(false).when(client).setDisplayName(eq(USERNAME), eq("Taken"), eq(SCOPE), any(Duration.class));
        doReturn(true).when(client).setDisplayName(eq(USERNAME), eq("Available"), eq(SCOPE), any(Duration.class));

        String result = client.setDisplayNameWithRetry(USERNAME, 5, SCOPE, Duration.ofMinutes(15));

        assertThat(result).isEqualTo("Available");
        verify(client, times(2)).setDisplayName(anyString(), anyString(), any(GatewayRequestScope.class), any(Duration.class));
    }

    @Test
    @DisplayName("returns null after maxRetries when every attempt conflicts")
    void setDisplayNameWithRetry_returnsNullAfterMaxRetriesExhausted() {
        doReturn(true).when(displayNameService).hasDisplayNames();
        when(displayNameService.getRandomDisplayName()).thenReturn("Taken1", "Taken2", "Taken3");
        doReturn(false).when(client).setDisplayName(anyString(), anyString(), any(GatewayRequestScope.class), any(Duration.class));

        String result = client.setDisplayNameWithRetry(USERNAME, 3, SCOPE, Duration.ofMinutes(15));

        assertThat(result).isNull();
        verify(client, times(3)).setDisplayName(anyString(), anyString(), any(GatewayRequestScope.class), any(Duration.class));
    }

    @Test
    @DisplayName("skips the attempt (no setDisplayName call) when getRandomDisplayName returns null mid-loop")
    void setDisplayNameWithRetry_skipsAttemptWhenRandomNameIsNull() {
        doReturn(true).when(displayNameService).hasDisplayNames();
        // First attempt: null → skip. Second: real name → call setDisplayName.
        when(displayNameService.getRandomDisplayName()).thenReturn(null, "RealName");
        doReturn(true).when(client).setDisplayName(eq(USERNAME), eq("RealName"), eq(SCOPE), any(Duration.class));

        String result = client.setDisplayNameWithRetry(USERNAME, 3, SCOPE, Duration.ofMinutes(15));

        assertThat(result).isEqualTo("RealName");
        // Only one setDisplayName call — the null iteration was skipped.
        verify(client, times(1)).setDisplayName(anyString(), anyString(), any(GatewayRequestScope.class), any(Duration.class));
    }

    @Test
    @DisplayName("zero maxRetries returns null without calling setDisplayName")
    void setDisplayNameWithRetry_zeroMaxRetriesReturnsNull() {
        doReturn(true).when(displayNameService).hasDisplayNames();

        String result = client.setDisplayNameWithRetry(USERNAME, 0, SCOPE, Duration.ofMinutes(15));

        assertThat(result).isNull();
        verify(client, never()).setDisplayName(anyString(), anyString(), any(GatewayRequestScope.class), any(Duration.class));
    }

    @Test
    @DisplayName("every attempt carries the caller's deadline, not a fresh wait per attempt")
    void retryLoopPropagatesTheBatchDeadline() {
        Duration batchDeadline = Duration.ofSeconds(42);
        doReturn(true).when(displayNameService).hasDisplayNames();
        when(displayNameService.getRandomDisplayName()).thenReturn("Taken", "Available");
        doReturn(false).when(client)
                .setDisplayName(eq(USERNAME), eq("Taken"), eq(SCOPE), eq(batchDeadline));
        doReturn(true).when(client)
                .setDisplayName(eq(USERNAME), eq("Available"), eq(SCOPE), eq(batchDeadline));

        String result = client.setDisplayNameWithRetry(USERNAME, 5, SCOPE, batchDeadline);

        // The point of review F2: a batch of registrations shares ONE deadline, so a name
        // collision must spend the remaining wait rather than start a new one. Both attempts
        // therefore carry the caller's Duration verbatim — a loop that re-read
        // gatewayBudget.registrationMaxWait() per attempt would fail these stubs and fall
        // through to real HTTP, which is exactly the failure this class now documents.
        assertThat(result).isEqualTo("Available");
        verify(client, times(2))
                .setDisplayName(anyString(), anyString(), eq(SCOPE), eq(batchDeadline));
    }

    @Test
    @DisplayName("every attempt carries the caller's scope, so a DELETE can cancel a re-roll")
    void retryLoopPropagatesTheCallersScope() {
        doReturn(true).when(displayNameService).hasDisplayNames();
        when(displayNameService.getRandomDisplayName()).thenReturn("Taken", "Available");
        doReturn(false).when(client)
                .setDisplayName(eq(USERNAME), eq("Taken"), eq(SCOPE), any(Duration.class));
        doReturn(true).when(client)
                .setDisplayName(eq(USERNAME), eq("Available"), eq(SCOPE), any(Duration.class));

        client.setDisplayNameWithRetry(USERNAME, 5, SCOPE, Duration.ofMinutes(15));

        // A28.6: the scope is what GatewayBudget.cancelScope(botGroupId) keys on. A re-roll that
        // built a fresh group-less scope — which is exactly what this method used to do one frame
        // down, in setDisplayName — would be uncancellable, so a DELETE during a 500-account
        // create would park behind up to fifteen minutes of registration wait per collision.
        // Captured rather than matched so the failure message names the group that was lost.
        org.mockito.ArgumentCaptor<GatewayRequestScope> scopes =
                org.mockito.ArgumentCaptor.forClass(GatewayRequestScope.class);
        verify(client, times(2))
                .setDisplayName(anyString(), anyString(), scopes.capture(), any(Duration.class));
        assertThat(scopes.getAllValues())
                .as("every update-fullname attempt must name the group whose accounts these are")
                .allSatisfy(scope -> assertThat(scope.botGroupId()).isEqualTo("group-1"));
    }
}
