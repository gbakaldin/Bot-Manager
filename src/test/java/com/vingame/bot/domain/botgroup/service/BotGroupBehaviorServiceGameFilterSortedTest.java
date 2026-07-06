package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.domain.game.sort.GameSortRow;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 5 — {@code BotGroupBehaviorService.filterGamesSorted}.
 * Verifies the load → enrich (aggregate over referencing groups) → sort wiring
 * (AD-11): games come from {@link GameService#filter}, the bot-group aggregates come
 * from {@link BotGroupService#findByGameId} (keyed on the Game Mongo {@code _id}), and
 * the enriched rows are returned in sorted order. No runtimes are registered here, so
 * the active-* aggregates are 0 (→ N/A); the configured aggregates are asserted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupBehaviorService.filterGamesSorted (Phase 5)")
class BotGroupBehaviorServiceGameFilterSortedTest {

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
    @DisplayName("Loads env-scoped games, aggregates over referencing groups, sorts in-memory")
    void loadsEnrichesSorts() {
        Game gameA = Game.builder().id("g-a").name("Alpha").build();
        Game gameB = Game.builder().id("g-b").name("Bravo").build();

        GameFilter filter = new GameFilter();
        filter.setSortBy("BOT_COUNT");
        filter.setSortDir("desc");

        when(gameService.filter(eq(BrandCode.G2), eq(ProductCode.P_097), eq("env-1"), any(GameFilter.class)))
                .thenReturn(List.of(gameA, gameB));
        // g-a: two groups, botCount 5 + 15 = 20
        when(botGroupService.findByGameId("g-a")).thenReturn(List.of(
                BotGroup.builder().id("grp-a1").gameId("g-a").botCount(5).build(),
                BotGroup.builder().id("grp-a2").gameId("g-a").botCount(15).build()));
        // g-b: one group, botCount 3
        when(botGroupService.findByGameId("g-b")).thenReturn(List.of(
                BotGroup.builder().id("grp-b1").gameId("g-b").botCount(3).build()));

        List<GameSortRow> rows = service.filterGamesSorted(BrandCode.G2, ProductCode.P_097, "env-1", filter);

        // BOT_COUNT desc → g-a (20) before g-b (3).
        assertThat(rows).extracting(r -> r.game().getId()).containsExactly("g-a", "g-b");
        // Aggregates computed over the referencing groups.
        assertThat(rows.get(0).botGroupCount()).isEqualTo(2);
        assertThat(rows.get(0).botCount()).isEqualTo(20);
        assertThat(rows.get(1).botGroupCount()).isEqualTo(1);
        assertThat(rows.get(1).botCount()).isEqualTo(3);
        // No runtimes registered → inactive.
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.activeGroupCount()).isZero();
            assertThat(r.activeBotCount()).isZero();
            assertThat(r.active()).isFalse();
        });
        verify(botGroupService, times(1)).findByGameId("g-a");
        verify(botGroupService, times(1)).findByGameId("g-b");
    }

    @Test
    @DisplayName("An unknown sortBy propagates as BadRequestException (400) through the real sort path")
    void unknownSortKeyPropagates() {
        Game gameA = Game.builder().id("g-a").name("Alpha").build();

        when(gameService.filter(any(), any(), eq("env-1"), any(GameFilter.class)))
                .thenReturn(List.of(gameA));
        when(botGroupService.findByGameId("g-a")).thenReturn(List.of());

        GameFilter filter = new GameFilter();
        filter.setSortBy("nonsense");

        assertThatThrownBy(() -> service.filterGamesSorted(BrandCode.G2, ProductCode.P_097, "env-1", filter))
                .isInstanceOf(com.vingame.bot.common.exception.BadRequestException.class)
                .hasMessageContaining("nonsense");
    }

    @Test
    @DisplayName("ACTIVE_* aggregates count ONLY running groups; configured BOT_COUNT sums ALL referencing groups")
    void activeAggregatesCountRunningOnly() {
        Game active = Game.builder().id("g-active").name("Active").build();
        Game idle = Game.builder().id("g-idle").name("Idle").build();

        // g-active is referenced by three groups: two running, one stopped.
        // Configured botCount is summed over ALL three (10+5+8 = 23). The active-*
        // aggregates count only the running two: 2 groups, 4+2 = 6 running bots.
        BotGroup a1 = BotGroup.builder().id("grp-a1").gameId("g-active").botCount(10).build();
        BotGroup a2 = BotGroup.builder().id("grp-a2").gameId("g-active").botCount(5).build();
        BotGroup a3 = BotGroup.builder().id("grp-a3").gameId("g-active").botCount(8).build();
        // g-idle: one referencing group, not running.
        BotGroup b1 = BotGroup.builder().id("grp-b1").gameId("g-idle").botCount(4).build();

        when(gameService.filter(any(), any(), eq("env-1"), any(GameFilter.class)))
                .thenReturn(List.of(active, idle));
        when(botGroupService.findByGameId("g-active")).thenReturn(List.of(a1, a2, a3));
        when(botGroupService.findByGameId("g-idle")).thenReturn(List.of(b1));

        // Register runtimes: grp-a1 (4 running bots) and grp-a2 (2 running bots) are
        // ACTIVE; grp-a3 and grp-b1 have no runtime (not running).
        registerRunningGroup("grp-a1", 4);
        registerRunningGroup("grp-a2", 2);
        try {
            GameFilter filter = new GameFilter();
            filter.setSortBy("ACTIVE_BOT_COUNT");
            filter.setSortDir("desc");

            List<GameSortRow> rows = service.filterGamesSorted(BrandCode.G2, ProductCode.P_097, "env-1", filter);

            // Active game (present value) sorts before the idle game (N/A → bottom).
            assertThat(rows).extracting(r -> r.game().getId()).containsExactly("g-active", "g-idle");

            GameSortRow activeRow = rows.get(0);
            assertThat(activeRow.botGroupCount()).isEqualTo(3);       // all referencing groups
            assertThat(activeRow.botCount()).isEqualTo(23);          // Σ configured over ALL three
            assertThat(activeRow.activeGroupCount()).isEqualTo(2);   // only the two running
            assertThat(activeRow.activeBotCount()).isEqualTo(6);     // 4 + 2 running bots
            assertThat(activeRow.active()).isTrue();

            GameSortRow idleRow = rows.get(1);
            assertThat(idleRow.botGroupCount()).isEqualTo(1);
            assertThat(idleRow.botCount()).isEqualTo(4);             // configured, even though inactive
            assertThat(idleRow.activeGroupCount()).isZero();
            assertThat(idleRow.activeBotCount()).isZero();
            assertThat(idleRow.active()).isFalse();
        } finally {
            removeRunningGroup("grp-a1");
            removeRunningGroup("grp-a2");
        }
    }

    /* ---- runtime-injection helpers (mirror the Phase 3 stats test) ---- */

    /**
     * Register an ACTIVE runtime for {@code groupId} with {@code runningBots}
     * non-completed bot futures, so {@code isGroupRunning} is true and
     * {@code getRunningBotCountForGroup} returns {@code runningBots}.
     */
    private void registerRunningGroup(String groupId, int runningBots) {
        BotGroupRuntime runtime = new BotGroupRuntime(groupId, runningBots, "env-1");
        try {
            Field f = BotGroupRuntime.class.getDeclaredField("botFutures");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Future<?>> futures = (List<Future<?>>) f.get(runtime);
            futures.clear();
            for (int i = 0; i < runningBots; i++) {
                futures.add(new CompletableFuture<>()); // never completed → isDone() == false
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        runningGroups().put(groupId, runtime);
    }

    private void removeRunningGroup(String groupId) {
        BotGroupRuntime runtime = runningGroups().remove(groupId);
        if (runtime != null) {
            runtime.getExecutor().shutdownNow();
        }
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
}
