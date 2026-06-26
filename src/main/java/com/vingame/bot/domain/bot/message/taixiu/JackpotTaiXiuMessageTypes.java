package com.vingame.bot.domain.bot.message.taixiu;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;

/**
 * {@link TaiXiuMessageTypes} implementation for the P_114 / RIK
 * {@code taixiuJackpotPlugin} product — the Tai Xiu <b>jackpot</b> variant
 * (TAI_XIU_114_JACKPOT plan AD-5).
 * <p>
 * Behaviorally identical to the captured {@code MiniGame}/{@code taixiuPlugin}
 * product, with two structural deltas captured from the 114 frames
 * ({@code TaiXiuMessages/}):
 * <ul>
 *   <li><b>CMD offset +100 (AD-1).</b> {@link #cmdOffset()} returns {@code 100}, so
 *       the four effective CMDs become subscribe {@code 1105}, startGame {@code 1102},
 *       endGame {@code 1104}, bet {@code 1100} (the base 1005/1002/1004/1000 +100).
 *       {@link #getTypeRegistrations()} (inherited) therefore registers the inbound
 *       classes against {@code "1105"/"1102"/"1104"}.</li>
 *   <li><b>Outbound bet carries {@code a:false} (AD-2).</b> See
 *       {@link #emitsAutoBetFlag()} — the 114 bet body emits the extra auto-bet flag
 *       {@code a}; 116 emits no {@code a}. The flag is honored by
 *       {@code TaiXiuRequest}/{@code TaiXiuBet}, scoped to 114 so the shared
 *       betting-mini bet body is untouched.</li>
 * </ul>
 * <p>
 * <b>Inbound classes are REUSED (AD-3).</b> The bot-relevant fields are all present in
 * the 114 frames ({@code tFB}/{@code sid} on subscribe response, {@code sid} on
 * StartGame, {@code gB}/{@code gR}/{@code G}/{@code jpV}/{@code iJp} on EndGame) and the
 * existing {@code taixiu/*} classes tolerate the 114-only extras ({@code gid},
 * {@code sd}, live {@code tJpV}, {@code htr} items) — so this provider returns the
 * <b>same</b> concrete classes as {@link MiniGameTaiXiuMessageTypes}. No 114-specific
 * inbound classes are created (OI-2).
 * <p>
 * Resolved via {@code GameMessageTypesResolver.resolveTaiXiu(ProductCode.P_114)} (AD-6).
 */
public class JackpotTaiXiuMessageTypes implements TaiXiuMessageTypes {

    /**
     * {@inheritDoc}
     * <p>
     * The 114 jackpot product's CMDs are the base 116 CMDs +100 on all four frames
     * (AD-1), confirmed from the captured Subscribe/Start/Bet/End frames.
     */
    @Override
    public int cmdOffset() {
        return 100;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The 114 outbound bet carries an extra {@code a:false} field (AD-2) that the
     * shared betting-mini bet body does not emit. Returning {@code true} here makes
     * {@code TaiXiuGameBot.buildRequest()} pass the flag into {@code TaiXiuRequest},
     * which then builds the Tai-Xiu-specific bet body with {@code a}. 116 keeps the
     * default {@code false} and emits no {@code a}, so the shared body stays
     * byte-for-byte unchanged.
     */
    @Override
    public boolean emitsAutoBetFlag() {
        return true;
    }

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
     * No md5 StartGame variant was captured for 114 (same as 116, AD-8) — {@code null}.
     */
    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * No updateBet frame was captured for 114 (same as 116, OI-5) — {@code null}.
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
