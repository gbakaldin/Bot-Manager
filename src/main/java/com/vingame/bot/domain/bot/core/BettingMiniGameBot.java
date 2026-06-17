package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.message.request.Request;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.BotMemory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.GameState;
import com.vingame.bot.domain.bot.util.OutputPrinter;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.websocketparser.ObjectMapperProvider;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.Scenario;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.vingame.websocketparser.message.properties.MessageType.RECEIVED;
import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.cmd;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.typeOf;
import static com.vingame.websocketparser.scenario.processors.OutboundMessage.buildMessage;
import static com.vingame.websocketparser.scenario.processors.SendMode.INFINITE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
public class BettingMiniGameBot extends Bot {

    // Game configuration
    @Setter
    private GameMessageTypes messageTypes;

    private Request request;

    // Game state
    @Getter
    private SessionIdStore sidStore;
    private int offset;

    private long blockBetTime = 3_000L;
    private volatile GameState gameState;
    private long timeForBetting = 0L;
    private final AtomicLong remainingTime = new AtomicLong(0L);

    private ScheduledExecutorService scheduler;

    // Watchdog — fires if no game messages arrive within the configured timeout
    private ScheduledExecutorService watchdogScheduler;
    private volatile ScheduledFuture<?> watchdogTask;
    private long watchdogTimeoutMillis;

    // Betting state — per-bot RNG, owned by the bot and threaded into BetContext
    // on every tick. The strategy never holds its own RNG (see Decision 13 and
    // BettingStrategyFactory javadoc); the bot owns lifecycle and seeding.
    private Random rng = new Random();

    // Factual rolling history (Phase 2 of BETTING_STRATEGIES). Populated from
    // incoming WS messages in onStartGame/onEndGame and from outbound bets in
    // bet(). Read by the strategy via BetContext on every tick.
    @Getter
    private BotMemory memory;

    // Strategy factory (injected by BotFactory) used at initializeSubclass to
    // build the per-bot BettingStrategy instance for this.strategyId.
    @Setter
    private BettingStrategyFactory strategyFactory;

    // Per-bot strategy instance (built in initializeSubclass via strategyFactory).
    // One instance per bot per restart; state-carrying (Decision 1, 10).
    @Getter
    private BettingStrategy strategy;

    // Pre-computed decision shared between the sendAsync condition and the
    // supplier. The scenario engine throws if the supplier returns null
    // (SendAsync.processInternal:135), so the condition computes decide(ctx)
    // and parks the Optional here; the supplier reads it back to build the bet.
    // See BETTING_STRATEGIES.md Implementation Note 1.
    private final AtomicReference<Optional<BetDecision>> pendingDecision =
            new AtomicReference<>(Optional.empty());

    // Visible for testing — allows deterministic randomness in unit tests by
    // injecting a mocked or seeded Random. Preserves the legacy test seam used
    // by BettingMiniGameBotTest / BettingMiniGameBotTipDispatchTest.
    void setRandom(Random random) {
        this.rng = random;
    }

    public BettingMiniGameBot() {
        super();
    }

