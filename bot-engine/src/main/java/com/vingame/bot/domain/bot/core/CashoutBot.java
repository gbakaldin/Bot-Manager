package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.core.cashout.CashoutBehavior;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.FrameAction;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Outcome;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Plan;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.TimeoutAction;
import com.vingame.bot.domain.bot.message.CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import com.vingame.bot.domain.bot.message.cashout.CashoutSubscribeResponse;
import com.vingame.bot.domain.bot.message.request.CashoutRequest;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.vingame.websocketparser.message.properties.MessageType.RECEIVED;
import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.cmd;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.typeOf;
import static com.vingame.websocketparser.scenario.processors.OutboundMessage.buildMessage;
import static com.vingame.websocketparser.scenario.processors.SendMode.INFINITE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Cash-out bot for per-player multiplier games — 119 Balloon and Soccer
 * ({@code docs/plans/CASHOUT_BOT.md}). Each bet is one player's private game: bet,
 * watch the multiplier climb, cash out at a target or burst. No shared round, no
 * StartGame/EndGame, no betting window. Modelled on {@link SlotMachineBot}.
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li><b>Bet</b> (AD-10): a polled {@code sendAsync} at 250 ms. The condition asks the
 *       {@link CashoutBetStateMachine} for a {@link Plan} and parks it; the supplier pops
 *       it, debits locally and arms the watchdog.</li>
 *   <li><b>Cash-out</b> (AD-10): sent straight from the frame handler when the machine
 *       says {@code SEND_CASHOUT}, on the client captured once in
 *       {@link #botBehaviorScenario()} — no second scheduler, no poll latency, because a
 *       late cash-out is a lost one.</li>
 *   <li><b>Frames</b>: one handler for both the progress ({@code X501}) and the cash-out
 *       reply ({@code X502}) classes; the outcome is keyed on {@code isFinal}, never on
 *       the cmd (AD-5). ws-parser runs four inbound workers per client, so frames for
 *       one bet arrive concurrently and out of order — every decision is the machine's
 *       single CAS (AD-8).</li>
 *   <li><b>Watchdog</b> (AD-9): armed only while a bet is in flight, never for an idle
 *       bot. Timeout = time since the bet's last frame. Recovery is a ladder, not a
 *       reconnect per timeout.</li>
 * </ul>
 *
 * <h2>Accounting</h2> (AD-11) On send, {@code creditBalance(amount)} debits locally.
 * On the terminal frame — the server's confirmation — {@code bot_bets_placed_total} /
 * {@code bot_bet_amount_total} are incremented with the plan's stake (the burst frame
 * carries none), and gross winnings are credited. A timeout is not a confirmed bet and
 * is not counted there; its local debit stands until a drift re-sync corrects it.
 *
 * <h2>Logging</h2> (AD-13) Nothing per bet or per bot at INFO — this class contains no
 * INFO call and {@code PerBotInfoLogGuardTest} keeps it that way.
 */
@Slf4j
public class CashoutBot extends Bot {

    /** Defaults when {@code BotConfiguration} leaves the AD-9 fields unset ({@code <= 0}). */
    static final long DEFAULT_FRAME_TIMEOUT_SECONDS = 20L;
    static final long DEFAULT_TIMEOUT_BACKOFF_SECONDS = 30L;
    static final int DEFAULT_RECONNECT_AFTER_TIMEOUTS = 3;

    /** AD-6: the stake window when the group leaves {@code maxBet} unset — Balloon parity. */
    static final long DEFAULT_MIN_STAKE = 1_000L;
    static final long DEFAULT_MAX_STAKE = 100_000L;

    /** AD-10: the bet loop's poll interval. Immaterial jitter on a 0.5-4.5 s pause. */
    static final long BET_LOOP_INTERVAL_MS = 250L;

    @Setter
    private CashoutMessageTypes messageTypes;

    private CashoutRequest request;

    /** CMD base, {@code Game.offset} (AD-2): subscribe = offset, bet = +1, cash-out = +2. */
    private int offset;

    private CashoutBetStateMachine machine;

    // Test seams: set before initializeSubclass() to make the machine deterministic.
    private LongSupplier clock;
    private Random rng;

    /** AD-6: the server's stakes inside the group's window. Empty until subscribe — the bot does not bet. */
    private volatile List<Long> eligibleStakes = List.of();

    /** Park-and-pop (AD-10): the condition parks, the supplier pops. The engine forbids a null supplier. */
    private final AtomicReference<Optional<Plan>> pendingPlan = new AtomicReference<>(Optional.empty());

