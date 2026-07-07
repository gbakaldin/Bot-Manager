package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.botgroup.model.ActivationDecision;
import com.vingame.bot.domain.botgroup.model.ActivationEvaluator;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic reconciler for time-scheduled bot groups (TIMED_ACTIVATION AD-8, AD-11
 * piece #3). Every tick it loads the {@link ActivationMode#SCHEDULED} groups,
 * evaluates the pure {@link ActivationEvaluator#decide} against the current instant,
 * and drives the existing {@link BotGroupBehaviorService#start}/{@code stop}
 * lifecycle so {@code actual → desired} converges (AD-2).
 *
 * <p>This is the only piece that touches Spring / Mongo / services — the predicate
 * ({@code ActivationWindow.isActiveAt}) and the decision ({@code ActivationEvaluator})
 * are pure and reused verbatim by the future Fleet abstraction (AD-11).
 */
@Slf4j
@Component
public class ActivationScheduler {

    private final BotGroupRepository botGroupRepository;
    private final BotGroupBehaviorService behaviorService;

    /**
     * Business wall-clock zone the windows are interpreted in (AD-5). Single
     * app-wide value, read only here, defaulting to Vietnam time.
     */
    private final ZoneId zone;

    /** Reconcile cadence in seconds (AD-8, default 60 = minute granularity). */
    private final long tickSeconds;

    private ScheduledExecutorService reconciler;

    public ActivationScheduler(BotGroupRepository botGroupRepository,
                               @Lazy BotGroupBehaviorService behaviorService,
                               @Value("${bot.activation.zone:Asia/Ho_Chi_Minh}") String zone,
                               @Value("${bot.activation.tick-seconds:60}") long tickSeconds) {
        this.botGroupRepository = botGroupRepository;
        this.behaviorService = behaviorService;
        this.zone = ZoneId.of(zone);
        this.tickSeconds = tickSeconds;
    }

    @PostConstruct
    public void start() {
        reconciler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("activation-reconciler").factory()
        );

        // Align the first tick to the next wall-clock minute boundary in the
        // configured zone (AD-8), so an 18:00 window flips at ~18:00:0x and
        // transition logs land on clean minute marks.
        long initialDelayMillis = millisUntilNextMinute();

        reconciler.scheduleAtFixedRate(
                this::reconcileAll,
                initialDelayMillis,
                tickSeconds * 1000L,
                TimeUnit.MILLISECONDS
        );

        log.info("Activation reconciler started (zone: {}, tick: {}s, first tick in {} ms)",
                zone, tickSeconds, initialDelayMillis);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down activation reconciler...");
        if (reconciler != null) {
            reconciler.shutdownNow();
        }
    }

    /**
     * Milliseconds from now until the next {@code :00} minute boundary in the
     * configured zone.
     */
    private long millisUntilNextMinute() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        return Duration.between(now, nextMinute).toMillis();
    }

    /**
     * One reconcile pass over every SCHEDULED group. Each group is isolated in its
     * own try/catch (one failure must not abort the tick) and tagged with group MDC,
     * mirroring the existing health/logout schedulers.
     */
    void reconcileAll() {
        Instant now = Instant.now();
        List<BotGroup> groups = botGroupRepository.findByActivationMode(ActivationMode.SCHEDULED);

        for (BotGroup group : groups) {
            BotMdc.setGroupContext(group.getId(), group.getEnvironmentId());
            try {
                reconcileGroup(group, now);
            } catch (Exception e) {
                log.error("Activation reconcile error for group {}: {}",
                        group.getId(), e.getMessage(), e);
            } finally {
                BotMdc.clear();
            }
        }
    }

    private void reconcileGroup(BotGroup group, Instant now) {
        String id = group.getId();
        boolean running = behaviorService.isGroupRunning(id);
        boolean dead = group.getTargetStatus() == BotGroupStatus.DEAD
                || behaviorService.getActualStatus(id) == BotGroupStatus.DEAD;

        ActivationDecision decision = ActivationEvaluator.decide(
                group.getActivationMode(), group.getActivationWindow(),
                running, dead, now, zone);

        switch (decision) {
            case START -> {
                log.info("Activation reconcile: group {} window open → START", id);
                behaviorService.start(id);
            }
            case STOP -> {
                log.info("Activation reconcile: group {} window closed → STOP", id);
                behaviorService.stop(id);
            }
            case NONE -> log.debug("Activation reconcile: group {} → NONE (running={}, dead={})",
                    id, running, dead);
        }
    }
}
