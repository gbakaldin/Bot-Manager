package com.vingame.bot.domain.bot.core.cashout;

import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The life of one cash-out bet, with no I/O ({@code docs/plans/CASHOUT_BOT.md} AD-8,
 * AD-9). The bot feeds it ticks, frames and watchdog expiries; it answers with what to
 * do. It never sends, logs, sleeps or reads a clock of its own: time comes from an
 * injected {@link LongSupplier} and randomness from an injected {@link Random}, so every
 * transition is unit-testable.
 *
 * <pre>
 * IDLE    --tryPlace(now >= nextBetAt)-----------------------&gt; PLACED(plan, sentAt)
 * PLACED  --first frame (stake matches, sid not recently ended)-&gt; LIVE / CASHING / IDLE
 * LIVE    --frame, multiplier >= target, !final--------------&gt; CASHING   [SEND_CASHOUT once]
 * LIVE|CASHING --frame, !final-------------------------------&gt; same, lastFrameAt = now
 * PLACED|LIVE|CASHING --final frame--------------------------&gt; IDLE      [ENDED]
 * PLACED|LIVE|CASHING --onTimeout, silent >= frameTimeout----&gt; IDLE      [TIMED_OUT]
 * any     --reset()------------------------------------------&gt; IDLE      [nothing]
 * </pre>
 *
 * <h2>One atomic, every transition a CAS</h2>
 *
 * ws-parser runs four inbound workers per client, so a progress frame and the terminal
 * frame of one bet can be handled at the same instant, in either order. <b>All</b>
 * state — the bet phase, the bound sid, the frozen plan, the consecutive-timeout count
 * and the recently-ended ring — lives in immutable records behind <b>one</b>
 * {@link AtomicReference}, and every change is a {@code compareAndSet} retried on
 * conflict. That is what makes "exactly one cash-out" and "a final frame wins every
 * race" true by construction. Do not add a second atomic beside it.
 *
 * <h2>Rules worth knowing before changing anything</h2>
 * <ul>
 *   <li><b>The target is drawn once</b>, in {@link #tryPlace}, and frozen in the
 *       {@link Plan}. It is never re-drawn per frame or per tick.</li>
 *   <li><b>Binding.</b> The bet frame carries no sid, so the first frame after PLACED
 *       binds the server's sid — only if its stake is absent or equals the plan's, and
 *       its sid is not one of the last {@value #RECENT_CAPACITY} ended (or timed-out)
 *       bets. Without that ring, a late frame of the previous bet could bind the next.</li>
 *   <li>A frame for another sid, or any frame while IDLE, is ignored.</li>
 *   <li>CASHING keeps accepting progress frames and never emits a second cash-out.</li>
 *   <li><b>Timeout ladder</b> (AD-9). {@code consecutiveTimeouts} is reset only by a
 *       frame that binds to a bet — never by {@link #reset()}. While it is &gt;= 1 every
 *       bet is a <i>probe</i> at the cheapest eligible stake, and the next bet waits at
 *       least the timeout backoff. A reconnect is requested at
 *       {@code R·2^k} consecutive timeouts for {@code k = 0..5}, then every
 *       {@code R·32}.</li>
 * </ul>
 *
 * <p>Public (not package-private as the plan sketches) because its only caller,
 * {@code CashoutBot}, lives one package up in {@code domain.bot.core}.
 */
public final class CashoutBetStateMachine {

    /** How many ended sids are remembered to refuse late frames (AD-8). */
    static final int RECENT_CAPACITY = 4;

    /** The highest doubling step of the reconnect ladder: {@code R·2^5 = R·32}. */
    private static final int LADDER_TOP_SHIFT = 5;

    /** Why a bet ended. {@link #label()} is the {@code outcome} tag of the metric (AD-12). */
    public enum Outcome {
        CASHOUT("cashout"),
        BURST("burst"),
        TIMEOUT("timeout");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One bet, decided at {@link #tryPlace} and frozen for its whole life.
     *
     * @param amount the stake
     * @param target the multiplier to cash out at
     * @param probe  whether this bet is a timeout-ladder probe at the cheapest stake
     */
    public record Plan(long amount, double target, boolean probe) {
    }

    /** What the bot must do after a frame. */
    public sealed interface FrameAction
            permits FrameAction.None, FrameAction.Progress, FrameAction.SendCashout, FrameAction.Ended {

        /** The frame was ignored: no bet live, another sid, or a stale frame. */
        record None() implements FrameAction {
        }

        /** A non-final frame of the live bet; re-arm the watchdog. */
        record Progress(long sid) implements FrameAction {
        }

        /** The live bet crossed its target: send the cash-out for {@code sid}. Emitted once per bet. */
        record SendCashout(Plan plan, long sid, double multiplier) implements FrameAction {
        }

        /**
         * The bet is over. {@code outcome} is {@link Outcome#BURST} or {@link Outcome#CASHOUT};
         * {@code winnings} is the frame's gross payout ({@code 0} on a burst).
         */
        record Ended(Outcome outcome, Plan plan, long sid, long winnings, long nextDelayMs)
                implements FrameAction {
        }
    }

    /** The shared no-op answer. */
    public static final FrameAction NONE = new FrameAction.None();

    /** What the bot must do after a watchdog expiry. */
    public sealed interface TimeoutAction permits TimeoutAction.None, TimeoutAction.TimedOut {

        /** Nothing timed out: no bet in flight, or a frame arrived since the watchdog was armed. */
        record None() implements TimeoutAction {
        }

        /**
         * The in-flight bet got no frame for the frame timeout and was abandoned.
         *
         * @param plan                the abandoned bet
         * @param consecutiveTimeouts the ladder count, including this one
         * @param nextDelayMs         how long until the next bet may be placed
         * @param reconnect           whether this count is a reconnect rung (AD-9)
         */
        record TimedOut(Plan plan, int consecutiveTimeouts, long nextDelayMs, boolean reconnect)
                implements TimeoutAction {
        }
    }

    /** The shared no-op answer. */
    public static final TimeoutAction NO_TIMEOUT = new TimeoutAction.None();

    // ------------------------------------------------------------------ state

    /** What survives across bets: the ladder count and the recently-ended ring. */
    private record Memo(int timeouts, List<Long> recentlyEnded) {

        static final Memo EMPTY = new Memo(0, List.of());

        boolean hasEnded(long sid) {
            return recentlyEnded.contains(sid);
        }

        /** A frame bound to a bet: the ladder resets. */
        Memo bound() {
            return timeouts == 0 ? this : new Memo(0, recentlyEnded);
        }

        Memo remember(long sid) {
            List<Long> next = new ArrayList<>(recentlyEnded);
            next.add(sid);
            while (next.size() > RECENT_CAPACITY) {
                next.remove(0);
            }
            return new Memo(timeouts, Collections.unmodifiableList(next));
        }

        Memo timedOut() {
            return new Memo(timeouts + 1, recentlyEnded);
        }
    }

    private sealed interface BetState permits Idle, Placed, Live, Cashing {
        Memo memo();
    }

    private record Idle(Memo memo, long nextBetAt) implements BetState {
    }

    private record Placed(Memo memo, Plan plan, long sentAt) implements BetState {
    }

    private record Live(Memo memo, Plan plan, long sid, long lastFrameAt) implements BetState {
    }

    private record Cashing(Memo memo, Plan plan, long sid, long lastFrameAt) implements BetState {
    }

    private final CashoutBehavior behavior;
    private final long frameTimeoutMillis;
    private final long timeoutBackoffMillis;
    private final int reconnectAfterTimeouts;
    private final LongSupplier clock;
    private final Random random;

    private final AtomicReference<BetState> state;

    /**
     * @param behavior               target and delay ranges (AD-7)
     * @param frameTimeoutMillis     silence on a live bet that counts as a timeout
     *                               ({@code bot.cashout.frame-timeout-seconds})
     * @param timeoutBackoffMillis   the minimum wait after a timeout
     *                               ({@code bot.cashout.timeout-backoff-seconds})
     * @param reconnectAfterTimeouts {@code R} of the reconnect ladder
     *                               ({@code bot.cashout.reconnect-after-timeouts});
     *                               {@code <= 0} disables reconnects
     * @param clock                  milliseconds, monotonic enough for deltas
     * @param random                 all draws (stake, target, delay)
     */
    public CashoutBetStateMachine(CashoutBehavior behavior,
                                  long frameTimeoutMillis,
                                  long timeoutBackoffMillis,
                                  int reconnectAfterTimeouts,
                                  LongSupplier clock,
                                  Random random) {
        if (frameTimeoutMillis <= 0) {
            throw new IllegalArgumentException("frameTimeoutMillis must be > 0, got " + frameTimeoutMillis);
        }
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        this.frameTimeoutMillis = frameTimeoutMillis;
        this.timeoutBackoffMillis = Math.max(0L, timeoutBackoffMillis);
        this.reconnectAfterTimeouts = reconnectAfterTimeouts;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.state = new AtomicReference<>(new Idle(Memo.EMPTY, clock.getAsLong()));
    }

    // ------------------------------------------------------------------ placing

    /**
     * Try to start a bet now. Succeeds only from IDLE, once {@code nextBetAt} has passed,
     * with a non-empty stake set and a balance that covers the drawn stake. The stake is a
     * uniform pick from {@code eligibleStakes} — or the cheapest of them while the ladder
     * count is &gt;= 1 (a probe) — and the target is drawn here, once, and frozen.
     *
     * @param eligibleStakes the server's stakes within the group's window (AD-6)
     * @param balance        the bot's current local balance
     * @return the plan, now PLACED; empty if nothing was placed
     */
    public Optional<Plan> tryPlace(List<Long> eligibleStakes, long balance) {
        return place(eligibleStakes, balance, true);
    }

    /**
     * {@link #tryPlace} without the {@code nextBetAt} check. Only for the bot's
     * park-and-pop race fallback: a {@link #reset()} that lands between the send
     * condition and the send supplier puts the machine back in IDLE with a fresh delay,
     * and the supplier may not return nothing.
     *
     * @param eligibleStakes the server's stakes within the group's window
     * @param balance        the bot's current local balance
     * @return the plan, now PLACED; empty if not IDLE, no stakes, or unaffordable
     */
    public Optional<Plan> placeIgnoringDelay(List<Long> eligibleStakes, long balance) {
        return place(eligibleStakes, balance, false);
    }

    private Optional<Plan> place(List<Long> eligibleStakes, long balance, boolean honourDelay) {
        if (eligibleStakes == null || eligibleStakes.isEmpty()) {
            return Optional.empty();
        }
        BetState current = state.get();
        if (!(current instanceof Idle idle)) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (honourDelay && now < idle.nextBetAt()) {
            return Optional.empty();
        }
        boolean probe = idle.memo().timeouts() >= 1;
        long amount = probe
                ? Collections.min(eligibleStakes)
                : eligibleStakes.get(random.nextInt(eligibleStakes.size()));
        if (balance < amount) {
            return Optional.empty();
        }
        Plan plan = new Plan(amount, drawTarget(), probe);
        return state.compareAndSet(current, new Placed(idle.memo(), plan, now))
                ? Optional.of(plan)
                : Optional.empty();
    }

    // ------------------------------------------------------------------ frames

    /**
     * Feed one bet frame (progress or cash-out reply — the cmd does not matter, AD-5).
     * Safe to call from several threads at once.
     *
     * @param frame the parsed frame
     * @return what the bot must do; {@link #NONE} if the frame was ignored
     */
    public FrameAction onFrame(CashoutBetFrame frame) {
        while (true) {
            BetState current = state.get();
            long now = clock.getAsLong();
            BetState next;
            FrameAction action;
            switch (current) {
                case Idle idle -> {
                    return NONE;
                }
                case Placed placed -> {
                    if (placed.memo().hasEnded(frame.sid())) {
                        return NONE;
                    }
                    OptionalLong stake = frame.stake();
                    if (stake.isPresent() && stake.getAsLong() != placed.plan().amount()) {
                        return NONE;
                    }
                    Memo memo = placed.memo().bound();
                    if (frame.isFinal()) {
                        long delay = drawDelay();
                        next = new Idle(memo.remember(frame.sid()), now + delay);
                        action = ended(frame, placed.plan(), delay);
                    } else if (frame.multiplier() >= placed.plan().target()) {
                        next = new Cashing(memo, placed.plan(), frame.sid(), now);
                        action = new FrameAction.SendCashout(placed.plan(), frame.sid(), frame.multiplier());
                    } else {
                        next = new Live(memo, placed.plan(), frame.sid(), now);
                        action = new FrameAction.Progress(frame.sid());
                    }
                }
                case Live live -> {
                    if (frame.sid() != live.sid()) {
                        return NONE;
                    }
                    if (frame.isFinal()) {
                        long delay = drawDelay();
                        next = new Idle(live.memo().remember(live.sid()), now + delay);
                        action = ended(frame, live.plan(), delay);
                    } else if (frame.multiplier() >= live.plan().target()) {
                        next = new Cashing(live.memo(), live.plan(), live.sid(), now);
                        action = new FrameAction.SendCashout(live.plan(), live.sid(), frame.multiplier());
                    } else {
                        next = new Live(live.memo(), live.plan(), live.sid(), now);
                        action = new FrameAction.Progress(live.sid());
                    }
                }
                case Cashing cashing -> {
                    if (frame.sid() != cashing.sid()) {
                        return NONE;
                    }
                    if (frame.isFinal()) {
                        long delay = drawDelay();
                        next = new Idle(cashing.memo().remember(cashing.sid()), now + delay);
                        action = ended(frame, cashing.plan(), delay);
                    } else {
                        next = new Cashing(cashing.memo(), cashing.plan(), cashing.sid(), now);
                        action = new FrameAction.Progress(cashing.sid());
                    }
                }
            }
            if (state.compareAndSet(current, next)) {
                return action;
            }
        }
    }

    private static FrameAction.Ended ended(CashoutBetFrame frame, Plan plan, long delay) {
        Outcome outcome = frame.isBurst() ? Outcome.BURST : Outcome.CASHOUT;
        // CashoutBetFrame.winningsFor ignores the user: the frame is about one player's
        // private bet. The bot passes nothing here and accounts from this value (AD-11).
        return new FrameAction.Ended(outcome, plan, frame.sid(), frame.winningsFor(null), delay);
    }

    // ------------------------------------------------------------------ watchdog

    /**
     * The watchdog fired. Abandons the in-flight bet if it has really been silent for the
     * frame timeout — measured from its last frame, or from the send if none arrived —
     * and otherwise does nothing, so a watchdog that raced a frame is harmless.
     *
     * @return {@link #NO_TIMEOUT}, or the abandoned bet with the ladder decision
     */
    public TimeoutAction onTimeout() {
        while (true) {
            BetState current = state.get();
            long now = clock.getAsLong();
            long reference;
            Plan plan;
            Memo memo;
            switch (current) {
                case Idle idle -> {
                    return NO_TIMEOUT;
                }
                case Placed placed -> {
                    reference = placed.sentAt();
                    plan = placed.plan();
                    memo = placed.memo();
                }
                case Live live -> {
                    reference = live.lastFrameAt();
                    plan = live.plan();
                    memo = live.memo().remember(live.sid());
                }
                case Cashing cashing -> {
                    reference = cashing.lastFrameAt();
                    plan = cashing.plan();
                    memo = cashing.memo().remember(cashing.sid());
                }
            }
            if (now - reference < frameTimeoutMillis) {
                return NO_TIMEOUT;
            }
            Memo afterTimeout = memo.timedOut();
            int count = afterTimeout.timeouts();
            long delay = Math.max(timeoutBackoffMillis, drawDelay());
            if (state.compareAndSet(current, new Idle(afterTimeout, now + delay))) {
                return new TimeoutAction.TimedOut(plan, count, delay, isReconnectRung(count));
            }
        }
    }

    /**
     * Whether {@code consecutiveTimeouts} is a reconnect rung: {@code R·2^k} for
     * {@code k = 0..5}, then every multiple of {@code R·32}. With {@code R = 3}:
     * 3, 6, 12, 24, 48, 96, 192, 288, ...
     */
    boolean isReconnectRung(int consecutiveTimeouts) {
        int r = reconnectAfterTimeouts;
        if (r <= 0 || consecutiveTimeouts <= 0) {
            return false;
        }
        long top = (long) r << LADDER_TOP_SHIFT;
        if (consecutiveTimeouts > top) {
            return consecutiveTimeouts % top == 0;
        }
        if (consecutiveTimeouts % r != 0) {
            return false;
        }
        return Integer.bitCount(consecutiveTimeouts / r) == 1;
    }

    // ------------------------------------------------------------------ reset & reads

    /**
     * Back to IDLE from any state, with a fresh pause and no emission. Called on
     * reconnect and on (re-)subscribe. Does <b>not</b> reset the timeout ladder (AD-9):
     * a reconnect that reaches the same silent server must keep climbing it.
     */
    public void reset() {
        while (true) {
            BetState current = state.get();
            Idle next = new Idle(current.memo(), clock.getAsLong() + drawDelay());
            if (state.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /** @return whether a bet is PLACED, LIVE or CASHING. */
    public boolean inFlight() {
        return !(state.get() instanceof Idle);
    }

    /**
     * @return milliseconds until the in-flight bet would time out (never negative), or
     *         {@code -1} when no bet is in flight
     */
    public long millisUntilTimeout() {
        BetState current = state.get();
        long reference = switch (current) {
            case Idle idle -> -1L;
            case Placed placed -> placed.sentAt();
            case Live live -> live.lastFrameAt();
            case Cashing cashing -> cashing.lastFrameAt();
        };
        if (reference < 0) {
            return -1L;
        }
        return Math.max(0L, reference + frameTimeoutMillis - clock.getAsLong());
    }

    /** @return the timeout-ladder count. */
    public int consecutiveTimeouts() {
        return state.get().memo().timeouts();
    }

    /** @return the in-flight plan, if any. */
    public Optional<Plan> currentPlan() {
        return switch (state.get()) {
            case Idle idle -> Optional.empty();
            case Placed placed -> Optional.of(placed.plan());
            case Live live -> Optional.of(live.plan());
            case Cashing cashing -> Optional.of(cashing.plan());
        };
    }

    /** @return when the next bet may be placed, if IDLE. */
    public OptionalLong nextBetAt() {
        return state.get() instanceof Idle idle ? OptionalLong.of(idle.nextBetAt()) : OptionalLong.empty();
    }

    /** @return the bound server sid, if LIVE or CASHING. */
    public OptionalLong boundSid() {
        return switch (state.get()) {
            case Live live -> OptionalLong.of(live.sid());
            case Cashing cashing -> OptionalLong.of(cashing.sid());
            default -> OptionalLong.empty();
        };
    }

    // ------------------------------------------------------------------ draws

    private double drawTarget() {
        double span = behavior.maxTarget() - behavior.minTarget();
        return span <= 0 ? behavior.minTarget() : behavior.minTarget() + random.nextDouble() * span;
    }

    private long drawDelay() {
        long span = behavior.maxDelayMs() - behavior.minDelayMs();
        return behavior.minDelayMs() + (span <= 0 ? 0L : random.nextLong(span + 1));
    }
}
