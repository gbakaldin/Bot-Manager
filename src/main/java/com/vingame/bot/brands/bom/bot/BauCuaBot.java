package com.vingame.bot.brands.bom.bot;

import com.vingame.bot.environment.store.ConnectionData;
import com.vingame.bot.brands.bom.message.bettingmini.Request;
import com.vingame.bot.brands.bom.message.bettingmini.response.StartGameData;
import com.vingame.bot.core.BettingMiniGameBot;
import com.vingame.bot.util.OutputPrinter;
import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.message.response.ActionResponseMessage;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class BauCuaBot extends BettingMiniGameBot {

    private static final int MAX_BETS_PER_SESSION = 25;
    private static final long MIN_BET = 5_000L;
    private static final long MAX_BET = 100_000L;
    private static final long BET_STEP = 5_000L;
    private static final int CMD_PREFIX = 2000;

    private static boolean outputLoggingScenarioSet = false;

    public static final List<Integer> cmd = Arrays.asList(3000, 3005, 3002, 3007, 3006).stream()
            .map(c -> c + CMD_PREFIX)
            .collect(Collectors.toList());

    private int numberOfBetsInCurrentSession = 0;
    private final Random random = new Random();

    public BauCuaBot(ConnectionData data, String userName, String password) {
        super(data, userName, password, new Request("gourdCrabPlugin", "MiniGame3", CMD_PREFIX), CMD_PREFIX);
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
