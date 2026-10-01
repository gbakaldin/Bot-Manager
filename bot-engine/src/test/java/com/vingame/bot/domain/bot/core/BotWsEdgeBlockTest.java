package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.gateway.CapturedBlockPage;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.infrastructure.gateway.RecordingGatewayBudget;
import com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The WebSocket half of block detection (GATEWAY_REQUEST_BUDGET AD-13's second entry point, A15):
 * an upgrade the edge refuses with its 403 page opens the same circuit a login would.
 * <p>
 * No socket: {@code connect()} is a mock that throws the handshake exception Netty would, carrying
 * the edge's status and headers.
 */
@DisplayName("Bot — a WS upgrade refused by the Cloudflare edge opens the circuit")
class BotWsEdgeBlockTest {

    private VingameWebSocketClient wsClient;
    private ApiGatewayClient apiGatewayClient;
    private ClientFactory clientFactory;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        clientFactory = mock(ClientFactory.class);
        wsClient = mock(VingameWebSocketClient.class);
        TokensProvider tokens = mock(TokensProvider.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");
        when(apiGatewayClient.authenticate(any(), any(), any())).thenReturn(tokens);
        when(tokens.getAgencyToken()).thenReturn("18-aaaaaaaaaaaaaaaa");
        when(tokens.getAuthToken()).thenReturn("session-bbbbbbbbbb");
        when(clientFactory.newClient(any(), anyString())).thenReturn(wsClient);
    }

    private BotGatewayTierTest.TierBot bot(GatewayBudget budget) {
        BotGatewayTierTest.TierBot bot = new BotGatewayTierTest.TierBot();
        bot.setClients(apiGatewayClient, mock(GameMsClient.class), clientFactory);
        bot.setGatewayBudget(budget);
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("authtestws1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(Game.builder().id("g1").name("BauCua").pluginName("Plugin")
                        .gameType(com.vingame.bot.domain.game.model.GameType.BETTING_MINI)
                        .offset(2000).numberOfOptions(6).build())
                .zoneName("MiniGame").timeoutMillis(1000L).watchdogTimeoutSeconds(120L)
                .build());
        return bot;
    }

    private static WebSocketClientHandshakeException handshake(HttpResponseStatus status, boolean cloudflarePage) {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.add("server", "cloudflare");
        headers.add("cf-ray", CapturedBlockPage.CF_RAY);
        headers.add("content-type", cloudflarePage ? "text/html; charset=UTF-8" : "application/json");
        return new WebSocketClientHandshakeException("Invalid handshake response getStatus: " + status,
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, status, headers));
    }

    @Test
    @DisplayName("enforce: the block opens the circuit and the bot sees a budget outcome, not a handshake error")
    void enforceOpensTheCircuit() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), meters, System::nanoTime);
        try {
            doThrow(handshake(HttpResponseStatus.FORBIDDEN, true)).when(wsClient).connect();

            assertThatThrownBy(() -> bot(budget).initialize())
                    .as("AD-9: a GatewayBudgetException is non-terminal for a bot and classifies as "
                            + "\"budget\" at creation — a raw handshake error would not")
                    .isInstanceOfSatisfying(GatewayCircuitOpenException.class,
                            e -> assertThat(e.getCfRay()).isEqualTo(CapturedBlockPage.CF_RAY));
            assertThat(budget.snapshot().circuitOpen()).isTrue();
            assertThat(meters.get(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL)
                    .tags("endpoint", "ws-upgrade").counter().count()).isEqualTo(1.0);
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("observe: the block is reported and the original handshake failure propagates unchanged")
    void observeChangesNothing() {
        RecordingGatewayBudget budget = new RecordingGatewayBudget();
        WebSocketClientHandshakeException refused = handshake(HttpResponseStatus.FORBIDDEN, true);
        doThrow(refused).when(wsClient).connect();

        assertThatThrownBy(() -> bot(budget).initialize()).isSameAs(refused);
        assertThat(budget.edgeBlocks()).containsExactly("ws-upgrade|" + CapturedBlockPage.CF_RAY);
    }

    @Test
    @DisplayName("a handshake refused for any other reason is not reported as a block")
    void otherFailuresAreNotBlocks() {
        RecordingGatewayBudget budget = new RecordingGatewayBudget();
        WebSocketClientHandshakeException badGateway = handshake(HttpResponseStatus.BAD_GATEWAY, true);
        doThrow(badGateway).when(wsClient).connect();
        assertThatThrownBy(() -> bot(budget).initialize()).isSameAs(badGateway);

        WebSocketClientHandshakeException jsonForbidden = handshake(HttpResponseStatus.FORBIDDEN, false);
        doThrow(jsonForbidden).when(wsClient).connect();
        assertThatThrownBy(() -> bot(budget).initialize()).isSameAs(jsonForbidden);

        assertThat(budget.edgeBlocks()).isEmpty();
    }
}
