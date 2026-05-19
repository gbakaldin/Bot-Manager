package com.vingame.bot.domain.bot.message.g2.b52;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class B52UpdateBetMessage extends UpdateBetMessage {

    private int gS;
    private long rmT;

    @JsonCreator
    public B52UpdateBetMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("gS") int gS,
            @JsonProperty("rmT") long rmT) {
        super(cmd);
        this.gS = gS;
        this.rmT = rmT;
    }

    @Override
    public int getGameState() {
        return gS;
    }

    public long getRemainingTime() {
        return rmT;
    }
}
