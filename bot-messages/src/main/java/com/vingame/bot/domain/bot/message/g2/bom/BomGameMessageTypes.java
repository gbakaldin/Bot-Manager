package com.vingame.bot.domain.bot.message.g2.bom;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Message types provider for BOM product.
 * Supplies concrete BOM message classes for deserialization.
 * <p>
 * Claims <b>two</b> products: 097 (BOM) and 098 (B52) both resolved to this provider
 * from {@code GameMessageTypesResolver}'s switch before PLUGIN_HOT_RELOAD Phase 2c,
 * and {@link MessageTypesImpl#products()} is a list precisely so that stays true.
 */
@Component
@MessageTypesImpl(gameType = GameType.BETTING_MINI, products = {"097", "098"})
public class BomGameMessageTypes implements GameMessageTypes {

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return BomSubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return BomStartGameMessage.class;
    }

    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return BomStartGameMd5Message.class;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return BomUpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return BomEndGameMessage.class;
    }
}
