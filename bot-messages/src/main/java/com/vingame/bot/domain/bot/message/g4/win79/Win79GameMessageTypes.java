package com.vingame.bot.domain.bot.message.g4.win79;

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
 * Message types provider for P_119 / WIN79 betting-mini games.
 * <p>
 * Derived from live {@code gourdCrabPlugin} (Bau Cua) frames captured 2026-09-10.
 * All four inbound CMDs sit at <b>offset 2000</b> — subscribe {@code 5000},
 * updateBet {@code 5002}, startGame {@code 5005}, endGame {@code 5006} — which is
 * the same offset BauCua uses elsewhere. The offset lives on the {@code Game}
 * record, not here.
 * <p>
 * The outbound bet frame needed no work: {@code Bet.BetData} already emits
 * {@code {"cmd":5002,"aid":1,"b":…,"eid":…,"sid":…}}, matching the real client
 * field-for-field.
 */
@Component
@MessageTypesImpl(gameType = GameType.BETTING_MINI, products = "119")
public class Win79GameMessageTypes implements GameMessageTypes {

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return Win79SubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return Win79StartGameMessage.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code null} — WIN79's betting-mini games are <b>not</b> md5 (confirmed
     * 2026-09-10). {@code getTypeRegistrations(offset, md5)} only dereferences this
     * when {@code md5} is true, so the game's {@code Game.md5} must stay
     * {@code false}; setting it true would NPE at registration rather than fail
     * gracefully.
     */
    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return null;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return Win79UpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return Win79EndGameMessage.class;
    }
}
