package com.vingame.bot.config.client;

import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.auth.AuthStrategyFactory;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The budget reaches the client, the bots and the metrics as <b>one object per environment</b>
 * (GATEWAY_REQUEST_BUDGET AD-1).
 * <p>
 * Every part of this is a silent failure if it is wrong. Two budget objects for one environment
 * would each count half the traffic, so the window would read low and the near-cap alert would
 * not fire before the block. A budget that reached {@code ApiGatewayClient} but not the
 * {@code Bot} would count HTTP and miss every WebSocket upgrade — up to a third of a group
 * start. A budget keyed by anything other than {@code environmentId} would not line up with
 * {@code EnvironmentClientRegistry}, so the client and the bots of one environment could end up
 * pacing against different windows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnvironmentClientRegistry — gateway budget wiring")
class EnvironmentClientRegistryBudgetWiringTest {

    @Mock
    private EnvironmentService environmentService;
    @Mock
    private EventLoopGroup eventLoopGroup;
    @Mock
    private ObjectProvider<ApiGatewayClient> apiGatewayClientProvider;
    @Mock
    private AuthStrategyFactory authStrategyFactory;

    private GatewayBudgetRegistry budgetRegistry;
    private EnvironmentClientRegistry registry;

    @BeforeEach
    void setUp() {
        budgetRegistry = new GatewayBudgetRegistry(
                GatewayBudgetSettings.defaults(), new SimpleMeterRegistry());
        registry = new EnvironmentClientRegistry(environmentService, eventLoopGroup,
                apiGatewayClientProvider, authStrategyFactory, budgetRegistry,
                "https://gamems.example.test");
    }

    private Environment env(String id, ProductCode product) {
        return Environment.builder()
                .id(id).name("env-" + id).productCode(product)
                .apiGatewayUrl("https://api.example.test")
                .webSocketMiniUrl("wss://ws.example.test/mini")
                .appId("legacy").headers(new HashMap<>())
                .customZone(false).useJwtAuth(false)
                .build();
    }

    private ApiGatewayClient stubClient() {
        ApiGatewayClient client = mock(ApiGatewayClient.class);
        when(client.init(anyString(), anyString(), any(AuthProfile.class), any())).thenReturn(client);
        return client;
    }

    private AuthProfile profile() {
        return new AuthProfile("/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                "/gwms/v1/bot/update-fullname.aspx", "stub-xtoken", ctx -> null);
    }

    @Test
    @DisplayName("the client, the holder and the budget registry all hold the SAME budget object")
    void oneBudgetObjectPerEnvironment() {
        Environment env = env("env-1", ProductCode.P_116);
        when(environmentService.findById("env-1")).thenReturn(env);
        ApiGatewayClient client = stubClient();
        when(apiGatewayClientProvider.getObject()).thenReturn(client);
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(profile());

        EnvironmentClients clients = registry.getClients("env-1");

        ArgumentCaptor<GatewayBudget> captor = ArgumentCaptor.forClass(GatewayBudget.class);
        verify(client).init(anyString(), anyString(), any(AuthProfile.class), captor.capture());

        // The identity is the assertion. BotFactory reads getGatewayBudget() off this holder and
        // wires it into the bot, so if these three were ever different objects a bot's WS
        // upgrades and its HTTP requests would be counted against different windows.
        assertThat(captor.getValue())
                .as("the client is initialised with the environment's budget")
                .isSameAs(clients.getGatewayBudget())
                .isSameAs(budgetRegistry.find("env-1"));
        assertThat(captor.getValue())
                .as("a real budget, never the UNLIMITED pass-through")
                .isNotSameAs(GatewayBudget.UNLIMITED);
    }

    @Test
    @DisplayName("the budget is keyed by environmentId, exactly like the client registry")
    void keyedByEnvironmentId() {
        Environment env1 = env("env-1", ProductCode.P_116);
        Environment env2 = env("env-2", ProductCode.P_097);
        when(environmentService.findById("env-1")).thenReturn(env1);
        when(environmentService.findById("env-2")).thenReturn(env2);
        // Built BEFORE the outer when(...): stubClient() itself stubs, and Mockito forbids
        // starting a stubbing while another one is in progress.
        ApiGatewayClient first = stubClient();
        ApiGatewayClient second = stubClient();
        when(apiGatewayClientProvider.getObject()).thenReturn(first, second);
        when(authStrategyFactory.getAuthProfile(any(Environment.class))).thenReturn(profile());

        EnvironmentClients a = registry.getClients("env-1");
        EnvironmentClients b = registry.getClients("env-2");
        EnvironmentClients aAgain = registry.getClients("env-1");

        assertThat(a.getGatewayBudget())
                .as("Cloudflare's rule is per gateway HOST, so two environments must not throttle "
                        + "each other")
                .isNotSameAs(b.getGatewayBudget());
        assertThat(aAgain.getGatewayBudget()).isSameAs(a.getGatewayBudget());
        assertThat(budgetRegistry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("the budget carries the environment's name and product for its metric labels")
    void theBudgetIsLabelledWithTheEnvironmentIdentity() {
        Environment env = env("env-1", ProductCode.P_116);
        when(environmentService.findById("env-1")).thenReturn(env);
        ApiGatewayClient client = stubClient();
        when(apiGatewayClientProvider.getObject()).thenReturn(client);
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(profile());

        GatewayBudget.Snapshot snapshot = registry.getClients("env-1").getGatewayBudget().snapshot();

        // `product` is what routes a product-scoped alert to a brand's room and what the
        // dashboards filter by; the NUMERIC code, not the enum constant name.
        assertThat(snapshot.environmentId()).isEqualTo("env-1");
        assertThat(snapshot.environmentName()).isEqualTo("env-env-1");
        assertThat(snapshot.productCode()).isEqualTo(ProductCode.P_116.getCode());
    }

    @Test
    @DisplayName("an environment with no productCode still gets a budget")
    void aNullProductCodeIsTolerated() {
        // Environment.productCode is a plain nullable field and older documents predate it.
        // A budget that refused to exist without one would take the whole environment's clients
        // down with it at group start.
        Environment env = env("env-1", null);
        when(environmentService.findById("env-1")).thenReturn(env);
        ApiGatewayClient client = stubClient();
        when(apiGatewayClientProvider.getObject()).thenReturn(client);
        when(authStrategyFactory.getAuthProfile(env)).thenReturn(profile());

        GatewayBudget budget = registry.getClients("env-1").getGatewayBudget();

        assertThat(budget).isNotNull();
        assertThat(budget.snapshot().productCode()).isNull();
    }
}
