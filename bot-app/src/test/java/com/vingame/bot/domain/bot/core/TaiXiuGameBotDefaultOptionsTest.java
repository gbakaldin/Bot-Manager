package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the staging Tai Xiu init crash: a Tai Xiu game created with
 * <b>no option config</b> (neither {@code optionAffinities} nor legacy
 * {@code numberOfOptions}) threw {@code IllegalStateException: ... has neither
 * optionAffinities nor legacy numberOfOptions set} from
 * {@link Game#getEffectiveOptionAffinities()} on the shared init path
 * ({@code BettingMiniGameBot.initializeSubclass} log line, and every later
 * {@code ctx.game().getEffectiveOptionAffinities()} strategy read).
 *
 * <p>Tai Xiu is intrinsically a 2-option game (Tài vs Xỉu, eids {@code 1}/{@code 2}),
 * so {@link TaiXiuGameBot#initializeSubclass()} now defaults the game to 2 equal
 * options via {@link Game#applyTaiXiuOptionDefaults()} before the inherited init
 * runs. This test proves the default applies and the init no longer throws, while
 * confirming the default is <b>Tai-Xiu-scoped</b>: a BettingMini game with no
 * option config still throws (no behavior change for other game types).
 */
@DisplayName("TaiXiuGameBot default 2-option config (staging regression)")
class TaiXiuGameBotDefaultOptionsTest {

    private TaiXiuGameBot bot;

    private TaiXiuGameBot buildBot(Game game) {
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(1_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(2_000_000).minBetsPerRound(1).maxBetsPerRound(1)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("taixiubot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        BettingStrategy fixed = new BettingStrategy() {
            @Override
            public Optional<BetDecision> decide(BetContext context) {
                return Optional.of(new BetDecision(1, 500_000L));
            }
            @Override
            public void onRoundEnd(RoundResult result) { /* no-op */ }
        };
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(fixed);

        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setStrategyFactory(factory);
        b.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        b.setRandom(new Random(0L));
        return b;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bot == null) return;
        ScheduledExecutorService w = (ScheduledExecutorService) readField(bot, "watchdogScheduler");
        if (w != null) w.shutdownNow();
    }

    @Test
    @DisplayName("Tai Xiu game with NO option config initializes with 2 effective options and does not throw")
    void taiXiu_noOptionConfig_defaultsToTwoOptions() {
        // Reproduces the staging config: a Tai Xiu game with neither
        // optionAffinities nor numberOfOptions set.
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .build();
        assertThat(game.getOptionAffinities()).as("precondition: no optionAffinities").isNull();

        bot = buildBot(game);

        // Before the fix this threw IllegalStateException on the
        // game.getEffectiveOptionAffinities() read inside the inherited init.
        assertThatCode(bot::initializeSubclass).doesNotThrowAnyException();

        // The shared Game now resolves to exactly the two Tài/Xỉu entries with
        // eids {1, 2} — the values the strategy emits as the bet eid.
        assertThat(game.getEffectiveOptionAffinities())
                .containsOnlyKeys(1, 2)
                .containsValues(1, 1);
        assertThat(game.getEffectiveOptionAffinities()).hasSize(2);
        // memory captures the same defaulted game, so strategy reads see 2 options.
        assertThat(bot.getMemory().getGame().getEffectiveOptionAffinities()).hasSize(2);
    }

    @Test
    @DisplayName("explicit numberOfOptions:2 is left unchanged (operator override wins)")
    void taiXiu_explicitConfig_unchanged() {
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .numberOfOptions(2)
                .build();

        bot = buildBot(game);
        bot.initializeSubclass();

        // Explicit numberOfOptions:2 synthesizes the legacy {0:1, 1:1} range; the
        // Tai Xiu default does NOT overwrite it. The default applies only when no
        // option field is set.
        assertThat(game.getOptionAffinities()).as("default did not overwrite explicit config").isNull();
        assertThat(game.getEffectiveOptionAffinities()).containsOnlyKeys(0, 1);
    }

    @Test
    @DisplayName("Game.applyTaiXiuOptionDefaults defaults to {1:1, 2:1} when no option config")
    void applyTaiXiuOptionDefaults_setsTwoEntries() {
        Game game = Game.builder().id("g").name("TaiXiu").build();
        game.applyTaiXiuOptionDefaults();
        assertThat(game.getEffectiveOptionAffinities()).isEqualTo(Map.of(1, 1, 2, 1));
    }

    @Test
    @DisplayName("BettingMini with NO option config still throws (default is Tai-Xiu-scoped)")
    void bettingMini_noOptionConfig_stillThrows() {
        // The Tai Xiu default is applied only on the Tai Xiu bot path. A
        // BettingMini game with no option config is still a misconfiguration and
        // getEffectiveOptionAffinities() must still throw — no behavior change for
        // other game types.
        Game game = Game.builder()
                .id("g-baucua").name("BauCua").pluginName("baucuaPlugin")
                .offset(2000)
                .build();

        assertThatThrownBy(game::getEffectiveOptionAffinities)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither optionAffinities nor legacy numberOfOptions");
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new IllegalStateException("field not found: " + name);
    }
}
