package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvStatusKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameStatusKey;
import com.vingame.bot.infrastructure.observability.InfoGaugeRefresher.InfoGauges;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    // ---- Phase 2: game_info / environment_info join gauges ----

    @Test
    void gameInfoGauge_carriesGameIdNameAndType_withValueOne() {
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI"),
                new GameInfo("game-uuid-2", "SlotA", "SLOT")));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge bauCua = registry.find("game_info").tag("gameId", "game-uuid-1").gauge();
        assertThat(bauCua).isNotNull();
        assertThat(bauCua.value()).isEqualTo(1.0);
        assertThat(bauCua.getId().getTag("gameName")).isEqualTo("BauCua");
        assertThat(bauCua.getId().getTag("gameType")).isEqualTo("BETTING_MINI");

        Gauge slot = registry.find("game_info").tag("gameName", "SlotA").gauge();
        assertThat(slot).isNotNull();
        assertThat(slot.getId().getTag("gameId")).isEqualTo("game-uuid-2");
        assertThat(slot.getId().getTag("gameType")).isEqualTo("SLOT");
    }

    @Test
    void environmentInfoGauge_carriesEnvironmentIdAndName_withValueOne() {
        when(behaviorService.listRunningEnvironmentInfo()).thenReturn(List.of(
                new EnvInfo("env-uuid-1", "Staging")));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge env = registry.find("environment_info").tag("environmentId", "env-uuid-1").gauge();
        assertThat(env).isNotNull();
        assertThat(env.value()).isEqualTo(1.0);
        assertThat(env.getId().getTag("environmentName")).isEqualTo("Staging");
    }

    @Test
    void infoGauges_dropStaleRowsOnRefresh() {
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI")));
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("game_info").tag("gameId", "game-uuid-1").gauge()).isNotNull();

        // The game stops: the next refresh must drop its row.
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of());
        InfoGaugeRefresher.refresh(behaviorService, gauges);
        assertThat(registry.find("game_info").tag("gameId", "game-uuid-1").gauge()).isNull();
    }

    @Test
    void infoGauges_areOnTheAggregateExclusionList() {
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-xyz");
        MDC.put(BotMdc.GAME_ID, "game-mdc");

        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI")));
        when(behaviorService.listRunningEnvironmentInfo()).thenReturn(List.of(
                new EnvInfo("env-uuid-1", "Staging")));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge game = registry.find("game_info").tag("gameId", "game-uuid-1").gauge();
        assertThat(game).isNotNull();
        assertThat(game.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
        Gauge env = registry.find("environment_info").tag("environmentId", "env-uuid-1").gauge();
        assertThat(env).isNotNull();
        assertThat(env.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
    }

    // ---- Phase 3: per-game / per-env status MultiGauges ----

    @Test
    void botsByGameStatusGauge_breaksDownBotCountsByStatusPerGame() {
        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.CONNECTION_AUTHENTICATED), 3,
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.DEAD), 1));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge connected = registry.find("bots_by_game_status")
                .tag("gameId", "game-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge();
        assertThat(connected).isNotNull();
        assertThat(connected.value()).isEqualTo(3.0);
        assertThat(connected.getId().getTag("gameName")).isEqualTo("BauCua");

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
                new EnvStatusKey("env-uuid-1", BotStatus.CONNECTION_AUTHENTICATED), 5,
                new EnvStatusKey("env-uuid-1", BotStatus.DEAD), 2));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge connected = registry.find("bots_by_env_status")
                .tag("environmentId", "env-uuid-1")
                .tag("status", BotStatus.CONNECTION_AUTHENTICATED.name())
                .gauge();
        assertThat(connected).isNotNull();
        assertThat(connected.value()).isEqualTo(5.0);

        Gauge dead = registry.find("bots_by_env_status")
                .tag("environmentId", "env-uuid-1")
                .tag("status", BotStatus.DEAD.name())
                .gauge();
        assertThat(dead).isNotNull();
        assertThat(dead.value()).isEqualTo(2.0);
    }

    @Test
    void statusMultiGauges_areOnTheAggregateExclusionList() {
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-xyz");

        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.DEAD), 1));
        when(behaviorService.countBotsByEnvAndStatus()).thenReturn(Map.of(
                new EnvStatusKey("env-uuid-1", BotStatus.DEAD), 1));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        Gauge game = registry.find("bots_by_game_status").tag("gameId", "game-uuid-1").gauge();
        assertThat(game).isNotNull();
        assertThat(game.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
        Gauge env = registry.find("bots_by_env_status").tag("environmentId", "env-uuid-1").gauge();
        assertThat(env).isNotNull();
        assertThat(env.getId().getTag(BotMdc.BOT_GROUP_ID)).isNull();
    }
}
