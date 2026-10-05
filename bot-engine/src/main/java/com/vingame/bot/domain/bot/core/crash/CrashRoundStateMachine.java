package com.vingame.bot.domain.bot.core.crash;

import com.vingame.bot.domain.bot.message.crash.CrashTick;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * One crash bot's view of the shared round, and of the one bet it places in it, with no
 * I/O ({@code docs/plans/AVIATOR_BOT.md} AD-8). The bot feeds it round frames, acks and
 * ticks; it answers with what to do. It never sends, logs, sleeps or reads a clock of its
 * own: time comes from an injected {@link LongSupplier} and randomness from an injected
 * {@link Random}, so every transition is unit-testable.
 *
 * <pre>
 * WAITING --onRoundStart(sid)-----------------------&gt; OPEN(sid, betAt = now + U(delay))
 * OPEN(sid) --tryPlace(sid, ladder, balance)--------&gt; PLACED(sid, plan{amount, targetH, eid})
 * OPEN --onBettingClosed(sid)-----------------------&gt; WAITING                 [bet window missed]
 * PLACED --onBetAck(eid, b match)-------------------&gt; LIVE                    [Confirmed]
 * LIVE --onTick(sid): crashedFor(eid)---------------&gt; SETTLED                 [Ended(CRASH)]
 * LIVE --onTick(sid): multiplierFor(eid) &gt;= target-&gt; CASHING                 [SendCashout] once
 * CASHING --onTick----------------------------------&gt; CASHING                 (crash flag ignored)
 * CASHING --onCashoutAck(eid, b match)--------------&gt; SETTLED                 [Ended(CASHOUT)]
 * PLACED --onRoundEnd-------------------------------&gt; WAITING                 [Ended(UNACKED)]
 * LIVE|CASHING --onRoundEnd-------------------------&gt; WAITING                 [Ended(CRASH)]
 * SETTLED|OPEN|WAITING --onRoundEnd-----------------&gt; WAITING                 [RoundClosed]
 * PLACED|LIVE|CASHING --onRoundStart(other sid)-----&gt; OPEN(new)               [old bet's Ended first]
 * any --reset()-------------------------------------&gt; WAITING                 [nothing]
 * </pre>
 *
 * <h2>One atomic, every transition a CAS</h2>
 *
 * The bet task, ws-parser's four inbound workers and the silence task all touch this
 * object, and two ticks of one round can be handled at the same instant in either order.
 * <b>All</b> state — the phase, the round sid and the frozen plan — lives in immutable
 * records behind <b>one</b> {@link AtomicReference}; every change is a
 * {@code compareAndSet}. That is what makes "at most one cash-out per bet" and "exactly one
 * of {cash-out, crash} on a race" true by construction. Do not add a second atomic or a
 * {@code betLive}/{@code cashedOut} flag beside it (CASHOUT concern #1).
 *
 * <p><b>What the CAS does not guarantee: no cash-out on the wire after the crash.</b> The
 * decision is atomic, the send is not. A cash-out decided on the last tick before the crash
 * can reach the server after it; the server refuses it, no ack arrives, and the round end
 * records {@link Outcome#CRASH}. That is correct and is not an error.
 *
 * <h2>Rules worth knowing before changing anything</h2>
 * <ul>
 *   <li><b>The crashed flag is checked before the multiplier</b> (F-1). After a runner
 *       crashes its value freezes at a real number that can be at or above the target
 *       (2.86 against 2.85); only {@code !crashedFor(eid)} makes the comparison
 *       meaningful. The plan's own {@code eid} is the only runner ever read, which makes
 *       the legacy bot's "either runner crossed" bug unrepresentable.</li>
 *   <li><b>The plan is drawn once</b>, in {@link #tryPlace}, and frozen: stake, target in
 *       hundredths and runner. Nothing re-draws it.</li>
 *   <li><b>Acks carry no sid</b> (F-3). They bind on {@code (eid, stake)} and only in their
 *       single pending state — a bet ack in PLACED, a cash-out ack in CASHING.</li>
 *   <li>A tick, betting-closed or round-end whose sid is <b>older</b> than the state's is
 *       ignored; a tick for any other sid is ignored. SETTLED ignores every tick, so
 *       nothing is ever sent after a crash or an ack, and a stale pre-crash tick handled
 *       after the crash tick does nothing.</li>
 *   <li><b>A round start is accepted for any positive sid other than the current one</b>,
 *       not only a newer one. Rounds are ~50 s apart, so a stale round start cannot
 *       overtake a newer one through the inbound workers; accepting a lower sid is what
 *       lets a server that restarts its sid counter without dropping the socket keep
 *       being played. A duplicate round start for the current sid is ignored.</li>
 *   <li><b>A reset abandons an in-flight bet without an outcome.</b> The server settles it;
 *       the bot already counted it as placed at the ack. Accepted (plan Concerns) — do not
 *       "fix" it with a fake outcome. The round sid survives the reset, so a late frame of
 *       the abandoned round is still recognised as stale.</li>
 * </ul>
 *
 * <p>Public because its only caller, {@code CrashBot}, lives one package up (CASHOUT
 * Amendment A1).
 */
public final class CrashRoundStateMachine {

    /** {@link Action.Ended#multiplierH()} when the machine did not see the value. */
    public static final long UNKNOWN_MULTIPLIER = -1L;

    /** How a bet ended. {@link #label()} is the {@code outcome} tag of {@code bot_crash_bets_total} (AD-11). */
    public enum Outcome {
        CASHOUT("cashout"),
        CRASH("crash"),
        UNACKED("unacked");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Where the machine is. For logs and tests; callers act on {@link Action}s, not on this. */
    public enum Phase {
        WAITING, OPEN, PLACED, LIVE, CASHING, SETTLED
    }

    /**
     * One bet, decided at {@link #tryPlace} and frozen for the round.
     *
     * @param amount  the stake
     * @param targetH the cash-out target in hundredths ({@code 2.85 → 285})
     * @param eid     the runner, {@code 1..runnerCount}; used for both the bet and the cash-out
     */
    public record Plan(long amount, long targetH, int eid) {
    }

    /**
     * A round opened.
     *
     * @param sid        the new round
     * @param betAt      clock time from which {@link #tryPlace} succeeds
     * @param betDelayMs {@code betAt - now}: the delay to schedule the bet task for
     * @param abandoned  the outcome of an in-flight bet of an earlier round whose round end
     *                   was missed (UNACKED from PLACED, CRASH from LIVE/CASHING)
     */
    public record Opened(long sid, long betAt, long betDelayMs, Optional<Action.Ended> abandoned) {
    }

    /** What the bot must do after a frame. */
    public sealed interface Action
            permits Action.None, Action.Confirmed, Action.SendCashout, Action.Ended, Action.RoundClosed {

        /** Ignored: wrong state, stale or other sid, non-matching ack, or a tick below target. */
        record None() implements Action {
        }

        /** The server acked the bet: count it as placed (AD-11). */
        record Confirmed(Plan plan, long sid) implements Action {
        }

        /** The bet's own runner reached its target: send the cash-out. Emitted once per bet. */
        record SendCashout(Plan plan, long sid, long multiplierH) implements Action {
        }

        /**
         * The bet is over.
         *
         * @param outcome     why
         * @param plan        the bet
         * @param sid         the bet's round
         * @param winnings    gross payout ({@code wm}) on {@link Outcome#CASHOUT}, else {@code 0}
         * @param multiplierH the runner's value on the tick that decided it, or
         *                    {@link #UNKNOWN_MULTIPLIER}
         * @param crashed     whether a crashed flag on the bet's own runner decided it
         */
        record Ended(Outcome outcome, Plan plan, long sid, long winnings, long multiplierH, boolean crashed)
                implements Action {
        }

        /**
         * A round end with no bet outcome left to report.
         *
         * @param sid  the round that ended
         * @param plan the round's bet if it had already settled (SETTLED), else empty
         */
        record RoundClosed(long sid, Optional<Plan> plan) implements Action {
        }
    }

    /** The shared no-op answer. */
    public static final Action NONE = new Action.None();

    // ------------------------------------------------------------------ state

    private sealed interface State permits Waiting, Open, Placed, Live, Cashing, Settled {
        long sid();
    }

    private record Waiting(long sid) implements State {
    }

    private record Open(long sid, long betAt) implements State {
    }

    private record Placed(long sid, Plan plan) implements State {
    }

    private record Live(long sid, Plan plan) implements State {
    }

    private record Cashing(long sid, Plan plan) implements State {
    }

    private record Settled(long sid, Plan plan) implements State {
    }

    private final CrashBehavior behavior;
    private final int runnerCount;
    private final LongSupplier clock;
    private final Random random;

    private final AtomicReference<State> state = new AtomicReference<>(new Waiting(0L));

    /**
     * @param behavior    target and bet-delay ranges (AD-7)
     * @param runnerCount {@code CrashMessageTypes.runnerCount()}, {@code >= 1} (AD-2)
     * @param clock       milliseconds, monotonic enough for deltas
     * @param random      all draws (delay, stake, target, runner)
     */
    public CrashRoundStateMachine(CrashBehavior behavior, int runnerCount, LongSupplier clock, Random random) {
        if (runnerCount < 1) {
            throw new IllegalArgumentException("runnerCount must be >= 1, got " + runnerCount);
        }
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        this.runnerCount = runnerCount;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    // ------------------------------------------------------------------ round start

    /**
     * Betting opened for {@code sid}. Draws the bet delay. An in-flight bet of an earlier
     * round (its round end was lost) is closed first and returned in
     * {@link Opened#abandoned()}.
     *
     * @return the opened round; empty for {@code sid <= 0} or a duplicate of the current sid,
     *         in which case the bot must not schedule a bet task
     */
    public Optional<Opened> onRoundStart(long sid) {
        if (sid <= 0) {
            return Optional.empty();
        }
        while (true) {
            State current = state.get();
            if (current.sid() == sid) {
                return Optional.empty();
            }
            long now = clock.getAsLong();
            long delay = drawDelay();
            Optional<Action.Ended> abandoned = switch (current) {
                case Placed p -> Optional.of(ended(Outcome.UNACKED, p.plan(), p.sid()));
                case Live l -> Optional.of(ended(Outcome.CRASH, l.plan(), l.sid()));
                case Cashing c -> Optional.of(ended(Outcome.CRASH, c.plan(), c.sid()));
                case Waiting w -> Optional.empty();
                case Open o -> Optional.empty();
                case Settled s -> Optional.empty();
            };
            if (state.compareAndSet(current, new Open(sid, now + delay))) {
                return Optional.of(new Opened(sid, now + delay, delay, abandoned));
            }
        }
    }

    // ------------------------------------------------------------------ placing

    /**
     * Try to place this round's bet. Succeeds only from {@code OPEN(sid)}, once
     * {@code betAt} has passed, with at least one affordable rung. Draws the stake
     * (uniform over the affordable rungs), the target and the runner, and freezes them.
     * Fails harmlessly after betting closed, a reset, or a newer round.
     *
     * @param sid     the round the bet task was scheduled for
     * @param ladder  the group's stake ladder, ascending ({@link CrashStakes#ladder})
     * @param balance the bot's current local balance
     * @return the plan, now PLACED; empty if nothing was placed
     */
    public Optional<Plan> tryPlace(long sid, List<Long> ladder, long balance) {
        State current = state.get();
        if (!(current instanceof Open open) || open.sid() != sid) {
            return Optional.empty();
        }
        if (clock.getAsLong() < open.betAt()) {
            return Optional.empty();
        }
        int affordable = affordableCount(ladder, balance);
        if (affordable == 0) {
            return Optional.empty();
        }
        long amount = ladder.get(random.nextInt(affordable));
        Plan plan = new Plan(amount, drawTargetH(), 1 + random.nextInt(runnerCount));
        return state.compareAndSet(current, new Placed(sid, plan)) ? Optional.of(plan) : Optional.empty();
    }

    /**
     * How many rungs of an ascending ladder a balance covers — a binary search, never an
     * iteration, because {@link CrashStakes#ladder} may hold billions of computed rungs.
     *
     * @param ladder  ascending stakes; {@code null} or empty gives {@code 0}
     * @param balance the balance
     * @return the number of rungs {@code <= balance}; they are the prefix {@code [0, n)}
     */
    public static int affordableCount(List<Long> ladder, long balance) {
        if (ladder == null || ladder.isEmpty()) {
            return 0;
        }
        int lo = 0;
        int hi = ladder.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ladder.get(mid) <= balance) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ------------------------------------------------------------------ acks & frames

    /**
     * The bet ack ({@code X702}). Binds only in PLACED, on the plan's runner and stake.
     *
     * @return {@link Action.Confirmed}, or {@link #NONE}
     */
    public Action onBetAck(int eid, long stake) {
        while (true) {
            State current = state.get();
            if (!(current instanceof Placed placed)
                    || placed.plan().eid() != eid || placed.plan().amount() != stake) {
                return NONE;
            }
            if (state.compareAndSet(current, new Live(placed.sid(), placed.plan()))) {
                return new Action.Confirmed(placed.plan(), placed.sid());
            }
        }
    }

    /**
     * Betting closed ({@code X706}). An OPEN round that never placed goes back to WAITING —
     * the bet window was missed. Every other state is unaffected; an older sid is ignored.
     */
    public void onBettingClosed(long sid) {
        while (true) {
            State current = state.get();
            if (!(current instanceof Open open) || sid < open.sid()) {
                return;
            }
            if (state.compareAndSet(current, new Waiting(sid))) {
                return;
            }
        }
    }

    /**
     * A flight tick ({@code X709}). Acts only in LIVE, for the bet's own round: a crashed
     * flag on the bet's runner settles it as a loss; otherwise a value at or above the
     * target moves it to CASHING and emits the one cash-out. Allocation-free below target.
     *
     * @return {@link Action.Ended} (CRASH), {@link Action.SendCashout}, or {@link #NONE}
     */
    public Action onTick(CrashTick tick) {
        while (true) {
            State current = state.get();
            if (!(current instanceof Live live) || tick.sid() != live.sid()) {
                return NONE;
            }
            Plan plan = live.plan();
            int eid = plan.eid();
            State next;
            Action action;
            // F-1: the flag first. A crashed runner's frozen value can be >= the target.
            if (tick.crashedFor(eid)) {
                next = new Settled(live.sid(), plan);
                action = new Action.Ended(Outcome.CRASH, plan, live.sid(), 0L, tick.multiplierFor(eid), true);
            } else {
                long multiplierH = tick.multiplierFor(eid);
                if (multiplierH < plan.targetH()) {
                    return NONE;
                }
                next = new Cashing(live.sid(), plan);
                action = new Action.SendCashout(plan, live.sid(), multiplierH);
            }
            if (state.compareAndSet(current, next)) {
                return action;
            }
        }
    }

    /**
     * The cash-out ack ({@code X703}). Binds only in CASHING, on the plan's runner and stake.
     *
     * @param winnings the gross payout, {@code round(wm)}
     * @return {@link Action.Ended} (CASHOUT), or {@link #NONE}
     */
    public Action onCashoutAck(int eid, long stake, long winnings) {
        while (true) {
            State current = state.get();
            if (!(current instanceof Cashing cashing)
                    || cashing.plan().eid() != eid || cashing.plan().amount() != stake) {
                return NONE;
            }
            if (state.compareAndSet(current, new Settled(cashing.sid(), cashing.plan()))) {
                return new Action.Ended(Outcome.CASHOUT, cashing.plan(), cashing.sid(), winnings,
                        UNKNOWN_MULTIPLIER, false);
            }
        }
    }

    /**
     * The round ended ({@code X707}). Closes whatever the round left: an unacked bet, a live
     * or cashing bet (a loss — a cash-out still pending at the round end was refused), or
     * nothing. An older sid is ignored.
     *
     * @return {@link Action.Ended}, {@link Action.RoundClosed}, or {@link #NONE} for a stale sid
     */
    public Action onRoundEnd(long sid) {
        while (true) {
            State current = state.get();
            if (sid < current.sid()) {
                return NONE;
            }
            Action action = switch (current) {
                case Placed p -> ended(Outcome.UNACKED, p.plan(), p.sid());
                case Live l -> ended(Outcome.CRASH, l.plan(), l.sid());
                case Cashing c -> ended(Outcome.CRASH, c.plan(), c.sid());
                case Settled s -> new Action.RoundClosed(sid, Optional.of(s.plan()));
                case Open o -> new Action.RoundClosed(sid, Optional.empty());
                case Waiting w -> new Action.RoundClosed(sid, Optional.empty());
            };
            if (state.compareAndSet(current, new Waiting(sid))) {
                return action;
            }
        }
    }

    private static Action.Ended ended(Outcome outcome, Plan plan, long sid) {
        return new Action.Ended(outcome, plan, sid, 0L, UNKNOWN_MULTIPLIER, false);
    }

    // ------------------------------------------------------------------ reset & reads

    /**
     * Back to WAITING from any state, with no emission. Called on reconnect and on the
     * subscribe reply. An in-flight bet is abandoned without an outcome; the round sid is
     * kept so late frames of that round stay stale.
     */
    public void reset() {
        while (true) {
            State current = state.get();
            if (current instanceof Waiting) {
                return;
            }
            if (state.compareAndSet(current, new Waiting(current.sid()))) {
                return;
            }
        }
    }

    /** @return the current phase. */
    public Phase phase() {
        return switch (state.get()) {
            case Waiting w -> Phase.WAITING;
            case Open o -> Phase.OPEN;
            case Placed p -> Phase.PLACED;
            case Live l -> Phase.LIVE;
            case Cashing c -> Phase.CASHING;
            case Settled s -> Phase.SETTLED;
        };
    }

    /** @return the sid of the round the machine is in, or last saw; {@code 0} before any. */
    public long sid() {
        return state.get().sid();
    }

    /** @return whether a bet is PLACED, LIVE or CASHING. */
    public boolean inFlight() {
        State current = state.get();
        return current instanceof Placed || current instanceof Live || current instanceof Cashing;
    }

    /** @return this round's plan, from placement until the round end (including SETTLED). */
    public Optional<Plan> currentPlan() {
        return switch (state.get()) {
            case Placed p -> Optional.of(p.plan());
            case Live l -> Optional.of(l.plan());
            case Cashing c -> Optional.of(c.plan());
            case Settled s -> Optional.of(s.plan());
            case Waiting w -> Optional.empty();
            case Open o -> Optional.empty();
        };
    }

    /** @return when the bet may be placed, if OPEN. */
    public OptionalLong betAt() {
        return state.get() instanceof Open open ? OptionalLong.of(open.betAt()) : OptionalLong.empty();
    }

    /** @return the number of runners a plan's {@code eid} is drawn over. */
    public int runnerCount() {
        return runnerCount;
    }

    // ------------------------------------------------------------------ draws

    /** Uniform target in hundredths, {@code Math.round(t·100)}, drawn once per bet (AD-7). */
    private long drawTargetH() {
        double span = behavior.maxTarget() - behavior.minTarget();
        double target = span <= 0 ? behavior.minTarget() : behavior.minTarget() + random.nextDouble() * span;
        return Math.round(target * 100.0);
    }

    private long drawDelay() {
        long span = behavior.betDelayMaxMs() - behavior.betDelayMinMs();
        return behavior.betDelayMinMs() + (span <= 0 ? 0L : random.nextLong(span + 1));
    }
}
