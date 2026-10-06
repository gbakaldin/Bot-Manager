package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.core.crash.CrashBehavior;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Action;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Opened;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Outcome;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Plan;
import com.vingame.bot.domain.bot.core.crash.CrashStakes;
import com.vingame.bot.domain.bot.core.crash.RoundSilenceWatch;
import com.vingame.bot.domain.bot.message.CrashMessageTypes;
import com.vingame.bot.domain.bot.message.crash.CrashBetAck;
import com.vingame.bot.domain.bot.message.crash.CrashBettingClosed;
import com.vingame.bot.domain.bot.message.crash.CrashCashoutAck;
import com.vingame.bot.domain.bot.message.crash.CrashRoundEnd;
import com.vingame.bot.domain.bot.message.crash.CrashRoundStart;
import com.vingame.bot.domain.bot.message.crash.CrashSubscribeResponse;
import com.vingame.bot.domain.bot.message.crash.CrashTick;
import com.vingame.bot.domain.bot.message.request.CrashRequest;
import com.vingame.bot.domain.bot.util.OutputPrinter;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.websocketparser.ObjectMapperProvider;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.Scenario;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import static com.vingame.websocketparser.message.properties.MessageType.RECEIVED;
import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.cmd;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.typeOf;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Crash bot for shared-round multi-runner games — 119 Avatar ({@code aviatorPlugin},
 * {@code docs/plans/AVIATOR_BOT.md}). Every round: the server opens betting ({@code X705}),
 * the bot places one bet on one runner after a random delay, the rockets fly
 * ({@code X709} ticks) and the bot cashes out when <em>its own</em> runner reaches the
 * target it drew at placement — unless that runner crashes first. Modelled on
 * {@link CashoutBot}.
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li><b>Everything after subscribe is event-driven</b> (AD-10). No {@code sendAsync}
 *       stage. The round start schedules one one-shot bet task on the bot's single virtual
 *       scheduler ({@code crash-<user>}); the tick handler sends the cash-out directly. Both
 *       leave on the channel captured once in {@link #botBehaviorScenario()}.</li>
 *   <li><b>One atomic</b> (AD-8). Every decision is a CAS in {@link CrashRoundStateMachine};
 *       this class adds no {@code betLive} / {@code cashedOut} flag beside it. The bet task,
 *       ws-parser's four inbound workers and the silence task all touch the machine.</li>
 *   <li><b>Silence watch</b> (AD-9), armed from {@link #onStart()}, not from the subscribe
 *       reply, so a refused subscribe is caught too: reconnects at silent windows 1, 2, 4,
 *       8, 16, 32 and every 32 after that, one WARN per silence episode carrying
 *       {@code subscribed=}. A DEAD bot's task ends; a rung that lands while a reconnect is
 *       already running neither counts nor escalates (review B1).</li>
 *   <li><b>Never bet off the snapshot</b> (AD-12): the first bet is on the next round
 *       start after the subscribe reply.</li>
 * </ul>
 *
 * <h2>Accounting</h2> (AD-11) Send: {@code creditBalance(amount)}. Bet ack:
 * {@code bot_bets_placed_total} / {@code bot_bet_amount_total}. Cash-out ack: gross
 * winnings credited. Every finished bet: one {@code bot_crash_bets_total{outcome}}. A reset
 * abandons an in-flight bet with no outcome (plan Concerns) — the server settles it.
 *
 * <h2>Logging</h2> (AD-13) Nothing per bet, per round or per bot at INFO — this class
 * contains no INFO call and {@code PerBotInfoLogGuardTest} keeps it that way. The tick path
 * logs at TRACE only, behind an {@code isTraceEnabled} guard: ticks are 2/s/bot.
 */
@Slf4j
public class CrashBot extends Bot {

    /** AD-9: the silence window when {@code bot.watchdog.timeout.seconds} is unset ({@code <= 0}). */
    static final long DEFAULT_SILENCE_WINDOW_SECONDS = 180L;

    @Setter
    private CrashMessageTypes messageTypes;

    private CrashRequest request;

    /** CMD base, {@code Game.offset} (AD-4). */
    private int offset;

    private CrashRoundStateMachine machine;
    private RoundSilenceWatch watch;

    // Test seams: set before initializeSubclass() to make the machine and watch deterministic.
    private LongSupplier clock;
    private Random rng;

    /** AD-6: the group's stake ladder. Empty until the subscribe reply — the bot does not bet. */
    private volatile List<Long> ladder = List.of();

    /** Below-cheapest-rung state (AD-6): logged on the transitions only. */
    private final AtomicBoolean belowStakeFloor = new AtomicBoolean(false);

    /**
     * Set while {@code checkBalance} / {@code depositIsWarranted} run between rounds. A bet
     * task that fires meanwhile skips its round (AD-10 step 1, plan Concerns).
     */
    private final AtomicBoolean sessionCheckInProgress = new AtomicBoolean(false);

    // AD-13 one-shot DEBUG lines, per bot per JVM.
    private final AtomicBoolean firstCashoutAckLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstLossLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstRoundEndLogged = new AtomicBoolean(false);

    /** Latched on the first bet or cash-out send failure: WARN once per bot, DEBUG afterwards. */
    private final AtomicBoolean sendFailureWarned = new AtomicBoolean(false);

    // The send channel (AD-10): bound once per scenario, the client the subscribe left on.
    private volatile VingameWebSocketClient sendChannel;
    private volatile ObjectMapper sendMapper;

    /** One scheduler thread per bot: the bet task and the silence task (AD-10). */
    private ScheduledExecutorService scheduler;
    private final Object taskLock = new Object();
    private ScheduledFuture<?> betTask;     // guarded by taskLock
    private ScheduledFuture<?> silenceTask; // guarded by taskLock

    public CrashBot() {
        super();
    }

    /** Visible for testing: a fake clock for the machine and the watch. Call before {@link #initializeSubclass()}. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Visible for testing: a seeded RNG for the machine. Call before {@link #initializeSubclass()}. */
    void setRandom(Random random) {
        this.rng = random;
    }

    @Override
    protected void initializeSubclass() {
        Game game = configuration.getGame();
        Integer gameOffset = game.getOffset();
        if (gameOffset == null) {
            // AD-4: the offset IS the CMD base. Fail loud here rather than subscribe to cmd 0.
            throw new IllegalStateException(
                    "CRASH game '" + game.getName() + "' has no offset — offset carries the CMD base (AD-4)");
        }
        this.offset = gameOffset;
        this.request = messageTypes.newRequest(configuration.getZoneName(), game.getPluginName(), offset);

        if (rng == null) {
            // Deterministic-ish per user, distinct per process — the BettingMiniGameBot rule.
            rng = new Random(((long) getUserName().hashCode()) ^ System.nanoTime());
        }
        if (clock == null) {
            clock = System::currentTimeMillis;
        }
        int runners = messageTypes.runnerCount();
        this.machine = new CrashRoundStateMachine(CrashBehavior.LEGACY, runners, clock, rng);

        long windowSeconds = configuration.getWatchdogTimeoutSeconds() > 0
                ? configuration.getWatchdogTimeoutSeconds()
                : DEFAULT_SILENCE_WINDOW_SECONDS;
        this.watch = new RoundSilenceWatch(windowSeconds * 1_000L, clock);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("crash-" + getUserName()).factory());

        // AD-11: all three outcome series at 0 HERE, where Bot.initialize has already set the
        // MDC and before any frame or subscribe — so they exist even if the subscribe is
        // refused (CASHOUT release finding 3). Same MDC as every later increment.
        if (metrics != null) metrics.initCrashSeries();

        // LOG_VOLUME_TIERING tier 1: DEBUG per bot, one INFO line per group (AD-13).
        log.debug("CrashBot initialized: game={}, plugin={}, offset={}, runners={}, silenceWindow={}s",
                game.getName(), game.getPluginName(), offset, runners, windowSeconds);
        if (groupLifecycleAggregator != null) {
            groupLifecycleAggregator.recordInitialized("game=" + game.getName() + ", type=" + game.getGameType()
                    + ", offset=" + offset + ", runners=" + runners);
        }
    }

    // ------------------------------------------------------------------ session

    /**
     * Balance check and deposit decision, between rounds only (AD-11, AD-16). Local unless
     * the drift threshold or the minimum balance trips. Copied from {@link CashoutBot}.
     */
    void onNewSession() {
        sessionCheckInProgress.set(true);
        try {
            long balance = checkBalance();
            BotBehaviorConfig behavior = configuration.getBehaviorConfig();
            // GATEWAY_REQUEST_BUDGET AD-10, as in SlotMachineBot: depositIsWarranted re-reads
            // the server balance first if the drift read was deferred — a deposit is money.
            if (behavior.isAutoDepositEnabled() && depositIsWarranted(balance)) {
                long confirmed = expectedCurrentBalance.get();
                log.debug("Bot {}: balance {} below minimum {}, triggering deposit",
                        getUserName(), confirmed, getMinBalance());
                if (groupLifecycleAggregator != null) {
                    groupLifecycleAggregator.recordAutoDeposit(getMinBalance() - confirmed);
                }
                deposit();
            } else {
                log.debug("Bot {}: session balance {}", getUserName(), balance);
            }
        } finally {
            sessionCheckInProgress.set(false);
        }
    }

    // ------------------------------------------------------------------ frames

    /** The subscribe reply / snapshot ({@code X700}). Package-private as a test seam. */
    void onSubscribe(ActionResponseMessage<? extends CrashSubscribeResponse> data) {
        watch.onFrame();
        if (metrics != null) metrics.incBotMessage("crashSubscribe");

        // FIRST and unconditionally (AD-12, CashoutBot): a subscribe answered on a live socket
        // IS an authenticated connection, whatever the stake ladder says.
        markConnectionAuthenticated();
        watch.markSubscribed();
        machine.reset();

        BotBehaviorConfig behavior = configuration.getBehaviorConfig();
        List<Long> computed = CrashStakes.ladder(behavior.getMinBet(), behavior.getMaxBet(), behavior.getBetIncrement());
        if (computed.isEmpty()) {
            // AD-6: one WARN per bot per subscribe, then silence. The bot does not bet.
            log.warn("Bot {}: empty crash stake ladder — window {} holds no stake, not betting",
                    getUserName(), CrashStakes.window(behavior.getMinBet(), behavior.getMaxBet(), behavior.getBetIncrement()));
        } else {
            log.debug("Bot {}: subscribed — stake ladder {}, runners {}", getUserName(), computed, machine.runnerCount());
        }
        this.ladder = computed;
    }

    /** Round start, betting opens ({@code X705}). Package-private as a test seam. */
    void onRoundStart(ActionResponseMessage<? extends CrashRoundStart> data) {
        watch.onFrame();
        if (metrics != null) metrics.incBotMessage("crashRoundStart");

        long sid = data.getData().sid();
        Optional<Opened> opened = machine.onRoundStart(sid);
        if (opened.isEmpty()) {
            log.trace("Bot {}: ignored round start sid={}", getUserName(), sid);
            return;
        }
        Opened o = opened.get();
        // A missed round end: the old bet's outcome first.
        o.abandoned().ifPresent(this::onBetEnded);
        log.trace("Bot {}: round {} open, bet in {} ms", getUserName(), sid, o.betDelayMs());
        scheduleBet(sid, o.betDelayMs());
    }

    /** The bet ack ({@code X702}). Package-private as a test seam. */
    void onBetAck(ActionResponseMessage<? extends CrashBetAck> data) {
        watch.onFrame();
        if (metrics != null) metrics.incBotMessage("crashBetAck");

        CrashBetAck ack = data.getData();
        Action action = machine.onBetAck(ack.eid(), ack.stake());
        if (action instanceof Action.Confirmed confirmed) {
            // AD-11: confirmed bets, as in ENDGAME_METRICS AD-4.
            if (metrics != null) metrics.incBetsPlaced(1, confirmed.plan().amount());
            log.trace("Bot {}: bet acked sid={}, eid={}, b={}", getUserName(), confirmed.sid(), ack.eid(), ack.stake());
        } else {
            log.trace("Bot {}: ignored bet ack eid={}, b={}", getUserName(), ack.eid(), ack.stake());
        }
    }

    /** Betting closed ({@code X706}). Package-private as a test seam. */
    void onBettingClosed(ActionResponseMessage<? extends CrashBettingClosed> data) {
        watch.onFrame();
        if (metrics != null) metrics.incBotMessage("crashBettingClosed");

        long sid = data.getData().sid();
        machine.onBettingClosed(sid);
        log.trace("Bot {}: betting closed sid={}, phase={}", getUserName(), sid, machine.phase());
    }

    /**
     * A flight tick ({@code X709}), ~2/s/bot. Logs at TRACE only, behind a guard. Below the
     * target the state machine allocates nothing; the {@code crashTick} message counter does —
     * every {@link com.vingame.bot.infrastructure.observability.BotMetrics#incBotMessage}
     * builds its MDC tags and does a registry lookup, as for every other frame (review S2).
     * Package-private as a test seam.
     */
    void onTick(ActionResponseMessage<? extends CrashTick> data) {
        watch.onFrame();
        if (metrics != null) metrics.incBotMessage("crashTick");

        CrashTick tick = data.getData();
        Action action = machine.onTick(tick);
        if (action instanceof Action.SendCashout send) {
            sendCashout(send);
        } else if (action instanceof Action.Ended ended) {
            onBetEnded(ended);
        } else if (log.isTraceEnabled()) {
            log.trace("Bot {}: tick sid={}, {}", getUserName(), tick.sid(), multipliers(tick));
        }
    }

    /** Every runner's value, {@code x<h>} or {@code x<h>!} once crashed — TRACE only (review Y1). */
    private String multipliers(CrashTick tick) {
        StringBuilder sb = new StringBuilder();
        for (int eid = 1; eid <= machine.runnerCount(); eid++) {
            if (eid > 1) sb.append('/');
            sb.append('x').append(tick.multiplierFor(eid));
            if (tick.crashedFor(eid)) sb.append('!');
        }
        return sb.toString();
    }

    /** The cash-out ack ({@code X703}). Package-private as a test seam. */
    void onCashoutAck(ActionResponseMessage<? extends CrashCashoutAck> data) {
        watch.onFrame();
        if (metrics != null) metrics.incBotMessage("crashCashoutAck");

        CrashCashoutAck ack = data.getData();
        // The ack is about this player's own bet: no user name needed (CrashCashoutAck).
        long winnings = ack.winningsFor(null);
        Action action = machine.onCashoutAck(ack.eid(), ack.stake(), winnings);
        if (action instanceof Action.Ended ended) {
            if (firstCashoutAckLogged.compareAndSet(false, true)) {
                log.debug("Bot {}: first crash cash-out ack: eid={}, b={}, odd={}, wm={}",
                        getUserName(), ack.eid(), ack.stake(), ack.multiplier(), ack.payout());
            }
            onBetEnded(ended);
        } else {
            log.trace("Bot {}: ignored cash-out ack eid={}, b={}, wm={}", getUserName(), ack.eid(), ack.stake(), ack.payout());
        }
    }

    /**
     * Round end ({@code X707}). Package-private as a test seam.
     * <p>
     * A <b>stale</b> round end (an older sid than the machine's, {@code NONE}) neither counts
     * a round nor runs the session check: the round boundary it marks has already been seen,
     * and a balance check fired then could land inside the next round's bet window.
     */
    void onRoundEnd(ActionResponseMessage<? extends CrashRoundEnd> data) {
        watch.onFrame();
        if (metrics != null) metrics.incBotMessage("crashRoundEnd");

        CrashRoundEnd end = data.getData();
        Optional<Plan> plan = machine.currentPlan();
        Action action = machine.onRoundEnd(end.sid());
        if (action instanceof Action.None) {
            log.trace("Bot {}: ignored stale round end sid={}", getUserName(), end.sid());
            return;
        }
        if (firstRoundEndLogged.compareAndSet(false, true)) {
            // b only echoes our own plan; this is the one cross-check (AD-11).
            log.debug("Bot {}: first crash round end: sid={}, b={}, plan={}",
                    getUserName(), end.sid(), end.ownStake(), plan.orElse(null));
        }
        if (action instanceof Action.Ended ended) {
            onBetEnded(ended);
        } else {
            log.trace("Bot {}: round {} closed, own b={}", getUserName(), end.sid(), end.ownStake());
        }
        roundsObserved.incrementAndGet();
        onNewSession();
    }

    /** All bet accounting (AD-11): outcome counter, winnings, one-shot DEBUG, TRACE outcome. */
    private void onBetEnded(Action.Ended ended) {
        Plan plan = ended.plan();
        if (metrics != null) metrics.incCrashOutcome(ended.outcome().label());
        long winnings = ended.outcome() == Outcome.CASHOUT ? ended.winnings() : 0L;
        lastRoundWinnings = winnings;
        if (winnings > 0) {
            expectedCurrentBalance.addAndGet(winnings);
            cumulativeWinnings.addAndGet(winnings);
            if (metrics != null) metrics.incBotWinnings(winnings);
        }
        if (ended.outcome() == Outcome.CRASH && firstLossLogged.compareAndSet(false, true)) {
            log.debug("Bot {}: first crash loss: eid={}, targetH={}, multiplierH={}, crashed={}",
                    getUserName(), plan.eid(), plan.targetH(), ended.multiplierH(), ended.crashed());
        }
        log.trace("Bot {}: bet ended {} sid={}, b={}, eid={}, targetH={}, winnings={}",
                getUserName(), ended.outcome().label(), ended.sid(), plan.amount(), plan.eid(),
                plan.targetH(), winnings);
    }

    // ------------------------------------------------------------------ sends

    /**
     * The one-shot bet task (AD-10), fired at the round's {@code betAt}. Package-private as a
     * test seam.
     * <p>
     * <b>A closed channel places nothing</b> (review B3). The task lives on the bot's own
     * scheduler, not on the client, so closing the client does not kill it: periodic logout
     * ({@code logout()} → sleep → {@code restart()}) and a full reconnect both close the
     * channel while a round's bet delay may still be running. Placing then would debit the
     * local balance for a frame ws-parser drops, and leave a PLACED bet the next subscribe
     * abandons. So the check runs before {@code tryPlace}: no CAS, no debit, no send. An
     * <em>unbound</em> channel ({@code null}) is not this case and still goes through
     * {@link #send}'s failure path.
     */
    void placeBet(long sid) {
        if (isStopped()) return;
        VingameWebSocketClient channel = sendChannel;
        if (channel != null && !channel.isOpen()) {
            log.trace("Bot {}: send channel closed — no bet for round {}", getUserName(), sid);
            return;
        }
        if (sessionCheckInProgress.get()) {
            log.trace("Bot {}: session check in progress — skipping round {}", getUserName(), sid);
            return;
        }
        List<Long> stakes = ladder;
        if (stakes.isEmpty()) {
            return; // AD-6: WARNed once at subscribe.
        }
        long balance = expectedCurrentBalance.get();
        if (CrashRoundStateMachine.affordableCount(stakes, balance) == 0) {
            if (belowStakeFloor.compareAndSet(false, true)) {
                log.debug("Bot {}: balance {} below the cheapest stake {} — pausing bets",
                        getUserName(), balance, stakes.get(0));
            }
            return;
        }
        if (belowStakeFloor.compareAndSet(true, false)) {
            log.debug("Bot {}: balance {} covers the cheapest stake {} again — resuming bets",
                    getUserName(), balance, stakes.get(0));
        }
        Optional<Plan> placed = machine.tryPlace(sid, stakes, balance);
        if (placed.isEmpty()) {
            // The scheduler's delay and the wall clock can disagree by a millisecond or a
            // clock step: if this round is still open and betAt is ahead, wait the rest.
            OptionalLong betAt = machine.betAt();
            long now = clock.getAsLong();
            if (machine.sid() == sid && betAt.isPresent() && betAt.getAsLong() > now) {
                scheduleBet(sid, betAt.getAsLong() - now);
            } else {
                log.trace("Bot {}: no bet for round {} (phase {})", getUserName(), sid, machine.phase());
            }
            return;
        }
        Plan plan = placed.get();
        creditBalance(plan.amount());
        log.trace("Bot {}: sending bet sid={}, b={}, eid={}, targetH={}",
                getUserName(), sid, plan.amount(), plan.eid(), plan.targetH());
        send(request.bet(plan.amount(), sid, plan.eid()), "bet");
    }

    private void sendCashout(Action.SendCashout send) {
        log.trace("Bot {}: sending cash-out sid={}, eid={} at x{} (target x{})",
                getUserName(), send.sid(), send.plan().eid(), send.multiplierH(), send.plan().targetH());
        send(request.cashOut(send.sid(), send.plan().eid()), "cash-out");
    }

    /** On the captured channel. A frame handler or task must not throw (CashoutBot.sendCashout). */
    private void send(ActionRequestMessage message, String what) {
        VingameWebSocketClient channel = sendChannel;
        ObjectMapper mapper = sendMapper;
        try {
            if (channel == null || mapper == null) {
                throw new IllegalStateException("send channel not bound");
            }
            channel.send(message.serialize(mapper));
        } catch (RuntimeException e) {
            // The round clock bounds the bet either way: unacked or crash at the round end.
            if (sendFailureWarned.compareAndSet(false, true)) {
                log.warn("Bot {}: crash {} frame not sent (further failures at DEBUG)", getUserName(), what, e);
            } else {
                log.debug("Bot {}: crash {} frame not sent: {}", getUserName(), what, e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ scheduling

    /**
     * Schedule on the bot's single scheduler, or return {@code null} once it is shut down.
     * Package-private so a test can run tasks by hand.
     * <p>
     * The {@code isShutdown()} test is check-then-act against {@link #cleanup()}'s
     * {@code shutdownNow()} (review S1): a frame handled on an inbound worker while the group
     * stops can lose that race. The rejection is caught here, quietly, because a frame
     * handler must not throw into ws-parser's processor.
     */
    ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        if (scheduler == null || scheduler.isShutdown()) {
            return null;
        }
        try {
            return scheduler.schedule(task, Math.max(1L, delayMs), MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.trace("Bot {}: scheduler shut down — task not scheduled", getUserName());
            return null;
        }
    }

    private void scheduleBet(long sid, long delayMs) {
        synchronized (taskLock) {
            if (betTask != null) {
                betTask.cancel(false);
            }
            betTask = schedule(mdcWrap(() -> placeBet(sid)), delayMs);
        }
    }

    private void cancelBetTask() {
        synchronized (taskLock) {
            if (betTask != null) {
                betTask.cancel(false);
                betTask = null;
            }
        }
    }

    /** Cancel and reschedule the silence task (AD-9). */
    private void armSilenceWatch(long delayMs) {
        synchronized (taskLock) {
            if (silenceTask != null) {
                silenceTask.cancel(false);
            }
            silenceTask = schedule(mdcWrap(this::onSilenceCheck), delayMs);
        }
    }

    /**
     * The silence task fired (AD-9). Package-private as a test seam.
     * <p>
     * <b>DEAD ends the task</b> (review B1): it is the only self-re-arming watchdog, and a
     * DEAD bot's {@code triggerFullReconnect} is a no-op, so re-arming would only keep
     * counting expiries and re-arming group-scoped DEBUG for as long as the group lives.
     * Nothing revives a DEAD bot short of a group rebuild, which arms a fresh task from
     * {@link #onStart()}. <b>While a reconnect is already running</b> the task keeps
     * re-arming — the reconnect may fail back into silence — but a rung neither counts nor
     * escalates, because it starts nothing.
     * <p>
     * <b>One WARN per silence episode</b> (review S3). When the episode's first window
     * starts a reconnect, {@code triggerFullReconnect}'s own WARN is that line and carries
     * the window and {@code subscribed=} detail in its reason; this method logs at DEBUG.
     */
    void onSilenceCheck() {
        if (isStopped()) return;
        if (getStatus() == BotStatus.DEAD) {
            log.debug("Bot {}: DEAD — silence watch not re-armed", getUserName());
            return;
        }
        RoundSilenceWatch.Check check = watch.check();
        if (check instanceof RoundSilenceWatch.Check.Remaining remaining) {
            armSilenceWatch(remaining.millis());
            return;
        }
        RoundSilenceWatch.Check.Silent silent = (RoundSilenceWatch.Check.Silent) check;
        boolean reconnect = silent.reconnect() && !isReconnecting();
        if (silent.first() && !reconnect) {
            log.warn("Bot {}: no crash frame for {} s — silent window 1, subscribed={}{}",
                    getUserName(), watch.windowMillis() / 1_000L, silent.subscribed(),
                    silent.reconnect() ? ", reconnect already in progress" : "");
        } else {
            log.debug("Bot {}: no crash frame — silent window {}, subscribed={}{}",
                    getUserName(), silent.silentWindows(), silent.subscribed(),
                    reconnect ? ", reconnecting"
                            : silent.reconnect() ? ", reconnect already in progress" : "");
        }
        // Re-arm BEFORE escalating: a successful reconnect re-arms again from onStart().
        armSilenceWatch(watch.windowMillis());
        if (reconnect) {
            escalate(silent.silentWindows(), silent.subscribed());
        }
    }

    private void escalate(int silentWindows, boolean subscribed) {
        if (metrics != null) metrics.incBotWatchdogExpired();
        // Same early-warning arming as CashoutBot; a logging aid must never stop a reconnect.
        if (scopedDebugEscalator != null) {
            try {
                scopedDebugEscalator.onWatchdogExpiry(configuration.getBotGroupId());
            } catch (Exception e) {
                log.warn("Bot {}: scoped-debug escalation failed: {}", getUserName(), e.getMessage());
            }
        }
        // triggerFullReconnect WARNs the reason itself; "watchdog" prefix => reason=watchdog.
        triggerFullReconnect("watchdog: " + silentWindows + " silent windows of "
                + watch.windowMillis() / 1_000L + " s, subscribed=" + subscribed);
    }

    // ------------------------------------------------------------------ scenario

    /** Bind the send channel (AD-10). Package-private as a test seam. */
    void bindSendChannel(VingameWebSocketClient channel, ObjectMapper mapper) {
        this.sendChannel = channel;
        this.sendMapper = mapper;
    }

    private PipelineContext buildContext(String tag, ObjectMapper mapper, VingameWebSocketClient channel) {
        return PipelineContext.buildContext()
                .timeoutMillis(configuration.getTimeoutMillis())
                .client(channel)
                .objectMapper(mapper)
                .tag(tag)
                .build();
    }

    @Override
    protected Scenario botBehaviorScenario() {
        Game game = configuration.getGame();

        // AD-10: capture the client ONCE — the bet and the cash-out leave on the same channel
        // even if Bot.client is reassigned by a reconnect (RIK_114 AD-31's rule).
        VingameWebSocketClient channel = client;

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(messageTypes.getTypeRegistrations(offset));
        bindSendChannel(channel, mapper);

        // Every callback is MDC-wrapped: the processor pool carries none. No sendAsync stage.
        return pipeline(buildContext("[Crash][" + game.getName() + "]", mapper, channel))
                .waitFor(1_000L)
                .send(request::subscribe)
                .waitForMessage(cmd(offset + CrashMessageTypes.SUBSCRIBE_CODE).and(typeOf(RECEIVED)))
                .onMessage(messageTypes.subscribeResponseType(), mdcConsumer(this::onSubscribe))
                .onMessage(messageTypes.roundStartType(), mdcConsumer(this::onRoundStart))
                .onMessage(messageTypes.betAckType(), mdcConsumer(this::onBetAck))
                .onMessage(messageTypes.bettingClosedType(), mdcConsumer(this::onBettingClosed))
                .onMessage(messageTypes.tickType(), mdcConsumer(this::onTick))
                .onMessage(messageTypes.cashoutAckType(), mdcConsumer(this::onCashoutAck))
                .onMessage(messageTypes.roundEndType(), mdcConsumer(this::onRoundEnd))
                .compile();
    }

    @Override
    protected void beforeReconnect() {
        cancelBetTask();
        if (machine != null) {
            machine.reset();
        }
        if (watch != null) {
            // The silence COUNT is deliberately kept (AD-9); only the subscribe flag resets.
            watch.clearSubscribed();
        }
    }

    /**
     * Also cancels the pending bet task (review B3): {@code logout()} and {@code cleanup()}
     * both close the client through here, and a bet task must not outlive it. The machine is
     * left alone — the next subscribe reply resets it.
     */
    @Override
    public void stop() {
        cancelBetTask();
        super.stop();
    }

    @Override
    public void cleanup() {
        super.cleanup();
        synchronized (taskLock) {
            if (betTask != null) betTask.cancel(false);
            if (silenceTask != null) silenceTask.cancel(false);
            betTask = null;
            silenceTask = null;
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    @Override
    protected void onStart() {
        // No try/log (GATEWAY_REQUEST_BUDGET A33): a failure propagates to Bot.start().
        onNewSession();

        getClient().addScenario(OutputPrinter.debugOutputPrinter(
                messageTypes.cmds(offset),
                getUserName(),
                buildContext("OutputPrinter", ObjectMapperProvider.getDefault(), client),
                mdcSnapshot));

        getClient().addScenario(botBehaviorScenario());

        // AD-9: armed HERE, not on the subscribe reply, so a refused subscribe is caught.
        armSilenceWatch(watch.beginWindow());
    }

    /** Visible for testing. */
    CrashRoundStateMachine machine() {
        return machine;
    }

    /** Visible for testing. */
    RoundSilenceWatch watch() {
        return watch;
    }

    /** Visible for testing. */
    List<Long> ladder() {
        return ladder;
    }
}
