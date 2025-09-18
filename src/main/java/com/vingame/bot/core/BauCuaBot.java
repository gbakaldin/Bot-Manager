package com.vingame.bot.core;

import com.vingame.bot.brands.bom.message.bettingmini.Request;
import com.vingame.bot.brands.bom.message.bettingmini.response.StartGameData;
import com.vingame.bot.util.OutputPrinter;
import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.message.response.ActionResponseMessage;
import com.vingame.webocketparser.scenario.Scenario;

import java.util.List;
import java.util.Random;

public class BauCuaBot extends BettingMiniGameBot {

    private static final int MAX_BETS_PER_SESSION = 25;
    private static final long MIN_BET = 5_000L;
    private static final long MAX_BET = 100_000L;
    private static final long BET_STEP = 5_000L;
    private static final int CMD_PREFIX = 2000;

    private static boolean outputLoggingScenarioSet = false;

    private int numberOfBetsInCurrentSession = 0;
    private final Random random = new Random();

    public BauCuaBot(VingameWebsocketClient client, String botName) {
        super(client, new Request("gourdCrabPlugin", "MiniGame3", CMD_PREFIX), botName, CMD_PREFIX);
        addOutputLoggingScenario(client);
    }

    private static void addOutputLoggingScenario(VingameWebsocketClient client) {
        if (outputLoggingScenarioSet) {
            return;
        }

        List<Integer> cmd = List.of(
                CMD_PREFIX + 3000,
                CMD_PREFIX + 3005,
                CMD_PREFIX + 3002,
                //CMD_PREFIX + 3007,
                //CMD_PREFIX + 3008,
                //CMD_PREFIX + 3015,
                //CMD_PREFIX + 3009,
                //CMD_PREFIX + 3004,
                CMD_PREFIX + 3006);

        Scenario defaultOutput = OutputPrinter.defaultOutputPrinter(cmd);

        client.addScenario(defaultOutput);

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
