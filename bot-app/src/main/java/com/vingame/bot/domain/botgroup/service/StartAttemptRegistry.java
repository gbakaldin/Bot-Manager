package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.ClientSafeMessage;
import com.vingame.bot.domain.botgroup.model.StartOrigin;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
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
        StartAttempt attempt = new StartAttempt(origin);
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
     * Compare-and-cancel: call off the attempt in flight <b>only if</b> it has {@code origin}.
     * <p>
     * A separate "is it an attach?" check followed by {@link #cancel} is two steps, and between
     * them the attach can finish and its deferred follow-up (e.g. a scheduled restart) can open
     * the next attempt — which the plain cancel would then hit, leaving a group with no runtime
     * and {@code targetStatus=ACTIVE}. Atomic with {@code begin}/{@code finish} through
     * {@code computeIfPresent}.
     *
     * @return whether an attempt of that origin was open and is now cancelled
     */
    public boolean cancelIf(String botGroupId, StartOrigin origin) {
        boolean[] cancelled = {false};
        open.computeIfPresent(botGroupId, (id, attempt) -> {
            if (attempt.origin == origin) {
                attempt.cancelled = true;
                cancelled[0] = true;
            }
            return attempt;
        });
        return cancelled[0];
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
     * Record an operator-facing reason on the open attempt, for a failure the build <em>handles</em>
     * rather than throws (GATEWAY_REQUEST_BUDGET R10).
     * <p>
     * The zero-bot start path is the case that needs it: it persists {@code targetStatus=DEAD} and
     * a perfectly good {@code lastFailureReason}, then returns <b>normally</b>, so {@link #finish}
     * sees no throwable and {@code /status} would answer {@code DEAD, botsUp: 0, lastError: null}
     * — the canonical start failure, unreported by the field documented to report it.
     * <p>
     * {@code message} must be self-authored and operator-safe; it is published verbatim. A
     * recorded message wins over whatever {@link #finish} would derive from a throwable, which is
     * also how the restart's zero-bot {@code IllegalStateException} keeps its own wording while
     * the type stays sanitised. Last writer wins between two recorded messages: a {@code /restart}
     * whose rebuild came up empty records the zero-bot reason inside {@code startLocked} and then
     * the restart's own, and the restart's is the one an operator asked about.
     */
    public void recordFailure(String botGroupId, String message) {
        StartAttempt attempt = open.get(botGroupId);
        if (attempt != null && message != null) {
            attempt.error = truncate(message);
        }
    }

    /**
     * Close the attempt, retaining its counts and its failure reason for {@code /status}. Must be
     * called from a {@code finally}: an attempt left open reports the group as permanently
     * {@code STARTING} and blocks every later start.
     * <p>
     * <b>The retained message is classified, never a raw {@code toString()}</b>
     * (GATEWAY_REQUEST_BUDGET R2). {@code lastError} is read by {@code GET /{id}/status} and by
     * both acks — an unauthenticated surface — and the build is the most exception-rich path in
     * the application, so the same rule {@code RestExceptionHandler} applies to response bodies
     * applies here: our own exception types say what they mean, everything else is reported by
     * class name with the detail left in the server log, where the ERROR line already carries the
     * full stack trace. See {@link com.vingame.bot.common.exception.ClientSafeMessage}.
     * <p>
     * A reason already set by {@link #recordFailure} is kept: it is more specific than anything
     * derivable from the throwable.
     */
    public Set<FollowUp> finish(String botGroupId, Throwable error) {
        StartAttempt attempt = open.remove(botGroupId);
        if (attempt == null) {
            return EnumSet.noneOf(FollowUp.class);
        }
        if (error != null && attempt.error == null) {
            attempt.error = truncate(ClientSafeMessage.of(error));
        }
        last.put(botGroupId, attempt);
        // Read AFTER the remove: defer() flags through computeIfPresent, which is atomic with
        // the remove, so nothing can be added to this set once it is read here.
        if (attempt.cancelled) {
            // A cancelled attempt is a /stop, a delete or a dying group. Running its follow-ups
            // would restart a group the operator just stopped; the caller logs what was dropped.
            attempt.droppedFollowUps.addAll(attempt.followUps);
            return EnumSet.noneOf(FollowUp.class);
        }
        return attempt.followUps.isEmpty()
                ? EnumSet.noneOf(FollowUp.class)
                : EnumSet.copyOf(attempt.followUps);
    }

    /**
     * Something that arrived while an attempt was in flight and must run after it closes
     * (BOT_PROVISIONING review bug 2 / smell "dropped restarts").
     */
    public enum FollowUp {
        /** A registration completed: attach the new accounts once the holder lets go. */
        ATTACH,
        /** A booked scheduled restart landed during an attach: run it after the attach. */
        RESTART
    }

    /** What {@link #defer} did. */
    public enum Deferral {
        /** Flagged on the open attempt; its closer runs it. */
        DEFERRED,
        /** An attempt is open but this follow-up cannot be queued behind it. */
        NOT_DEFERRABLE,
        /** No attempt is open any more — the caller should simply try {@code begin} again. */
        NO_ATTEMPT
    }

    /**
     * Queue {@code followUp} on the attempt in flight, so the closer of that attempt runs it
     * after {@link #finish}. Atomic with {@code finish}'s remove: either the flag lands on an
     * attempt whose closer will see it, or the answer is {@link Deferral#NO_ATTEMPT} and the
     * caller retries {@code begin} — there is no window in which the request is lost.
     * <p>
     * An {@code ATTACH} can be queued behind any attempt (whoever holds the group re-reads it and
     * catches up). A {@code RESTART} only behind an attach: behind a start or another restart, the
     * pre-existing "already in flight" outcome is unchanged.
     */
    public Deferral defer(String botGroupId, FollowUp followUp) {
        Deferral[] result = {Deferral.NO_ATTEMPT};
        open.computeIfPresent(botGroupId, (id, attempt) -> {
            if (followUp == FollowUp.RESTART && attempt.origin != StartOrigin.ATTACH) {
                result[0] = Deferral.NOT_DEFERRABLE;
            } else {
                attempt.followUps.add(followUp);
                result[0] = Deferral.DEFERRED;
            }
            return attempt;
        });
        return result[0];
    }

    /** The origin of the attempt in flight, if one is open (not the retained one). */
    public Optional<StartOrigin> openOrigin(String botGroupId) {
        return Optional.ofNullable(open.get(botGroupId)).map(a -> a.origin);
    }

    /** Follow-ups the last attempt dropped because it was cancelled, for the closer's log line. */
    public Set<FollowUp> droppedFollowUps(String botGroupId) {
        StartAttempt attempt = last.get(botGroupId);
        return attempt == null || attempt.droppedFollowUps.isEmpty()
                ? EnumSet.noneOf(FollowUp.class)
                : EnumSet.copyOf(attempt.droppedFollowUps);
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
     * One start of one group. Mutable, and every mutable field is atomic or volatile: the
     * counters are incremented from the per-bot creation tasks, {@code phase} is written by the
     * build thread, {@code cancelled} by whoever calls {@code /stop}, and {@code /status} reads
     * all of them from the HTTP thread.
     * <p>
     * Private, with no accessors (R13). Everything outside this class reads an attempt through
     * the registry's own group-keyed methods, so a public accessor surface here was the beginning
     * of an API nobody had asked for — and {@code finishedAt} was written and never read at all.
     */
    private static final class StartAttempt {

        private final StartOrigin origin;
        private final Instant startedAt = Instant.now();
        private final AtomicInteger botsUp = new AtomicInteger();
        private final AtomicInteger botsFailed = new AtomicInteger();
        private volatile Phase phase = Phase.PENDING;
        private volatile boolean cancelled;
        private volatile String error;
        /** Mutated only inside {@code open.computeIfPresent} and read after {@code open.remove}. */
        private final Set<FollowUp> followUps = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Set<FollowUp> droppedFollowUps = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private StartAttempt(StartOrigin origin) {
            this.origin = origin;
        }
    }
}
