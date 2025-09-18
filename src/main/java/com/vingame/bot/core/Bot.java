package com.vingame.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.authentication.AuthClient;
import com.vingame.bot.authentication.GameMsClient;
import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.scenario.Scenario;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.util.function.Supplier;

@Slf4j
public abstract class Bot {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AuthClient authClient = new AuthClient();
    private final GameMsClient gameMsClient;

    @Getter
    private VingameWebsocketClient client;

    @Setter
    @Getter
    private String apiGateway;

    private volatile long lastFetchedBalance = -1;
    private volatile long expectedCurrentBalance = -100_000_000L;

    public Bot(VingameWebsocketClient client) {
        this.client = client;
        this.gameMsClient = new GameMsClient(client.getAgencyToken());
    }

    public void restart() {
        client = client.copy();
        client.connect();
        authenticate();
        start();
    }

    public void stop() {
        client.close();
    }

    protected abstract boolean shouldBet();

    protected void connectToSocket() {

    }

    public void authenticate() {

    }

    public void deposit() {
        if (lastFetchedBalance < 0) {
            return;
        }
        gameMsClient.deposit(1_000_000_000L, success -> {
            if (success) {
                log.info("Deposit successful, fetching new balance...");
                lastFetchedBalance = authClient.getBalance(apiGateway, getClient().getAuthToken());
                expectedCurrentBalance = lastFetchedBalance;
                log.info("New balance: {}", expectedCurrentBalance);
            } else {
                log.warn("Deposit failed.");
            }
        });
    }

    protected long checkBalance() {
        if (Math.abs(lastFetchedBalance - expectedCurrentBalance) > 1_000_000L) {
            lastFetchedBalance = authClient.getBalance(apiGateway, getClient().getAuthToken());
            expectedCurrentBalance = lastFetchedBalance;
        }

        return expectedCurrentBalance;
    }

    protected long getMinBalance() {
        return 5_000_000L;
    }

    protected abstract long resolveBetAmount();

    protected abstract Supplier<Boolean> resolveBetCondition();

    protected abstract Scenario botBehaviorScenario();

    protected void creditBalance(long amount) {
        this.expectedCurrentBalance -= amount;
    }

    public abstract void start();
}
