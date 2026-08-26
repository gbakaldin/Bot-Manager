package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvStatusKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameStatusKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GroupBalance;
import com.vingame.bot.infrastructure.observability.InfoGaugeRefresher.InfoGauges;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the staging defect where the value-1 join gauges registered
 * cleanly into the {@code MeterRegistry} (visible in the actuator JSON view) but were
 * <em>absent from the Prometheus scrape endpoint</em>, so {@code /api/v1/query} on
 * them returned empty vectors.
 * <p>
 * Root cause: {@code _info} is a <em>reserved Prometheus metric-name suffix</em>.
 * Micrometer's {@link PrometheusMeterRegistry} routes any meter named {@code *_info}
 * through an {@code InfoSnapshot}, whose exposition strips the reserved suffix — a
 * meter named {@code game_info} scraped as bare {@code game}, breaking the
 * {@code label_values(game_info, …)} dashboard query. The fix renames the join
 * gauges to the non-reserved {@code game_join} / {@code environment_join}.
 * <p>
 * Unlike {@link InfoGaugeRefresherTest} (which inspects the in-registry {@code Meter}
 * lookup — the very view that hid the bug), this test exercises the real
 * {@link PrometheusMeterRegistry#scrape()} text exposition, which is exactly what
 * Prometheus reads. It would have caught the defect.
 */
class InfoGaugePrometheusScrapeTest {

    private PrometheusMeterRegistry registry;
    private BotGroupBehaviorService behaviorService;
    private InfoGauges gauges;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        behaviorService = mock(BotGroupBehaviorService.class);
        gauges = InfoGaugeRefresher.registerInfoGauges(registry);
    }

    @Test
    void infoJoinGauges_renderToPrometheusScrape_withTheirLabels() {
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI", "env-uuid-1", "116")));
        when(behaviorService.listRunningEnvironmentInfo()).thenReturn(List.of(
                new EnvInfo("env-uuid-1", "Staging", "116")));
        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.DEAD,
                        "env-uuid-1", "BETTING_MINI", "116"), 1));
        when(behaviorService.countBotsByEnvAndStatus()).thenReturn(Map.of(
                new EnvStatusKey("env-uuid-1", BotStatus.DEAD, "116"), 1));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        String scrape = registry.scrape();

        // The two join gauges must appear under their literal names in the exposition.
        // Before the fix these scraped as bare "game" / "environment" (reserved-suffix
        // stripping), so these assertions failed (RED state confirmed).
        assertThat(scrape).contains("# TYPE game_join gauge");
        assertThat(scrape).contains(
                "game_join{environmentId=\"env-uuid-1\",gameId=\"game-uuid-1\","
                        + "gameName=\"BauCua\",gameType=\"BETTING_MINI\",product=\"116\"} 1.0");

        assertThat(scrape).contains("# TYPE environment_join gauge");
        assertThat(scrape).contains(
                "environment_join{environmentId=\"env-uuid-1\",environmentName=\"Staging\","
                        + "product=\"116\"} 1.0");

        // The suffix-stripped bare names must NOT leak into the exposition.
        assertThat(scrape).doesNotContain("\n# TYPE game gauge");
        assertThat(scrape).doesNotContain("\n# TYPE environment gauge");

        // Siblings (the working status gauges) keep rendering as before, now with the
        // environmentId / gameType / product labels VIPTALK_ALERTING_V2 Phase 1 adds.
        assertThat(scrape).contains(
                "bots_by_game_status{environmentId=\"env-uuid-1\",gameId=\"game-uuid-1\","
                        + "gameName=\"BauCua\",gameType=\"BETTING_MINI\",product=\"116\","
                        + "status=\"DEAD\"} 1.0");
        assertThat(scrape).contains(
                "bots_by_env_status{environmentId=\"env-uuid-1\",product=\"116\",status=\"DEAD\"} 1.0");
    }

    @Test
    void perEnvAggregateGauges_renderToPrometheusScrape_withEnvironmentIdAndProduct() {
        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 12));
        when(behaviorService.countOpenWsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 9));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        String scrape = registry.scrape();

        assertThat(scrape).contains("# TYPE bots_managed_by_env gauge");
        assertThat(scrape).contains(
                "bots_managed_by_env{environmentId=\"env-uuid-1\",product=\"116\"} 12.0");
        assertThat(scrape).contains("# TYPE ws_connections_open_by_env gauge");
        assertThat(scrape).contains(
                "ws_connections_open_by_env{environmentId=\"env-uuid-1\",product=\"116\"} 9.0");
    }

    @Test
    void phase4Gauges_renderToPrometheusScrape_withTheLabelsTheRulesRouteOn() {
        when(behaviorService.countDeadGroupsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 1));
        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", "tptxg2", "env-uuid-1", "116",
                        "game-uuid-1", "Tai Xiu", 25L, 250L)));

        InfoGaugeRefresher.refresh(behaviorService, gauges);

        String scrape = registry.scrape();

        // EnvironmentGroupDead reads this bare, with no vector matching — but it still
        // needs product/environmentId on the OUTPUT series to reach a product room.
        assertThat(scrape).contains("# TYPE groups_dead_by_env gauge");
        assertThat(scrape).contains(
                "groups_dead_by_env{environmentId=\"env-uuid-1\",product=\"116\"} 1.0");

        // GroupBalanceLow reads group_balance_ratio bare and names {{ $labels.groupName }}
        // in its summary, so groupName must survive into the exposition.
        assertThat(scrape).contains("# TYPE group_balance_ratio gauge");
        assertThat(scrape).contains(
                "group_balance_ratio{botGroupId=\"group-uuid-1\",environmentId=\"env-uuid-1\","
                        + "gameId=\"game-uuid-1\",gameName=\"Tai Xiu\",groupName=\"tptxg2\","
                        + "product=\"116\"} 0.1");
        assertThat(scrape).contains("# TYPE group_avg_balance gauge");
        assertThat(scrape).contains(
                "group_avg_balance{botGroupId=\"group-uuid-1\",environmentId=\"env-uuid-1\","
                        + "gameId=\"game-uuid-1\",gameName=\"Tai Xiu\",groupName=\"tptxg2\","
                        + "product=\"116\"} 25.0");
    }

    /**
     * PLUGIN_HOT_RELOAD Phase 1 — the three {@code plugin_classloaders_*} meters and
     * {@code bots_by_plugin_version}, checked against the <b>text exposition</b> rather than
     * the in-registry meter id.
     * <p>
     * Everything else that pins these names does it against a {@code SimpleMeterRegistry},
     * which applies no naming convention at all, so the strings
     * {@code grafana/provisioning/dashboards/plugin-runtime.json} queries and the strings
     * verification P1-4 greps for have until now been checked against nothing.
     * <p>
     * <b>This test is currently RED, and it is red because of a production defect, not a
     * test defect.</b> {@code plugin_classloaders_created_total} scrapes as
     * {@code plugin_classloaders_total}: {@code _created} is a <em>reserved Prometheus
     * suffix</em> (the OpenMetrics created-timestamp series), so the Prometheus client's
     * name sanitiser strips {@code _total}, then strips {@code _created}, and the counter
     * exposition appends {@code _total} to what is left. Its sibling
     * {@code plugin_classloaders_reclaimed_total} is untouched, which is what makes the
     * defect look like a typo rather than a rule. This is the same class of failure that
     * opened this file — {@code game_info} scraping as bare {@code game} — and it has the
     * same shape of consequence: the dashboard's "created vs reclaimed" panel, the one that
     * shows a retained loader, queries a name that will never exist, and P1-4 fails on the
     * box. Verified by probe against this registry: {@code _registered_total} and
     * {@code _loaded_total} both survive intact, so the fix is a one-constant rename in
     * {@code PluginClassLoaderMetrics} plus the panel, the alert prose and P1-4.
     */
    @Test
    void pluginRuntimeMeters_renderUnderTheNamesTheDashboardSpells() {
        new PluginClassLoaderMetrics(registry, () -> "builtin")
                .register("builtin", getClass().getClassLoader());

        when(behaviorService.countBotsByPluginVersion()).thenReturn(Map.of(
                new BotGroupBehaviorService.PluginVersionKey(
                        "group-uuid-1", "env-uuid-1", "116", "builtin"), 47));
        InfoGaugeRefresher.refresh(behaviorService, gauges);

        String scrape = registry.scrape();

        assertThat(scrape).contains("# TYPE plugin_classloaders_live gauge");
        assertThat(scrape).contains("plugin_classloaders_live{pluginVersion=\"builtin\"} 1.0");
        assertThat(scrape).contains("# TYPE plugin_classloaders_reclaimed_total counter");
        assertThat(scrape).contains(
                "plugin_classloaders_reclaimed_total{pluginVersion=\"builtin\"} 0.0");

        // The panel is `sum by (pluginVersion) (bots_by_plugin_version)`, and verification
        // P1-5 sums this family against bots_managed — both need the label on the exposition,
        // not merely on the in-registry id.
        assertThat(scrape).contains("# TYPE bots_by_plugin_version gauge");
        assertThat(scrape).contains(
                "bots_by_plugin_version{botGroupId=\"group-uuid-1\",environmentId=\"env-uuid-1\","
                        + "pluginVersion=\"builtin\",product=\"116\"} 47.0");

        assertThat(scrape)
                .as("plugin_classloaders_created_total is unreachable from Prometheus: "
                        + "`_created` is a reserved suffix, so this counter scrapes as "
                        + "`plugin_classloaders_total`. The Grafana panel and verification "
                        + "P1-4 both spell the name registered in code, which no scrape will "
                        + "ever contain. Rename the CREATED constant (e.g. to "
                        + "plugin_classloaders_registered_total, which round-trips intact) "
                        + "and update plugin-runtime.json, the MetaspaceGrowth description "
                        + "and P1-4 with it.")
                .contains("# TYPE plugin_classloaders_created_total counter")
                .contains("plugin_classloaders_created_total{pluginVersion=\"builtin\"} 1.0")
                .doesNotContain("plugin_classloaders_total");
    }
}
