package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import com.vingame.bot.infrastructure.gateway.CapturedBlockPage;
import com.vingame.bot.infrastructure.gateway.CloudflareBlockDetector;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA, GATEWAY_REQUEST_BUDGET Phase 5: the WebSocket half of block detection against a <b>real</b>
 * {@link VingameWebSocketClient} and the loopback {@link StubGateway} in block mode.
 * <p>
 * <b>Why this exists next to {@code BotWsEdgeBlockTest}.</b> That test mocks {@code connect()} to
 * throw a hand-built Netty {@code WebSocketClientHandshakeException}. Whether the pinned ws-parser
 * (3.0.5) actually surfaces the edge's 403 that way — with the status and headers still attached,
 * through {@code handshakeFuture().sync()}, past {@code HttpObjectAggregator(8192)} — was an
 * assumption in both the test and the production javadoc. If it does not, the WS entry point of
 * AD-13 is dead code in production while its unit test stays green. Here the library makes the
 * upgrade request itself, the stub answers it with the captured page, and the bot's own
 * {@code connectUnderBudget} classifies whatever the library throws.
 * <p>
 * Loopback only: the stub binds {@code 127.0.0.1} on an ephemeral port.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("QA — a real ws-parser upgrade refused by the (stub) Cloudflare edge is classified as a block")
class BotWsEdgeBlockLoopbackTest {

    private StubGateway stub;
    private final List<VingameWebSocketClient> clients = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        stub = StubGateway.start();
    }

    @AfterEach
    void tearDown() {
        clients.forEach(c -> {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // best effort: a never-connected client
            }
        });
        stub.close();
    }

    private ClientFactory factory() {
        ClientFactory factory = new ClientFactory() {
            @Override
            public VingameWebSocketClient newClient(TokensProvider tokens, String name) {
                VingameWebSocketClient client = super.newClient(tokens, name);
                clients.add(client);
                return client;
            }
        };
        factory.setUri(URI.create(stub.baseUrl().replace("http://", "ws://") + "/websocket"));
        factory.setZoneName("MiniGame");
        return factory;
    }

    private static TokensProvider tokens() {
        return TokensProvider.of("18-agency-0123456789", "session-0123456789", null);
    }

    @Test
    @DisplayName("the library's own handshake failure carries the edge's 403 and cf-ray")
    void theLibraryHandshakeFailureIsClassifiedAsABlock() {
        stub.block();
        VingameWebSocketClient client = factory().newClient(tokens(), "authtestws1");

        Throwable thrown = catchThrowable(client::connect);

        assertThat(thrown).as("a refused upgrade must surface from connect(), not be swallowed").isNotNull();
        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classifyHandshakeFailure(thrown);
        assertThat(verdict.edgeBlock())
                .as("what ws-parser 3.0.5 really throws for the captured page — %s", thrown)
                .isTrue();
        assertThat(verdict.cfRay()).isEqualTo(CapturedBlockPage.CF_RAY);
        assertThat(stub.countFor("/websocket")).isEqualTo(1);
    }

    @Test
    @DisplayName("an upgrade the origin answers with something other than a block page is not a block")
    void aNonBlockHandshakeFailureIsNotABlock() {
        // Unblocked, the stub answers the upgrade like any non-gateway path: HTTP 200 JSON, no
        // 101. The upgrade still fails — and must not be read as the edge refusing us.
        VingameWebSocketClient client = factory().newClient(tokens(), "authtestws1");

        Throwable thrown = catchThrowable(client::connect);

        assertThat(thrown).isNotNull();
        assertThat(CloudflareBlockDetector.classifyHandshakeFailure(thrown).edgeBlock()).isFalse();
    }

    @Test
    @DisplayName("end to end: Bot.initialize under enforce opens the circuit from a real refused upgrade")
    void aBotMeetingTheBlockOnItsUpgradeOpensTheCircuit() {
        stub.block();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), meters, System::nanoTime);
        ApiGatewayClient api = mock(ApiGatewayClient.class);
        when(api.getApiGateway()).thenReturn(stub.baseUrl());
        TokensProvider tokens = tokens();
        when(api.authenticate(any(), any(), any())).thenReturn(tokens);

        BotGatewayTierTest.TierBot bot = new BotGatewayTierTest.TierBot();
        bot.setClients(api, mock(GameMsClient.class), factory());
        bot.setGatewayBudget(budget);
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("authtestws1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(Game.builder().id("g1").name("BauCua").pluginName("Plugin")
                        .gameType(GameType.BETTING_MINI).offset(2000).numberOfOptions(6).build())
                .zoneName("MiniGame").timeoutMillis(1000L).watchdogTimeoutSeconds(120L)
                .build());
        try {
            assertThatThrownBy(bot::initialize)
                    .isInstanceOfSatisfying(GatewayCircuitOpenException.class,
                            e -> assertThat(e.getCfRay()).isEqualTo(CapturedBlockPage.CF_RAY));
            assertThat(budget.snapshot().circuitOpen()).isTrue();
            assertThat(meters.get(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL)
                    .tags("endpoint", "ws-upgrade").counter().count()).isEqualTo(1.0);
            assertThat(stub.totalReceived())
                    .as("exactly the one upgrade that met the block left the JVM")
                    .isEqualTo(1);
        } finally {
            bot.cleanup();
            budget.shutdown();
        }
    }
}
