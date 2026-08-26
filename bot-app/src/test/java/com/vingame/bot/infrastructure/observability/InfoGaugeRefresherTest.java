package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvStatusKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameStatusKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GroupBalance;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.PluginVersionKey;
import com.vingame.bot.infrastructure.observability.InfoGaugeRefresher.InfoGauges;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Phase 2 / Phase 3 info join gauges and per-game / per-env status
 * MultiGauges: that they carry the right label sets, that the status MultiGauges
 * break bot counts down by status per game and per env, that the {@link BotMdcTagsMeterFilter}
 * keeps them free of MDC tags, and that stale rows drop on refresh.
 * <p>
 * Drives {@link InfoGaugeRefresher#refresh} directly (no scheduling thread) so each
 * assertion is deterministic.
 */
class InfoGaugeRefresherTest {

    private MeterRegistry registry;
    private BotGroupBehaviorService behaviorService;
    private InfoGauges gauges;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        behaviorService = mock(BotGroupBehaviorService.class);
        gauges = InfoGaugeRefresher.registerInfoGauges(registry);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ---- Phase 2: game_join / environment_join join gauges ----

    @Test
    void gameInfoGauge_carriesGameIdNameAndType_withValueOne() {
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI", "env-uuid-1", "116"),
                new GameInfo("game-uuid-2", "SlotA", "SLOT", "env-uuid-1", "116")));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge bauCua = registry.find("game_join").tag("gameId", "game-uuid-1").gauge();
        assertThat(bauCua).isNotNull();
        assertThat(bauCua.value()).isEqualTo(1.0);
        assertThat(bauCua.getId().getTag("gameName")).isEqualTo("BauCua");
        assertThat(bauCua.getId().getTag("gameType")).isEqualTo("BETTING_MINI");
        assertThat(bauCua.getId().getTag("environmentId")).isEqualTo("env-uuid-1");
        assertThat(bauCua.getId().getTag("product")).isEqualTo("116");

        Gauge slot = registry.find("game_join").tag("gameName", "SlotA").gauge();
        assertThat(slot).isNotNull();
        assertThat(slot.getId().getTag("gameId")).isEqualTo("game-uuid-2");
        assertThat(slot.getId().getTag("gameType")).isEqualTo("SLOT");
        assertThat(slot.getId().getTag("product")).isEqualTo("116");
    }

    @Test
    void environmentInfoGauge_carriesEnvironmentIdAndName_withValueOne() {
        when(behaviorService.listRunningEnvironmentInfo()).thenReturn(List.of(
                new EnvInfo("env-uuid-1", "Staging", "116")));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge env = registry.find("environment_join").tag("environmentId", "env-uuid-1").gauge();
        assertThat(env).isNotNull();
        assertThat(env.value()).isEqualTo(1.0);
        assertThat(env.getId().getTag("environmentName")).isEqualTo("Staging");
        assertThat(env.getId().getTag("product")).isEqualTo("116");
    }

    @Test
    void infoGauges_dropStaleRowsOnRefresh() {
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI", "env-uuid-1", "116")));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("game_join").tag("gameId", "game-uuid-1").gauge()).isNotNull();

        // The game stops: the next refresh must drop its row.
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of());
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("game_join").tag("gameId", "game-uuid-1").gauge()).isNull();
    }

    @Test
    void infoGauges_areOnTheAggregateExclusionList() {
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-xyz");
        MDC.put(BotMdc.GAME_ID, "game-mdc");

        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI", "env-uuid-1", "116")));
        when(behaviorService.listRunningEnvironmentInfo()).thenReturn(List.of(
                new EnvInfo("env-uuid-1", "Staging", "116")));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge game = registry.find("game_join").tag("gameId", "game-uuid-1").gauge();
        assertThat(game).isNotNull();
        assertThat(game.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
        Gauge env = registry.find("environment_join").tag("environmentId", "env-uuid-1").gauge();
        assertThat(env).isNotNull();
        assertThat(env.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
    }

    // ---- Phase 3: per-game / per-env status MultiGauges ----

    @Test
    void botsByGameStatusGauge_breaksDownBotCountsByStatusPerGame() {
        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.CONNECTION_AUTHENTICATED,
                        "env-uuid-1", "BETTING_MINI", "116"), 3,
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.DEAD,
                        "env-uuid-1", "BETTING_MINI", "116"), 1));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge connected = registry.find("bots_by_game_status")
                .tag("gameId", "game-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge();
        assertThat(connected).isNotNull();
        assertThat(connected.value()).isEqualTo(3.0);
        assertThat(connected.getId().getTag("gameName")).isEqualTo("BauCua");
        // GameNoRounds (Phase 3) needs these three on the left operand so the
        // `unless` sides produce identical label sets after sum by(...).
        assertThat(connected.getId().getTag("gameType")).isEqualTo("BETTING_MINI");
        assertThat(connected.getId().getTag("environmentId")).isEqualTo("env-uuid-1");
        assertThat(connected.getId().getTag("product")).isEqualTo("116");

        Gauge dead = registry.find("bots_by_game_status")
                .tag("gameId", "game-uuid-1")
                .tag("status", BotStatus.DEAD.name())
                .gauge();
        assertThat(dead).isNotNull();
        assertThat(dead.value()).isEqualTo(1.0);
    }

    @Test
    void botsByEnvStatusGauge_breaksDownBotCountsByStatusPerEnvironment() {
        when(behaviorService.countBotsByEnvAndStatus()).thenReturn(Map.of(
                new EnvStatusKey("env-uuid-1", BotStatus.CONNECTION_AUTHENTICATED, "116"), 5,
                new EnvStatusKey("env-uuid-1", BotStatus.DEAD, "116"), 2));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge connected = registry.find("bots_by_env_status")
                .tag("environmentId", "env-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge();
        assertThat(connected).isNotNull();
        assertThat(connected.value()).isEqualTo(5.0);
        assertThat(connected.getId().getTag("product")).isEqualTo("116");

        Gauge dead = registry.find("bots_by_env_status")
                .tag("environmentId", "env-uuid-1")
                .tag("status", BotStatus.DEAD.name())
                .gauge();
        assertThat(dead).isNotNull();
        assertThat(dead.value()).isEqualTo(2.0);
    }

    @Test
    void botsByGameStatusGauge_updatesValue_whenCountChangesForExistingTagSet() {
        // Regression for the MultiGauge overwrite=false freeze: a persisting
        // (gameId, gameName, status) row whose count changes between refreshes
        // MUST reflect the new value. Before the fix, register(rows) (overwrite
        // defaulting to false) kept the first-registered value, so a second bot
        // group on an already-tracked game stayed invisible (the "7 active bots"
        // bug — two RIK groups, 7 + 100, gauge stuck at 7).
        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "Tai Xiu Jackpot", BotStatus.CONNECTION_AUTHENTICATED,
                        "env-uuid-1", "TAI_XIU", "114"), 7));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("bots_by_game_status")
                .tag("gameId", "game-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge().value()).isEqualTo(7.0);

        // A second group on the same game brings the live count to 107.
        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "Tai Xiu Jackpot", BotStatus.CONNECTION_AUTHENTICATED,
                        "env-uuid-1", "TAI_XIU", "114"), 107));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("bots_by_game_status")
                .tag("gameId", "game-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge().value()).isEqualTo(107.0);
    }

    @Test
    void botsByEnvStatusGauge_updatesValue_whenCountChangesForExistingTagSet() {
        // Same overwrite=false freeze, env scope: the per-env count must track
        // live state across refreshes for a persisting (environmentId, status) row.
        when(behaviorService.countBotsByEnvAndStatus()).thenReturn(Map.of(
                new EnvStatusKey("env-uuid-1", BotStatus.CONNECTION_AUTHENTICATED, "116"), 7));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("bots_by_env_status")
                .tag("environmentId", "env-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge().value()).isEqualTo(7.0);

        when(behaviorService.countBotsByEnvAndStatus()).thenReturn(Map.of(
                new EnvStatusKey("env-uuid-1", BotStatus.CONNECTION_AUTHENTICATED, "116"), 107));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("bots_by_env_status")
                .tag("environmentId", "env-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge().value()).isEqualTo(107.0);
    }

    @Test
    void statusMultiGauges_areOnTheAggregateExclusionList() {
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-xyz");

        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.DEAD,
                        "env-uuid-1", "BETTING_MINI", "116"), 1));
        when(behaviorService.countBotsByEnvAndStatus()).thenReturn(Map.of(
                new EnvStatusKey("env-uuid-1", BotStatus.DEAD, "116"), 1));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge game = registry.find("bots_by_game_status").tag("gameId", "game-uuid-1").gauge();
        assertThat(game).isNotNull();
        assertThat(game.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
        Gauge env = registry.find("bots_by_env_status").tag("environmentId", "env-uuid-1").gauge();
        assertThat(env).isNotNull();
        assertThat(env.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
    }

    // ---- VIPTALK_ALERTING_V2 Phase 1: per-environment aggregate MultiGauges ----

    @Test
    void botsManagedByEnvGauge_carriesEnvironmentIdAndProduct() {
        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 12,
                new EnvKey("env-uuid-2", "097"), 5));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge tip = registry.find("bots_managed_by_env").tag("environmentId", "env-uuid-1").gauge();
        assertThat(tip).isNotNull();
        assertThat(tip.value()).isEqualTo(12.0);
        assertThat(tip.getId().getTag("product")).isEqualTo("116");

        Gauge bom = registry.find("bots_managed_by_env").tag("environmentId", "env-uuid-2").gauge();
        assertThat(bom).isNotNull();
        assertThat(bom.value()).isEqualTo(5.0);
        assertThat(bom.getId().getTag("product")).isEqualTo("097");
    }

    @Test
    void wsConnectionsOpenByEnvGauge_carriesEnvironmentIdAndProduct_andTracksZero() {
        // A zero row is the case EnvironmentSocketDown must catch: bots are managed
        // but none holds a socket. It must be emitted, not omitted.
        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 12));
        when(behaviorService.countOpenWsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 0));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge open = registry.find("ws_connections_open_by_env")
                .tag("environmentId", "env-uuid-1").gauge();
        assertThat(open).isNotNull();
        assertThat(open.value()).isEqualTo(0.0);
        assertThat(open.getId().getTag("product")).isEqualTo("116");
    }

    @Test
    void perEnvGauges_areOnTheAggregateExclusionList() {
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.PRODUCT, "999");

        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 3));
        when(behaviorService.countOpenWsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 3));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        for (String name : new String[]{"bots_managed_by_env", "ws_connections_open_by_env"}) {
            Gauge g = registry.find(name).tag("environmentId", "env-uuid-1").gauge();
            assertThat(g).as("%s registered", name).isNotNull();
            assertThat(g.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
            // the row's own product wins; the refresher thread's MDC never leaks in
            assertThat(g.getId().getTag(BotMdc.PRODUCT)).isEqualTo("116");
        }
    }

    @Test
    void perEnvGauges_updateValues_whenCountsChangeForExistingTagSet() {
        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 7));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("bots_managed_by_env")
                .tag("environmentId", "env-uuid-1").gauge().value()).isEqualTo(7.0);

        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 107));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("bots_managed_by_env")
                .tag("environmentId", "env-uuid-1").gauge().value()).isEqualTo(107.0);
    }

    // ---- VIPTALK_ALERTING_V2 Phase 4: dead groups per env + per-group balance ----

    @Test
    void groupsDeadByEnvGauge_carriesEnvironmentIdAndProduct_andEmitsTheHealthyZero() {
        when(behaviorService.countDeadGroupsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 2,
                new EnvKey("env-uuid-2", "097"), 0));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge dead = registry.find("groups_dead_by_env").tag("environmentId", "env-uuid-1").gauge();
        assertThat(dead).isNotNull();
        assertThat(dead.value()).isEqualTo(2.0);
        assertThat(dead.getId().getTag("product")).isEqualTo("116");

        // The healthy environment keeps a series: EnvironmentGroupDead reads
        // `groups_dead_by_env > 0`, and a dashboard should show 0, not a gap.
        Gauge healthy = registry.find("groups_dead_by_env").tag("environmentId", "env-uuid-2").gauge();
        assertThat(healthy).isNotNull();
        assertThat(healthy.value()).isEqualTo(0.0);
    }

    @Test
    void groupBalanceGauges_carryGroupIdentityAndTheRatio() {
        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", "tptxg2", "env-uuid-1", "116",
                        "game-uuid-1", "Tai Xiu", 25_000_000L, 250_000_000L)));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge avg = registry.find("group_avg_balance").tag("botGroupId", "group-uuid-1").gauge();
        assertThat(avg).isNotNull();
        assertThat(avg.value()).isEqualTo(25_000_000.0);

        Gauge ratio = registry.find("group_balance_ratio").tag("botGroupId", "group-uuid-1").gauge();
        assertThat(ratio).isNotNull();
        assertThat(ratio.value()).isEqualTo(0.10);
        // The label set that routes GroupBalanceLow to a product room and names the
        // group in the message.
        assertThat(ratio.getId().getTag("groupName")).isEqualTo("tptxg2");
        assertThat(ratio.getId().getTag("environmentId")).isEqualTo("env-uuid-1");
        assertThat(ratio.getId().getTag("product")).isEqualTo("116");
        assertThat(ratio.getId().getTag("gameId")).isEqualTo("game-uuid-1");
        assertThat(ratio.getId().getTag("gameName")).isEqualTo("Tai Xiu");
    }

    @Test
    void groupBalanceGauges_dropTheRowWhenAGroupStopsQualifying() {
        // A group that enables auto-deposit, stops, or loses its last connected bot
        // disappears from listGroupBalances — its series must go with it rather than
        // freeze at the last ratio and keep GroupBalanceLow firing forever.
        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", "tptxg2", "env-uuid-1", "116",
                        "game-uuid-1", "Tai Xiu", 1L, 100L)));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("group_balance_ratio").gauges()).isNotEmpty();

        when(behaviorService.listGroupBalances()).thenReturn(List.of());
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("group_balance_ratio").gauges()).isEmpty();
        assertThat(registry.find("group_avg_balance").gauges()).isEmpty();
    }

    @Test
    void groupBalanceGauges_updateValues_whenTheRatioMovesForAnExistingGroup() {
        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", "tptxg2", "env-uuid-1", "116",
                        "game-uuid-1", "Tai Xiu", 50L, 100L)));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("group_balance_ratio").gauge().value()).isEqualTo(0.50);

        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", "tptxg2", "env-uuid-1", "116",
                        "game-uuid-1", "Tai Xiu", 5L, 100L)));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("group_balance_ratio").gauge().value()).isEqualTo(0.05);
    }

    @Test
    void phase4Gauges_areOnTheAggregateExclusionList() {
        MDC.put(BotMdc.BOT_GROUP_ID, "group-mdc");
        MDC.put(BotMdc.PRODUCT, "999");

        when(behaviorService.countDeadGroupsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 1));
        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", "tptxg2", "env-uuid-1", "116",
                        "game-uuid-1", "Tai Xiu", 10L, 100L)));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge dead = registry.find("groups_dead_by_env").gauge();
        assertThat(dead).isNotNull();
        assertThat(dead.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
        assertThat(dead.getId().getTag(BotMdc.PRODUCT)).isEqualTo("116");

        for (String name : new String[]{"group_avg_balance", "group_balance_ratio"}) {
            Gauge g = registry.find(name).gauge();
            assertThat(g).as("%s registered", name).isNotNull();
            // its own botGroupId, never the refresher thread's MDC
            assertThat(g.getId().getTag(BotMdc.BOT_GROUP_ID)).isEqualTo("group-uuid-1");
            assertThat(g.getId().getTag(BotMdc.PRODUCT)).isEqualTo("116");
        }
    }

    // ---- null-safety: a Game/Env tuple with null fields must not crash the refresh ----

    @Test
    void refresh_isNullSafe_whenInfoTuplesCarryNullFields() {
        // listRunningGameInfo null-guards gameType in the service, but the refresher
        // must also tolerate a null gameName/gameType/gameId without NPE, emitting "".
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", null, null, null, null)));
        when(behaviorService.listRunningEnvironmentInfo()).thenReturn(List.of(
                new EnvInfo("env-uuid-1", null, null)));
        // Game.productCode is nullable on older Mongo documents (Implementation
        // Note 4) — a null product must render as "" and never NPE.
        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", null), 1));
        when(behaviorService.countOpenWsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", null), 1));
        // Same for a group row: a group on a game with no productCode, or a runtime
        // built without a Game, must render "" rather than NPE out of the 10 s refresh.
        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", null, null, null, null, null, 10L, 100L)));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge game = registry.find("game_join").tag("gameId", "game-uuid-1").gauge();
        assertThat(game).isNotNull();
        assertThat(game.getId().getTag("gameName")).isEqualTo("");
        assertThat(game.getId().getTag("gameType")).isEqualTo("");
        assertThat(game.getId().getTag("environmentId")).isEqualTo("");
        assertThat(game.getId().getTag("product")).isEqualTo("");
        Gauge env = registry.find("environment_join").tag("environmentId", "env-uuid-1").gauge();
        assertThat(env).isNotNull();
        assertThat(env.getId().getTag("environmentName")).isEqualTo("");
        assertThat(env.getId().getTag("product")).isEqualTo("");
        assertThat(registry.find("bots_managed_by_env").gauge().getId().getTag("product"))
                .isEqualTo("");
        assertThat(registry.find("ws_connections_open_by_env").gauge().getId().getTag("product"))
                .isEqualTo("");
        Gauge ratio = registry.find("group_balance_ratio").gauge();
        assertThat(ratio).isNotNull();
        assertThat(ratio.getId().getTag("groupName")).isEqualTo("");
        assertThat(ratio.getId().getTag("product")).isEqualTo("");
        assertThat(ratio.getId().getTag("gameId")).isEqualTo("");
    }

    @Test
    void refresh_withEmptyLiveState_registersNoRows() {
        // All service methods default-mock to empty; refresh must not throw and must
        // leave the gauges with zero series.
        InfoGaugeRefresher.refresh(behaviorService, gauges);

        assertThat(registry.find("game_join").gauges()).isEmpty();
        assertThat(registry.find("environment_join").gauges()).isEmpty();
        assertThat(registry.find("bots_by_game_status").gauges()).isEmpty();
        assertThat(registry.find("bots_by_env_status").gauges()).isEmpty();
        assertThat(registry.find("bots_managed_by_env").gauges()).isEmpty();
        assertThat(registry.find("ws_connections_open_by_env").gauges()).isEmpty();
        assertThat(registry.find("groups_dead_by_env").gauges()).isEmpty();
        assertThat(registry.find("group_avg_balance").gauges()).isEmpty();
        assertThat(registry.find("group_balance_ratio").gauges()).isEmpty();
    }

    // ---- PLUGIN_HOT_RELOAD Phase 1: bots_by_plugin_version ----

    @Test
    void botsByPluginVersionGauge_carriesGroupEnvProductAndVersion() {
        when(behaviorService.countBotsByPluginVersion()).thenReturn(Map.of(
                new PluginVersionKey("group-uuid-1", "env-uuid-1", "116", "builtin"), 47));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge row = registry.find("bots_by_plugin_version").tag("botGroupId", "group-uuid-1").gauge();
        assertThat(row).isNotNull();
        assertThat(row.value()).isEqualTo(47.0);
        assertThat(row.getId().getTag("environmentId")).isEqualTo("env-uuid-1");
        assertThat(row.getId().getTag("product")).isEqualTo("116");
        assertThat(row.getId().getTag("pluginVersion")).isEqualTo("builtin");
    }

    @Test
    void botsByPluginVersionGauge_splitsOneGroupAcrossTwoVersions() {
        // The step-5/6 drain shape, and the reason the version is a ROW label rather than a
        // group-level MDC key: mid-drain a group is genuinely mixed-version, so one group
        // must be able to produce two rows that sum to its bot count.
        when(behaviorService.countBotsByPluginVersion()).thenReturn(Map.of(
                new PluginVersionKey("group-uuid-1", "env-uuid-1", "116", "builtin"), 30,
                new PluginVersionKey("group-uuid-1", "env-uuid-1", "116", "v2"), 20));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        assertThat(registry.find("bots_by_plugin_version").gauges()).hasSize(2);
        assertThat(registry.find("bots_by_plugin_version")
                .tag("pluginVersion", "builtin").gauge().value()).isEqualTo(30.0);
        assertThat(registry.find("bots_by_plugin_version")
                .tag("pluginVersion", "v2").gauge().value()).isEqualTo(20.0);
    }

    @Test
    void botsByPluginVersionGauge_isOnTheAggregateExclusionList() {
        // AD-4/AD-5: this is the one family that legitimately carries pluginVersion, and it
        // must carry the version of the bots it counted — not whatever the refresher
        // thread's MDC was left holding.
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.PLUGIN_VERSION, "mdc-leak");

        when(behaviorService.countBotsByPluginVersion()).thenReturn(Map.of(
                new PluginVersionKey("group-uuid-1", "env-uuid-1", "116", "builtin"), 3));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge row = registry.find("bots_by_plugin_version").tag("botGroupId", "group-uuid-1").gauge();
        assertThat(row).isNotNull();
        assertThat(row.getId().getTag("pluginVersion")).isEqualTo("builtin");
        assertThat(row.getId().getTags())
                .as("the refresher thread's MDC must not reach this row")
                .noneMatch(tag -> "group-xyz".equals(tag.getValue()));
    }

    // ---- scheduler lifecycle (PostConstruct start / PreDestroy stop) ----

    @Test
    void scheduler_startsAndInvokesRefresh_thenStopsCleanly() throws Exception {
        CountDownLatch refreshed = new CountDownLatch(1);
        when(behaviorService.listRunningGameInfo()).thenAnswer(inv -> {
            refreshed.countDown();
            return List.of();
        });

        InfoGaugeRefresher refresher = new InfoGaugeRefresher(behaviorService, registry);
        invoke(refresher, "start");
        try {
            // The fixed-rate task fires immediately (initialDelay 0); it must run at least once.
            assertThat(refreshed.await(5, TimeUnit.SECONDS)).isTrue();
            verify(behaviorService, atLeastOnce()).listRunningGameInfo();
        } finally {
            invoke(refresher, "stop");
        }

        ScheduledExecutorService scheduler = scheduler(refresher);
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    void stop_isNullSafe_whenSchedulerNeverStarted() throws Exception {
        InfoGaugeRefresher refresher = new InfoGaugeRefresher(behaviorService, registry);
        // No start() called; stop() must not NPE on a null scheduler.
        invoke(refresher, "stop");
        assertThat(scheduler(refresher)).isNull();
    }

    @Test
    void refreshQuietly_swallowsExceptions_soSchedulerSurvives() throws Exception {
        // A throwing service must not propagate out of the scheduled task (which would
        // cancel all future runs). The refresher logs and continues.
        when(behaviorService.listRunningGameInfo()).thenThrow(new RuntimeException("boom"));
        InfoGaugeRefresher refresher = new InfoGaugeRefresher(behaviorService, registry);
        // refreshQuietly is the scheduled body; calling it directly must not throw.
        invoke(refresher, "refreshQuietly");
    }

    private static void invoke(Object target, String method) throws Exception {
        var m = InfoGaugeRefresher.class.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(target);
    }

    private static ScheduledExecutorService scheduler(InfoGaugeRefresher refresher) throws Exception {
        Field f = InfoGaugeRefresher.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        return (ScheduledExecutorService) f.get(refresher);
    }
}
