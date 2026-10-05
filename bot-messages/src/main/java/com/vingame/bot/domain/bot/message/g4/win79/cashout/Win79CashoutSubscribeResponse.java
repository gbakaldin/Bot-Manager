package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.cashout.CashoutSubscribeResponse;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 119 cash-out subscribe reply ({@code X500}), from the real-client capture
 * (2026-10-05, {@code balloonPlugin}):
 * <pre>{@code {"aSt":false,"cmd":1500,"jackpots":[…],"bets":[1000,10000,100000,500000,1000000,5000000,10000000],"sid":0}}</pre>
 * Only {@code bets} is read ({@code docs/plans/CASHOUT_BOT.md} AD-6); {@code sid} is
 * kept for diagnostics and is {@code 0} on the subscribe reply. {@code jackpots} and
 * {@code aSt} are ignored, not modelled.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CashoutSubscribeResponse extends CashoutSubscribeResponse {

    private final List<Long> bets;
    private final long sid;

    @JsonCreator
    public Win79CashoutSubscribeResponse(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("bets") List<Long> bets,
            @JsonProperty("sid") long sid) {
        super(cmd);
        this.bets = bets;
        this.sid = sid;
    }

    /**
     * @return {@code bets} without {@code null} elements, sorted ascending; empty when
     *         absent. Nulls are dropped <em>before</em> sorting: sorting first throws an NPE
     *         inside {@code onSubscribe}, after the bot is already marked authenticated, and
     *         the bot then sits connected and never bets.
     */
    @Override
    public List<Long> allowedBets() {
        if (bets == null) {
            return List.of();
        }
        List<Long> sorted = new ArrayList<>(bets.size());
        for (Long bet : bets) {
            if (bet != null) {
                sorted.add(bet);
            }
        }
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }
}
