package com.vingame.bot.brands.bom.message.bettingmini.response;


import com.vingame.webocketparser.message.request.Data;

public class UpdateBet extends Data {
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
