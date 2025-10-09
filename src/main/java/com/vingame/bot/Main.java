package com.vingame.bot;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Main {

    public static void main(String[] args) {
        try {
            BotManager manager = new BotManager();
            runBots(manager);
        } catch (Throwable t) {
            log.error("Unhandled exception or fatal error", t);
        }
    }

    private static void runBots(BotManager manager) {
        manager.setupBots();
        manager.runBots();
    }
}
