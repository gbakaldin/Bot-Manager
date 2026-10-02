package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Tier 1 of LOG_VOLUME_TIERING: folds the per-bot INFO lines emitted at group start
 * into <b>one group-level line each</b>.
 * <p>
 * <b>The problem.</b> Four INFO call sites fired once per bot:
 * {@code BettingMiniGameBot}/{@code SlotMachineBot} "… initialized: …" at construction,
 * and the "balance … below minimum …, triggering deposit" twins on the first session.
 * A 30k-bot fleet start emitted 30k `initialized` lines — a line class whose rate is a
 * direct function of bot count, which is precisely what INFO may no longer contain.
 * All four are now DEBUG at the call site and feed this aggregator, which emits
 * <pre>
 *   group &lt;id&gt; (&lt;name&gt;): 47/47 bots initialized, game=BauCua, strategy=RANDOM
 *   group &lt;id&gt; (&lt;name&gt;): 47 bots auto-deposited, total 2300000000
 * </pre>
 * at INFO. One line per group per start, instead of one per bot.
 * <p>
 * <b>Flush model.</b> Each counter flushes when it goes quiet — {@link #IDLE_FLUSH_NANOS}
 * (5 s) after the last increment, swept by a 1 s virtual-thread tick, the same scheduler
 * idiom as {@link SessionAggregationService}. The <em>initialized</em> counter additionally
 * flushes the instant it reaches the expected count declared by
 * {@link #expectInitialized(String, String, int)}, so the normal case emits exactly one
 * line at the moment the group finishes coming up rather than 5 s later. The idle sweep is
 * what covers the abnormal cases: a group that starts 47 of 50 bots (three auth failures)
 * still gets its {@code 47/50} line, and so does a group nobody declared an expectation for.
 * <p>
 * <b>Identity.</b> Keyed on {@code botGroupId} read from the calling thread's MDC, exactly
 * as {@link SessionAggregationService} and {@link BotMetrics} do — the aggregator never
 * holds a {@code Bot} reference. Bots reach it through
 * {@code Bot.setGroupLifecycleAggregator(...)}, wired in {@code BotFactory.createBot}
 * beside {@code setSessionAggregator}, and every call site is null-tolerant so unit tests
 * without a Spring context still run. A call from a thread with no {@code botGroupId} MDC
 * is dropped silently: the per-bot DEBUG line at the call site survives either way, so the
 * only thing lost is an aggregate that had no group to aggregate under.
 * <p>
 * <b>Anti-leak.</b> Entries are removed as they are emitted, the idle sweep guarantees
 * every entry is emitted, {@link #MAX_GROUPS} force-flushes the least recently touched
 * entry on overflow, and {@link #evictGroup(String)} drops a stopped group's pending
 * counters outright.
 */
@Slf4j
@Component
public class GroupLifecycleAggregator {

    /** Emit an entry once it has been idle this long. 5 s, per the plan's step 2. */
    static final long IDLE_FLUSH_NANOS = 5_000_000_000L;

    /**
     * Hard ceiling on how long an entry may be deferred by fresh contributions. The idle
     * deadline alone is not enough for the deposits counter: {@code recordAutoDeposit}
     * touches the entry on every contribution and has no completion condition (a deposit
     * round is open-ended), so a large group whose bots trickle below {@code minBalance}
     * more often than every 5 s never goes quiet — the summary is deferred indefinitely and
     * {@code amount} silently accumulates across what an operator reads as several separate
     * deposit rounds. 60 s is the same belt-and-braces {@code SessionAggregationService}
     * applies behind its own grace clock.
     */
    static final long MAX_WINDOW_NANOS = 60_000_000_000L;

    /** Sweep cadence. 1 s, so the 5 s idle deadline is honoured to within a second. */
    static final long TICK_SECONDS = 1L;

    /**
     * Hard cap on pending groups. Far above any real fleet (the app manages hundreds of
     * groups, not thousands), so hitting it means something is wrong — but an aggregator
     * added to bound log volume must not itself be able to grow without bound.
     */
    static final int MAX_GROUPS = 2_000;

    /**
     * One group's pending counts for one line class.
     * <p>
     * {@code mdcSnapshot} is captured from the first contributing bot so the emitted line
     * carries {@code botGroupId} / {@code environmentId} / {@code product} even though it
     * may be emitted on the sweep thread, which has no MDC of its own. Keeping the tag on
     * a demoted or aggregated line is what makes the aggregation safe to reason about.
     * <p>
     * Only the <b>group-level</b> keys ({@link BotMdc#GROUP_LEVEL_KEYS}) are kept. Copying
     * the whole context map would stamp a group-scoped fact — "47/47 bots initialized" —
     * with the {@code botId} and {@code botUserName} of whichever bot contributed first: a
     * drill-in filtered on one bot would surface a line about all 47, and the JSON document
     * would name a user who had nothing to do with 46 of them. {@code FleetRollupLogger}
     * already writes only these keys; this makes the two agree.
     */
    private static final class Pending {
        private final String groupId;
        private volatile String groupName;
        /** 0 when nobody declared one — the line then omits the denominator. */
        private volatile int expected;
        /** Free-text tail (game / strategy), captured from the first contributor. */
        private volatile String detail;
        private volatile Map<String, String> mdcSnapshot;
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicLong amount = new AtomicLong();
        private volatile long lastTouchNanos;
        private final long firstTouchNanos;

        private Pending(String groupId, long nowNanos) {
            this.groupId = groupId;
            this.lastTouchNanos = nowNanos;
            this.firstTouchNanos = nowNanos;
        }

        private void touch(long nowNanos) {
            this.lastTouchNanos = nowNanos;
        }

        private String displayName() {
            return groupName != null ? groupName : "?";
        }
    }

    private final ConcurrentHashMap<String, Pending> initializations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending> deposits = new ConcurrentHashMap<>();

    /**
     * Monotonic clock. Injectable so a test can drive the idle and max-window deadlines on
     * ONE timeline: {@link #sweepOnce(long)} already takes a timestamp, and without the feed
     * sites reading the same clock a test that advances the sweep also silently advances the
     * idle deadline it is trying to keep open.
     */
    private final LongSupplier nanoClock;

    private volatile ScheduledExecutorService sweeper;

    // Two constructors (the second is the test seam), so Spring must be told which one --
    // SpringBeanConstructorTest fails the build otherwise, for the reason recorded there.
    @Autowired
    public GroupLifecycleAggregator() {
        this(System::nanoTime);
    }

    GroupLifecycleAggregator(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    @PostConstruct
    public void startSweeper() {
        sweeper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("group-lifecycle-aggregation").factory());
        sweeper.scheduleAtFixedRate(this::runSweep, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        log.info("GroupLifecycleAggregator started (idle flush {}s)", IDLE_FLUSH_NANOS / 1_000_000_000L);
    }

    @PreDestroy
    public void stopSweeper() {
        ScheduledExecutorService scheduler = this.sweeper;
        if (scheduler != null) {
            scheduler.shutdownNow();
            // Emit whatever is still pending rather than swallowing it on shutdown: a
            // group that came up seconds before a stop still deserves its one line.
            flushAll();
            log.info("GroupLifecycleAggregator stopped");
        }
    }

    /** Scheduled entry point — never let an exception kill the fixed-rate task. */
    private void runSweep() {
        try {
            sweepOnce(nanoClock.getAsLong());
        } catch (Exception e) {
            log.error("GroupLifecycleAggregator sweep error: {}", e.getMessage(), e);
        }
    }

    /**
     * Declare how many bots a group is about to create, so its line can read
     * {@code 47/50} rather than a bare {@code 47} — the difference between "the group
     * started" and "the group started and three bots failed to authenticate". Called
     * from the group-start path in {@code BotGroupBehaviorService}, which is the only
     * place that knows both the target count and the group's display name.
     * <p>
     * Idempotent per group: a restart re-declares over the previous expectation.
     *
     * @param botGroupId the group about to create bots
     * @param groupName  its display name, for the emitted line
     * @param expected   the target bot count; {@code <= 0} declares no expectation
     */
    public void expectInitialized(String botGroupId, String groupName, int expected) {
        if (botGroupId == null) {
            return;
        }
        long now = nanoClock.getAsLong();
        Pending pending = initializations.computeIfAbsent(botGroupId, id -> new Pending(id, now));
        pending.groupName = groupName;
        pending.expected = Math.max(expected, 0);
        pending.touch(now);
        enforceCap(initializations);
    }

    /**
     * {@link #expectInitialized} for bots joining a group whose own line may still be open
     * (BOT_PROVISIONING attach). If the group's previous expectation has not flushed yet — a
     * start with a failed bot waits out the idle window — the new bots are <b>added</b> to it
     * rather than replacing it, so the line reads {@code 50/52} instead of {@code 48/2}. With
     * nothing open, it is exactly {@code expectInitialized(…, additional)}.
     */
    public void expectAdditional(String botGroupId, String groupName, int additional) {
        if (botGroupId == null) {
            return;
        }
        long now = nanoClock.getAsLong();
        boolean[] created = {false};
        Pending pending = initializations.computeIfAbsent(botGroupId, id -> {
            created[0] = true;
            return new Pending(id, now);
        });
        pending.groupName = groupName;
        pending.expected = created[0]
                ? Math.max(additional, 0)
                : pending.expected + Math.max(additional, 0);
        pending.touch(now);
        enforceCap(initializations);
    }

    /**
     * Per-bot "initialized" feed, called from {@code initializeSubclass()} on the
     * bot-creation thread (which has the bot's MDC applied by {@code Bot.initialize}).
     *
     * @param detail the game/strategy tail shared by every bot in the group, e.g.
     *               {@code "game=BauCua, strategy=RANDOM"}. Captured from the first
     *               contributor only — it is group-uniform by construction.
     */
    public void recordInitialized(String detail) {
        String botGroupId = MDC.get(BotMdc.BOT_GROUP_ID);
        if (botGroupId == null) {
            return; // no group identity on this thread — the per-bot DEBUG line still stands
        }
        long now = nanoClock.getAsLong();
        Pending pending = initializations.computeIfAbsent(botGroupId, id -> new Pending(id, now));
        if (pending.detail == null) {
            pending.detail = detail;
        }
        if (pending.mdcSnapshot == null) {
            pending.mdcSnapshot = groupLevelMdc();
        }
        int seen = pending.count.incrementAndGet();
        pending.touch(now);
        enforceCap(initializations);

        // Complete: emit now rather than waiting out the idle window, so the line lands
        // with the group start it describes. remove() makes the emit single-winner.
        if (pending.expected > 0 && seen >= pending.expected
                && initializations.remove(botGroupId, pending)) {
            emitInitialized(pending);
        }
    }

    /**
     * Per-bot auto-deposit feed, called where the bot decides its balance is below the
     * configured minimum. Unlike initializations there is no expected count — a deposit
     * round is inherently open-ended — so this line is always emitted by the idle sweep.
     *
     * @param amount the balance shortfall triggering the deposit, for the summed total
     */
    public void recordAutoDeposit(long amount) {
        String botGroupId = MDC.get(BotMdc.BOT_GROUP_ID);
        if (botGroupId == null) {
            return;
        }
        long now = nanoClock.getAsLong();
        Pending pending = deposits.computeIfAbsent(botGroupId, id -> new Pending(id, now));
        if (pending.mdcSnapshot == null) {
            pending.mdcSnapshot = groupLevelMdc();
        }
        pending.count.incrementAndGet();
        pending.amount.addAndGet(amount);
        pending.touch(now);
        enforceCap(deposits);
    }

    /**
     * Drop a stopped group's pending counters. Called from the group teardown path
     * alongside {@code SessionAggregationService.evictGroup}; safe to call anytime.
     */
    public void evictGroup(String botGroupId) {
        if (botGroupId == null) {
            return;
        }
        initializations.remove(botGroupId);
        deposits.remove(botGroupId);
    }

    /**
     * One idle-sweep pass. Package-private and clock-injected so tests drive it directly
     * and advance time without sleeping the interval — the same seam
     * {@code SessionAggregationService.flushOnce} uses.
     */
    void sweepOnce(long nowNanos) {
        drainIdle(initializations, nowNanos, false, this::emitInitialized);
        drainIdle(deposits, nowNanos, false, this::emitDeposits);
    }

    private void drainIdle(ConcurrentHashMap<String, Pending> map, long nowNanos,
                           boolean force, Consumer<Pending> emit) {
        for (Map.Entry<String, Pending> entry : map.entrySet()) {
            Pending pending = entry.getValue();
            // Idle OR too old. The max-window arm is what stops an entry that is touched
            // more often than the idle deadline from being deferred forever.
            boolean due = force
                    || nowNanos - pending.lastTouchNanos >= IDLE_FLUSH_NANOS
                    || nowNanos - pending.firstTouchNanos >= MAX_WINDOW_NANOS;
            if (!due) {
                continue;
            }
            // Value-guarded remove: a sweep racing a concurrent increment must not drop
            // a freshly touched entry, and only the winner emits.
            if (map.remove(entry.getKey(), pending)) {
                try {
                    emit.accept(pending);
                } catch (Exception e) {
                    log.warn("GroupLifecycleAggregator: failed to emit summary for group {}: {}",
                            pending.groupId, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Emit everything pending regardless of idleness (shutdown path).
     * <p>
     * An explicit {@code force} flag, not a sentinel {@code nowNanos}. Faking "everything is
     * idle" with {@code Long.MAX_VALUE / 2} relies on {@code nowNanos - lastTouchNanos}
     * being large and positive, and {@link System#nanoTime()}'s origin is unspecified — the
     * JDK is explicit that only differences between two readings are meaningful. With a
     * large negative origin the subtraction overflows negative, the idle guard passes, and
     * the shutdown flush silently emits <em>nothing</em>: the exact opposite of what it is
     * for.
     */
    private void flushAll() {
        long now = nanoClock.getAsLong();
        drainIdle(initializations, now, true, this::emitInitialized);
        drainIdle(deposits, now, true, this::emitDeposits);
    }

    private void emitInitialized(Pending pending) {
        int seen = pending.count.get();
        String counts = pending.expected > 0 ? seen + "/" + pending.expected : String.valueOf(seen);
        String detail = pending.detail != null ? ", " + pending.detail : "";
        withMdc(pending, () -> log.info("group {} ({}): {} bots initialized{}",
                pending.groupId, pending.displayName(), counts, detail));
    }

    private void emitDeposits(Pending pending) {
        withMdc(pending, () -> log.info("group {} ({}): {} bots auto-deposited, total {}",
                pending.groupId, pending.displayName(), pending.count.get(), pending.amount.get()));
    }

    /**
     * The calling bot's MDC narrowed to {@link BotMdc#GROUP_LEVEL_KEYS} — see {@link Pending}
     * for why the whole map is the wrong thing to snapshot.
     */
    private static Map<String, String> groupLevelMdc() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String key : BotMdc.GROUP_LEVEL_KEYS) {
            String value = MDC.get(key);
            if (value != null) {
                snapshot.put(key, value);
            }
        }
        return snapshot;
    }

    /**
     * Run {@code body} under the entry's captured MDC, restoring the caller's afterwards.
     * The sweep thread has no MDC of its own, so without this the aggregated line would
     * lose the {@code botGroupId} tag that every drill-in query depends on.
     */
    private void withMdc(Pending pending, Runnable body) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (pending.mdcSnapshot != null) {
                MDC.setContextMap(pending.mdcSnapshot);
            } else {
                MDC.clear();
                MDC.put(BotMdc.BOT_GROUP_ID, pending.groupId);
            }
            body.run();
        } finally {
            if (previous != null) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        }
    }

    /**
     * Bound the pending map (anti-leak). While over {@link #MAX_GROUPS}, emit and drop the
     * least recently touched entry — emitting rather than discarding, so an overflow costs
     * an early line rather than a lost one.
     */
    private void enforceCap(ConcurrentHashMap<String, Pending> map) {
        while (map.size() > MAX_GROUPS) {
            Map.Entry<String, Pending> oldest = map.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().lastTouchNanos))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            if (map.remove(oldest.getKey(), oldest.getValue())) {
                log.warn("GroupLifecycleAggregator: pending-group cap {} exceeded — flushing oldest {}",
                        MAX_GROUPS, oldest.getKey());
                if (map == initializations) {
                    emitInitialized(oldest.getValue());
                } else {
                    emitDeposits(oldest.getValue());
                }
            }
        }
    }

    /** Pending group counts — exposed for tests. */
    List<Integer> pendingSizes() {
        List<Integer> sizes = new ArrayList<>(2);
        sizes.add(initializations.size());
        sizes.add(deposits.size());
        return sizes;
    }
}
