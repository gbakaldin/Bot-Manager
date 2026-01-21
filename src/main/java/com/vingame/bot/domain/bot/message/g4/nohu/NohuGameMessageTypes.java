package com.vingame.bot.domain.bot.message.g4.nohu;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;

/**
 * Message types provider for NOHU product.
 * Supplies concrete NOHU message classes for deserialization.
 */
public class NohuGameMessageTypes implements GameMessageTypes {

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return NohuSubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return NohuStartGameMessage.class;
    }

    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return NohuStartGameMd5Message.class;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return NohuUpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return NohuEndGameMessage.class;
    }
}
