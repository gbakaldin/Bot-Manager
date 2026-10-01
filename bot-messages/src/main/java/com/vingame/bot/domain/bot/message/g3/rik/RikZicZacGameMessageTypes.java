package com.vingame.bot.domain.bot.message.g3.rik;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.GameRequestFactory;
import com.vingame.bot.domain.bot.message.request.ZicZacRequest;
import com.vingame.bot.domain.game.model.Game;

/**
 * Message types for RIK / P_114's <b>{@code ziczacPlugin}</b> — Plinko (a 16-row
 * Galton board) at <b>offset 9000</b>, subscribe CMD {@code 12000}.
 *
 * <h2>Not a bean, and that is the whole design</h2>
 *
 * This class carries <b>no {@code @Component} and no {@link MessageTypesImpl}</b>. It
 * could not have them even if that were wanted: {@code MessageTypesRegistry} fails
 * context refresh on a duplicate {@code (gameType, product)} key, and
 * {@code (BETTING_MINI, "114")} is already claimed by {@link RikGameMessageTypes}.
 * Instead it is reached as a <b>resolution result</b> —
 * {@link RikGameMessageTypes#forGame} hands back the single stateless instance it
 * holds when the {@code Game}'s plugin name is this game. So the registry's
 * inventory, the boot line ({@code BETTING_MINI 6 products [097, 098, 114, 116, 118,
 * 119]}) and the coverage tests are all unchanged by this game existing.
 *
 * <h2>What is specialised, and what is reused verbatim</h2>
 *
 * Only the two frames whose semantics actually differ:
 * <ul>
 *   <li><b>EndGame</b> → {@link RikZicZacEndGameMessage}: own win is
 *       {@code sum(mbs[].r)}, a field the generic 114 class does not have, and the
 *       jackpot pool meter is real and rising.</li>
 *   <li><b>UpdateBet</b> → {@link RikZicZacUpdateBetMessage}: exists to <i>drop</i>
 *       the {@code HasCrowdBets} marker, because ziczac's {@code bs} is our own
 *       per-ball state and not a crowd.</li>
 * </ul>
 * Subscribe, StartGame and StartGameMd5 return the existing {@code Rik*} classes
 * unchanged: the subscribe response is the same field set plus extras this codebase
 * ignores ({@code odds}, {@code jps}, {@code tFJp}, {@code tJpv2}, {@code tFJp2},
 * {@code htr}, {@code cH}), and {@code tFB}/{@code tFD} already resolve correctly
 * (17 000 / 2 000); {@code 12005} is byte-shaped like the other games' StartGame with
 * {@code md5} the literal {@code "-"}, so a ziczac {@code Game} runs with
 * {@code md5 = false}. Duplicating the three would be maintenance liability with no
 * behaviour change.
 * <p>
 * <b>No CMD is hardcoded.</b> {@code getTypeRegistrations(offset, md5)} derives all
 * four from {@code Game.offset = 9000}.
 *
 * <h2>Game facts an operator needs</h2>
 *
 * <ul>
 *   <li>Rounds are 30 s: {@code tFB 17000} bet window minus {@code tFD 2000}, then
 *       {@code tFP 13000} payout — 17.01 s startGame→endGame in 7/7 and 13.00 s
 *       endGame→startGame in 6/6.</li>
 *   <li><b>{@code mB = 50 000 000} is the per-ball maximum</b> — a tenth of
 *       {@code stockPlugin}'s. A strategy configured above it has bets rejected.</li>
 *   <li>The game has <b>no options</b>. {@code Game.optionAffinities} must still be
 *       non-empty, because {@code Game.getEffectiveOptionAffinities()} throws on an
 *       empty map; use the degenerate {@code {"0": 1}}, with which every picker is
 *       degenerate and the discarded option id costs nothing.</li>
 *   <li><b>Plinko variance is large.</b> Per-ball standard deviation of the return
 *       multiple is ≈1.2 against a mean of 0.956, so after 100 balls the observed
 *       ratio still has a standard error of ≈0.12. Do not read RTP off ten rounds;
 *       the falsifiable short-window checks are "winnings &gt; 0" and "stake matches
 *       the sends".</li>
 * </ul>
 *
 * <h2>The outbound bet body IS supplied here (Phase 2)</h2>
 *
 * The real client bets {@code {"cmd":12002,"b":60000,"c":1,"sid":…,"aid":1}} — a
 * per-ball stake plus a ball count, and <b>no {@code eid} at all</b> — while the
 * shared {@code Bet} emits {@code b} plus an {@code eid} and no {@code c}. Through the
 * shared body our bets were accepted in-round, echoed in the {@code 12002} UpdateBet,
 * and then <b>discarded at settlement</b>: {@code confirmed staked: 0 | total win: 0}
 * on 38/38 EndGames after the server-side restart (Amendment A1 §4). So this class
 * implements {@link GameRequestFactory} and hands every ziczac bot a
 * {@link ZicZacRequest}, whose {@code bet()} builds a
 * {@link com.vingame.bot.domain.bot.message.request.ZicZacBet} and whose
 * {@code commit()} is the inherited empty default — ziczac has no {@code 3022}.
 * <p>
 * <b>No allowlist here, and that is not an omission.</b> {@code RikGameMessageTypes}'
 * {@code requestFor} allowlists {@code stockPlugin} because that provider instance is
 * shared by every 114 game {@code forGame} does not route away (AD-11 there). This
 * provider is different: it is reachable <i>only</i> as the result of
 * {@link RikGameMessageTypes#forGame} matching {@code ziczacPlugin}, so by the time
 * {@link #requestFor} runs the game dimension has already been applied and there is
 * exactly one game it can be asked about. A second name would have to be added to
 * {@code forGame} first, and that edit is where the decision would be made.
 */
public class RikZicZacGameMessageTypes implements GameMessageTypes, GameRequestFactory {

    /**
     * {@inheritDoc}
     * <p>
     * Always a {@link ZicZacRequest} (Amendment A1 item 3). The plugin name is taken
     * from the {@link Game} rather than from a constant so the outbound envelope's
     * plugin slot carries exactly the spelling the operator configured — the same
     * string {@code forGame} matched case-insensitively and the same one the shared
     * {@code Request} would have used. {@code game} is never {@code null} at the sole
     * production call site ({@code BettingMiniGameBot.buildRequest}, see
     * {@link GameRequestFactory#requestFor}), and this provider cannot be resolved
     * for a {@code null} game at all: {@code forGame} returns the generic provider
     * for one.
     */
    @Override
    public GameRequest requestFor(Game game, String zoneName, int offset) {
        return new ZicZacRequest(game.getPluginName(), zoneName, offset);
    }

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return RikSubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return RikStartGameMessage.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reused from the generic 114 provider. {@code 12005}'s {@code md5} is the
     * literal {@code "-"} in 7/7 captured rounds, so a ziczac {@code Game} is
     * expected to run with {@code md5 = false} and this branch never to be selected —
     * but it is a real class rather than {@code null}, so a misconfigured game
     * degrades instead of NPE-ing at registration.
     */
    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return RikStartGameMd5Message.class;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return RikZicZacUpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return RikZicZacEndGameMessage.class;
    }
}
