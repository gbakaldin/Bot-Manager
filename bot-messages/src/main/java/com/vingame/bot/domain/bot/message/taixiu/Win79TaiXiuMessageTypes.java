package com.vingame.bot.domain.bot.message.taixiu;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * {@link TaiXiuMessageTypes} implementation for P_119 / WIN79.
 * <p>
 * Registering this provider is what makes a {@link GameType#TAI_XIU} bot group
 * creatable for 119 at all: {@code BotFactory}'s {@code case TAI_XIU} calls
 * {@code messageTypesRegistry.taiXiu(productKey(env))}, which threw
 * {@code "TaiXiuMessageTypes not yet implemented for product code: 119"} on the
 * bot thread — before authentication, before any socket — for every bot in the
 * group.
 * <p>
 * <b>WIN79 does have Tai Xiu</b> (confirmed 2026-09-10); it simply was not visible
 * in the staging lobby at the time the first probe group was scoped, which is why
 * the betting-mini {@code gourdCrabPlugin} game was brought up first.
 * <p>
 * <b>Inbound classes are reused verbatim</b>, exactly as
 * {@link JackpotTaiXiuMessageTypes} does for 114 (its AD-3). Tai Xiu's wire shape
 * is near-identical across brands; everything product-specific is expressed
 * through the two knobs below rather than through new message classes.
 *
 * <h2>The two knobs, and why they are set this way</h2>
 *
 * Both currently carry the interface defaults, i.e. the <b>P_116 shape</b>:
 * <ul>
 *   <li>{@link #cmdOffset()} = {@code 0} → subscribe {@code 1005}, startGame
 *       {@code 1002}, endGame {@code 1004}, bet {@code 1000}.</li>
 *   <li>{@link #emitsAutoBetFlag()} = {@code false} → the outbound bet body emits
 *       no {@code a} field.</li>
 * </ul>
 *
 * <b>Still not verified against a captured 119 frame</b>, but no longer a guess
 * (2026-09-11). The discriminator is the <b>plugin name, not the brand</b>: the CMD
 * block is a property of the game-server plugin the product deploys, and 119 runs
 * plain {@code taixiuPlugin} (the same plugin as 116), not 114's
 * {@code taixiuJackpotPlugin}. Two independent sources agree on that plugin's CMDs:
 * <ul>
 *   <li>the 2026-06-24 {@code MiniGame}/{@code taixiuPlugin} capture TAI_XIU_BOT was
 *       built from (1005/1002/1004/1000), and</li>
 *   <li>the game server itself — {@code TaiXiuPlugin}'s {@code TXCommand} declares
 *       {@code SUBSCRIBLE = 1005}, {@code START_BETTING = 1002},
 *       {@code END_GAME = 1004}, {@code ADD_BETTING = 1000}.</li>
 * </ul>
 * So {@code cmdOffset() == 0} is the base shape of this plugin and 114's {@code +100}
 * belongs to its jackpot variant. Correspondingly {@code emitsAutoBetFlag() == false}
 * is safe rather than merely untested: {@code a} is {@code TXF.ALLIN}, and the server's
 * {@code AddBetting} processor reads it as {@code data.getBoolean(TXF.ALLIN, false)} —
 * i.e. <b>defaulted, not required</b>, so omitting the field is equivalent to sending
 * {@code a:false}. The residual risk is therefore not the wire shape; it is only
 * whether 119 turns out to deploy the jackpot plugin after all, which the game record's
 * {@code pluginName} makes visible up front.
 *
 * <p><b>Do not "harden" this by registering both CMD blocks.</b> It would not work —
 * the bet CMD is outbound and can only carry one value — and it would hide which
 * variant 119 actually runs behind a provider that silently accepts either.
 *
 * <h2>How to correct it</h2>
 *
 * Both faults are loud, not silent, and both are a one-line edit <em>in this
 * class</em> — no other product's provider is touched:
 * <ul>
 *   <li><b>Wrong {@code cmdOffset}</b>: the bot subscribes and then receives
 *       nothing at all, because the inbound CMDs it registered do not match what
 *       arrives. If the observed inbound frames carry {@code 1102}/{@code 1104}
 *       rather than {@code 1002}/{@code 1004}, override {@code cmdOffset()} to
 *       return {@code 100}.</li>
 *   <li><b>Wrong {@code emitsAutoBetFlag}</b>: rounds are received normally but
 *       bets are rejected. Override {@code emitsAutoBetFlag()} to return
 *       {@code true} so the bet body carries {@code a:false}.</li>
 * </ul>
 */
@Component
@MessageTypesImpl(gameType = GameType.TAI_XIU, products = "119")
public class Win79TaiXiuMessageTypes implements TaiXiuMessageTypes {

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return TaiXiuSubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return TaiXiuStartGameMessage.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * No md5 StartGame variant is known for 119, same as 116 and 114 — {@code null}.
     * {@code getTypeRegistrations()} never registers an md5 entry, so this is not
     * dereferenced.
     */
    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * No updateBet frame is known for 119, same as 116 and 114 — {@code null}.
     */
    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return null;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return TaiXiuEndGameMessage.class;
    }
}
