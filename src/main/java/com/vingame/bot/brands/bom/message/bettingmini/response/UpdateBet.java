package com.vingame.bot.brands.bom.message.bettingmini.response;


import com.vingame.bot.brands.bom.message.bettingmini.polymorphism.CmdAwareMessage;
import com.vingame.webocketparser.message.request.Body;

public class UpdateBet extends Body implements CmdAwareMessage {
    public UpdateBet(int cmd) {
        super(cmd);
    }

    public int getGameState() {
        return 0;
    }

    public long getRemainingTime() {
        return 0L;
    }
}
