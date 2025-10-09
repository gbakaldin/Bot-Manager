package com.vingame.bot;

import com.vingame.bot.environment.store.ConnectionData;
import com.vingame.bot.environment.store.ConnectionDataStore;
import com.vingame.bot.brands.bom.bot.BauCuaBot;
import com.vingame.bot.brands.bom.bot.BauCuaMiniBot;
import com.vingame.bot.core.Bot;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
* TODO:
*  1. Alerting when the bots run out of money in prod
*  2. Bot configuration
* */
@Slf4j
public class BotManager {

    private final List<Bot> bots;

    public BotManager() {
        this.bots = new ArrayList<>();
    }

    public void setupBots() {
        int numBots = 4;
        int numBcMiniBots = 5;
        int numBcMiniBots2 = 40;

        String bauCuaPrefix = "bcB0Ttest";
        String bauCuaMiniPrefix = "bcminiB0Ttest";

        log.info("Starting bots with the following configuration:");
        log.info("Number of BauCua bots: {}, prefix: {}", numBots, bauCuaPrefix);
        log.info("Number of BauCuaMini bots: {}, prefix: {}", numBcMiniBots, bauCuaMiniPrefix);

        ConnectionData data = ConnectionDataStore.get("097");

        for (int i = 1; i <= numBots; i++) {
            this.bots.add(new BauCuaBot(data, "bcB0Ttest" + i, "123bcB0Ttest123"));
        }

        for (int i = 1; i <= numBcMiniBots; i++) {
            this.bots.add(new BauCuaMiniBot(data, "bcminiB0Ttest" + i, "123bcB0Ttest123"));
        }

        for (int i = 1; i <= numBcMiniBots2; i++) {
            this.bots.add(new BauCuaMiniBot(data, "testslot" + i, "agcde@345"));
        }
    }

    public void runBots() {
        log.info("Starting the bots...");
        for (Bot bot : bots) {
            new Thread(() -> {
                try {
                    bot.start();
                    log.info("Bot {} started", bot.getUserName());
                } catch (Exception e) {
                    log.error("Bot {} failed to start: {}", bot.getUserName(), e.getMessage());
                }

                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

                scheduler.scheduleWithFixedDelay(() -> {
                    if (!bot.getClient().isOpen()) {
                        log.info("Bot {} lost connection, attempting restart", bot.getUserName());
                        bot.restart();
                    }
                }, 5_000L, 3_000L, TimeUnit.MILLISECONDS);
            }).start();
        }
    }

}
