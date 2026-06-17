package com.vingame.bot.domain.bot.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the fail-loud-on-null-resolvedZoneName branch of
 * {@link BotFactory#createBot(String, BotConfiguration)} introduced by
 * RESTART_LIFECYCLE_FIX Architecture Decision 4.
 * <p>
 * The default branch ({@code customZone=false}) cannot produce null because the
 * constants in {@link Environment} are non-null. The only way to land in the
 * fail-loud branch is {@code customZone=true} with a blank/null custom field.
 * That is a real env misconfiguration and must surface with a message that names
 * the environment id, game type, customZone flag, and which custom field was
 * blank — so the operator can fix the env without grepping every bot's auth log.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotFactory - fail-loud on null/blank resolved zoneName")
class BotFactoryFailLoudTest {

    @Mock
    private EnvironmentClientRegistry clientRegistry;

    @Mock
    private EventLoopGroup eventLoopGroup;

    @Mock
    private BotMetrics botMetrics;

    @Mock
    private BettingStrategyFactory strategyFactory;

    private BotFactory factory() {
        return new BotFactory(clientRegistry, eventLoopGroup, botMetrics, strategyFactory);
    }

    private static EnvironmentClients envClientsWith(Environment env) {
        return new EnvironmentClients(
                env.getId(),
                mock(ApiGatewayClient.class),
                mock(GameMsClient.class),
                mock(ClientFactory.class),
                env);
    }

    private static BotConfiguration configFor(Game game) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("bot1").password("p").build())
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .game(game)
                .zoneName("ignored")
                .build();
    }

    @Test
    @DisplayName("customZone=true + mini game + blank miniZoneName → IllegalStateException naming the missing field")
    void createBot_throwsWhenCustomZoneTrueAndMiniZoneBlank_mini() {
        Environment env = Environment.builder()
                .id("env-1")
                .webSocketMiniUrl("ws://example/ws")
                .customZone(true)
                .miniZoneName("")
                .cardZoneName("MyCard")
                .build();
        Game game = Game.builder().id("game-1").gameType(GameType.BETTING_MINI).build();

        when(clientRegistry.getClients("env-1")).thenReturn(envClientsWith(env));

        assertThatThrownBy(() -> factory().createBot("env-1", configFor(game)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved zoneName is null/blank")
                .hasMessageContaining("environmentId=env-1")
                .hasMessageContaining("gameType=BETTING_MINI")
                .hasMessageContaining("customZone=true")
                .hasMessageContaining("bot1");
    }

    @Test
    @DisplayName("customZone=true + card game + null cardZoneName → IllegalStateException naming the missing field")
    void createBot_throwsWhenCustomZoneTrueAndCardZoneBlank_card() {
        Environment env = Environment.builder()
                .id("env-1")
                .webSocketMiniUrl("ws://example/ws")
                .customZone(true)
                .miniZoneName("MyMini")
                .cardZoneName(null)
                .build();
        Game game = Game.builder().id("game-1").gameType(GameType.CARD_GAME).build();

        when(clientRegistry.getClients("env-1")).thenReturn(envClientsWith(env));

        assertThatThrownBy(() -> factory().createBot("env-1", configFor(game)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved zoneName is null/blank")
                .hasMessageContaining("environmentId=env-1")
                .hasMessageContaining("gameType=CARD_GAME")
                .hasMessageContaining("customZone=true")
                .hasMessageContaining("bot1");
    }

    @Test
    @DisplayName("customZone=true + miniZoneName=\"   \" (whitespace only) → fails loud (isBlank check, not isEmpty)")
    void createBot_throwsWhenCustomZoneTrueAndMiniZoneIsWhitespace() {
        Environment env = Environment.builder()
                .id("env-1")
                .webSocketMiniUrl("ws://example/ws")
                .customZone(true)
                .miniZoneName("   ")
                .cardZoneName("MyCard")
                .build();
        Game game = Game.builder().id("game-1").gameType(GameType.BETTING_MINI).build();

        when(clientRegistry.getClients("env-1")).thenReturn(envClientsWith(env));

        assertThatThrownBy(() -> factory().createBot("env-1", configFor(game)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved zoneName is null/blank");
    }
}
