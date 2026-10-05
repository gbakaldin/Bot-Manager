package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.vingame.bot.domain.bot.message.CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import com.vingame.bot.domain.bot.message.cashout.CashoutSubscribeResponse;
import com.vingame.bot.domain.bot.message.request.CashoutRequest;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * P_119 / WIN79 cash-out provider — Balloon ({@code balloonPlugin}, offset 1500) and
 * Soccer ({@code soccerPlugin}, offset 2500). Both games share one wire shape and are
 * told apart only by {@code Game.pluginName} / {@code Game.offset}, so one provider
 * serves both ({@code docs/plans/CASHOUT_BOT.md} AD-1 / AD-3).
 *
 * <p>Stateless, like every provider: a table of class literals plus a request
 * factory.
 */
@Component
@MessageTypesImpl(gameType = GameType.CASHOUT, products = "119")
public class Win79CashoutMessageTypes implements CashoutMessageTypes {

    @Override
    public Class<? extends CashoutSubscribeResponse> subscribeResponseType() {
        return Win79CashoutSubscribeResponse.class;
    }

    @Override
    public Class<? extends CashoutBetFrame> progressType() {
        return Win79CashoutProgressFrame.class;
    }

    @Override
    public Class<? extends CashoutBetFrame> resultType() {
        return Win79CashoutResultFrame.class;
    }

    @Override
    public CashoutRequest newRequest(String zoneName, String pluginName, int offset) {
        return new Win79CashoutRequest(zoneName, pluginName, offset);
    }
}
