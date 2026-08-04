package com.vingame.bot.domain.bot.message;

/**
 * Extended start game message for MD5-based games.
 * MD5 games send the predetermined result hash at round start,
 * which is later verified against the actual result.
 */
public abstract class StartGameMd5Message extends StartGameMessage {

    protected StartGameMd5Message(int cmd) {
        super(cmd);
    }

    public abstract String getMd5Hash();
}
