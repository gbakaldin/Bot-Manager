package com.vingame.bot;

public class Main {

    public static void main(String[] args) {
        BotManager manager = new BotManager();
        runBots(manager);
    }

    private static void runBots(BotManager manager) {
        manager.setupBots();
        manager.runBots();
    }
}
