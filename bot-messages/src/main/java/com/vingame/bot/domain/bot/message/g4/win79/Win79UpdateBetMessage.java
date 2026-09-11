package com.vingame.bot.domain.bot.message.g4.win79;

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
 * WIN79 UpdateBet (CMD {@code 3002 + offset}; {@code 5002} at offset 2000).
 * <p>
 * Captured shape is {@code {"bs":[{"eid":5,"bc":1,"b":1000,"v":1000}],"cmd":5002}} —
 * a bet-state delta and nothing else.
 *
 * <h2>Two things that look wrong here and are not</h2>
 *
 * <b>1. There is no {@code gS}, so {@link #getGameState()} returns {@code 0}.</b>
 * That is safe rather than broken: {@code BettingMiniGameBot.onUpdate} guards with
 * {@code if (gameStateId > 0)}, so a zero leaves the phase untouched. The bot enters
 * {@code BET} on StartGame and leaves it on EndGame, and WIN79 simply never
 * re-asserts the phase mid-round the way Nohu's UpdateBet does. Do not "fix" this by
 * inventing a default — returning anything non-zero here would actively drive the
 * state machine from a frame that carries no state.
 * <p>
 * <b>2. This is only the second product with intra-round {@code bs}.</b>
 * {@code BettingMiniGameBot.onUpdate}'s comment calls Tip "the only product with
 * intra-round {@code bs}" — that is now stale. Implementing {@link HasCrowdBets}
 * here is what feeds {@code BetCoordinator.observeCrowd} the <em>live</em>
 * within-window crowd signal rather than only the end-of-round snapshot, which is
 * the whole point of CROWD_AWARE_COORDINATION AD-C3. It is inert unless the group
 * runs with coordination and crowd-awareness on.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79UpdateBetMessage extends UpdateBetMessage implements HasCrowdBets {

    private List<Win79BetInfo> bs;

    @JsonCreator
    public Win79UpdateBetMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("bs") List<Win79BetInfo> bs) {
        super(cmd);
        this.bs = bs;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always {@code 0} — WIN79's UpdateBet carries no {@code gS}. See the class
     * javadoc; the caller's {@code > 0} guard makes this a no-op, not a reset.
     */
    @Override
    public int getGameState() {
        return 0;
    }

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
