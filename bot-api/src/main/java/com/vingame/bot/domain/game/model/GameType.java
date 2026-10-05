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
    CASHOUT("Cash-out"),
    /**
     * Shared-round crash games — 119 Avatar ({@code aviatorPlugin}, two runners, Jake and
     * Neytiri). Every player bets in one betting window, then a multiplier climbs until the
     * runner crashes; the bot cashes out at a target drawn at placement or loses. One type
     * for every crash brand, not {@code AVIATOR}; the runner count is protocol metadata on
     * the brand's message layer. Separate from {@link #CASHOUT}, which has no shared rounds
     * ({@code docs/plans/AVIATOR_BOT.md} AD-3).
     */
    CRASH("Crash");

    private final String displayName;

    GameType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