    @Override
    protected void initializeSubclass() {
        Game game = configuration.getGame();
        this.offset = game.getOffset();
        this.sidStore = new SessionIdStore(0L);

        this.request = new Request(
            game.getPluginName(),
            configuration.getZoneName(),
            offset
        );

        // BotMemory is built after `game` is set so its captured Game reference is
        // non-null. Capacity defaults to BotMemory.DEFAULT_CAPACITY (50, per
        // BETTING_STRATEGIES Architecture Decision 3 — hardcoded for v1).
        this.memory = new BotMemory(game);

        // Phase 5: seed the per-bot RNG. Decision 13 — deterministic-ish per
        // user (hash of userName) but distinct per process (XOR with nanoTime)
        // so two restarts of the same bot don't replay an identical sequence.
        // Test fixtures override this via setRandom().
        this.rng = new Random(((long) getUserName().hashCode()) ^ System.nanoTime());

        // Phase 5: build the per-bot strategy via the factory. strategyId is
        // populated by BotGroupBehaviorService.createSingleBot via the
        // fill-to-target assignment over BotGroup.strategyMix; legacy/test
        // fixtures that bypass the assignment default to RANDOM (Decision 7).
        StrategyId effectiveId = strategyId != null ? strategyId : StrategyId.RANDOM;
        if (strategyFactory != null) {
            this.strategy = strategyFactory.create(effectiveId, rng.nextLong());
        } else {
            // Test seam: fixtures that don't wire the factory (and don't intend
            // to exercise the strategy code path) get a default instance so
            // the bet() supplier can still run without NPE. Production callers
            // always go through BotFactory which wires the factory.
            this.strategy = new com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy();
        }

        this.watchdogTimeoutMillis = configuration.getWatchdogTimeoutSeconds() * 1000L;
        this.watchdogScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("watchdog-" + getUserName()).factory()
        );

