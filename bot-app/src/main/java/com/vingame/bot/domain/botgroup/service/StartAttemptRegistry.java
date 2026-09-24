package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.botgroup.model.StartOrigin;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory record of the bot-group starts that are in flight, and of the last one that
 * finished (GATEWAY_REQUEST_BUDGET Phase 2, AD-15/AD-16/A1).
 * <p>
 * It exists because a start stopped being an HTTP-request-shaped thing. Under the gateway
 * request budget a 3,000-bot group legitimately takes 33-50 minutes to come up, so
 * {@code POST /start} answers {@code 200} the moment the work is <em>accepted</em> and the
 * build runs on a virtual thread. Three questions then have no other home:
 * <ul>
 *   <li><b>"is one already running?"</b> — {@link #begin} is a {@code putIfAbsent}, so a
 *       double-click submits one task, not two, and the second caller learns it lost;</li>
 *   <li><b>"how far along is it?"</b> — {@link #botsUp(String)} against the group's
 *       {@code botCount}, which is what {@code /status} renders while
 *       {@code actualStatus == STARTING};</li>
 *   <li><b>"should this stop?"</b> — {@link #cancel} flips a flag the build polls. It is a
 *       flag and not a thread interrupt because {@code CompletableFuture.join()} is not
 *       interruptible and {@code VingameWebSocketClient.connect()} swallows
 *       {@code InterruptedException} (see {@code GatewayRequestScope}); an interrupt-based
 *       design would be a design that does not work here.</li>
 * </ul>
 * <p>
 * <b>What it deliberately is not.</b> Nothing here is persisted, and nothing here is a
 * status: {@code BotGroupStatus.STARTING} lives on the runtime (A1), and the registry only
 * carries the detail that hangs off it. A JVM restart loses every attempt, which is correct
 * — an interrupted start is not a state to resume.
 * <p>
 * <b>Ownership.</b> Constructed and owned by {@link BotGroupBehaviorService} rather than
 * being a Spring bean. It has no dependencies, only one collaborator needs it (everything
 * else reads it through the behaviour service's accessors), and keeping it off the
 * constructor keeps every {@code @InjectMocks} fixture of that 2,500-line service compiling
 * and non-null — which is also why none of the call sites below have to be null-guarded.
 * <p>
 * Thread-safe: two {@link ConcurrentHashMap}s and per-attempt atomics/volatiles. Every
 * method is safe to call from the HTTP thread, the start's virtual thread, a reconciler tick
 * and a bot-creation task at the same time.
 */
@Slf4j
public class StartAttemptRegistry {

    /** How much of a failure message is retained for {@code /status}. */
    private static final int MAX_ERROR_LENGTH = 500;

    /** Attempts currently in flight, keyed by bot-group id. */
    private final Map<String, StartAttempt> open = new ConcurrentHashMap<>();

    /**
     * The most recent finished attempt per group, retained so {@code /status} can still
     * answer "how many came up" and "why did it fail" after the build ended. Cleared by
     * {@link #clearRetained(String)} on stop/delete, replaced by the next {@link #begin}, so the map
     * is bounded by the number of groups started since boot and never grows per attempt.
     */
    private final Map<String, StartAttempt> last = new ConcurrentHashMap<>();

    /**
     * Open an attempt for {@code botGroupId} unless one is already open.
     *
     * @return {@code true} when this call opened it — i.e. the caller owns the build and
     *         must eventually {@link #finish}. {@code false} means a start is already in
     *         flight and the caller must <b>not</b> submit a second one.
     */
    public boolean begin(String botGroupId, StartOrigin origin) {
        StartAttempt attempt = new StartAttempt(botGroupId, origin);
        boolean opened = open.putIfAbsent(botGroupId, attempt) == null;
        if (opened) {
            // A new start supersedes whatever the previous one reported: "botsUp = 12,
            // lastError = auth failed" from an hour ago would read as this attempt's
            // progress the moment /status is polled.
            last.remove(botGroupId);
        }
        return opened;
    }

    /** Advance the open attempt's phase; a no-op when nothing is open. */
    public void progress(String botGroupId, Phase phase) {
        StartAttempt attempt = open.get(botGroupId);
        if (attempt != null) {
            attempt.phase = phase;
        }
    }

    /** One more bot came up in the current build. */
    public void botUp(String botGroupId) {
        StartAttempt attempt = open.get(botGroupId);
        if (attempt != null) {
            attempt.botsUp.incrementAndGet();
        }
    }

    /** One more bot failed to come up in the current build. */
    public void botFailed(String botGroupId) {
        StartAttempt attempt = open.get(botGroupId);
        if (attempt != null) {
            attempt.botsFailed.incrementAndGet();
        }
    }

    /**
     * Call off the start in flight, if any. The attempt stays open — the build is what
     * closes it, after it has unwound — so a second {@code /stop} is idempotent and the
     * group keeps reporting {@code STARTING} until the teardown actually completes.
     *
     * @return whether there was an attempt to cancel (for the caller's log line)
     */
    public boolean cancel(String botGroupId) {
        StartAttempt attempt = open.get(botGroupId);
        if (attempt == null) {
            return false;
        }
        attempt.cancelled = true;
        return true;
    }

    /**
     * Whether the start in flight for this group has been called off. {@code false} when no
     * start is open, which is what makes this safe to poll from the per-bot creation task
     * without knowing whether a start is tracked at all.
     */
    public boolean isCancelled(String botGroupId) {
        StartAttempt attempt = open.get(botGroupId);
        return attempt != null && attempt.cancelled;
    }

    /** Whether a start is in flight for this group (AD-16's "running" for reconcilers). */
    public boolean isOpen(String botGroupId) {
        return open.containsKey(botGroupId);
    }

    /**
     * Close the attempt, retaining its counts and — when {@code error} is non-null — its
     * message for {@code /status}. Must be called from a {@code finally}: an attempt left
     * open reports the group as permanently {@code STARTING} and blocks every later start.
     */
    public void finish(String botGroupId, Throwable error) {
        StartAttempt attempt = open.remove(botGroupId);
        if (attempt == null) {
            return;
        }
        attempt.finishedAt = Instant.now();
        attempt.error = error == null ? null : truncate(error.toString());
        last.put(botGroupId, attempt);
    }

    /**
     * Forget the retained record of this group's last start — the stop/delete path.
     * {@code lastError} and the progress counts are retained "until the next start or stop"
     * (AD-17), and this is the stop half; it also keeps {@link #last} bounded.
     * <p>
     * <b>An attempt that is still OPEN is deliberately left alone</b>, and that is not tidiness.
     * The cancellation flag a cancelled build polls lives on the open attempt, so removing it
     * would <em>uncancel</em> the build: a {@code /stop} that lands in the window between
     * "accepted" and "the build took the group lock" would cancel the attempt, find no runtime to
     * tear down, persist {@code STOPPED}, drop the attempt — and the build would then wake up,
     * see no cancellation, and bring the group up anyway. The build's own {@code finally} is what
     * closes an open attempt, always.
     */
    public void clearRetained(String botGroupId) {
        last.remove(botGroupId);
    }

    /**
     * Bots built so far by the current start, or by the last one that finished.
     * {@code null} when this group has not been started in this JVM — which is what the DTO
     * renders as "no progress to report" rather than as zero.
     */
    public Integer botsUp(String botGroupId) {
        StartAttempt attempt = current(botGroupId);
        return attempt == null ? null : attempt.botsUp.get();
    }

    /** Bots that failed in the current or last start; {@code null} when there was none. */
    public Integer botsFailed(String botGroupId) {
        StartAttempt attempt = current(botGroupId);
        return attempt == null ? null : attempt.botsFailed.get();
    }

    /** The last start failure for this group, or {@code null}. */
    public String lastError(String botGroupId) {
        StartAttempt attempt = last.get(botGroupId);
        return attempt == null ? null : attempt.error;
    }

    /** The origin of the current or last attempt. */
    public Optional<StartOrigin> origin(String botGroupId) {
        return Optional.ofNullable(current(botGroupId)).map(a -> a.origin);
    }

    /**
     * One-line rendering of the attempt in flight, for the log line a second {@code /start}
     * emits. Never throws; {@code "none"} when nothing is open.
     */
    public String describe(String botGroupId) {
        StartAttempt attempt = open.get(botGroupId);
        if (attempt == null) {
            return "none";
        }
        return "origin " + attempt.origin + ", phase " + attempt.phase
                + ", " + attempt.botsUp.get() + " up / " + attempt.botsFailed.get() + " failed"
                + ", elapsed " + Duration.between(attempt.startedAt, Instant.now()).toSeconds() + "s"
                + (attempt.cancelled ? ", cancelled" : "");
    }

    private StartAttempt current(String botGroupId) {
        StartAttempt attempt = open.get(botGroupId);
        return attempt != null ? attempt : last.get(botGroupId);
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    /**
     * How far a start has got. Coarse on purpose — it exists to make a log line about a
     * cancelled or duplicated start say something useful, not to be a state machine.
     */
    public enum Phase {

        /** Accepted, submitted, not yet inside the per-group lock. */
        PENDING,

        /** Inside {@code startLocked}: environment and game resolved, bots being built. */
        BUILDING,

        /** Bots built; being handed to the group's executor. */
        STARTING_BOTS
    }

    /**
     * One start of one group. Mutable and thread-confined only in the sense that every
     * mutable field is atomic or volatile: the counters are incremented from the per-bot
     * creation tasks, {@code phase} is written by the build thread, {@code cancelled} by
     * whoever calls {@code /stop}, and {@code /status} reads all of them from the HTTP
     * thread.
     */
    public static final class StartAttempt {

        private final String botGroupId;
        private final StartOrigin origin;
        private final Instant startedAt = Instant.now();
        private final AtomicInteger botsUp = new AtomicInteger();
        private final AtomicInteger botsFailed = new AtomicInteger();
        private volatile Phase phase = Phase.PENDING;
        private volatile boolean cancelled;
        private volatile Instant finishedAt;
        private volatile String error;

        private StartAttempt(String botGroupId, StartOrigin origin) {
            this.botGroupId = botGroupId;
            this.origin = origin;
        }

        public String botGroupId() {
            return botGroupId;
        }

        public StartOrigin origin() {
            return origin;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public Phase phase() {
            return phase;
        }

        public int botsUp() {
            return botsUp.get();
        }

        public int botsFailed() {
            return botsFailed.get();
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public Instant finishedAt() {
            return finishedAt;
        }

        public String error() {
            return error;
        }
    }
}
