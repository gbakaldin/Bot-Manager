package com.vingame.bot.domain.bot.message.g2.b52;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;

/**
 * Message types provider for the B52 message shapes.
 * <p>
 * <b>Deliberately not registered.</b> It carries neither {@code @Component} nor
 * {@code @MessageTypesImpl}, so {@code MessageTypesRegistry} never sees it — which
 * is exactly what {@code GameMessageTypesResolver}'s switch did before
 * PLUGIN_HOT_RELOAD Phase 2c: product 098 (whose {@code ProductCode} name is
 * {@code P_098("098", "B52", …)}) resolved to {@link
 * com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes}, and this class was
 * reachable only from tests. Annotating it for 098 would be a behaviour change, not
 * a completion; if the 098 wire shape is ever confirmed to be this one, that is its
 * own decision with its own evidence.
 */
public class B52GameMessageTypes implements GameMessageTypes {

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return B52SubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return B52StartGameMessage.class;
    }

    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return B52StartGameMd5Message.class;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return B52UpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return B52EndGameMessage.class;
    }
}
