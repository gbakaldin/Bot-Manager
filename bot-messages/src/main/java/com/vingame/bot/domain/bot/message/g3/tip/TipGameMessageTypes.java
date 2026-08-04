package com.vingame.bot.domain.bot.message.g3.tip;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;

/**
 * Message types provider for TIP product.
 * Supplies concrete TIP message classes for deserialization.
 */
public class TipGameMessageTypes implements GameMessageTypes {

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return TipSubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return TipStartGameMessage.class;
    }

    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return TipStartGameMd5Message.class;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return TipUpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return TipEndGameMessage.class;
    }
}
