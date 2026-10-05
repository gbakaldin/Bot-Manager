package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.vingame.bot.domain.bot.message.CrashMessageTypes;
import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.crash.CrashBetAck;
import com.vingame.bot.domain.bot.message.crash.CrashBettingClosed;
import com.vingame.bot.domain.bot.message.crash.CrashCashoutAck;
import com.vingame.bot.domain.bot.message.crash.CrashRoundEnd;
import com.vingame.bot.domain.bot.message.crash.CrashRoundStart;
import com.vingame.bot.domain.bot.message.crash.CrashSubscribeResponse;
import com.vingame.bot.domain.bot.message.crash.CrashTick;
import com.vingame.bot.domain.bot.message.request.CrashRequest;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * P_119 / WIN79 crash provider — Avatar ({@code aviatorPlugin}, offset 1700), two runners:
 * eid 1 Jake, eid 2 Neytiri ({@code docs/plans/AVIATOR_BOT.md} AD-2 / AD-5). Uses the
 * contract's default codes and registrations.
 *
 * <p>Stateless, like every provider: a table of class literals plus a request factory.
 */
@Component
@MessageTypesImpl(gameType = GameType.CRASH, products = "119")
public class Win79CrashMessageTypes implements CrashMessageTypes {

    @Override
    public Class<? extends CrashSubscribeResponse> subscribeResponseType() {
        return Win79CrashSubscribeResponse.class;
    }

    @Override
    public Class<? extends CrashRoundStart> roundStartType() {
        return Win79CrashRoundStart.class;
    }

    @Override
    public Class<? extends CrashBettingClosed> bettingClosedType() {
        return Win79CrashBettingClosed.class;
    }

    @Override
    public Class<? extends CrashBetAck> betAckType() {
        return Win79CrashBetAck.class;
    }

    @Override
    public Class<? extends CrashTick> tickType() {
        return Win79CrashTick.class;
    }

    @Override
    public Class<? extends CrashCashoutAck> cashoutAckType() {
        return Win79CrashCashoutAck.class;
    }

    @Override
    public Class<? extends CrashRoundEnd> roundEndType() {
        return Win79CrashRoundEnd.class;
    }

    /** Jake and Neytiri. */
    @Override
    public int runnerCount() {
        return Win79CrashRunners.COUNT;
    }

    @Override
    public CrashRequest newRequest(String zoneName, String pluginName, int offset) {
        return new Win79CrashRequest(zoneName, pluginName, offset);
    }
}
