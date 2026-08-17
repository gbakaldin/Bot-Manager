package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Phase 2 / Phase 3 {@link MultiGauge}s and refreshes their row sets from
 * live bot state on a fixed cadence.
 * <ul>
 *   <li><b>game_join</b> {@code {gameId, gameName, gameType, environmentId, product}}
 *       value 1 — join gauge so a dashboard maps {@code gameId} to readable names
 *       (AD-2).</li>
 *   <li><b>environment_join</b> {@code {environmentId, environmentName, product}}
 *       value 1 — env-name join gauge; {@code environmentName} and {@code product}
 *       are threaded into the runtime at group start (AD-2, AD-V1).</li>
 *   <li><b>bots_by_game_status</b>
 *       {@code {gameId, gameName, gameType, environmentId, product, status}} — bot
 *       count per game per status (AD-3).</li>
 *   <li><b>bots_by_env_status</b> {@code {environmentId, product, status}} — bot
 *       count per environment per status (AD-3).</li>
 *   <li><b>bots_managed_by_env</b> {@code {environmentId, product}} — per-environment
 *       analogue of the {@code bots_managed} fleet gauge (AD-V1/AD-V2).</li>
 *   <li><b>ws_connections_open_by_env</b> {@code {environmentId, product}} —
 *       per-environment analogue of {@code ws_connections_open}, using the same
 *       {@code Bot.isConnected()} predicate as the fleet gauge (deliberately
 *       <em>not</em> {@code BotStatus}).</li>
 *   <li><b>groups_dead_by_env</b> {@code {environmentId, product}} —
 *       per-environment analogue of {@code groups_dead_currently}, so a DEAD group
 *       is alertable in its owning product's room instead of only showing up as a
 *       step in that environment's dead-<em>bot</em> ratio (Phase 4).</li>
 *   <li><b>group_avg_balance</b>
 *       {@code {botGroupId, groupName, environmentId, product, gameId, gameName}} —
 *       mean expected balance over a group's connected bots, absolute, for
 *       dashboards (AD-V12).</li>
 *   <li><b>group_balance_ratio</b> {@code {…same labels…}} — the same mean divided
 *       by the group's deposit amount, so one threshold (0.10) covers every
 *       currency scale and deposit size. Rows exist only for non-auto-deposit
 *       groups with at least one active bot (AD-V13).</li>
 * </ul>
 * {@code product} (VIPTALK_ALERTING_V2 AD-V1) is the numeric product code and is
 * functionally determined by {@code environmentId} / {@code gameId}, so it adds an
 * extra label to existing series rather than new series. The fleet aggregates in
 * {@link ObservabilityConfig} stay unlabelled (AD-V2).
 * <p>
 * All nine meter names are on the {@link BotMdcTagsMeterFilter} aggregate allow-list,
 * so they never inherit MDC tags from the refresher thread.
 * <p>
 * <b>Why {@code _join}, not {@code _info}:</b> {@code _info} is a <em>reserved
 * Prometheus metric-name suffix</em>. Micrometer's {@code PrometheusMeterRegistry}
 * routes any meter whose name ends in {@code _info} through an {@code InfoSnapshot},
 * whose exposition strips the reserved suffix — so a meter named {@code game_info}
 * scrapes as bare {@code game} and a {@code label_values(game_info, …)} dashboard
 * query returns an empty vector. The plain {@code _join} suffix is not reserved, so
 * these value-1 join gauges render verbatim alongside the sibling status gauges.
 * <p>
 * Refresh is driven by a dedicated single-thread virtual-thread scheduler (project
 * idiom), aligned to the Prometheus scrape interval. {@link MultiGauge#register}
 * replaces the row set each cycle, so a game/env that stops disappears on the next
 * refresh (no stale dropdown entries). The scheduler is shut down on bean destroy.
 */
@Slf4j
@Component
public class InfoGaugeRefresher {

    /** Refresh cadence, seconds. Matched to the Prometheus scrape interval. */
    private static final long REFRESH_INTERVAL_SECONDS = 10;

    private final BotGroupBehaviorService behaviorService;
    private final InfoGauges gauges;
    private ScheduledExecutorService scheduler;

    public InfoGaugeRefresher(BotGroupBehaviorService behaviorService, MeterRegistry registry) {
        this.behaviorService = behaviorService;
        this.gauges = registerInfoGauges(registry);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("metrics-multigauge-refresher").factory());
        scheduler.scheduleAtFixedRate(this::refreshQuietly, 0,
                REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Info-gauge refresher started (interval={}s)", REFRESH_INTERVAL_SECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void refreshQuietly() {
        try {
            refresh(behaviorService, gauges);
        } catch (Exception e) {
            log.error("Info-gauge refresh failed: {}", e.getMessage());
        }
    }

    /**
     * The MultiGauges, kept together so the refresher (and tests) re-register
     * all of them from one live snapshot.
     */
    record InfoGauges(MultiGauge gameInfo,
                      MultiGauge environmentInfo,
                      MultiGauge botsByGameStatus,
                      MultiGauge botsByEnvStatus,
                      MultiGauge botsManagedByEnv,
                      MultiGauge wsConnectionsOpenByEnv,
                      MultiGauge groupsDeadByEnv,
                      MultiGauge groupAvgBalance,
                      MultiGauge groupBalanceRatio) {
    }

    /**
     * Register the MultiGauges against the given registry. Package-private so
     * tests build them on a {@code SimpleMeterRegistry} and drive {@link #refresh}
     * deterministically without scheduling.
     */
    static InfoGauges registerInfoGauges(MeterRegistry registry) {
        MultiGauge gameInfo = MultiGauge.builder("game_join")
                .description("Join gauge mapping gameId to its readable gameName and gameType (value always 1)")
                .register(registry);
        MultiGauge environmentInfo = MultiGauge.builder("environment_join")
                .description("Join gauge mapping environmentId to its readable environmentName (value always 1)")
                .register(registry);
        MultiGauge botsByGameStatus = MultiGauge.builder("bots_by_game_status")
                .description("Number of bots in each status, broken down per game")
                .register(registry);
        MultiGauge botsByEnvStatus = MultiGauge.builder("bots_by_env_status")
                .description("Number of bots in each status, broken down per environment")
                .register(registry);
        MultiGauge botsManagedByEnv = MultiGauge.builder("bots_managed_by_env")
                .description("Number of managed bot instances, broken down per environment")
                .register(registry);
        MultiGauge wsConnectionsOpenByEnv = MultiGauge.builder("ws_connections_open_by_env")
                .description("Number of bots with an open WebSocket connection, broken down per environment")
                .register(registry);
        MultiGauge groupsDeadByEnv = MultiGauge.builder("groups_dead_by_env")
                .description("Number of bot groups currently in DEAD state, broken down per environment")
                .register(registry);
        MultiGauge groupAvgBalance = MultiGauge.builder("group_avg_balance")
                .description("Average expected balance over a group's connected bots (non-auto-deposit groups only)")
                .register(registry);
        MultiGauge groupBalanceRatio = MultiGauge.builder("group_balance_ratio")
                .description("Average expected balance as a fraction of the group's deposit amount "
                        + "(non-auto-deposit groups only)")
                .register(registry);
        return new InfoGauges(gameInfo, environmentInfo, botsByGameStatus, botsByEnvStatus,
                botsManagedByEnv, wsConnectionsOpenByEnv, groupsDeadByEnv,
                groupAvgBalance, groupBalanceRatio);
    }

    /**
     * Re-register the row set of every MultiGauge from the current live bot
     * iteration. Side-effect-isolated so tests can drive a deterministic refresh.
     * <p>
     * <b>Every register call passes {@code overwrite=true}.</b> Micrometer's
     * {@link MultiGauge#register(Iterable)} (single-arg) defaults to
     * {@code overwrite=false}, which, for a row whose tag-set already exists, keeps
     * the <em>original</em> gauge bound to its first-registered value and silently
     * discards the new one — so the value freezes at whatever it was when that
     * {@code (gameId, status)} / {@code (environmentId, status)} tuple first
     * appeared. That made a second bot group on an already-tracked game/env (or any
     * status-count change on a persisting row) invisible to the gauge. Passing
     * {@code true} removes and re-creates each row every cycle so the value tracks
     * the live count. Stale-row dropping is unaffected (rows absent from the new set
     * are removed either way).
     */
    static void refresh(BotGroupBehaviorService behaviorService, InfoGauges gauges) {
        gauges.gameInfo().register(behaviorService.listRunningGameInfo().stream()
                .map(g -> MultiGauge.Row.of(
                        Tags.of("gameId", nullSafe(g.gameId()),
                                "gameName", nullSafe(g.gameName()),
                                "gameType", nullSafe(g.gameType()),
                                "environmentId", nullSafe(g.environmentId()),
                                "product", nullSafe(g.product())),
                        1))
                .toList(), true);

        gauges.environmentInfo().register(behaviorService.listRunningEnvironmentInfo().stream()
                .map(e -> MultiGauge.Row.of(
                        Tags.of("environmentId", nullSafe(e.environmentId()),
                                "environmentName", nullSafe(e.environmentName()),
                                "product", nullSafe(e.product())),
                        1))
                .toList(), true);

        gauges.botsByGameStatus().register(behaviorService.countBotsByGameAndStatus().entrySet().stream()
                .map(en -> MultiGauge.Row.of(
                        Tags.of("gameId", nullSafe(en.getKey().gameId()),
                                "gameName", nullSafe(en.getKey().gameName()),
                                "gameType", nullSafe(en.getKey().gameType()),
                                "environmentId", nullSafe(en.getKey().environmentId()),
                                "product", nullSafe(en.getKey().product()),
                                "status", en.getKey().status().name()),
                        en.getValue()))
                .toList(), true);

        gauges.botsByEnvStatus().register(behaviorService.countBotsByEnvAndStatus().entrySet().stream()
                .map(en -> MultiGauge.Row.of(
                        Tags.of("environmentId", nullSafe(en.getKey().environmentId()),
                                "product", nullSafe(en.getKey().product()),
                                "status", en.getKey().status().name()),
                        en.getValue()))
                .toList(), true);

        gauges.botsManagedByEnv().register(behaviorService.countManagedBotsByEnv().entrySet().stream()
                .map(en -> MultiGauge.Row.of(envTags(en.getKey()), en.getValue()))
                .toList(), true);

        gauges.wsConnectionsOpenByEnv().register(behaviorService.countOpenWsByEnv().entrySet().stream()
                .map(en -> MultiGauge.Row.of(envTags(en.getKey()), en.getValue()))
                .toList(), true);

        gauges.groupsDeadByEnv().register(behaviorService.countDeadGroupsByEnv().entrySet().stream()
                .map(en -> MultiGauge.Row.of(envTags(en.getKey()), en.getValue()))
                .toList(), true);

        // Two rows per group off ONE snapshot: the absolute average (dashboards) and
        // the ratio the alert threshold is expressed in. Taking both from the same
        // listGroupBalances() call keeps them consistent within a refresh — reading
        // the live bots twice could show a balance and a ratio from different instants.
        var groupBalances = behaviorService.listGroupBalances();
        gauges.groupAvgBalance().register(groupBalances.stream()
                .map(b -> MultiGauge.Row.of(groupTags(b), b.avgExpectedBalance()))
                .toList(), true);
        gauges.groupBalanceRatio().register(groupBalances.stream()
                .map(b -> MultiGauge.Row.of(groupTags(b), b.ratio()))
                .toList(), true);
    }

    private static Tags groupTags(BotGroupBehaviorService.GroupBalance balance) {
        return Tags.of("botGroupId", nullSafe(balance.botGroupId()),
                "groupName", nullSafe(balance.groupName()),
                "environmentId", nullSafe(balance.environmentId()),
                "product", nullSafe(balance.product()),
                "gameId", nullSafe(balance.gameId()),
                "gameName", nullSafe(balance.gameName()));
    }

    private static Tags envTags(BotGroupBehaviorService.EnvKey key) {
        return Tags.of("environmentId", nullSafe(key.environmentId()),
                "product", nullSafe(key.product()));
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
