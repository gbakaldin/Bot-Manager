package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 3 — {@code BotGroupBehaviorService.computeStats}.
 * Pins the load-bearing decisions:
 * <ul>
 *   <li>AD-13: a non-running group (no runtime) yields an all-null (all-N/A) block.</li>
 *   <li>AD-9: {@code roundsSinceRestart} = MAX of per-bot {@code roundsObserved};
 *       {@code activeTimeSeconds} derived from {@code startedAt}.</li>
 *   <li>AD-10 / Implementation Note 5: {@code averageBalance} / {@code averageWinning}
 *       are means over {@code isConnected()} bots ONLY, and {@code null} (never 0)
 *       when zero bots are active — even for a live runtime.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupBehaviorService.computeStats (Phase 3)")
class BotGroupBehaviorServiceStatsTest {

    @Mock private BotGroupService botGroupService;
    @Mock private EnvironmentService environmentService;
    @Mock private GameService gameService;
    @Mock private BotFactory botFactory;
    @Mock private BotMetrics botMetrics;
    @Mock private SessionAggregationService sessionAggregationService;

    @InjectMocks private BotGroupBehaviorService service;

    @BeforeEach
    void initConfigFields() {
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 10);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", true);
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

    @Test
    @DisplayName("No runtime → every field is N/A (null), block itself non-null (AD-13)")
    void noRuntimeAllNull() {
        BotGroupStatsDTO stats = service.computeStats("g-missing");

        assertThat(stats).isNotNull();
        assertThat(stats.getRoundsSinceRestart()).isNull();
        assertThat(stats.getActiveTimeSeconds()).isNull();
        assertThat(stats.getActiveBots()).isNull();
        assertThat(stats.getAverageBalance()).isNull();
        assertThat(stats.getAverageWinning()).isNull();
    }

    @Test
    @DisplayName("roundsSinceRestart = MAX of per-bot roundsObserved; averages over connected bots only (AD-9/AD-10)")
    void runningGroupComputesMaxRoundsAndActiveOnlyAverages() {
        BotGroupRuntime runtime = new BotGroupRuntime("g-1", 3, "env-1");
        try {
            // Two connected bots (counted in averages), one disconnected (excluded).
            // Rounds is the MAX across ALL bots — even the disconnected one contributes.
            Bot connectedA = statsBot(true, 10_000L, 500L, 7L);
            Bot connectedB = statsBot(true, 30_000L, 1_500L, 4L);
            Bot disconnected = statsBot(false, 999_999L, 999_999L, 42L);

            putBots(runtime, List.of(connectedA, connectedB, disconnected));
            runningGroups().put("g-1", runtime);

            BotGroupStatsDTO stats = service.computeStats("g-1");

            // MAX roundsObserved across all bots = 42 (the disconnected bot's counter
            // still counts — dedup-free max, robust to subscriber pruning).
            assertThat(stats.getRoundsSinceRestart()).isEqualTo(42L);
            // Only the 2 connected bots are active.
            assertThat(stats.getActiveBots()).isEqualTo(2);
            // Averages over connected bots ONLY: balance = (10000+30000)/2 = 20000,
            // winning = (500+1500)/2 = 1000. The disconnected bot's huge values must
            // NOT leak in.
            assertThat(stats.getAverageBalance()).isEqualTo(20_000L);
            assertThat(stats.getAverageWinning()).isEqualTo(1_000L);
            // Active time is derived from startedAt (set at construction).
            assertThat(stats.getActiveTimeSeconds()).isNotNull().isGreaterThanOrEqualTo(0L);
        } finally {
            runtime.getExecutor().shutdownNow();
            runningGroups().remove("g-1");
        }
    }

    @Test
    @DisplayName("Live runtime with ZERO connected bots → averages null (N/A), not 0; rounds still computed (Note 5)")
    void liveRuntimeAllReconnectingYieldsNullAverages() {
        BotGroupRuntime runtime = new BotGroupRuntime("g-1", 2, "env-1");
        try {
            // Both bots disconnected (e.g. all reconnecting) but they DID observe rounds.
            Bot d1 = statsBot(false, 12_000L, 800L, 3L);
            Bot d2 = statsBot(false, 8_000L, 200L, 9L);

            putBots(runtime, List.of(d1, d2));
            runningGroups().put("g-1", runtime);

            BotGroupStatsDTO stats = service.computeStats("g-1");

            assertThat(stats.getActiveBots()).isEqualTo(0);
            // Averages must be N/A (null), never 0 — a live runtime whose bots are all
            // reconnecting has no meaningful mean balance/winning.
            assertThat(stats.getAverageBalance()).isNull();
            assertThat(stats.getAverageWinning()).isNull();
            // Rounds is still the max over all bots regardless of connection state.
            assertThat(stats.getRoundsSinceRestart()).isEqualTo(9L);
            assertThat(stats.getActiveTimeSeconds()).isNotNull();
        } finally {
            runtime.getExecutor().shutdownNow();
            runningGroups().remove("g-1");
        }
    }

    @Test
    @DisplayName("Running group with no bots → rounds 0, activeBots 0, averages null")
    void runningGroupNoBots() {
        BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
        try {
            runningGroups().put("g-1", runtime);

            BotGroupStatsDTO stats = service.computeStats("g-1");

            assertThat(stats.getRoundsSinceRestart()).isEqualTo(0L);
            assertThat(stats.getActiveBots()).isEqualTo(0);
            assertThat(stats.getAverageBalance()).isNull();
            assertThat(stats.getAverageWinning()).isNull();
            assertThat(stats.getActiveTimeSeconds()).isNotNull();
        } finally {
            runtime.getExecutor().shutdownNow();
            runningGroups().remove("g-1");
        }
    }

    /* ---- helpers ---- */

    private static Bot statsBot(boolean connected, long balance, long cumulativeWinnings, long roundsObserved) {
        Bot b = mock(Bot.class);
        lenient().when(b.isConnected()).thenReturn(connected);
        lenient().when(b.getExpectedBalance()).thenReturn(balance);
        lenient().when(b.getCumulativeWinnings()).thenReturn(new AtomicLong(cumulativeWinnings));
        lenient().when(b.getRoundsObserved()).thenReturn(new AtomicLong(roundsObserved));
        return b;
    }

    @SuppressWarnings("unchecked")
    private Map<String, BotGroupRuntime> runningGroups() {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("runningGroups");
            f.setAccessible(true);
            return (Map<String, BotGroupRuntime>) f.get(service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void putBots(BotGroupRuntime runtime, List<Bot> bots) {
        try {
            Field f = BotGroupRuntime.class.getDeclaredField("botInstances");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Bot> list = (List<Bot>) f.get(runtime);
            list.clear();
            list.addAll(bots);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
