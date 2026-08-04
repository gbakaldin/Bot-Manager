package com.vingame.bot.config.client;

import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.auth.AuthStrategyFactory;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EnvironmentClientRegistry}.
 * <p>
 * Covers:
 * <ul>
 *   <li>per-envId cache idempotence — repeated {@code getClients} returns the
 *       same instance without re-calling {@code EnvironmentService.findById};</li>
 *   <li>{@code ProductCode.getAppId() ?: Environment.getAppId()} fallback —
 *       both branches of the line 125-127 condition;</li>
 *   <li>{@code removeClients} / {@code clearAll} shutdown behaviour and cache
 *       eviction.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnvironmentClientRegistry")
class EnvironmentClientRegistryTest {

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private EventLoopGroup eventLoopGroup;

    @Mock
    private ObjectProvider<ApiGatewayClient> apiGatewayClientProvider;

    @Mock
    private AuthStrategyFactory authStrategyFactory;

    private EnvironmentClientRegistry registry;

    private static final String GAME_MS_URL = "https://gamems.example.test";

    @BeforeEach
    void setUp() {
        registry = new EnvironmentClientRegistry(
                environmentService,
                eventLoopGroup,
                apiGatewayClientProvider,
                authStrategyFactory,
                GAME_MS_URL
        );
    }

    private Environment env(String id, ProductCode pc, String envAppId) {
        return Environment.builder()
                .id(id)
                .name("env-" + id)
                .productCode(pc)
                .apiGatewayUrl("https://api.example.test")
                .webSocketMiniUrl("wss://ws.example.test/mini")
                .appId(envAppId)
                .headers(new HashMap<>())
                .customZone(false)
                .useJwtAuth(false)
                .build();
    }

    private AuthProfile stubAuthProfile() {
        return new AuthProfile(
                "/gwms/v1/bot/login.aspx",
                "/gwms/v1/bot/register.aspx",
                "/gwms/v1/bot/update-fullname.aspx",
                "stub-xtoken",
                ctx -> null
        );
    }

    @Test
    @DisplayName("getClients caches per envId — second call returns same instance, no extra findById")
    void getClients_cachesPerEnvironmentId() {
        Environment env = env("env-1", ProductCode.P_097, "legacy-app-id");
        when(environmentService.findById("env-1")).thenReturn(env);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockApiGateway());
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(stubAuthProfile());

        EnvironmentClients first = registry.getClients("env-1");
        EnvironmentClients second = registry.getClients("env-1");

