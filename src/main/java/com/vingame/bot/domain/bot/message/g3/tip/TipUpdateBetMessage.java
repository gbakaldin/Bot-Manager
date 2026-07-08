package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TipUpdateBetMessage extends UpdateBetMessage implements HasCrowdBets {

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

    /**
     * Per-option crowd distribution from the UpdateBet {@code bs} array (AD-C1) —
     * the live, intra-round crowd signal that grows through the bet window; Tip is
     * the only product carrying {@code bs} on UpdateBet (Findings / AD-C3). The
     * {@code BetInfoWithTotal} entry carries this bot's own {@code b} (D3, unused by
     * v1). Empty when no {@code bs}.
     */
    @Override
    public List<CrowdOption> crowdBets() {
        if (bs == null) {
            return List.of();
        }
        return bs.stream()
                .map(e -> new CrowdOption(e.getEid(), e.getV(), e.getB(), e.getBc()))
                .toList();
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