    /**
     * Below-cheapest-stake state (AD-6): logged on the transitions only. The gate is
     * evaluated every 250 ms, so a per-tick line would be ~14k lines/hour per broke bot.
     */
    private final AtomicBoolean belowStakeFloor = new AtomicBoolean(false);

    /**
     * AD-9 invariant: {@code checkBalance} / {@code depositIsWarranted} run on the message
     * thread between a bet's end and the next bet. While they run — a deposit can wait up to
     * {@code sessionBudgetWait()} — no new bet is placed, so that wait never eats into a live
     * bet's frame timeout.
     */
    private final AtomicBoolean sessionCheckInProgress = new AtomicBoolean(false);

    // AD-13 one-shot DEBUG per bot per JVM: the first terminal frame of each outcome. This
    // is how staging reads the uncaptured winning-frame shape (OI-1, V-8) without TRACE.
    private final AtomicBoolean firstCashoutFrameLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstBurstFrameLogged = new AtomicBoolean(false);

    /** Latched on the first cash-out send failure: WARN once per bot, DEBUG afterwards (AD-13). */
    private final AtomicBoolean cashoutFailureWarned = new AtomicBoolean(false);

    // The cash-out channel (AD-10): bound once per scenario, the same client the bet left on.
    private volatile VingameWebSocketClient cashoutChannel;
    private volatile ObjectMapper cashoutMapper;

    private ScheduledExecutorService watchdogScheduler;
    private final Object watchdogLock = new Object();
    private ScheduledFuture<?> watchdogTask; // guarded by watchdogLock

    public CashoutBot() {
        super();
    }

    /** Visible for testing: a fake clock for the state machine. Call before {@link #initializeSubclass()}. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Visible for testing: a seeded RNG for the state machine. Call before {@link #initializeSubclass()}. */
    void setRandom(Random random) {
        this.rng = random;
    }

