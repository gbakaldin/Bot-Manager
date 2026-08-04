package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TipStartGameMessage extends StartGameMessage {

    private String cdt;
    private int gid;
    private TipSubscribeMessage.JackpotCountdown jpCD;
    private long sid;

    @JsonCreator
    public TipStartGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("cdt") String cdt,
            @JsonProperty("gid") int gid,
            @JsonProperty("jpCD") TipSubscribeMessage.JackpotCountdown jpCD,
            @JsonProperty("sid") long sid) {
        super(cmd);
        this.cdt = cdt;
        this.gid = gid;
        this.jpCD = jpCD;
        this.sid = sid;
    }

    @Override
    public long getSessionId() {
        return sid;
    }
}
