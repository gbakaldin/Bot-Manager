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
 * {@link TaiXiuMessageTypes} implementation for the captured
 * {@code MiniGame}/{@code taixiuPlugin} product (TAI_XIU_BOT plan AD-4). Supplies
 * that product's concrete Tai Xiu message classes for polymorphic deserialization;
 * the CMDs (1005/1002/1004) come from the interface at the default
 * {@code cmdOffset()==0} (AD-1).
 * <p>
 * Resolved via {@code MessageTypesRegistry.taiXiu("116")}. Mirrors the per-product
 * betting-mini providers (e.g. {@code TipGameMessageTypes}) — note that 116 is
 * claimed twice across the two tables, once here for TAI_XIU and once by
 * {@code TipGameMessageTypes} for BETTING_MINI. The registry keys on
 * {@code (gameType, product)}, so that is not a collision.
 */
@Component
@MessageTypesImpl(gameType = GameType.TAI_XIU, products = "116")
public class MiniGameTaiXiuMessageTypes implements TaiXiuMessageTypes {

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
     * No md5 StartGame variant was captured for this product (AD-10), so there is no
     * {@code TaiXiuStartGameMd5Message} to return — {@code null}. The effective
     * {@code startGameCmd()} (1002 at offset 0) is shared by both variants and
     * {@link #getTypeRegistrations()} never registers an md5 entry, so this is never
     * dereferenced in v1.
     */
    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * No updateBet frame was captured (OI-5), so updateBet is omitted in v1 —
     * {@code null}. {@link #getTypeRegistrations()} does not register an updateBet
     * entry, so this is never dereferenced.
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
