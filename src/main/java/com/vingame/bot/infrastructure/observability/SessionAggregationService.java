package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * App-wide singleton that replaces per-frame WebSocket log noise with per-session
 * semantic summaries (AGGREGATED_SESSION_LOGGING plan AD-1/AD-2/AD-5/AD-8).
 * <p>
 * Bots feed it from their existing inbound/outbound handlers — {@code onStartGame}
 * ({@link #onSessionStart}), the {@code bet()} supplier ({@link #recordBet}), and
 * {@code onEndGame} ({@link #onSessionEnd}). Identity ({@code botGroupId},
 * {@code gameId}, {@code gameName}) is read from the calling thread's MDC exactly
 * as {@link BotMetrics} does; the {@code sid} is passed explicitly, so the service
 * never holds a {@code Bot} reference. It is injected into every bot via
 * {@code Bot.setSessionAggregator(...)} in {@code BotFactory.createBot}, mirroring
 * {@code setMetrics}, and is null-tolerant on the bot side so unit tests without
 * Spring still run.
 * <p>
 * <b>Emit levels (resolved decision).</b> The StartGame session-entry line and the
 * EndGame results summary emit at <b>INFO</b> — they are per-session/per-round
 * lifecycle, one line per group per round, not per-bot detail. The 5s UpdateBet
 * running summary ({@link #flushOnce(long)}) emits at <b>DEBUG</b> (per-round detail,
 * one line per active session every 5s — 720 lines/hr/session would breach the INFO
 * low-tens/hr norm).
 * <p>
 * <b>Slots (Phase 3, AD-12).</b> Slot machines have no shared {@code sid} round and
 * no StartGame/EndGame, so instead of a session key they use a synthetic per-{@code
 * (botGroupId, gameId)} rolling window created lazily on the first {@link #recordSpin}
 * feed and fed win/jackpot data by {@link #recordSpinResult}. The same 5s flush emits
 * one <b>DEBUG</b> slot window summary per {@code (group, gameId)} via
 * {@link SlotSessionStrategy}; slots have no INFO lifecycle line. The window reuses the
 * SAME eviction machinery — TTL sweep and {@code evictGroup} — so it cannot leak either.
 * <p>
 * <b>Anti-leak invariant (load-bearing).</b> The session map is bounded four ways
 * (AD-8), so unbounded growth — the leak-driven outage this feature exists to
 * prevent — is impossible by construction:
 * <ol>
 *   <li>a hard {@link #MAX_SESSIONS} cap enforced on every insert and on each flush
 *       tick (oldest entry dropped + one WARN on overflow);</li>
 *   <li>a per-entry {@code lastActivityNanos} backing the {@link #TTL_NANOS} idle
 *       sweep in the 5s flush task (reclaims sessions whose EndGame was never
 *       observed — server pruning, disconnect, a group stopped mid-round);</li>
 *   <li>grace-then-evict of ended sessions ({@link #GRACE_NANOS} after the last
 *       activity) in the same flush task, so a late straggler EndGame/flush cannot
 *       resurrect-then-orphan an entry;</li>
 *   <li>{@link #evictGroup(String)} for immediate group-stop teardown, wired into
 *       {@code BotGroupBehaviorService.stop}.</li>
 * </ol>
 * The flush scheduler is a single app-wide virtual-thread executor started in
 * {@link #startFlushScheduler()} and shut down cleanly in {@link #stopFlushScheduler()}
 * so no scheduler thread leaks.
 * <p>
 * All feed methods are lock-free (the accumulator uses {@link java.util.concurrent.atomic.LongAdder}
 * and a concurrent key set) and the first-seen guards are race-free ({@code putIfAbsent}
 * for StartGame, a per-accumulator CAS for EndGame), so a netty IO thread or a
 * scenario virtual thread is never blocked.
 */
@Slf4j
@Component
public class SessionAggregationService {

    /** Hard cap on live sessions. On overflow the oldest entry is dropped + one WARN. */
    static final int MAX_SESSIONS = 10_000;

    /** Idle TTL after which a stale/abandoned session is swept. 60s per plan AD-8. */
    static final long TTL_NANOS = 60_000_000_000L;

    /**
     * Grace period after a session's last activity before an ended session is
     * removed (AD-8) — one flush interval, so a late straggler EndGame/flush that
     * touches the entry resets this clock rather than resurrecting an orphan.
     */
    static final long GRACE_NANOS = 5_000_000_000L;

    /** Flush / TTL-sweep interval (AD-7). One tick every 5s. */
    static final long FLUSH_INTERVAL_SECONDS = 5L;

    /**
     * Synthetic session id for the per-{@code (botGroupId, gameId)} slot window
     * (AD-12). Slots have no server {@code sid} round, so every spin for a
     * {@code (group, gameId)} folds into ONE long-lived rolling accumulator under
     * this sentinel sid. {@code Long.MIN_VALUE} can never collide with a real
     * server sid (always positive), and {@code gameId} already keeps distinct slot
     * games apart, so exactly one window exists per {@code (group, gameId)}.
     */
    static final long SLOT_WINDOW_SID = Long.MIN_VALUE;

    /** Key = {@code (botGroupId, gameId, sid)} (AD-2). */
    record SessionKey(String botGroupId, String gameId, long sid) {
    }

    private final ConcurrentHashMap<SessionKey, SessionAccumulator> sessions = new ConcurrentHashMap<>();

    /**
     * Single app-wide virtual-thread scheduler driving the 5s UpdateBet flush and
     * the TTL/grace eviction sweep (AD-7). One shared scheduler — not per-group —
     * keeps thread count flat and matches the singleton model. Started in
     * {@link #startFlushScheduler()}, shut down in {@link #stopFlushScheduler()}.
     */
    private volatile ScheduledExecutorService flushScheduler;

    /**
     * Start the single app-wide 5s flush + eviction scheduler (AD-7). Mirrors the
     * virtual-thread scheduled-executor pattern of
     * {@code BotGroupBehaviorService.startHealthMonitoring}. Runs one tick every
     * {@link #FLUSH_INTERVAL_SECONDS}s.
     */
    @PostConstruct
    public void startFlushScheduler() {
        flushScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("session-aggregation-flush").factory());
        flushScheduler.scheduleAtFixedRate(this::runFlush,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("SessionAggregationService flush scheduler started ({}s interval, TTL {}s)",
                FLUSH_INTERVAL_SECONDS, TTL_NANOS / 1_000_000_000L);
    }

    /**
     * Shut the flush scheduler down cleanly on context teardown so no virtual-thread
     * scheduler leaks (load-bearing for the anti-leak invariant).
     */
    @PreDestroy
    public void stopFlushScheduler() {
        ScheduledExecutorService scheduler = this.flushScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
            log.info("SessionAggregationService flush scheduler stopped");
        }
    }

    /** Scheduled entry point — never let an exception kill the fixed-rate task. */
    private void runFlush() {
        try {
            flushOnce(System.nanoTime());
        } catch (Exception e) {
            log.error("SessionAggregationService flush error: {}", e.getMessage(), e);
        }
    }

    /**
     * One flush + eviction pass (AD-6/AD-7/AD-8). Package-private and clock-injected
     * ({@code nowNanos}) so tests drive it directly and advance time without sleeping
     * the 5s interval.
     * <p>
     * For each live session, in order:
     * <ul>
     *   <li><b>Evict</b> if idle past {@link #TTL_NANOS} (stale/abandoned — EndGame
     *       never observed) or ended and idle past {@link #GRACE_NANOS} (grace-then-
     *       evict). Removed with the value-guarded {@code remove(k, v)} so a flush
     *       racing a concurrent re-insert can't drop a fresh entry.</li>
     *   <li>Otherwise, for an <b>active</b> round-boundary session (not ended), emit
     *       ONE DEBUG UpdateBet running-summary line under the entry's captured MDC,
     *       then advance its since-last-flush baseline and flush sequence.</li>
     * </ul>
     * Ended sessions are not logged (avoid spamming a closed round); idle non-ended
     * sessions past TTL are evicted rather than logged. Finishes with the
     * {@link #enforceSizeCap()} backstop.
     */
    void flushOnce(long nowNanos) {
        for (Map.Entry<SessionKey, SessionAccumulator> entry : sessions.entrySet()) {
            SessionKey key = entry.getKey();
            SessionAccumulator acc = entry.getValue();

            // Per-session containment (anti-leak, load-bearing). Guard each entry's
            // eviction/emit in its own try/catch so a single poisoned session (a
            // renderer/strategy throw) cannot unwind the loop and skip the TTL/grace
            // eviction of every session after it in this tick, nor the trailing
            // enforceSizeCap() backstop. Lock-free; the catch only logs and continues.
            try {
                long idleNanos = nowNanos - acc.lastActivityNanos();
                boolean stale = idleNanos >= TTL_NANOS;
                boolean endedPastGrace = acc.isEnded() && idleNanos >= GRACE_NANOS;
                if (stale || endedPastGrace) {
                    sessions.remove(key, acc);
                    continue;
                }

                // Emit the periodic summary for every live, not-yet-ended session: the
                // UpdateBet running line for active round-based sessions and the slot
                // window line for slots (which never mark `ended`, so they flush until
                // the TTL sweep or group stop reclaims them — AD-12). Ended-within-grace
                // round sessions are skipped (their round is closed).
                if (!acc.isEnded()) {
                    emitFlush(key, acc);
                }
            } catch (Exception e) {
                // Log under the entry's captured MDC (botGroupId/gameType/etc.) so a
                // persistently-throwing session is visible, then continue to the next
                // entry — one bad session must not skip its neighbours' eviction.
                logFlushError(key, acc, e);
            }
        }
        enforceSizeCap();
    }

    /**
     * Emit one UpdateBet flush line for an active session and advance its baseline.
     * Runs on the single flush thread, so the baseline advance needs no CAS (AD-6);
     * counters are read lock-free. The line is tagged with the session's captured MDC
     * so it carries {@code botGroupId}/{@code gameType}/etc. even though the flush
     * thread has no MDC of its own.
     */
    private void emitFlush(SessionKey key, SessionAccumulator acc) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            applyMdc(acc.mdcSnapshot());
            acc.nextFlushSeq();
            SessionContext ctx = contextFor(key);
            // Snapshot the flush-delta counters ONCE before rendering so the rendered
            // "since last" delta and the baseline advance below read identical values.
            // Without this the render read and a re-read at advance time straddle a
            // concurrent recordBet/recordSpin, and the in-between arrival is lost from
            // both this tick's delta (rendered first) and the next's (baseline already
            // advanced past it). The flush thread is the sole baseline writer, so this
            // stays single-writer — no CAS (AD-6).
            acc.captureFlushSnapshot();
            log.debug(acc.strategy().renderFlushLine(acc, ctx));
            // Advance the since-last-flush baselines to the SAME snapshot the render
            // used (not a fresh re-read), so the next tick's deltas ("new bettors since
            // last" / "spins since last") count only arrivals after the snapshot. Both
            // baselines are advanced every tick — each strategy reads only the one it
            // renders.
            acc.advanceBaseline(acc.flushBettorSnapshot(), acc.flushStakedSnapshot());
            acc.advanceSpinBaseline(acc.flushSpinSnapshot());
        } finally {
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            } else {
                MDC.clear();
            }
        }
    }

    /**
     * WARN-log a contained per-session flush failure under the session's captured MDC,
     * restoring the previous MDC afterwards. Kept separate so the containment catch in
     * {@link #flushOnce(long)} stays a one-liner and cannot itself throw the loop off
     * course (MDC restore is in a finally).
     */
    private void logFlushError(SessionKey key, SessionAccumulator acc, Exception e) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            applyMdc(acc.mdcSnapshot());
            log.warn("SessionAggregationService: flush error for session {} — skipped this tick: {}",
                    key, e.getMessage(), e);
        } finally {
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            } else {
                MDC.clear();
            }
        }
    }

    private static void applyMdc(Map<String, String> snapshot) {
        if (snapshot != null) {
            MDC.setContextMap(snapshot);
        } else {
            MDC.clear();
        }
    }

    /**
     * StartGame feed. The FIRST bot to observe {@code sid} for its
     * {@code (botGroupId, gameId)} logs one session-entry line for the whole group;
     * the other 99 bots are no-ops. The winner is decided race-free by
     * {@code putIfAbsent} — only the thread whose insert wins logs.
     *
     * @param sid       the session id from {@code StartGameMessage.getSessionId()}
     * @param strategy  the per-game-type render strategy (a singleton)
     * @param rawSample lazy supplier of one raw message sample; invoked ONLY on the
     *                  winning first-seen path, so the 99 losing bots never serialize
     *                  the frame (AD-9). May be {@code null}.
     */
    public void onSessionStart(long sid, SessionAggregationStrategy strategy, Supplier<String> rawSample) {
        SessionKey key = keyFor(sid);
        if (key == null) {
            return; // no MDC identity (e.g. non-bot thread) — nothing to key on
        }
        SessionAccumulator candidate = new SessionAccumulator(
                strategy, MDC.getCopyOfContextMap(), System.nanoTime());
        SessionAccumulator existing = sessions.putIfAbsent(key, candidate);
        if (existing != null) {
            return; // lost the race — another bot already logged the entry line
        }
        enforceSizeCap();

        SessionContext ctx = contextFor(key);
        String line = strategy.renderStartLine(candidate, ctx);
        String sample = rawSample != null ? rawSample.get() : null;
        if (sample != null) {
            log.info("{} | sample: {}", line, sample);
        } else {
            log.info(line);
        }
    }

    /**
     * Outbound-bet feed (the uniform cross-product stake source — the UpdateBet
     * frame body carries no bettor/stake data, AD, Findings). Lock-free.
     * <p>
     * If no accumulator exists for the key the bet is dropped silently: a bot only
     * bets after its own {@code onStartGame} created the entry, so this is an edge
     * case (e.g. a late bet on an already-evicted round), never the steady state.
     *
     * @param sid    the session the bet was staked in ({@code sidStore.get()})
     * @param bettor the bot's user name (distinct-bettor key)
     * @param amount the staked amount
     */
    public void recordBet(long sid, String bettor, long amount) {
        SessionKey key = keyFor(sid);
        if (key == null) {
            return;
        }
        SessionAccumulator acc = sessions.get(key);
        if (acc == null) {
            return;
        }
        acc.recordBet(bettor, amount);
    }

    /**
     * Slot spin feed — the outbound {@code spin()} tap (AD-12). Slots have no
     * StartGame to create the accumulator, so this creates the per-{@code (group,
     * gameId)} synthetic window lazily on the first spin ({@code computeIfAbsent}
     * under {@link #SLOT_WINDOW_SID}) and records the staked spin. Lock-free; runs
     * on the scenario thread with the bot's MDC applied (so the captured snapshot
     * tags the flush lines).
     *
     * @param strategy   the slot render strategy ({@link SlotSessionStrategy#INSTANCE})
     * @param bettor     the bot's user name (distinct-spinner key)
     * @param totalStake the total staked for the spin ({@code perLineBet * numLines})
     */
    public void recordSpin(SessionAggregationStrategy strategy, String bettor, long totalStake) {
        SessionKey key = keyFor(SLOT_WINDOW_SID);
        if (key == null) {
            return;
        }
        SessionAccumulator acc = sessions.computeIfAbsent(key, k ->
                new SessionAccumulator(strategy, MDC.getCopyOfContextMap(), System.nanoTime()));
        acc.recordBet(bettor, totalStake);
        enforceSizeCap();
    }

    /**
     * Slot spin-result feed — the inbound {@code onSpinResult} tap (AD-12). Adds the
     * spin's gross winnings and a jackpot hit (when the frame's {@code iJ} flag is
     * set) to the same synthetic window. A no-op if the window was already evicted
     * (TTL/group-stop) or the spin send was never recorded. Lock-free.
     *
     * @param winnings this spin's gross winnings ({@code sum(wls[].crd)})
     * @param jackpot  whether the spin hit a jackpot ({@code iJ})
     */
    public void recordSpinResult(long winnings, boolean jackpot) {
        SessionKey key = keyFor(SLOT_WINDOW_SID);
        if (key == null) {
            return;
        }
        SessionAccumulator acc = sessions.get(key);
        if (acc == null) {
            return;
        }
        acc.recordWin(winnings, jackpot);
    }

    /**
     * EndGame feed. Every bot accumulates its own {@code winnings}/{@code betAmount}
     * first; then exactly one bot — the first to win the per-accumulator CAS — logs
     * the results summary. Winnings use the value the bot already computed via
     * {@code HasBotWinnings}/{@code HasBetTotals}, which is correct per game type
     * (including Tai Xiu's {@code G}).
     *
     * @param sid       the just-closed session id ({@code endGameSessionId(msg)} — for
     *                  Tai Xiu the tracked {@code sidStore} value, since its frame has
     *                  no {@code sid})
     * @param winnings  this bot's gross winnings for the round
     * @param betAmount this bot's server-confirmed staked amount for the round
     * @param rawSample lazy supplier of one raw sample; invoked ONLY on the first-close
     *                  path (AD-9). May be {@code null}.
     */
    public void onSessionEnd(long sid, long winnings, long betAmount, Supplier<String> rawSample) {
        SessionKey key = keyFor(sid);
        if (key == null) {
            return;
        }
        SessionAccumulator acc = sessions.get(key);
        if (acc == null) {
            return; // round already evicted, or never observed a StartGame
        }
        acc.recordEnd(winnings, betAmount);
        if (!acc.markEndLogged()) {
            return; // another bot already logged the summary for this session
        }
        SessionContext ctx = contextFor(key);
        String line = acc.strategy().renderEndLine(acc, ctx);
        String sample = rawSample != null ? rawSample.get() : null;
        if (sample != null) {
            log.info("{} | sample: {}", line, sample);
        } else {
            log.info(line);
        }
        // Grace-then-evict (AD-8): recordEnd() marked the entry `ended` and touched
        // lastActivityNanos, so the next flush tick removes it once idle past
        // GRACE_NANOS. The entry lingers for the grace window so a late straggler
        // EndGame/flush touches (and thus re-graces) it rather than resurrecting an
        // orphan; the TTL sweep is the backstop if the grace clock keeps getting reset.
    }

    /**
     * Remove every live session belonging to a stopped group (AD-8 group-stop hook).
     * Called from the group teardown path in Phase 2; safe to call anytime.
     */
    public void evictGroup(String botGroupId) {
        if (botGroupId == null) {
            return;
        }
        sessions.keySet().removeIf(k -> botGroupId.equals(k.botGroupId()));
    }

    /** Live session count — exposed for tests and (later) a heap gauge. */
    int liveSessionCount() {
        return sessions.size();
    }

    // ---- internals ----

    private SessionKey keyFor(long sid) {
        String botGroupId = MDC.get(BotMdc.BOT_GROUP_ID);
        String gameId = MDC.get(BotMdc.GAME_ID);
        if (botGroupId == null || gameId == null) {
            return null;
        }
        return new SessionKey(botGroupId, gameId, sid);
    }

    private SessionContext contextFor(SessionKey key) {
        String gameName = MDC.get(BotMdc.GAME_NAME);
        return new SessionContext(key.botGroupId(), gameName, key.sid());
    }

    /**
     * Enforce the hard size cap (AD-8): while over {@link #MAX_SESSIONS}, drop the
     * least-recently-active entry and WARN once. This makes an unbounded leak — the
     * failure this whole feature exists to prevent — impossible by construction.
     */
    private void enforceSizeCap() {
        while (sessions.size() > MAX_SESSIONS) {
            Map.Entry<SessionKey, SessionAccumulator> oldest = sessions.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().lastActivityNanos()))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            if (sessions.remove(oldest.getKey(), oldest.getValue())) {
                log.warn("SessionAggregationService: session cap {} exceeded — evicted oldest {}",
                        MAX_SESSIONS, oldest.getKey());
            }
        }
    }
}
