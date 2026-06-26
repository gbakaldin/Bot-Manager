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
 *   <li><b>game_info</b> {@code {gameId, gameName, gameType}} value 1 — join gauge
 *       so a dashboard maps {@code gameId} to readable names (AD-2).</li>
 *   <li><b>environment_info</b> {@code {environmentId, environmentName}} value 1 —
 *       env-name join gauge; {@code environmentName} is threaded into the runtime at
 *       group start (AD-2).</li>
 *   <li><b>bots_by_game_status</b> {@code {gameId, gameName, status}} — bot count per
 *       game per status (AD-3).</li>
 *   <li><b>bots_by_env_status</b> {@code {environmentId, status}} — bot count per
 *       environment per status (AD-3).</li>
 * </ul>
 * All four meter names are on the {@link BotMdcTagsMeterFilter} aggregate allow-list,
 * so they never inherit MDC tags from the refresher thread.
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
     * The four MultiGauges, kept together so the refresher (and tests) re-register
     * all of them from one live snapshot.
     */
    record InfoGauges(MultiGauge gameInfo,
                      MultiGauge environmentInfo,
                      MultiGauge botsByGameStatus,
                      MultiGauge botsByEnvStatus) {
    }

    /**
     * Register the four MultiGauges against the given registry. Package-private so
     * tests build them on a {@code SimpleMeterRegistry} and drive {@link #refresh}
     * deterministically without scheduling.
     */
    static InfoGauges registerInfoGauges(MeterRegistry registry) {
        MultiGauge gameInfo = MultiGauge.builder("game_info")
                .description("Join gauge mapping gameId to its readable gameName and gameType (value always 1)")
                .register(registry);
        MultiGauge environmentInfo = MultiGauge.builder("environment_info")
                .description("Join gauge mapping environmentId to its readable environmentName (value always 1)")
                .register(registry);
        MultiGauge botsByGameStatus = MultiGauge.builder("bots_by_game_status")
                .description("Number of bots in each status, broken down per game")
                .register(registry);
        MultiGauge botsByEnvStatus = MultiGauge.builder("bots_by_env_status")
                .description("Number of bots in each status, broken down per environment")
                .register(registry);
        return new InfoGauges(gameInfo, environmentInfo, botsByGameStatus, botsByEnvStatus);
    }

    /**
     * Re-register the row set of all four MultiGauges from the current live bot
     * iteration. Side-effect-isolated so tests can drive a deterministic refresh.
     */
    static void refresh(BotGroupBehaviorService behaviorService, InfoGauges gauges) {
        gauges.gameInfo().register(behaviorService.listRunningGameInfo().stream()
                .map(g -> MultiGauge.Row.of(
                        Tags.of("gameId", nullSafe(g.gameId()),
                                "gameName", nullSafe(g.gameName()),
                                "gameType", nullSafe(g.gameType())),
                        1))
                .toList());

        gauges.environmentInfo().register(behaviorService.listRunningEnvironmentInfo().stream()
                .map(e -> MultiGauge.Row.of(
                        Tags.of("environmentId", nullSafe(e.environmentId()),
                                "environmentName", nullSafe(e.environmentName())),
                        1))
                .toList());

        gauges.botsByGameStatus().register(behaviorService.countBotsByGameAndStatus().entrySet().stream()
                .map(en -> MultiGauge.Row.of(
                        Tags.of("gameId", nullSafe(en.getKey().gameId()),
                                "gameName", nullSafe(en.getKey().gameName()),
                                "status", en.getKey().status().name()),
                        en.getValue()))
                .toList());

        gauges.botsByEnvStatus().register(behaviorService.countBotsByEnvAndStatus().entrySet().stream()
                .map(en -> MultiGauge.Row.of(
                        Tags.of("environmentId", nullSafe(en.getKey().environmentId()),
                                "status", en.getKey().status().name()),
                        en.getValue()))
                .toList());
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
