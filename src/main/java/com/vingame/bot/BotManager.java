package com.vingame.bot;

import com.vingame.bot.brands.bom.BomConnection;
import com.vingame.bot.core.BauCuaBot;
import com.vingame.bot.core.Bot;
import com.vingame.webocketparser.VingameWebsocketClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/*
* TODO:
*  1. Add more bots
*  2. Debug level logging
*  3. Alerting when the bots run out of money in prod
*  4. Bot configuration
* */
@Slf4j
public class BotManager {

    private final List<Bot> bots;

    public BotManager() {
        this.bots = new ArrayList<>();
    }

    public void setupBots() {
        int numBots = 4;

        for (int i = 1; i <= numBots; i++) {
            Bot bot = new BauCuaBot(connectBot("bcB0Ttest" + i, "123bcB0Ttest123"), "test" + i);
            bot.setApiGateway("https://apigw-bomwin.sgame.us");
            this.bots.add(bot);
        }
    }

    public void runBots() {
        for (Bot bot : bots) {
            new Thread(() -> {
                bot.authenticate();
                bot.start();

                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                while (true) {
                    if (!bot.getClient().isOpen()) {
                        try {
                            Thread.sleep(3000L);
                            if (!bot.getClient().isOpen()) {
                                bot.restart();
                            }
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                }
            }).start();
        }
    }

    public VingameWebsocketClient connectBot(String username, String password) {

        return BomConnection.createConnection(username, password);
    }


}
