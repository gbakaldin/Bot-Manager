package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.crash.CrashBetAck;
import com.vingame.bot.domain.bot.message.crash.CrashBettingClosed;
import com.vingame.bot.domain.bot.message.crash.CrashCashoutAck;
import com.vingame.bot.domain.bot.message.crash.CrashRoundEnd;
import com.vingame.bot.domain.bot.message.crash.CrashRoundStart;
import com.vingame.bot.domain.bot.message.crash.CrashSubscribeResponse;
import com.vingame.bot.domain.bot.message.crash.CrashTick;
import com.vingame.bot.domain.bot.message.request.CrashRequest;

import java.util.List;

/**
 * Provider interface for CRASH game message types — shared-round crash games such as
 * 119 Avatar ({@code aviatorPlugin}, offset 1700). See {@code docs/plans/AVIATOR_BOT.md}.
 *
 * <p><b>CMD = offset + code</b> (AD-4), codes starting at zero: subscribe / snapshot
 * {@code X700}, bet / bet ack {@code X702}, cash-out / cash-out ack {@code X703}, round
 * start {@code X705}, betting closed {@code X706}, round end {@code X707}, flight tick
 * {@code X709}. A brand with different codes overrides {@link #getTypeRegistrations(int)}
 * and {@link #cmds(int)}. The bet board ({@code X708}) and jackpot ({@code X716}) frames
 * are <b>not</b> registered and never parsed.
 *
 * <p><b>Product-keyed</b> (AD-5), resolved by {@code MessageTypesRegistry.crash(productCode)}.
 * A second crash brand is one new provider class.
 *
 * <p><b>The runner is protocol metadata</b> (AD-2): {@link #runnerCount()} says how many
 * runners fly per round (119: 2, Jake and Neytiri); it is not on {@code Game}, the DTO or
 * the UI.
 *
 * <p>Does not extend {@link GameMessageTypes} or {@link CashoutMessageTypes}: the shapes are
 * disjoint and the lookups are separate.
 */
public interface CrashMessageTypes {

    int SUBSCRIBE_CODE = 0;
    int BET_CODE = 2;
    int CASHOUT_CODE = 3;
    int ROUND_START_CODE = 5;
    int BETTING_CLOSED_CODE = 6;
    int ROUND_END_CODE = 7;
    int TICK_CODE = 9;

    /** @return the class for the subscribe reply / snapshot ({@code offset + SUBSCRIBE_CODE}). */
    Class<? extends CrashSubscribeResponse> subscribeResponseType();

    /** @return the class for the round start ({@code offset + ROUND_START_CODE}). */
    Class<? extends CrashRoundStart> roundStartType();

    /** @return the class for betting closed ({@code offset + BETTING_CLOSED_CODE}). */
    Class<? extends CrashBettingClosed> bettingClosedType();

    /** @return the class for the bet ack ({@code offset + BET_CODE}). */
    Class<? extends CrashBetAck> betAckType();

    /** @return the class for flight ticks ({@code offset + TICK_CODE}). */
    Class<? extends CrashTick> tickType();

    /** @return the class for the cash-out ack ({@code offset + CASHOUT_CODE}). */
    Class<? extends CrashCashoutAck> cashoutAckType();

    /** @return the class for the round end ({@code offset + ROUND_END_CODE}). */
    Class<? extends CrashRoundEnd> roundEndType();

    /**
     * How many runners fly per round (AD-2). The bot draws its {@code eid} uniformly over
     * {@code [1..runnerCount()]} once per round.
     *
     * @return the runner count; {@code 1} unless the brand flies several
     */
    default int runnerCount() {
        return 1;
    }

    /**
     * Build the outbound-frame builder for one bot.
     *
     * @param zoneName   the resolved WS zone (CRASH is a mini game, so {@code MiniGame} by
     *                   default — AD-14)
     * @param pluginName the game's SmartFox extension, {@code Game.pluginName}
     *                   ({@code aviatorPlugin})
     * @param offset     the game's CMD offset, {@code Game.offset}
     * @return a request builder with all three bound
     */
    CrashRequest newRequest(String zoneName, String pluginName, int offset);

    /**
     * The inbound cmds this contract registers, in code order — what the bot's raw-frame
     * OutputPrinter filters on.
     *
     * @param offset the game's CMD offset
     * @return the seven registered cmds
     */
    default List<Integer> cmds(int offset) {
        return List.of(
                offset + SUBSCRIBE_CODE,
                offset + BET_CODE,
                offset + CASHOUT_CODE,
                offset + ROUND_START_CODE,
                offset + BETTING_CLOSED_CODE,
                offset + ROUND_END_CODE,
                offset + TICK_CODE);
    }

    /**
     * Jackson registrations for one game: one inbound class per cmd, in code order.
     *
     * @param offset the game's CMD offset
     * @return registrations for {@code ObjectMapper.registerSubtypes}
     */
    default NamedType[] getTypeRegistrations(int offset) {
        return new NamedType[] {
            new NamedType(subscribeResponseType(), String.valueOf(offset + SUBSCRIBE_CODE)),
            new NamedType(betAckType(), String.valueOf(offset + BET_CODE)),
            new NamedType(cashoutAckType(), String.valueOf(offset + CASHOUT_CODE)),
            new NamedType(roundStartType(), String.valueOf(offset + ROUND_START_CODE)),
            new NamedType(bettingClosedType(), String.valueOf(offset + BETTING_CLOSED_CODE)),
            new NamedType(roundEndType(), String.valueOf(offset + ROUND_END_CODE)),
            new NamedType(tickType(), String.valueOf(offset + TICK_CODE)),
        };
    }
}
