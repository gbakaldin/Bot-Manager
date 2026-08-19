package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.ScopedDebugRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * LOG_VOLUME_TIERING AD-12 — turns scoped DEBUG on for a group <b>before</b> it dies, on the
 * early-warning signals the app already computes.
 * <p>
 * <b>Why not on DEAD.</b> Enabling DEBUG for a group that is already DEAD produces logs of a
 * group that has stopped doing anything: the interesting minutes are the ones before the
 * declaration, and they are gone. All three triggers below fire while the group is still
 * running, and all three are read from sites that already existed:
 * <ol>
 *   <li><b>Watchdog expiry</b> — {@code BettingMiniGameBot}'s "no game message in Ns"; the
 *       first one for a group is escalated. This is the signal for the silent-zombie /
 *       subscriber-pruning failure mode, where the connection is fine and the game is
 *       simply not talking to the bot any more.</li>
 *   <li><b>Reconnect rate</b> — the two sites that increment {@code bot_reconnects_total}.
 *       A group churning reconnects is a group about to accumulate DEAD bots.</li>
 *   <li><b>Rising dead ratio</b> — {@code monitorHealth}'s {@code dead/total}, escalated
 *       once it crosses <em>half</em> of {@code bot.group.dead.threshold} while still under
 *       it. Half is the point at which the group is visibly deteriorating but has not yet
 *       been declared dead, which is exactly the window worth logging.</li>
 * </ol>
 * <b>Bounded by construction.</b> One escalation per group per cooldown (default 15 min, the
 * same as the TTL), so a flapping group expiring a watchdog every three minutes holds DEBUG
 * open for its 15 minutes and then goes quiet rather than re-arming forever. The registry's
 * own {@code maxScopes} cap bounds a fleet-wide incident: a bad deploy that sickens 300
 * groups at once escalates the first N and refuses the rest with a single WARN, instead of
 * quietly reconstructing fleet-wide DEBUG.
 * <p>
 * Every call site is null-tolerant ({@code Bot} holds this via a fluent setter, exactly like
 * {@code GroupLifecycleAggregator}), so bots built in unit tests without a Spring context
 * simply never escalate.
 */
@Slf4j
@Component
public class ScopedDebugEscalator {

    /** Anti-leak ceiling on the per-group bookkeeping maps. Far above any real fleet. */
    static final int MAX_TRACKED_GROUPS = 2_000;

    private final ScopedDebugRegistry registry;
    private final boolean enabled;
    private final Duration ttl;
    private final Duration cooldown;
    private final int reconnectThreshold;
    private final Duration reconnectWindow;
    private final double deadRatioTrigger;
    private final double deadRatioCeiling;
    private final LongSupplier clock;

    /** groupId → epoch millis of the last escalation, for the cooldown. */
    private final ConcurrentHashMap<String, Long> lastEscalation = new ConcurrentHashMap<>();
    /** groupId → rolling reconnect count within the current window. */
    private final ConcurrentHashMap<String, ReconnectWindow> reconnects = new ConcurrentHashMap<>();

    // Two constructors (the second is the test seam), so Spring must be told which one —
    // SpringBeanConstructorTest fails the build otherwise, for the reason recorded there.
    @Autowired
    public ScopedDebugEscalator(
            ScopedDebugRegistry registry,
            @Value("${bot.logging.scoped-debug.enabled:true}") boolean scopedDebugEnabled,
            @Value("${bot.logging.scoped-debug.escalation.enabled:true}") boolean escalationEnabled,
            @Value("${bot.logging.scoped-debug.escalation.minutes:15}") int ttlMinutes,
            @Value("${bot.logging.scoped-debug.escalation.cooldown-minutes:15}") int cooldownMinutes,
            @Value("${bot.logging.scoped-debug.escalation.reconnect-threshold:5}") int reconnectThreshold,
            @Value("${bot.logging.scoped-debug.escalation.reconnect-window-minutes:5}") int reconnectWindowMinutes,
            @Value("${bot.group.dead.threshold:0.80}") double deadGroupThreshold) {
        this(registry, scopedDebugEnabled && escalationEnabled, Duration.ofMinutes(ttlMinutes),
                Duration.ofMinutes(cooldownMinutes), reconnectThreshold,
                Duration.ofMinutes(reconnectWindowMinutes), deadGroupThreshold,
                System::currentTimeMillis);
    }

    /** Full-control constructor — the test seam (injectable clock and thresholds). */
    ScopedDebugEscalator(ScopedDebugRegistry registry, boolean enabled, Duration ttl,
                         Duration cooldown, int reconnectThreshold, Duration reconnectWindow,
                         double deadGroupThreshold, LongSupplier clock) {
        this.registry = registry;
        this.enabled = enabled;
        this.ttl = ttl;
        this.cooldown = cooldown;
        this.reconnectThreshold = reconnectThreshold;
        this.reconnectWindow = reconnectWindow;
        // Escalate at half the dead threshold: deteriorating, not yet declared dead.
        this.deadRatioTrigger = deadGroupThreshold / 2.0;
        this.deadRatioCeiling = deadGroupThreshold;
        this.clock = clock;
    }

