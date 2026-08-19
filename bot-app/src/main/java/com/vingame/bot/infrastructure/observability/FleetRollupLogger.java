package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GroupHealth;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tier 2 of LOG_VOLUME_TIERING: the periodic fleet rollup — the INFO line that tells an
 * operator what the fleet is doing without asking any per-bot or per-round line to do it.
 * <p>
 * Every 5 minutes it emits <b>one line per running environment</b>:
 * <pre>
 *   env &lt;id&gt; (&lt;name&gt;, product 116): groups=3, bots=150, connected=148, dead=2,
 *                                    deadGroups=0, rounds=412, staked=12345678
 * </pre>
 * and <b>a second line only for a group that is not clean</b> — any DEAD or RECONNECTING
 * bot, or the group itself marked DEAD:
 * <pre>
 *   group &lt;id&gt; (&lt;name&gt;): playing=48, reconnecting=1, dead=1/50, rounds=137, staked=411522
 * </pre>
 * That asymmetry is the design: a healthy fleet of 300 groups costs a handful of lines per
 * cycle, and the volume grows with <em>sickness</em> rather than with fleet size, which is
 * the property the whole tier model turns on.
 * <p>
 * <b>Not a new subsystem.</b> Every figure is a read over accessors that already exist and
 * are already called on a 10 s cadence by {@link InfoGaugeRefresher}, plus
 * {@code listGroupHealth()}, which returns exactly what
 * {@code BotGroupBehaviorService.monitorHealth} already computes per group every 30 s. The
 * scheduler is the project's virtual-thread idiom, modelled on {@link InfoGaugeRefresher}.
 * <p>
 * <b>Why rounds and staked are here (AD-8).</b> The per-round session summaries were the
 * only INFO class whose rate scaled with round rate, so they are now DEBUG. Carrying the
 * drained per-group rounds/stake on a line that is emitted anyway downsamples that signal
 * from ~13 lines/s to two numbers every 5 minutes, at INFO, without adding a line class.
 * Groups that are clean contribute their activity to their environment's totals; a group
 * that is unclean additionally gets its own figures on its detail line, which is where an
 * operator asking "is this sick group still betting?" will look.
 */
@Slf4j
@Component
public class FleetRollupLogger {

    /** Rollup cadence, seconds. 5 minutes, per the plan's step 3. */
    static final long ROLLUP_INTERVAL_SECONDS = 300;

    private final BotGroupBehaviorService behaviorService;
    private final SessionAggregationService sessionAggregationService;
    private ScheduledExecutorService scheduler;

