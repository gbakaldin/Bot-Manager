package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.request.Request;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.GameState;
import com.vingame.bot.domain.bot.util.OutputPrinter;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.websocketparser.ObjectMapperProvider;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.Scenario;
import com.vingame.websocketparser.scenario.processors.OutboundMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.vingame.websocketparser.message.properties.MessageType.RECEIVED;
import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.cmd;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.typeOf;
import static com.vingame.websocketparser.scenario.processors.OutboundMessage.buildMessage;
import static com.vingame.websocketparser.scenario.processors.SendMode.INFINITE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Concrete bot implementation for all BettingMini game types.
 * Configured via Game entity and BotBehaviorConfig.
 * <p>
 * Supports all games of BettingMini type (BauCua, TaiXiuSeven, etc.)
 * through dynamic configuration rather than subclassing.
 */
@Slf4j
public class BettingMiniGameBot extends Bot {

    // Game configuration
    @Setter
    private GameMessageTypes messageTypes;

    // Request factory for outbound messages
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

    // Betting state
    private int numberOfBetsInCurrentSession = 0;
    private final Random random = new Random();

    public BettingMiniGameBot() {
        super();
    }

    @Override
    protected void initializeSubclass() {
        Game game = configuration.getGame();
        this.offset = game.getOffset();
        this.sidStore = new SessionIdStore(0L);

        // Create Request factory with game-specific config
        this.request = new Request(
            game.getPluginName(),
            configuration.getZoneName(),
            offset
        );

        log.info("BettingMiniGameBot initialized: game={}, offset={}, options={}, md5={}",
                game.getName(), offset, game.getNumberOfOptions(), game.isMd5());
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
        // Use virtual thread for countdown timer (lightweight, scalable to 100k+ bots)
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("countdown-" + getUserName()).factory()
        );

        remainingTime.set(timeForBetting);
        scheduler.scheduleAtFixedRate(() -> {
            if (remainingTime.get() > 0) {
                remainingTime.addAndGet(-1_000L);
            }
        }, 0L, 1_000L, MILLISECONDS);
    }

    private void onSubscribe(ActionResponseMessage<? extends SubscribeMessage> data) {
        markConnectionAuthenticated();
        SubscribeMessage msg = data.getData();
        blockBetTime = msg.getTimeForDecision();
        timeForBetting = msg.getTimeForBetting();
    }

    private void onStartGame(ActionResponseMessage<? extends StartGameMessage> data) {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        startRemainingTimeCountDown();
        StartGameMessage msg = data.getData();
        sidStore.set(msg.getSessionId());
        gameState = BettingMiniGameState.BET;
        numberOfBetsInCurrentSession = 0;
    }

    private void onUpdate(ActionResponseMessage<? extends UpdateBetMessage> data) {
        UpdateBetMessage msg = data.getData();
        int gameStateId = msg.getGameState();

        if (gameStateId > 0) {
            gameState = BettingMiniGameState.from(gameStateId);
        }
    }

    private void onEndGame(ActionResponseMessage<? extends EndGameMessage> data) {
        gameState = BettingMiniGameState.PAYOUT;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        onNewSession();
    }

    private Supplier<ActionRequestMessage> bet() {
        return () -> {
            long nextBetAmount = resolveBetAmount();
            creditBalance(nextBetAmount);

            int entryToBet = resolveNextEntryToBet();
            return request.bet(nextBetAmount, entryToBet, sidStore.get());
        };
    }

    private int resolveNextEntryToBet() {
        List<Integer> options = configuration.getGame().getEffectiveBettingOptions();
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
            // Skip bet based on configured probability
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

        // Configure ObjectMapper with dynamic type registrations
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(messageTypes.getTypeRegistrations(offset, game.isMd5()));

        // Get message classes - type-safe since abstract classes extend Body
        Class<? extends SubscribeMessage> subscribeClass = messageTypes.subscribeType();
        Class<? extends StartGameMessage> startGameClass = game.isMd5() ? messageTypes.startGameMd5Type() : messageTypes.startGameType();
        Class<? extends UpdateBetMessage> updateBetClass = messageTypes.updateBetType();
        Class<? extends EndGameMessage> endGameClass = messageTypes.endGameType();

        return pipeline(buildContext("[Betting Mini][" + configuration.getGame().getName() + "]", mapper))
                .waitFor(1_000L)
                .send(request::subscribe)
                .waitForMessage(cmd(GameMessageTypes.SUBSCRIBE_CODE + offset).and(typeOf(RECEIVED)))
                .onMessage(subscribeClass, this::onSubscribe)
                .onMessage(startGameClass, this::onStartGame)
                .onMessage(updateBetClass, this::onUpdate)
                .sendAsync(buildMessage()
                        .messageSupplier(bet())
                        .mode(INFINITE)
                        .condition(resolveBetCondition())
                        .interval(resolveIntervalBetweenBets(), MILLISECONDS)
                        .build())
                .onMessage(endGameClass, this::onEndGame)
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
        getClient().addScenario(OutputPrinter.debugOutputPrinter(
            cmdList,
            getUserName(),
            buildContext("OutputPrinter", ObjectMapperProvider.getDefault())
        ));

        getClient().addScenario(botBehaviorScenario());
    }
}
