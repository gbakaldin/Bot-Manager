package com.vingame.bot.domain.bot.message.g2.bom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class BomStartGameMessage extends StartGameMessage {

    private long sid;

    @JsonCreator
    public BomStartGameMessage(
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
