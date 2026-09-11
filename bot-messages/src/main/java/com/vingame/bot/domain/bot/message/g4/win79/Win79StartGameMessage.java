package com.vingame.bot.domain.bot.message.g4.win79;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import lombok.Getter;
import lombok.Setter;

/**
 * WIN79 StartGame (CMD {@code 3005 + offset}; {@code 5005} for
 * {@code gourdCrabPlugin} at offset 2000).
 * <p>
 * Captured shape is minimal — {@code {"cmd":5005,"sid":9648}} — matching
 * {@code NohuStartGameMessage} exactly. There is <b>no md5 variant</b> for this
 * product (confirmed 2026-09-10), so {@code Win79GameMessageTypes.startGameMd5Type()}
 * returns {@code null} and the game's {@code Game.md5} must stay {@code false}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79StartGameMessage extends StartGameMessage {

    private long sid;

    @JsonCreator
    public Win79StartGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid) {
        super(cmd);
        this.sid = sid;
    }

    @Override
    public long getSessionId() {
        return sid;
    }
}