    public FleetRollupLogger(BotGroupBehaviorService behaviorService,
                             SessionAggregationService sessionAggregationService) {
        this.behaviorService = behaviorService;
        this.sessionAggregationService = sessionAggregationService;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("fleet-rollup-logger").factory());
        scheduler.scheduleAtFixedRate(this::rollupQuietly,
                ROLLUP_INTERVAL_SECONDS, ROLLUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Fleet rollup logger started (interval={}s)", ROLLUP_INTERVAL_SECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void rollupQuietly() {
        try {
            rollupOnce();
        } catch (Exception e) {
            log.error("Fleet rollup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Emit one rollup cycle. Package-private so tests drive it deterministically without
     * scheduling — the same seam {@link InfoGaugeRefresher#refresh} exposes.
     * <p>
     * Drains the rollup counters exactly once per cycle, before anything is rendered, so a
     * group's activity is attributed to precisely one line: its environment's totals, and
     * its own detail line when it has one.
     */
    void rollupOnce() {
        List<GroupHealth> groups = List.copyOf(behaviorService.listGroupHealth());
        if (groups.isEmpty()) {
            // Nothing running. Emit nothing at all rather than a line saying so: an idle
            // instance must not cost a line every 5 minutes forever.
            sessionAggregationService.drainRollup(); // still drain, so nothing accumulates
            return;
        }

        Map<String, long[]> activityByGroup = new HashMap<>();
        for (SessionAggregationService.GroupRollup entry : sessionAggregationService.drainRollup()) {
            activityByGroup.put(entry.botGroupId(), new long[]{entry.rounds(), entry.staked()});
        }

        emitEnvironmentLines(groups, activityByGroup);
        emitUncleanGroupLines(groups, activityByGroup);
    }

    /** One INFO line per running environment. */
    private void emitEnvironmentLines(List<GroupHealth> groups, Map<String, long[]> activityByGroup) {
        Map<String, EnvInfo> names = new LinkedHashMap<>();
        for (EnvInfo info : behaviorService.listRunningEnvironmentInfo()) {
            names.put(info.environmentId(), info);
        }
        Map<EnvKey, Integer> managed = behaviorService.countManagedBotsByEnv();
        Map<EnvKey, Integer> openWs = behaviorService.countOpenWsByEnv();
        Map<EnvKey, Integer> deadGroups = behaviorService.countDeadGroupsByEnv();

        // Aggregate the drained per-group activity up to the environment, so a clean
        // group's rounds and stake are still represented even though it prints no line
        // of its own.
        Map<String, long[]> activityByEnv = new LinkedHashMap<>();
        Map<String, Integer> groupCountByEnv = new LinkedHashMap<>();
        // Dead bots are summed from the SAME per-group snapshot the detail lines are
        // rendered from, not re-read via countBotsByEnvAndStatus(). Two reads of live bot
        // state taken microseconds apart can disagree, and an env line whose dead count
        // does not equal the sum of its groups' is the kind of discrepancy that costs an
        // operator ten minutes during an incident.
        Map<String, Integer> deadBotsByEnv = new LinkedHashMap<>();
        for (GroupHealth group : groups) {
            String envId = group.environmentId();
            if (envId == null) continue;
            groupCountByEnv.merge(envId, 1, Integer::sum);
            deadBotsByEnv.merge(envId, group.dead(), Integer::sum);
            long[] activity = activityByGroup.get(group.botGroupId());
            if (activity != null) {
                long[] total = activityByEnv.computeIfAbsent(envId, k -> new long[2]);
                total[0] += activity[0];
                total[1] += activity[1];
            }
        }

        for (Map.Entry<String, Integer> entry : groupCountByEnv.entrySet()) {
            String envId = entry.getKey();
            EnvInfo info = names.get(envId);
            String product = info != null ? info.product() : null;
            EnvKey key = new EnvKey(envId, product);
            long[] activity = activityByEnv.getOrDefault(envId, new long[2]);

            // Tag the line so it is filterable by environment in Loki exactly like a
            // per-bot line. Keeping the MDC tag on aggregated lines is what makes the
            // demotions below them safe. The null botGroupId is skipped, not written:
            // an environment line has no group, and "botGroupId: null" in the JSON
            // document is a different thing from the key being absent.
            BotMdc.setGroupContext(null, envId, product);
            try {
                log.info("env {} ({}, product {}): groups={}, bots={}, connected={}, dead={}, "
                                + "deadGroups={}, rounds={}, staked={}",
                        envId,
                        info != null ? info.environmentName() : "?",
                        product != null ? product : "?",
                        entry.getValue(),
                        managed.getOrDefault(key, 0),
                        openWs.getOrDefault(key, 0),
                        deadBotsByEnv.getOrDefault(envId, 0),
                        deadGroups.getOrDefault(key, 0),
                        activity[0], activity[1]);
            } finally {
                BotMdc.clear();
            }
        }
    }

    /**
     * One INFO line per group that is <em>not</em> clean. This is the line whose absence
     * is the signal: a cycle that prints only environment lines means every group is
     * healthy.
     */
    private void emitUncleanGroupLines(List<GroupHealth> groups, Map<String, long[]> activityByGroup) {
        for (GroupHealth group : groups) {
            if (group.isClean()) {
                continue;
            }
            long[] activity = activityByGroup.getOrDefault(group.botGroupId(), new long[2]);
            BotMdc.setGroupContext(group.botGroupId(), group.environmentId(), group.product());
            try {
                log.info("group {} ({}): playing={}, reconnecting={}, dead={}/{}, "
                                + "groupDead={}, rounds={}, staked={}",
                        group.botGroupId(),
                        group.groupName() != null ? group.groupName() : "?",
                        group.playing(), group.reconnecting(), group.dead(), group.total(),
                        group.groupDead(), activity[0], activity[1]);
            } finally {
                BotMdc.clear();
            }
        }
    }
}
