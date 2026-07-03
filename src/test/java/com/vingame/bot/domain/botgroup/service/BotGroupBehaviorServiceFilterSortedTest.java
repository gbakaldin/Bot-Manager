package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.sort.BotGroupSortRow;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 4 — {@code BotGroupBehaviorService.filterSorted}.
 * Verifies the load → enrich → sort wiring (AD-11):
 * <ul>
 *   <li>env-scoped groups come from {@link BotGroupService#filter};</li>
 *   <li>the resolved {@code gameType} is looked up once per <em>distinct</em> gameId;</li>
 *   <li>a missing game resolves to a {@code null} gameType (N/A) without aborting;</li>
 *   <li>rows are returned in sorted order (in-memory).</li>
 * </ul>
 * Groups here are not running, so their stats are the all-null Phase-3 block.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupBehaviorService.filterSorted (Phase 4)")
class BotGroupBehaviorServiceFilterSortedTest {

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
    @DisplayName("Loads env-scoped groups, resolves gameType once per distinct id, sorts in-memory")
    void loadsEnrichesSorts() {
        BotGroup g1 = BotGroup.builder().id("g1").name("Charlie").gameId("game-slot").build();
        BotGroup g2 = BotGroup.builder().id("g2").name("Alpha").gameId("game-betting").build();
        BotGroup g3 = BotGroup.builder().id("g3").name("Bravo").gameId("game-slot").build(); // same gameId as g1

        BotGroupFilter filter = new BotGroupFilter();
        filter.setSortBy("NAME");
        filter.setSortDir("asc");

        when(botGroupService.filter(eq("env-1"), any(BotGroupFilter.class)))
                .thenReturn(List.of(g1, g2, g3));
        when(gameService.findById("game-slot"))
                .thenReturn(Game.builder().id("game-slot").gameType(GameType.SLOT).build());
        when(gameService.findById("game-betting"))
                .thenReturn(Game.builder().id("game-betting").gameType(GameType.BETTING_MINI).build());

        List<BotGroupSortRow> rows = service.filterSorted("env-1", filter);

        // Sorted by NAME asc.
        assertThat(rows).extracting(r -> r.group().getId()).containsExactly("g2", "g3", "g1");
        // gameType resolved and attached.
        assertThat(rows).extracting(BotGroupSortRow::gameType)
                .containsExactly("BETTING_MINI", "SLOT", "SLOT");
        // Not running → all-null stats block (non-null DTO).
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.stats()).isNotNull();
            assertThat(r.stats().getActiveBots()).isNull();
        });
        // "game-slot" is shared by g1 and g3 → looked up exactly once.
        verify(gameService, times(1)).findById("game-slot");
        verify(gameService, times(1)).findById("game-betting");
    }

    @Test
    @DisplayName("An unknown sortBy propagates as BadRequestException (400) through the real sort path")
    void unknownSortKeyPropagates() {
        BotGroup g1 = BotGroup.builder().id("g1").name("Alpha").build();

        when(botGroupService.filter(eq("env-1"), any(BotGroupFilter.class)))
                .thenReturn(List.of(g1));

        BotGroupFilter filter = new BotGroupFilter();
        filter.setSortBy("nonsense");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.filterSorted("env-1", filter))
                .isInstanceOf(com.vingame.bot.common.exception.BadRequestException.class)
                .hasMessageContaining("nonsense");
    }

    @Test
    @DisplayName("A missing referenced game resolves to null gameType (N/A) without aborting the filter")
    void missingGameYieldsNullType() {
        BotGroup g1 = BotGroup.builder().id("g1").name("Alpha").gameId("gone").build();

        when(botGroupService.filter(eq("env-1"), any(BotGroupFilter.class)))
                .thenReturn(List.of(g1));
        when(gameService.findById("gone"))
                .thenThrow(new ResourceNotFoundException("Game not found with id: gone"));

        List<BotGroupSortRow> rows = service.filterSorted("env-1", new BotGroupFilter());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).gameType()).isNull();
    }
}
