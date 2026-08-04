package com.vingame.bot.domain.botgroup.model;

public enum BotGroupPlayingStatus {

    PLAYING, //The bot group is currently playing the game
    IDLE, //The bot group is active but not participating in the game at the moment
    PENDING //The bot group is trying to play the game, but the game server is unresponsive
}