    /**
     * A bot's watchdog expired — the group has a bot that is connected but no longer being
     * talked to. Escalated on the first occurrence per cooldown.
     */
    public void onWatchdogExpiry(String botGroupId) {
        escalate(botGroupId, "watchdog expiry");
    }

    /**
     * A bot reconnected. Escalates once the group's rolling count crosses the threshold —
     * called from the same two sites that increment {@code bot_reconnects_total}, so the
     * metric and the escalation can never disagree about what a "reconnect event" is.
     */
    public void onReconnect(String botGroupId) {
        if (!enabled || botGroupId == null) {
            return;
        }
        long now = clock.getAsLong();
        ReconnectWindow window = reconnects.computeIfAbsent(botGroupId, id -> new ReconnectWindow(now));
        int count = window.record(now, reconnectWindow.toMillis());
        enforceCap(reconnects);
        if (count >= reconnectThreshold) {
            if (escalate(botGroupId, count + " reconnects in " + reconnectWindow.toMinutes() + "m")) {
                // Only reset on a successful escalation: a cooldown-suppressed group must
                // not have its counter cleared, or a sustained churn would never re-trigger
                // once the cooldown lapses.
                window.reset(now);
            }
        }
    }

    /**
     * The 30 s group health sample. Escalates while the group is deteriorating and still
     * under the dead threshold — above it, {@code handleBotGroupDeath} takes over and the
     * group has already stopped being interesting to log.
     *
     * @param dead  bots currently DEAD
     * @param total bots in the group
     */
    public void onGroupHealth(String botGroupId, long dead, long total) {
        if (!enabled || botGroupId == null || total <= 0 || dead <= 0) {
            return;
        }
        double ratio = (double) dead / total;
        if (ratio < deadRatioTrigger || ratio >= deadRatioCeiling) {
            return;
        }
        escalate(botGroupId, String.format("dead ratio %d/%d rising (threshold %.2f)",
                dead, total, deadRatioCeiling));
    }

    /** Drop a stopped group's bookkeeping, alongside the other per-group aggregators. */
    public void evictGroup(String botGroupId) {
        if (botGroupId == null) {
            return;
        }
        lastEscalation.remove(botGroupId);
        reconnects.remove(botGroupId);
    }

    /**
     * Arm scoped DEBUG for a group, honouring the cooldown.
     *
     * @return {@code true} if this call actually armed it
     */
    private boolean escalate(String botGroupId, String trigger) {
        if (!enabled || botGroupId == null) {
            return false;
        }
        long now = clock.getAsLong();
        // Atomic claim of the cooldown slot: two threads seeing the same expiry (a whole
        // group's watchdogs firing together is the normal case) must produce one line.
        AtomicBoolean won = new AtomicBoolean(false);
        lastEscalation.compute(botGroupId, (id, previous) -> {
            if (previous != null && now - previous < cooldown.toMillis()) {
                return previous;
            }
            won.set(true);
            return now;
        });
        if (!won.get()) {
            return false;
        }
        enforceCap(lastEscalation);

        Optional<Instant> expiry = registry.enable(botGroupId, ttl);
        if (expiry.isEmpty()) {
            log.warn("scoped debug escalation refused for group {} ({}): the registry is at its "
                    + "concurrent-scope cap of {}", botGroupId, trigger, registry.getMaxScopes());
            return false;
        }
        // One INFO line per escalation naming the trigger and the expiry — the "why" for the
        // sudden appearance of DEBUG lines from one group in an otherwise INFO log.
        log.info("scoped debug escalated for group {} — trigger: {}, expires {}",
                botGroupId, trigger, expiry.get());
        return true;
    }

    /** Bound the bookkeeping maps; oldest entry first. */
    private static void enforceCap(ConcurrentHashMap<String, ?> map) {
        while (map.size() > MAX_TRACKED_GROUPS) {
            String oldest = map.entrySet().stream()
                    .min(Comparator.comparingLong(e -> timestampOf(e.getValue())))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null || map.remove(oldest) == null) {
                return;
            }
        }
    }

    private static long timestampOf(Object value) {
        if (value instanceof Long millis) {
            return millis;
        }
        if (value instanceof ReconnectWindow window) {
            return window.startMillis;
        }
        return 0L;
    }

    /** A group's reconnect count over a sliding window, reset wholesale when it lapses. */
    private static final class ReconnectWindow {
        private long startMillis;
        private int count;

        private ReconnectWindow(long nowMillis) {
            this.startMillis = nowMillis;
        }

        private synchronized int record(long nowMillis, long windowMillis) {
            if (nowMillis - startMillis > windowMillis) {
                startMillis = nowMillis;
                count = 0;
            }
            return ++count;
        }

        private synchronized void reset(long nowMillis) {
            startMillis = nowMillis;
            count = 0;
        }
    }
}
