package com.vingame.bot.brands.bom.bot;

import com.vingame.bot.environment.store.ConnectionData;
import com.vingame.bot.brands.bom.message.bettingmini.Request;
import com.vingame.bot.brands.bom.message.bettingmini.response.StartGameData;
import com.vingame.bot.core.BettingMiniGameBot;
import com.vingame.bot.util.OutputPrinter;
import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.message.response.ActionResponseMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
public class BauCuaMiniBot extends BettingMiniGameBot {

    private static final int MAX_BETS_PER_SESSION = 25;
    private static final long MIN_BET = 5_000L;
    private static final long MAX_BET = 100_000L;
    private static final long BET_STEP = 5_000L;
    private static final int CMD_PREFIX = 6000;

    private static boolean outputLoggingScenarioSet = false;

    private int numberOfBetsInCurrentSession = 0;
    private final Random random = new Random();

    private static final List<Integer> cmd = Arrays.asList(7000, 7005, 7002, 7007, 7006).stream()
            .map(c -> c + CMD_PREFIX)
            .collect(Collectors.toList());

    public BauCuaMiniBot(ConnectionData data, String userName, String password) {
        super(data, userName, password, new Request("gourdCrabMiniPlugin", "MiniGame3", CMD_PREFIX), CMD_PREFIX);
        log.info("Creating the bot: login: {} | max bet: {} | max bets per session: {}", userName, MAX_BET, MAX_BETS_PER_SESSION);
        addOutputLoggingScenario(this.getClient());
        getClient().addScenario(OutputPrinter.debugOutputPrinter(cmd, userName));
    }

    private static void addOutputLoggingScenario(VingameWebsocketClient client) {
        if (outputLoggingScenarioSet) {
            return;
        }

        client.addScenario(OutputPrinter.defaultOutputPrinter(cmd));
        outputLoggingScenarioSet = true;
    }

    @Override
    protected void onStartGame(ActionResponseMessage<StartGameData> data) {
        super.onStartGame(data);
        numberOfBetsInCurrentSession = 0;
    }

    @Override
    protected boolean shouldBet() {
        if (numberOfBetsInCurrentSession < MAX_BETS_PER_SESSION) {
            if (random.nextInt(100) < 60) {
                return false;
            }

            numberOfBetsInCurrentSession++;
            return true;
        }

        return false;
    }

    @Override
    protected long resolveBetAmount() {
        int maxSteps = Math.toIntExact((MAX_BET - MIN_BET) / BET_STEP);
        long steps = random.nextInt(maxSteps + 1);

        return MIN_BET + (steps * BET_STEP);
    }

    @Override
    protected int resolveNextEntryToBet() {
        return random.nextInt(6);
    }
}
