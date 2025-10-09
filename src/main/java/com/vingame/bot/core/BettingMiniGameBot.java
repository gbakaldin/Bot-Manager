package com.vingame.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.environment.store.ConnectionData;
import com.vingame.bot.brands.bom.message.bettingmini.Request;
import com.vingame.bot.brands.bom.message.bettingmini.response.EndGameData;
import com.vingame.bot.brands.bom.message.bettingmini.response.StartGameData;
import com.vingame.bot.brands.bom.message.bettingmini.response.Subscribe;
import com.vingame.bot.brands.bom.message.bettingmini.response.UpdateBet;
import com.vingame.bot.util.BettingMiniGameState;
import com.vingame.bot.util.GameState;
import com.vingame.bot.util.SessionIdStore;
import com.vingame.webocketparser.message.properties.MessageProperty;
import com.vingame.webocketparser.message.properties.MessageType;
import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.response.ActionResponseMessage;
import com.vingame.webocketparser.scenario.Scenario;
import com.vingame.webocketparser.scenario.processors.SendMode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.vingame.webocketparser.scenario.matchers.Qualifier.qualifier;
import static com.vingame.webocketparser.scenario.matchers.Qualifier.typeOf;

@Slf4j
public abstract class BettingMiniGameBot extends Bot {

    private final Request REQUEST;

    @Getter
    private final SessionIdStore sidStore;
    private final int cmdPrefix;

    private long blockBetTime = 3_000L;
    private volatile GameState gameState;
    private long timeForBetting = 0L;
    private final AtomicLong remainingTime = new AtomicLong(0L);

    private ScheduledExecutorService scheduler;

    public BettingMiniGameBot(ConnectionData data, String userName, String password, Request request, int cmdPrefix) {
        super(data, userName, password);

        this.REQUEST = request;
        this.cmdPrefix = cmdPrefix;

        this.sidStore = new SessionIdStore(0L);
    }

    protected abstract int resolveNextEntryToBet();


    protected void onNewSession() {
        if (checkBalance() < getMinBalance()) {
            deposit();
        }
    }

    protected void startRemainingTimeCountDown() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        remainingTime.set(timeForBetting);
        scheduler.scheduleAtFixedRate(() -> {
            if (remainingTime.get() > 0) {
                remainingTime.addAndGet(-1_000L);
            }
        }, 0L, 1_000L, TimeUnit.MILLISECONDS);
    }

    protected long resolveDelayMillisBeforeFirstBet() {
        return 0L;
    }

    protected void onSubscribe(ActionResponseMessage<Subscribe> data) {
        blockBetTime = data.getData().getTFD();
        timeForBetting = data.getData().getTFB();
    }

    protected void onStartGame(ActionResponseMessage<StartGameData> data) {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        startRemainingTimeCountDown();
        sidStore.set(data.getData().getSid());
        gameState = BettingMiniGameState.BET;
    }

    protected void onUpdate(ActionResponseMessage<UpdateBet> data) {
        int gameStateId = data.getData().getGameState();

        if (gameStateId > 0) {
            gameState = BettingMiniGameState.from(gameStateId);
        }
    }

    protected void onEndGame(ActionResponseMessage<EndGameData> data) {
        gameState = BettingMiniGameState.PAYOUT;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        onNewSession();
    }

    protected Supplier<ActionRequestMessage> bet() {
        return () -> {
            long nextBetAmount = resolveBetAmount();
            creditBalance(nextBetAmount);

            return REQUEST.bet(nextBetAmount, resolveNextEntryToBet(), sidStore.get());
        };
    }

    protected boolean doesEnoughTimeRemain() {
        return remainingTime.get() >= blockBetTime;
    }

    protected boolean canBet() {
        boolean sessionExists = sidStore.get() != 0L;
        boolean betPhaseActive = gameState == BettingMiniGameState.BET;

        return sessionExists && betPhaseActive && doesEnoughTimeRemain();
    }

    @Override
    public Supplier<Boolean> resolveBetCondition() {
        return () -> canBet() && shouldBet();
    }

    protected long resolveIntervalBetweenBets() {
        return 1_000L;
    }

    @Override
    public Scenario botBehaviorScenario() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return Scenario.pipeline()
                .useClient(getClient())
                .send(REQUEST::subscribe)
                .waitForMessage(qualifier(MessageProperty.CMD, cmdPrefix + 3000), typeOf(MessageType.RECEIVED))
                .onMessage(this::onSubscribe, Subscribe.class, mapper)
                .onMessage(this::onStartGame, StartGameData.class, mapper)
                .onMessage(this::onUpdate, UpdateBet.class, mapper)
                .waitFor(resolveDelayMillisBeforeFirstBet(), TimeUnit.MILLISECONDS)
                .sendAsync(bet(), SendMode.INFINITE, resolveBetCondition(), resolveIntervalBetweenBets())
                .onMessage(this::onEndGame, EndGameData.class, mapper)
                .compile();
    }

    @Override
    public void onStart() {
        onNewSession();
        getClient().addScenario(botBehaviorScenario());
    }
}
