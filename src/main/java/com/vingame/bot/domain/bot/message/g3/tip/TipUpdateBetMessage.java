package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TipUpdateBetMessage extends UpdateBetMessage {

    private List<TipSubscribeMessage.BetInfoWithTotal> bs;
    private int gid;

    @JsonCreator
    public TipUpdateBetMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("bs") List<TipSubscribeMessage.BetInfoWithTotal> bs,
            @JsonProperty("gid") int gid) {
        super(cmd);
        this.bs = bs;
        this.gid = gid;
    }

    // Tip's UpdateBet JSON sample does not carry a `gS` field. BettingMiniGameBot
    // only acts on getGameState() > 0, so returning 0 means UpdateBet messages on
    // Tip never advance game state — verify against staging traffic and revisit
    // if Tip actually emits a phase-transition field under a different key.
    @Override
    public int getGameState() {
        return 0;
    }
}
