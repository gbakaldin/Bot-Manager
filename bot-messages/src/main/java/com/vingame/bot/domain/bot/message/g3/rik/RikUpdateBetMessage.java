package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * RIK (P_114) UpdateBet (CMD {@code 3002 + offset}; {@code 13002} at offset 10000).
 * <p>
 * Captured shape is
 * {@code {"bs":[{"eid":1,"bc":0,"b":0,"v":0},{"eid":0,"bc":1,"b":3000,"v":3000}],"cmd":13002}}
 * — a bet-state delta and nothing else. It is the Win79 shape exactly.
 *
 * <h2>There is no {@code gS}, so {@link #getGameState()} returns {@code 0}</h2>
 *
 * That is safe rather than broken: {@code BettingMiniGameBot.onUpdate} guards with
 * {@code if (gameStateId > 0)}, so a zero leaves the phase untouched. The bot enters
 * {@code BET} on StartGame and leaves it on EndGame, and RIK simply never re-asserts
 * the phase mid-round the way Nohu's UpdateBet does. <b>Do not "fix" this by
 * inventing a default</b> — returning anything non-zero here would actively drive
 * the state machine off a frame that carries no state.
 * <p>
 * Implementing {@link HasCrowdBets} here is what feeds
 * {@code BetCoordinator.observeCrowd} the <em>live</em> within-window crowd signal
 * rather than only the end-of-round snapshot. It is inert unless the group runs with
 * coordination and crowd-awareness on.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RikUpdateBetMessage extends UpdateBetMessage implements HasCrowdBets {

    private List<RikBetInfo> bs;

    @JsonCreator
    public RikUpdateBetMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("bs") List<RikBetInfo> bs) {
        super(cmd);
        this.bs = bs;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always {@code 0} — RIK's UpdateBet carries no {@code gS}. See the class
     * javadoc; the caller's {@code > 0} guard makes this a no-op, not a reset.
     */
    @Override
    public int getGameState() {
        return 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Entries arrive in server order ({@code eid} 1 before {@code eid} 0 in the
     * capture) — map by {@code eid}, never by index.
     */
    @Override
    public List<CrowdOption> crowdBets() {
        if (bs == null) {
            return List.of();
        }
        return bs.stream()
                .map(e -> new CrowdOption(e.eid(), e.v(), e.b(), e.bc()))
                .toList();
    }
}