        log.info("BettingMiniGameBot initialized: game={}, offset={}, options={}, md5={}, watchdog={}s, strategy={}",
                game.getName(), offset, game.getEffectiveOptionAffinities().size(), game.isMd5(),
                configuration.getWatchdogTimeoutSeconds(), effectiveId);
    }

    private void onNewSession() {
        long balance = checkBalance();
        BotBehaviorConfig behavior = configuration.getBehaviorConfig();
        if (behavior.isAutoDepositEnabled() && balance < getMinBalance()) {
            log.info("Bot {}: balance {} below minimum {}, triggering deposit", getUserName(), balance, getMinBalance());
            deposit();
        } else {
            log.debug("Bot {}: session balance {}", getUserName(), balance);
        }
    }

    private void startRemainingTimeCountDown() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("countdown-" + getUserName()).factory()
        );

        remainingTime.set(timeForBetting);
        // Wrap with mdcWrap so any future log lines from this countdown task carry the
        // bot's identity. The countdown-<userName> virtual thread is created with an
        // empty MDC; today the body is logging-free but the wrap is cheap insurance.
        scheduler.scheduleAtFixedRate(mdcWrap(() -> {
            if (remainingTime.get() > 0) {
                remainingTime.addAndGet(-1_000L);
            }
        }), 0L, 1_000L, MILLISECONDS);
    }

    private void scheduleWatchdog() {
        if (watchdogTask != null && !watchdogTask.isDone()) {
            watchdogTask.cancel(false);
        }
        // Wrap with mdcWrap so the warn-log + triggerFullReconnect() inside
        // onWatchdogExpired carry the bot's MDC. The watchdog-<userName> virtual
        // thread is created with an empty MDC.
        watchdogTask = watchdogScheduler.schedule(
                mdcWrap(this::onWatchdogExpired),
                watchdogTimeoutMillis,
                MILLISECONDS
        );
    }

    private void onWatchdogExpired() {
        if (isStopped()) return;
        log.warn("Bot {}: no game message in {}s — triggering full reconnect",
                getUserName(), configuration.getWatchdogTimeoutSeconds());
        if (metrics != null) metrics.incBotWatchdogExpired();
        triggerFullReconnect("watchdog timeout (" + configuration.getWatchdogTimeoutSeconds() + "s without game message)");
    }

    private void onSubscribe(ActionResponseMessage<? extends SubscribeMessage> data) {
        if (metrics != null) metrics.incBotMessage("subscribe");
        markConnectionAuthenticated();
        SubscribeMessage msg = data.getData();
        blockBetTime = msg.getTimeForDecision();
        timeForBetting = msg.getTimeForBetting();
        scheduleWatchdog();
    }

    private void onStartGame(ActionResponseMessage<? extends StartGameMessage> data) {
        if (metrics != null) metrics.incBotMessage("startGame");
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        startRemainingTimeCountDown();
        StartGameMessage msg = data.getData();
        sidStore.set(msg.getSessionId());
        gameState = BettingMiniGameState.BET;
        // Phase 2 of BETTING_STRATEGIES: kick off the in-flight RoundState for
        // bet→result correlation. Balance snapshot mirrors expectedCurrentBalance.
        // Phase 5: the per-round bet counter now lives on the strategy
        // (RandomBehaviorStrategy resets it on sessionId change via
        // currentRound.sessionId, so no explicit reset call is needed here).
        if (memory != null) {
            memory.beginRound(msg.getSessionId(), expectedCurrentBalance.get());
        }
        // Clear any stale decision from the previous round so the new round
        // starts with no parked bet.
        pendingDecision.set(Optional.empty());
        scheduleWatchdog();
    }

    private void onUpdate(ActionResponseMessage<? extends UpdateBetMessage> data) {
        if (metrics != null) metrics.incBotMessage("updateBet");
        UpdateBetMessage msg = data.getData();
        int gameStateId = msg.getGameState();

        if (gameStateId > 0) {
            gameState = BettingMiniGameState.from(gameStateId);
        }
    }

    private void onEndGame(ActionResponseMessage<? extends EndGameMessage> data) {
        if (metrics != null) metrics.incBotMessage("endGame");

        EndGameMessage msg = data.getData();
        // Marker-interface dispatch (ENDGAME_METRICS plan, Phase A/C).
        // Per-message extraction. Independent `if` checks — a message may
        // implement multiple interfaces. The pre-Phase-A capability hooks
        // (getWinnings / getJackpot / canCheckTotalWinnings / getTotalWinnings
        // / getRoundTotalBetAmount) were deleted in Phase C; the message
        // payload now owns extraction.
        // Extraction (local-accumulator updates) runs unconditionally — those
        // fields back BotHealthDTO and are independent of Prometheus wiring.
        // Only the metric emission is gated on `metrics != null`.
        long payout = 0L;
        if (msg instanceof HasBotWinnings hw) {
            long w = hw.winningsFor(getUserName());
            payout = w;
            lastRoundWinnings = w;
            if (metrics != null && w > 0) metrics.incBotWinnings(w);
        }
        if (metrics != null) {
            if (msg instanceof HasJackpot hj) {
                long j = hj.jackpotFor(getUserName());
                if (j > 0) metrics.incBotJackpot(j);
            }
            if (msg instanceof HasBetTotals bt) {
                // Batch increment: bot_bets_placed_total += count,
                // bot_bet_amount_total += amount. Two-counter math, no average.
                metrics.incBetsPlaced(bt.betCountFor(getUserName()),
                        bt.betAmountFor(getUserName()));
            }
        }

        // Phase 2 of BETTING_STRATEGIES: finalize the in-flight RoundState into
        // a RoundResult and push onto BotMemory.lastResults. v1 cannot yet
        // extract winningOption (no HasWinningOption marker exists — Implementation
        // Note 4); pass Optional.empty(). globalRecentWins stays empty for v1.
        // Phase 5: route the finalized RoundResult into strategy.onRoundEnd
        // so stateful strategies (Martingale, trend-followers) can update
        // their interpretive state. RandomBehaviorStrategy is a no-op.
        if (memory != null) {
            Optional<Integer> winningOption = Optional.empty();
            RoundResult roundResult = memory.completeRound(msg.getSessionId(), winningOption, payout);
            memory.recordGlobalWin(winningOption);
            if (strategy != null) {
                strategy.onRoundEnd(roundResult);
            }
        } else if (strategy != null) {
            // Defensive: feed the strategy a synthetic RoundResult derived from
            // the EndGame so it can update its own state even when memory is
            // not wired (test fixtures only — production always wires memory).
            RoundResult synthetic = new RoundResult(
                    msg.getSessionId(), Optional.empty(),
                    Map.of(), payout, payout, Instant.now());
            strategy.onRoundEnd(synthetic);
        }

        gameState = BettingMiniGameState.PAYOUT;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        scheduleWatchdog();
        onNewSession();
    }

    @Override
    protected void beforeReconnect() {
        if (watchdogTask != null) {
            watchdogTask.cancel(false);
            watchdogTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        sidStore.set(0L);
        gameState = null;
        remainingTime.set(0L);
        // Strategy state is intentionally not reset here — a reconnect mid-round
        // does not produce a new RoundResult, and strategies that care about
        // cross-round state (e.g. Martingale loss streak) should not lose it on
        // a transient WS disconnect. RandomBehaviorStrategy's per-round counter
        // re-syncs on the next StartGame via the sessionId-change branch.
        pendingDecision.set(Optional.empty());
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (watchdogScheduler != null && !watchdogScheduler.isShutdown()) {
            watchdogScheduler.shutdownNow();
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Build a fresh {@link BetContext} snapshot for the current tick. Called
     * from the scenario condition (and indirectly from the supplier via the
     * parked {@link #pendingDecision}) on the {@code pool-N-thread-1} scenario
     * thread. Strategies read this synchronously and must not cache it across
     * calls — the in-flight RoundState, balance, and memory snapshots are all
     * stale by the next tick.
     */
    private BetContext buildBetContext() {
        return new BetContext(
                memory,
                configuration.getBehaviorConfig(),
                configuration.getGame(),
                expectedCurrentBalance.get(),
                memory != null ? memory.getCurrentRound() : null,
                rng);
    }

    /**
     * Phase 5: the {@code sendAsync} supplier reads the decision parked by
     * {@link #betCondition()}. The scenario engine throws if a supplier returns
     * null (SendAsync.processInternal:135), so the only path that reaches this
     * supplier is one where the condition saw a present decision; we pop it,
     * book-keep the bet, and build the WS message.
     */
    private Supplier<ActionRequestMessage> bet() {
        return () -> {
            Optional<BetDecision> popped = pendingDecision.getAndSet(Optional.empty());
            if (popped.isEmpty()) {
                // Should not happen — the condition gates the supplier and
                // only returns true when a decision is parked. Defensive: if
                // we ever reach here we'd otherwise NPE the scenario engine,
                // so synthesize a no-bet by returning a sentinel-free path.
                // The only safe thing to do is throw — the engine's null guard
                // will then surface this as a loud error rather than a silent
                // drop. Mirrors Implementation Note 1.
                throw new IllegalStateException(
                        "bet() supplier invoked with no parked decision — " +
                                "condition/supplier ordering violated");
            }
            BetDecision decision = popped.get();
            long amount = decision.amount();
            int optionId = decision.optionId();
            creditBalance(amount);

            long currentSid = sidStore.get();
            // Phase 2 of BETTING_STRATEGIES: accumulate bet→result correlation.
            if (memory != null) {
                memory.recordBetSent(currentSid, optionId, amount);
            }
            log.debug("Bot {}: sending bet option={}, amount={}, sid={}",
                    getUserName(), optionId, amount, currentSid);
            return request.bet(amount, optionId, currentSid);
        };
    }

    private boolean doesEnoughTimeRemain() {
        return remainingTime.get() >= blockBetTime;
    }

    private boolean canBet() {
        boolean sessionExists = sidStore.get() != 0L;
        boolean betPhaseActive = gameState == BettingMiniGameState.BET;

        return sessionExists && betPhaseActive && doesEnoughTimeRemain();
    }

    /**
     * Phase 5: the {@code sendAsync} condition. Gates on the phase-level
     * {@link #canBet()} predicate (session live, BET phase, time remaining),
     * then asks the strategy for a {@link BetDecision} and parks the result
     * in {@link #pendingDecision}. Returns {@code true} only when a decision
     * is present, ensuring the downstream supplier always sees a non-empty
     * parked value.
     *
     * <p>Per CLAUDE.md the per-tick decide() outcome is DEBUG-level.
     */
    private Supplier<Boolean> betCondition() {
        return () -> {
            if (!canBet()) {
                return false;
            }
            if (strategy == null) {
                return false;
            }
            Optional<BetDecision> decision = strategy.decide(buildBetContext());
            if (decision.isEmpty()) {
                log.debug("Bot {}: strategy skipped tick (no decision)", getUserName());
                return false;
            }
            pendingDecision.set(decision);
            log.debug("Bot {}: strategy parked decision option={}, amount={}",
                    getUserName(), decision.get().optionId(), decision.get().amount());
            return true;
        };
    }

    private long resolveIntervalBetweenBets() {
        return 1_000L;
    }

    private PipelineContext buildContext(String tag, ObjectMapper mapper) {
        return PipelineContext.buildContext()
                .timeoutMillis(configuration.getTimeoutMillis())
                .client(client)
                .objectMapper(mapper)
                .tag(tag)
                .build();
    }

    @Override
    protected Scenario botBehaviorScenario() {
        Game game = configuration.getGame();

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(messageTypes.getTypeRegistrations(offset, game.isMd5()));

        Class<? extends SubscribeMessage> subscribeClass = messageTypes.subscribeType();
        Class<? extends StartGameMessage> startGameClass = game.isMd5() ? messageTypes.startGameMd5Type() : messageTypes.startGameType();
        Class<? extends UpdateBetMessage> updateBetClass = messageTypes.updateBetType();
        Class<? extends EndGameMessage> endGameClass = messageTypes.endGameType();

        // onMessage handlers run on the per-client netty-ws-message-processor-ws-<userName>
        // pool; sendAsync's supplier + condition run on a scenario-owned pool-N-thread-1.
        // None of these threads carry MDC by default — wrap each callback so its
        // log lines (and the OutputPrinter-emitted lines that share the pool) carry
        // the bot's identity.
        return pipeline(buildContext("[Betting Mini][" + configuration.getGame().getName() + "]", mapper))
                .waitFor(1_000L)
                .send(request::subscribe)
                .waitForMessage(cmd(GameMessageTypes.SUBSCRIBE_CODE + offset).and(typeOf(RECEIVED)))
                .onMessage(subscribeClass, mdcConsumer(this::onSubscribe))
                .onMessage(startGameClass, mdcConsumer(this::onStartGame))
                .onMessage(updateBetClass, mdcConsumer(this::onUpdate))
                .sendAsync(buildMessage()
                        .messageSupplier(mdcSupplier(bet()))
                        .mode(INFINITE)
                        .condition(mdcSupplier(betCondition()))
                        .interval(resolveIntervalBetweenBets(), MILLISECONDS)
                        .build())
                .onMessage(endGameClass, mdcConsumer(this::onEndGame))
                .compile();
    }

    @Override
    protected void onStart() {
        try {
            onNewSession();
        } catch (Exception e) {
            log.error("Bot {}: initial session setup failed", getUserName(), e);
            throw e;
        }

        List<Integer> cmdList = List.of(
            GameMessageTypes.SUBSCRIBE_CODE + offset,
            GameMessageTypes.UPDATE_BET_CODE + offset,
            GameMessageTypes.START_GAME_CODE + offset,
            GameMessageTypes.END_GAME_CODE + offset
        );
        // Pass the bot's MDC snapshot so the "User <name>: ..." log lines emitted
        // from the netty-ws-message-processor-ws-<userName> pool carry botGroupId,
        // environmentId, gameType, etc. for Promtail to promote to Loki labels.
        getClient().addScenario(OutputPrinter.debugOutputPrinter(
            cmdList,
            getUserName(),
            buildContext("OutputPrinter", ObjectMapperProvider.getDefault()),
            mdcSnapshot
        ));

        getClient().addScenario(botBehaviorScenario());
    }
}