    @Override
    protected void initializeSubclass() {
        Game game = configuration.getGame();
        Integer gameOffset = game.getOffset();
        if (gameOffset == null) {
            // AD-2: the offset IS the CMD base. Fail loud here rather than subscribe to cmd 0.
            throw new IllegalStateException(
                    "CASHOUT game '" + game.getName() + "' has no offset — offset carries the CMD base (AD-2)");
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
        long frameTimeoutSeconds = positiveOr(configuration.getCashoutFrameTimeoutSeconds(), DEFAULT_FRAME_TIMEOUT_SECONDS);
        long backoffSeconds = positiveOr(configuration.getCashoutTimeoutBackoffSeconds(), DEFAULT_TIMEOUT_BACKOFF_SECONDS);
        int reconnectAfter = configuration.getCashoutReconnectAfterTimeouts() > 0
                ? configuration.getCashoutReconnectAfterTimeouts()
                : DEFAULT_RECONNECT_AFTER_TIMEOUTS;
        this.machine = new CashoutBetStateMachine(CashoutBehavior.LEGACY,
                frameTimeoutSeconds * 1_000L, backoffSeconds * 1_000L, reconnectAfter, clock, rng);

        this.watchdogScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cashout-watchdog-" + getUserName()).factory());

        // LOG_VOLUME_TIERING tier 1: DEBUG per bot, one INFO line per group (AD-13).
        log.debug("CashoutBot initialized: game={}, plugin={}, offset={}, frameTimeout={}s, backoff={}s, reconnectAfter={}",
                game.getName(), game.getPluginName(), offset, frameTimeoutSeconds, backoffSeconds, reconnectAfter);
        if (groupLifecycleAggregator != null) {
            groupLifecycleAggregator.recordInitialized(
                    "game=" + game.getName() + ", type=" + game.getGameType() + ", offset=" + offset);
        }
    }

    private static long positiveOr(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    // ------------------------------------------------------------------ session

    /**
     * Balance check and deposit decision, between bets only (AD-9 invariant, AD-16).
     * Local unless the drift threshold or the minimum balance trips.
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

    /**
     * The subscribe reply ({@code X500}). Package-private as a test seam.
     */
    void onSubscribe(ActionResponseMessage<? extends CashoutSubscribeResponse> data) {
        if (metrics != null) metrics.incBotMessage("cashoutSubscribe");

        // FIRST and unconditionally, as in SlotMachineBot: a subscribe answered on a live
        // socket IS an authenticated connection. Gating it on a usable stake set would wedge
        // a misconfigured bot in AUTHENTICATING_CONNECTION, with nothing to time it out.
        markConnectionAuthenticated();
        // AD-12: all three outcome series at 0 before anything can increment one.
        if (metrics != null) metrics.initCashoutSeries();

        List<Long> allowed = data.getData().allowedBets();
        BotBehaviorConfig behavior = configuration.getBehaviorConfig();
        long min = behavior.getMaxBet() > 0 ? behavior.getMinBet() : DEFAULT_MIN_STAKE;
        long max = behavior.getMaxBet() > 0 ? behavior.getMaxBet() : DEFAULT_MAX_STAKE;
        List<Long> eligible = eligibleStakes(allowed, min, max);
        if (eligible.isEmpty()) {
            // AD-6: one WARN per bot per subscribe, then silence. The bot does not bet.
            log.warn("Bot {}: no eligible cash-out stake — server bets {} within [{}, {}] is empty, not betting",
                    getUserName(), allowed, min, max);
        } else {
            log.debug("Bot {}: subscribed — server bets {}, eligible stakes {}", getUserName(), allowed, eligible);
        }
        this.eligibleStakes = eligible;
        machine.reset();
        onNewSession();
    }

    /** AD-6: {@code allowed ∩ [min, max]}, ascending. */
    static List<Long> eligibleStakes(List<Long> allowed, long min, long max) {
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        List<Long> eligible = new ArrayList<>();
        for (Long stake : allowed) {
            if (stake != null && stake >= min && stake <= max && stake > 0) {
                eligible.add(stake);
            }
        }
        Collections.sort(eligible);
        return List.copyOf(eligible);
    }

    // ------------------------------------------------------------------ frames

    /**
     * Every bet frame, progress or cash-out reply. Package-private as a test seam.
     */
    void onFrame(ActionResponseMessage<? extends CashoutBetFrame> data) {
        CashoutBetFrame frame = data.getData();
        if (metrics != null) {
            metrics.incBotMessage(frame.getCmd() == offset + CashoutMessageTypes.CASHOUT_CODE
                    ? "cashoutResult" : "cashoutProgress");
        }

        FrameAction action = machine.onFrame(frame);
        switch (action) {
            case FrameAction.None none ->
                    log.trace("Bot {}: ignored cash-out frame cmd={}, sid={}", getUserName(), frame.getCmd(), frame.sid());
            case FrameAction.Progress progress ->
                    // No watchdog re-arm per frame: the watchdog re-checks the machine when it
                    // fires and re-arms for the remainder, which is the same "time since last
                    // frame" timeout without a cancel+schedule per progress tick.
                    log.trace("Bot {}: progress sid={}, x{}", getUserName(), progress.sid(), frame.multiplier());
            case FrameAction.SendCashout send -> sendCashout(send);
            case FrameAction.Ended ended -> onBetEnded(ended, frame);
        }
    }

    private void sendCashout(FrameAction.SendCashout send) {
        log.trace("Bot {}: sending cash-out sid={} at x{} (target x{})",
                getUserName(), send.sid(), send.multiplier(), send.plan().target());
        VingameWebSocketClient channel = cashoutChannel;
        ObjectMapper mapper = cashoutMapper;
        try {
            if (channel == null || mapper == null) {
                throw new IllegalStateException("cash-out channel not bound");
            }
            channel.send(request.cashOut(send.sid()).serialize(mapper));
        } catch (RuntimeException e) {
            // A frame handler must not throw. The bet will end on its own (burst) or time out.
            if (cashoutFailureWarned.compareAndSet(false, true)) {
                log.warn("Bot {}: cash-out frame not sent (further failures at DEBUG)", getUserName(), e);
            } else {
                log.debug("Bot {}: cash-out frame not sent: {}", getUserName(), e.toString());
            }
        }
    }

    private void onBetEnded(FrameAction.Ended ended, CashoutBetFrame frame) {
        cancelWatchdog();
        Plan plan = ended.plan();

        // AD-11: the terminal frame is the server's confirmation of the bet.
        if (metrics != null) metrics.incBetsPlaced(1, plan.amount());
        long winnings = frame.winningsFor(getUserName());
        lastRoundWinnings = winnings;
        if (winnings > 0) {
            expectedCurrentBalance.addAndGet(winnings);
            cumulativeWinnings.addAndGet(winnings);
            if (metrics != null) metrics.incBotWinnings(winnings);
        }
        // "Rounds" means completed bets for CASHOUT, as completed spins for SLOT.
        roundsObserved.incrementAndGet();
        if (metrics != null) metrics.incCashoutOutcome(ended.outcome().label());

        // Phase 4 hook: the CashoutWindow session aggregate is fed here (null-safe). Not
        // wired in Phase 3 — there is no CashoutSessionStrategy yet, and feeding the slot
        // strategy would mislabel these bets as spins.

        logFirstTerminalFrame(ended.outcome(), frame);
        log.trace("Bot {}: bet ended {} sid={}, stake={}, target=x{}, winnings={}, next bet in {} ms",
                getUserName(), ended.outcome().label(), ended.sid(), plan.amount(), plan.target(),
                winnings, ended.nextDelayMs());

        onNewSession();
    }

    private void logFirstTerminalFrame(Outcome outcome, CashoutBetFrame frame) {
        AtomicBoolean latch = outcome == Outcome.BURST ? firstBurstFrameLogged : firstCashoutFrameLogged;
        if (!latch.compareAndSet(false, true)) {
            return;
        }
        String what = outcome == Outcome.BURST ? "burst" : "cash-out";
        // The concrete frame's toString carries the product's own keys (119: blS, aS, aSt,
        // sL, nextOdds, nextCrd) that the contract deliberately does not name.
        log.debug("Bot {}: first {} terminal frame: cmd={}, sid={}, crd={}, odds={}, iF={}, b={}, unmapped={}, frame={}",
                getUserName(), what, frame.getCmd(), frame.sid(), frame.cashoutValue(), frame.multiplier(),
                frame.isFinal(), frame.stake().isPresent() ? frame.stake().getAsLong() : "absent",
                frame.unmapped(), frame);
    }

    // ------------------------------------------------------------------ watchdog

    private void armWatchdog(long delayMs) {
        synchronized (watchdogLock) {
            if (watchdogTask != null) {
                watchdogTask.cancel(false);
            }
            if (watchdogScheduler == null || watchdogScheduler.isShutdown()) {
                watchdogTask = null;
                return;
            }
            watchdogTask = watchdogScheduler.schedule(
                    mdcWrap(this::onWatchdogExpired), Math.max(1L, delayMs), MILLISECONDS);
        }
    }

    private void cancelWatchdog() {
        synchronized (watchdogLock) {
            if (watchdogTask != null) {
                watchdogTask.cancel(false);
                watchdogTask = null;
            }
        }
    }

    /**
     * The watchdog fired (AD-9). Package-private as a test seam.
     */
    void onWatchdogExpired() {
        if (isStopped()) return;
        TimeoutAction action = machine.onTimeout();
        if (action instanceof TimeoutAction.TimedOut timedOut) {
            if (metrics != null) metrics.incCashoutOutcome(Outcome.TIMEOUT.label());
            log.debug("Bot {}: cash-out bet of {} unanswered for the frame timeout — timeout #{}, next bet in {} ms{}",
                    getUserName(), timedOut.plan().amount(), timedOut.consecutiveTimeouts(), timedOut.nextDelayMs(),
                    timedOut.reconnect() ? ", reconnecting" : "");
            if (timedOut.reconnect()) {
                escalate(timedOut.consecutiveTimeouts());
            }
        } else if (machine.inFlight()) {
            // A frame arrived since the watchdog was armed: re-arm for what is left.
            armWatchdog(machine.millisUntilTimeout());
        }
    }

    private void escalate(int consecutiveTimeouts) {
        if (metrics != null) metrics.incBotWatchdogExpired();
        // Same early-warning arming as BettingMiniGameBot's watchdog; a logging aid must
        // never be able to stop a reconnect.
        if (scopedDebugEscalator != null) {
            try {
                scopedDebugEscalator.onWatchdogExpiry(configuration.getBotGroupId());
            } catch (Exception e) {
                log.warn("Bot {}: scoped-debug escalation failed: {}", getUserName(), e.getMessage());
            }
        }
        // triggerFullReconnect WARNs the reason itself; "watchdog" prefix => reason=watchdog.
        triggerFullReconnect("watchdog: " + consecutiveTimeouts + " cash-out bets unanswered");
    }

    // ------------------------------------------------------------------ bet loop

    /**
     * The {@code sendAsync} condition (AD-10). Package-private as a test seam.
     */
    Supplier<Boolean> betCondition() {
        return () -> {
            List<Long> stakes = eligibleStakes;
            if (stakes.isEmpty() || machine.inFlight() || sessionCheckInProgress.get()) {
                return false;
            }
            long balance = expectedCurrentBalance.get();
            long floor = stakes.get(0);
            if (balance >= floor) {
                if (belowStakeFloor.compareAndSet(true, false)) {
                    log.debug("Bot {}: balance {} covers the cheapest stake {} again — resuming bets",
                            getUserName(), balance, floor);
                }
            } else {
                if (belowStakeFloor.compareAndSet(false, true)) {
                    log.debug("Bot {}: balance {} below the cheapest stake {} — pausing bets",
                            getUserName(), balance, floor);
                }
                return false;
            }
            Optional<Plan> plan = machine.tryPlace(stakes, balance);
            if (plan.isEmpty()) {
                return false;
            }
            pendingPlan.set(plan);
            return true;
        };
    }

    /**
     * The {@code sendAsync} supplier (AD-10). Package-private as a test seam.
     */
    Supplier<ActionRequestMessage> bet() {
        return () -> {
            Optional<Plan> popped = pendingPlan.getAndSet(Optional.empty());
            Plan plan;
            if (popped.isPresent() && machine.currentPlan().filter(p -> p == popped.get()).isPresent()) {
                plan = popped.get();
            } else {
                // Race fallback, as in SlotMachineBot.spin(): a beforeReconnect between
                // condition and supplier cleared the parked plan and reset the machine.
                // Re-derive; the engine forbids returning nothing.
                log.debug("Bot {}: bet() found no parked plan — re-deriving", getUserName());
                Optional<Plan> again = machine.placeIgnoringDelay(eligibleStakes, expectedCurrentBalance.get());
                if (again.isEmpty()) {
                    // Nothing placeable (no stake set, or a bet already in flight). A
                    // re-subscribe is the one frame that is harmless to send twice.
                    return request.subscribe();
                }
                plan = again.get();
            }
            creditBalance(plan.amount());
            armWatchdog(machine.millisUntilTimeout());
            log.trace("Bot {}: sending bet {} target x{}{}", getUserName(), plan.amount(), plan.target(),
                    plan.probe() ? " (probe)" : "");
            return request.bet(plan.amount());
        };
    }

    // ------------------------------------------------------------------ scenario

    /** Bind the cash-out channel (AD-10). Package-private as a test seam. */
    void bindCashoutChannel(VingameWebSocketClient channel, ObjectMapper mapper) {
        this.cashoutChannel = channel;
        this.cashoutMapper = mapper;
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
        bindCashoutChannel(channel, mapper);

        Class<? extends CashoutSubscribeResponse> subscribeClass = messageTypes.subscribeResponseType();
        Class<? extends CashoutBetFrame> progressClass = messageTypes.progressType();
        Class<? extends CashoutBetFrame> resultClass = messageTypes.resultType();

        // Every callback is MDC-wrapped: the processor pool and the sendAsync thread carry none.
        return pipeline(buildContext("[Cashout][" + game.getName() + "]", mapper, channel))
                .waitFor(1_000L)
                .send(request::subscribe)
                .waitForMessage(cmd(offset + CashoutMessageTypes.SUBSCRIBE_CODE).and(typeOf(RECEIVED)))
                .onMessage(subscribeClass, mdcConsumer(this::onSubscribe))
                .sendAsync(buildMessage()
                        .messageSupplier(mdcSupplier(bet()))
                        .mode(INFINITE)
                        .condition(mdcSupplier(betCondition()))
                        .interval(BET_LOOP_INTERVAL_MS, MILLISECONDS)
                        .build())
                .onMessage(progressClass, mdcConsumer(this::onFrame))
                .onMessage(resultClass, mdcConsumer(this::onFrame))
                .compile();
    }

    @Override
    protected void beforeReconnect() {
        cancelWatchdog();
        if (machine != null) {
            machine.reset();
        }
        pendingPlan.set(Optional.empty());
    }

    @Override
    public void cleanup() {
        super.cleanup();
        cancelWatchdog();
        if (watchdogScheduler != null && !watchdogScheduler.isShutdown()) {
            watchdogScheduler.shutdownNow();
        }
    }

    @Override
    protected void onStart() {
        // No try/log (GATEWAY_REQUEST_BUDGET A33): a failure propagates to Bot.start().
        onNewSession();

        List<Integer> cmdList = List.of(
                offset + CashoutMessageTypes.SUBSCRIBE_CODE,
                offset + CashoutMessageTypes.BET_CODE,
                offset + CashoutMessageTypes.CASHOUT_CODE);
        getClient().addScenario(OutputPrinter.debugOutputPrinter(
                cmdList,
                getUserName(),
                buildContext("OutputPrinter", ObjectMapperProvider.getDefault(), client),
                mdcSnapshot));

        getClient().addScenario(botBehaviorScenario());
    }

    /** Visible for testing. */
    CashoutBetStateMachine machine() {
        return machine;
    }

    /** Visible for testing. */
    List<Long> eligibleStakes() {
        return eligibleStakes;
    }
}
