package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * lifecycle, one line per group per round, not per-bot detail. (The 5s UpdateBet
 * flush of Phase 2 is DEBUG.)
 * <p>
 * <b>Anti-leak invariant (load-bearing).</b> The session map is bounded three ways
 * (AD-8): a hard {@link #MAX_SESSIONS} cap enforced on every insert (oldest entry
 * dropped + one WARN on overflow, so a leak is impossible by construction), a
 * per-entry {@code lastActivityNanos} backing the {@link #TTL_NANOS} sweep (Phase 2),
 * and {@link #evictGroup(String)} for group-stop teardown (wired in Phase 2). This
 * phase ships the cap and the removal hooks; the scheduled sweep is Phase 2.
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

    /** Idle TTL after which a session is swept (Phase 2). 60s per plan AD-8. */
    static final long TTL_NANOS = 60_000_000_000L;

    /** Key = {@code (botGroupId, gameId, sid)} (AD-2). */
    record SessionKey(String botGroupId, String gameId, long sid) {
    }

    private final ConcurrentHashMap<SessionKey, SessionAccumulator> sessions = new ConcurrentHashMap<>();

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
        // Note (AD-8): explicit grace-then-evict of the ended key is wired in Phase 2
        // together with the TTL sweep; until then the size cap + TTL backstop bound
        // the map. The entry stays until then so a late straggler EndGame/flush does
        // not resurrect-then-orphan it.
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
