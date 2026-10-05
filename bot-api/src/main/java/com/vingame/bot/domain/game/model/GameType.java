package com.vingame.bot.domain.game.model;

public enum GameType {

    BETTING_MINI("Betting mini"),
    SLOT("Slot"),
    TAI_XIU("Tài Xỉu"),
    CARD_GAME("Card game"),
    UP_DOWN("Up / Down"),
    /**
     * Per-player cash-out games — 119 Balloon ({@code balloonPlugin}) and Soccer
     * ({@code soccerPlugin}). Bet, watch the multiplier climb, cash out or burst; no
     * shared round. Not a crash/Aviator type: those have shared rounds
     * ({@code docs/plans/CASHOUT_BOT.md} AD-1).
     */
    CASHOUT("Cash-out");

    private final String displayName;

    GameType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
