package com.vingame.bot.domain.game.model;

public enum GameType {

    BETTING_MINI("Betting mini"),
    SLOT("Slot"),
    TAI_XIU("Tài Xỉu"),
    CARD_GAME("Card game"),
    UP_DOWN("Up / Down");

    private final String displayName;

    GameType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
