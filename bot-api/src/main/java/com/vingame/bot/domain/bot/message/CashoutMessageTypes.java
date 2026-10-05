package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import com.vingame.bot.domain.bot.message.cashout.CashoutSubscribeResponse;
import com.vingame.bot.domain.bot.message.request.CashoutRequest;

/**
 * Provider interface for CASHOUT game message types — per-player cash-out games such
 * as 119 Balloon ({@code balloonPlugin}, offset 1500) and Soccer
 * ({@code soccerPlugin}, offset 2500). See {@code docs/plans/CASHOUT_BOT.md}.
 *
 * <p><b>CMD = offset + code</b> (AD-2), the same arithmetic as
 * {@link GameMessageTypes} with codes starting at zero: subscribe {@code X500},
 * bet / progress {@code X501}, cash-out / result {@code X502}.
 *
 * <p><b>Product-keyed, not product-neutral</b> (AD-3). 119 is the only brand known to
 * run these plugins and the bet body ({@code aid}, {@code sL}, {@code aS},
 * {@code aSt}) looks brand-specific, so a provider claims products like the
 * betting-mini and Tai Xiu ones do, and is resolved by
 * {@code MessageTypesRegistry.cashout(productCode)}. A second brand is one new
 * provider class.
 *
 * <p>Does not extend {@link GameMessageTypes}: the shapes are disjoint (no
 * StartGame/EndGame, no md5 variant) and the lookups are separate.
 */
public interface CashoutMessageTypes {

    int SUBSCRIBE_CODE = 0;
    int BET_CODE = 1;
    int CASHOUT_CODE = 2;

    /** @return the class for the subscribe reply ({@code offset + SUBSCRIBE_CODE}). */
    Class<? extends CashoutSubscribeResponse> subscribeResponseType();

    /** @return the class for progress frames ({@code offset + BET_CODE}). */
    Class<? extends CashoutBetFrame> progressType();

    /** @return the class for cash-out replies ({@code offset + CASHOUT_CODE}). */
    Class<? extends CashoutBetFrame> resultType();

    /**
     * Build the outbound-frame builder for one bot.
     *
     * @param zoneName   the resolved WS zone (CASHOUT is a mini game, so {@code MiniGame}
     *                   by default — AD-14)
     * @param pluginName the game's SmartFox extension, {@code Game.pluginName}
     *                   ({@code balloonPlugin} / {@code soccerPlugin})
     * @param offset     the game's CMD offset, {@code Game.offset}
     * @return a request builder with all three bound
     */
    CashoutRequest newRequest(String zoneName, String pluginName, int offset);

    /**
     * Jackson registrations for one game: subscribe reply at {@code offset}, progress at
     * {@code offset + 1}, cash-out reply at {@code offset + 2}. Two inbound classes, not
     * one class under two ids, because a burst can arrive on either cmd and the outcome
     * must not depend on how Jackson resolves a doubly-registered class (AD-5).
     *
     * @param offset the game's CMD offset
     * @return registrations for {@code ObjectMapper.registerSubtypes}
     */
    default NamedType[] getTypeRegistrations(int offset) {
        return new NamedType[] {
            new NamedType(subscribeResponseType(), String.valueOf(offset + SUBSCRIBE_CODE)),
            new NamedType(progressType(), String.valueOf(offset + BET_CODE)),
            new NamedType(resultType(), String.valueOf(offset + CASHOUT_CODE)),
        };
    }
}