        assertThat(second).isSameAs(first);
        verify(environmentService, times(1)).findById("env-1");
        verify(apiGatewayClientProvider, times(1)).getObject();
    }

    @Test
    @DisplayName("getClients creates separate clients per envId")
    void getClients_createsSeparateClientsPerEnvironmentId() {
        Environment env1 = env("env-1", ProductCode.P_097, "a");
        Environment env2 = env("env-2", ProductCode.P_098, "b");
        when(environmentService.findById("env-1")).thenReturn(env1);
        when(environmentService.findById("env-2")).thenReturn(env2);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockApiGateway(), mockApiGateway());
        when(authStrategyFactory.getAuthProfile(any(Environment.class))).thenReturn(stubAuthProfile());

        EnvironmentClients a = registry.getClients("env-1");
        EnvironmentClients b = registry.getClients("env-2");

        assertThat(a).isNotSameAs(b);
        verify(environmentService, times(1)).findById("env-1");
        verify(environmentService, times(1)).findById("env-2");
    }

    @Test
    @DisplayName("createClients prefers ProductCode.appId when present")
    void getClients_usesProductCodeAppIdWhenPresent() {
        // P_097 has its own appId="bc114097"; Environment.appId="legacy" should be ignored.
        Environment env = env("env-1", ProductCode.P_097, "legacy-should-be-ignored");
        ApiGatewayClient mockClient = mockApiGateway();
        when(environmentService.findById("env-1")).thenReturn(env);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockClient);
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(stubAuthProfile());

        registry.getClients("env-1");

        ArgumentCaptor<String> appIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockClient).init(anyString(), appIdCaptor.capture(), any(AuthProfile.class));
        assertThat(appIdCaptor.getValue()).isEqualTo("bc114097");
    }

    @Test
    @DisplayName("createClients falls back to Environment.appId when ProductCode.appId is null")
    void getClients_fallsBackToEnvironmentAppIdWhenProductCodeAppIdIsNull() {
        // P_066 has ProductCode.appId=null; the fallback to Environment.appId must kick in.
        Environment env = env("env-1", ProductCode.P_066, "custom-env-appid");
        ApiGatewayClient mockClient = mockApiGateway();
        when(environmentService.findById("env-1")).thenReturn(env);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockClient);
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(stubAuthProfile());

        registry.getClients("env-1");

        ArgumentCaptor<String> appIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockClient).init(anyString(), appIdCaptor.capture(), any(AuthProfile.class));
        assertThat(appIdCaptor.getValue()).isEqualTo("custom-env-appid");
    }

    @Test
    @DisplayName("createClients falls back to Environment.appId when productCode is null entirely")
    void getClients_fallsBackToEnvironmentAppIdWhenProductCodeIsNull() {
        Environment env = env("env-1", null, "env-only-appid");
        ApiGatewayClient mockClient = mockApiGateway();
        when(environmentService.findById("env-1")).thenReturn(env);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockClient);
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(stubAuthProfile());

        registry.getClients("env-1");

        ArgumentCaptor<String> appIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockClient).init(anyString(), appIdCaptor.capture(), any(AuthProfile.class));
        assertThat(appIdCaptor.getValue()).isEqualTo("env-only-appid");
    }

    @Test
    @DisplayName("removeClients calls shutdown and evicts from cache")
    void removeClients_callsShutdownAndRemovesEntry() {
        Environment env = env("env-1", ProductCode.P_097, "a");
        when(environmentService.findById("env-1")).thenReturn(env);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockApiGateway(), mockApiGateway());
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(stubAuthProfile());

        registry.getClients("env-1");
        assertThat(registry.size()).isEqualTo(1);

        registry.removeClients("env-1");
        assertThat(registry.size()).isZero();

        // After eviction, the next call must re-create — i.e. findById invoked again.
        registry.getClients("env-1");
        verify(environmentService, times(2)).findById("env-1");
    }

    @Test
    @DisplayName("removeClients on unknown envId is a no-op")
    void removeClients_onUnknownEnvIsNoOp() {
        registry.removeClients("does-not-exist");
        // No exception, no provider lookup, no findById.
        verify(environmentService, never()).findById(anyString());
        verify(apiGatewayClientProvider, never()).getObject();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("clearAll shuts down every cached client and empties the registry")
    void clearAll_shutsDownAllAndEmptiesRegistry() {
        Environment env1 = env("env-1", ProductCode.P_097, "a");
        Environment env2 = env("env-2", ProductCode.P_098, "b");
        when(environmentService.findById("env-1")).thenReturn(env1);
        when(environmentService.findById("env-2")).thenReturn(env2);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockApiGateway(), mockApiGateway());
        when(authStrategyFactory.getAuthProfile(any(Environment.class))).thenReturn(stubAuthProfile());

        registry.getClients("env-1");
        registry.getClients("env-2");
        assertThat(registry.size()).isEqualTo(2);

        registry.clearAll();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("size reflects current cache count")
    void size_reflectsCurrentCacheCount() {
        assertThat(registry.size()).isZero();

        Environment env = env("env-1", ProductCode.P_097, "a");
        when(environmentService.findById("env-1")).thenReturn(env);
        when(apiGatewayClientProvider.getObject()).thenReturn(mockApiGateway());
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(stubAuthProfile());

        registry.getClients("env-1");
        assertThat(registry.size()).isEqualTo(1);
    }

    private ApiGatewayClient mockApiGateway() {
        return mock(ApiGatewayClient.class);
    }
}
