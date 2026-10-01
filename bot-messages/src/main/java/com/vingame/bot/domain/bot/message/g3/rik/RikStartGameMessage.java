package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import lombok.Getter;
import lombok.Setter;

/**
 * RIK (P_114) StartGame (CMD {@code 3005 + offset}; {@code 13005} for
 * {@code stockPlugin} at offset 10000).
 * <p>
 * Captured shape is {@code {"cmd":13005,"sid":3793248,"md5":"-"}}. The {@code md5}
 * field is tolerated and ignored here; it is modelled by
 * {@link RikStartGameMd5Message}, which is what the registration uses when the
 * game's {@code Game.md5} is true.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RikStartGameMessage extends StartGameMessage {

    private long sid;

    @JsonCreator
    public RikStartGameMessage(
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
