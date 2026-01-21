package com.vingame.bot.domain.bot.util;

public enum BettingMiniGameState implements GameState {

    BET, PAYOUT;

    public static BettingMiniGameState from(int state) {
        if (state == 2) {
            return BET;
        }

        return PAYOUT;
    }
}
