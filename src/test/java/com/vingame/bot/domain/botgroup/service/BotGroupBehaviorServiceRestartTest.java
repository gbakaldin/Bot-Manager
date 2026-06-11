package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Restart-lifecycle tests for {@link BotGroupBehaviorService#restart(String)}.
 * <p>
 * Locks in two regressions:
 * <ol>
 *   <li>Restart must recreate bots after stop — symmetry between initial start and post-restart start.</li>
 *   <li>Restart must fail loudly when {@code start} produces zero bots while {@code botCount > 0}
 *       (RESTART_LIFECYCLE_FIX Architecture Decision 6). The original symptom — 18/18 bots auto-started
 *       cleanly, all 18 silently fail on restart — must surface as an exception, not a silent zero-bot
 *       runtime with {@code targetStatus=ACTIVE}.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupBehaviorService - restart lifecycle")
class BotGroupBehaviorServiceRestartTest {

    @Mock
    private BotGroupService botGroupService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private GameService gameService;

    @Mock
    private BotFactory botFactory;

    @Mock
    private BotMetrics botMetrics;

    @InjectMocks
    private BotGroupBehaviorService service;

    @BeforeEach
    void initConfigFields() {
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 10);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 5);
    }

    @AfterEach
    void shutdownExecutors() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
        }
    }

    private static Bot stubBot(String username) {
        Bot b = mock(Bot.class);
        lenient().when(b.getUserName()).thenReturn(username);
        lenient().when(b.getTotalBetsPlaced()).thenReturn(new AtomicLong(0));
        lenient().when(b.getTotalBetAmount()).thenReturn(new AtomicLong(0));
        return b;
    }

    @Test
    @DisplayName("restart() recreates bots after stop — second start invokes the factory again for every bot")
    void restart_recreatesBotsAfterStop() {
        BotGroup group = BotGroup.builder()
                .id("g-1")
                .name("Group")
                .environmentId("env-1")
                .gameId("game-1")
                .botCount(3)
                .namePrefix("bot")
                .password("pass")
                .build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        // Each createBot call returns a fresh mock bot — total 6 calls over start + restart's start.
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        // First start
        service.start("g-1");
        verify(botFactory, times(3)).createBot(anyString(), any(BotConfiguration.class));

        // Restart → stop, then start again. Second start should drive 3 more factory calls.
        service.restart("g-1");
        verify(botFactory, atLeast(6)).createBot(anyString(), any(BotConfiguration.class));
    }

    @Test
    @DisplayName("restart() throws IllegalStateException when zero bots are produced for a non-zero botCount")
    void restart_failsLoudlyWhenZeroBotsCreated() {
        BotGroup group = BotGroup.builder()
                .id("g-1")
                .name("Group")
                .environmentId("env-1")
                .gameId("game-1")
                .botCount(3)
                .namePrefix("bot")
                .password("pass")
                .build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);

        // First start succeeds (3 bots). Then on restart, every createBot throws.
        AtomicLong callCount = new AtomicLong(0);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> {
                    long n = callCount.incrementAndGet();
                    if (n <= 3) {
                        return stubBot("bot-" + n);
                    }
                    throw new RuntimeException("auth failed for bot " + n);
                });

        service.start("g-1");

        // restart() must throw — silent zero-bot completion is the bug we are fixing.
        assertThatThrownBy(() -> service.restart("g-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0/3");
    }
}
