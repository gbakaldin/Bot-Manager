package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.request.Request;
import com.vingame.bot.domain.bot.strategy.BotMemory;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.GameState;
import com.vingame.bot.domain.bot.util.OutputPrinter;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.config.bot.BotBehaviorConfig;
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

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
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

    // Betting state
    private int numberOfBetsInCurrentSession = 0;
    private Random random = new Random();

    // Factual rolling history (Phase 2 of BETTING_STRATEGIES). Populated from
    // incoming WS messages in onStartGame/onEndGame and from outbound bets in
    // bet(). Not yet consumed by any strategy — that's Phase 3+.
    @Getter
    private BotMemory memory;

    // Visible for testing — allows deterministic randomness in unit tests.
    void setRandom(Random random) {
        this.random = random;
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

        this.watchdogTimeoutMillis = configuration.getWatchdogTimeoutSeconds() * 1000L;
        this.watchdogScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("watchdog-" + getUserName()).factory()
        );

        log.info("BettingMiniGameBot initialized: game={}, offset={}, options={}, md5={}, watchdog={}s",
                game.getName(), offset, game.getEffectiveOptionAffinities().size(), game.isMd5(),
                configuration.getWatchdogTimeoutSeconds());
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
        numberOfBetsInCurrentSession = 0;
        // Phase 2 of BETTING_STRATEGIES: kick off the in-flight RoundState for
        // bet→result correlation. Balance snapshot mirrors expectedCurrentBalance.
        if (memory != null) {
            memory.beginRound(msg.getSessionId(), expectedCurrentBalance.get());
        }
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
        if (memory != null) {
            Optional<Integer> winningOption = Optional.empty();
            memory.completeRound(msg.getSessionId(), winningOption, payout);
            memory.recordGlobalWin(winningOption);
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
        numberOfBetsInCurrentSession = 0;
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

    private Supplier<ActionRequestMessage> bet() {
        return () -> {
            long nextBetAmount = resolveBetAmount();
            creditBalance(nextBetAmount);

            int entryToBet = resolveNextEntryToBet();
            long currentSid = sidStore.get();
            // Phase 2 of BETTING_STRATEGIES: accumulate bet→result correlation.
            // Strategy is not yet consumed (Phase 5); this is data-plumbing only.
            if (memory != null) {
                memory.recordBetSent(currentSid, entryToBet, nextBetAmount);
            }
            return request.bet(nextBetAmount, entryToBet, currentSid);
        };
    }

    private int resolveNextEntryToBet() {
        // Phase 1 keeps the call-site behavior identical to the pre-affinity world:
        // uniform pick over the option-id set. Affinity-aware option selection is a
        // future BettingStrategy's concern (see BETTING_STRATEGIES.md, Architecture
        // Decision 5 — affinity is a neutral prior, not "high = bet more").
        List<Integer> options = List.copyOf(
                configuration.getGame().getEffectiveOptionAffinities().keySet());
        return options.get(random.nextInt(options.size()));
    }

    private boolean doesEnoughTimeRemain() {
        return remainingTime.get() >= blockBetTime;
    }

    private boolean canBet() {
        boolean sessionExists = sidStore.get() != 0L;
        boolean betPhaseActive = gameState == BettingMiniGameState.BET;

        return sessionExists && betPhaseActive && doesEnoughTimeRemain();
    }

    @Override
    protected boolean shouldBet() {
        BotBehaviorConfig behavior = configuration.getBehaviorConfig();

        if (numberOfBetsInCurrentSession < behavior.getMaxBetsPerRound()) {
            if (random.nextInt(100) < behavior.getBetSkipPercentage()) {
                return false;
            }

            numberOfBetsInCurrentSession++;
            return true;
        }

        return false;
    }

    @Override
    protected Supplier<Boolean> resolveBetCondition() {
        return () -> canBet() && shouldBet();
    }

    @Override
    protected long resolveBetAmount() {
        BotBehaviorConfig behavior = configuration.getBehaviorConfig();

        long minBet = behavior.getMinBet();
        long maxBet = behavior.getMaxBet();
        long betStep = behavior.getBetIncrement();

        int maxSteps = Math.toIntExact((maxBet - minBet) / betStep);
        long steps = random.nextInt(maxSteps + 1);

        return minBet + (steps * betStep);
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
                        .condition(mdcSupplier(resolveBetCondition()))
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
