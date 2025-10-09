package com.vingame.bot.core;

import com.vingame.bot.environment.ApiGatewayClient;
import com.vingame.bot.brands.bom.ClientFactory;
import com.vingame.bot.environment.GameMsClient;
import com.vingame.bot.environment.store.ConnectionData;
import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.scenario.Scenario;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public abstract class Bot {

    private final ApiGatewayClient authClient;

    private final GameMsClient gameMsClient;

    @Getter
    private VingameWebsocketClient client;

    @Getter
    private final String userName;

    @Getter
    private final String apiGateway;

    private volatile long lastFetchedBalance = -1;
    private final AtomicLong expectedCurrentBalance = new AtomicLong(-100_000_000L);

    public Bot(ConnectionData data, String userName, String password) {
        String fingerprint = "f298e7a233c88c9980a7d90dc707fbe7" + Math.floor(Math.random() * 99999);

        this.userName = userName;
        this.authClient = new ApiGatewayClient(data.getApiGateway(), data.getAppId(), userName, password, fingerprint);
        this.apiGateway = data.getApiGateway();

        ClientFactory clientFactory = new ClientFactory();
        clientFactory.setUri(data.getUri());
        clientFactory.setHeaders(data.getHeaders());
        clientFactory.setZoneName(data.getZoneName());
        clientFactory.setEncryption(true);

        this.client = clientFactory.newClient();

        this.gameMsClient = new GameMsClient();
    }

    public void restart() {
        client = client.copy();
        start();
    }

    public void stop() {
        client.close();
    }

    protected abstract boolean shouldBet();

    protected void connectToSocket() {
        client.connect();
    }

    public void authenticate() {
        try {
            client.authenticate(authClient::authenticate);
        } catch (Exception e) {
            log.error("Bot {}: Authentication failed: {}", userName, e.getMessage());
            return;
        }
        gameMsClient.setAgencyToken(client.getAgencyToken());
    }

    public void deposit() {
        if (lastFetchedBalance < 0) {
            return;
        }
        gameMsClient.deposit(1_000_000_000L, success -> {
            if (success) {
                log.info("Bot {}: Deposit successful, fetching new balance...", userName);
                lastFetchedBalance = authClient.getBalance(apiGateway, getClient().getAuthToken());
                expectedCurrentBalance.set(lastFetchedBalance);
                log.info("Bot {}: New balance: {}", userName, expectedCurrentBalance);
            } else {
                log.warn("Bot {}: Deposit failed", userName);
            }
        });
    }

    protected long checkBalance() {
        if (Math.abs(lastFetchedBalance - expectedCurrentBalance.get()) > 1_000_000L) {
            lastFetchedBalance = authClient.getBalance(apiGateway, getClient().getAuthToken());
            expectedCurrentBalance.set(lastFetchedBalance);
        }

        return expectedCurrentBalance.get();
    }

    protected long getMinBalance() {
        return 5_000_000L;
    }

    protected abstract long resolveBetAmount();

    protected abstract Supplier<Boolean> resolveBetCondition();

    protected abstract Scenario botBehaviorScenario();

    protected void creditBalance(long amount) {
        this.expectedCurrentBalance.addAndGet(-amount);
    }

    public final void start() {
        authenticate();
        connectToSocket();
        onStart();
    }

    protected abstract void onStart();

}
