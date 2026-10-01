package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;

/**
 * {@code ziczacPlugin} (RIK / P_114 Plinko) UpdateBet — CMD {@code 3002 + offset},
 * i.e. {@code 12002} at offset 9000.
 * <p>
 * Captured shape is
 * {@code {"bs":[{"eid":0,"b":60000},{"eid":1,"b":60000},…],"cmd":12002}} — one entry
 * per ball, and nothing else.
 *
 * <h2>This class exists to REMOVE a marker, not to add one</h2>
 *
 * {@link RikUpdateBetMessage} implements {@link HasCrowdBets} and maps its {@code bs}
 * to {@code CrowdOption(eid, v, b, bc)}. That is right for the other two 114 games
 * and <b>wrong for this one</b>: ziczac's {@code bs} is <b>our own per-ball state</b>,
 * not a crowd. It carries no {@code v} and no {@code bc} at all — in round
 * {@code 1995084} it simply grows by one entry per bet we send, each carrying our own
 * {@code 60000} — so the generic class would publish an all-zero-value crowd
 * distribution to {@code BetCoordinator.observeCrowd} for a game that <b>has no
 * options to distribute over</b>. An empty crowd is inert; a fabricated one is not.
 * <p>
 * The room feed does exist on this game — {@code 12007} ({@code tpBs}/{@code bs} of
 * {@code {v, dn}}, {@code tbc}) and {@code 12019} ({@code tbps[]{r,v,dn,odd}}) — and
 * both are outside the four-CODE contract, so there is no slot to deliver them
 * through. Consequence, stated plainly: <b>crowd-aware coordination and
 * {@code optionAffinities} are inert on ziczac</b>, not broken.
 *
 * <h2>{@code bs} is not modelled either</h2>
 *
 * Nothing reads it — the class implements no marker — so modelling it would be
 * documentation with a deserialization liability attached. The posture is the same
 * one {@link RikEndGameMessage} takes for {@code ps} / {@code bPl}: the shape is
 * preserved verbatim in the committed capture and in
 * {@code /messages/rik/ziczac-updateBet.json}.
 *
 * <h2>There is no {@code gS}, so {@link #getGameState()} returns {@code 0}</h2>
 *
 * Same as {@link RikUpdateBetMessage}, and for the same reason:
 * {@code BettingMiniGameBot.onUpdate} guards with {@code if (gameStateId > 0)}, so a
 * zero leaves the phase untouched. The bot enters {@code BET} on StartGame and leaves
 * it on EndGame. <b>Do not "fix" this by inventing a default</b> — a non-zero here
 * would drive the state machine off a frame that carries no state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RikZicZacUpdateBetMessage extends UpdateBetMessage {

    @JsonCreator
    public RikZicZacUpdateBetMessage(@JsonProperty("cmd") int cmd) {
        super(cmd);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always {@code 0} — ziczac's UpdateBet carries no {@code gS}. See the class
     * javadoc; the caller's {@code > 0} guard makes this a no-op, not a reset.
     */
    @Override
    public int getGameState() {
        return 0;
    }
}
