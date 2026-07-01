package com.vingame.bot.domain.bot.strategy.slot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * v1 default slot strategy — always stakes the smallest (first) allowed bet
 * value from the server-sourced set. Stateless.
 *
 * <p>The allowed set arrives sorted ascending from the bot's {@code onSubscribe}
 * ({@code Js[].b} of the {@code cmd:1300} response), so {@code get(0)} is the
 * minimum stake.
 *
 * <p><b>Spring scope.</b> Prototype-scoped so {@link SlotStrategyFactory}
 * produces a fresh instance per bot (mirrors the betting strategy beans). Holds
 * no state today, but prototype scope keeps the contract uniform with future
 * stateful slot strategies.
 */
@Slf4j
@Component
@Scope("prototype")
@SlotStrategyImpl(SlotStrategyId.FIXED)
public final class FixedBetStrategy implements SlotStrategy {

    @Override
    public long chooseBet(SlotBetContext ctx) {
        long amount = ctx.allowedBetValues().get(0);
        log.trace("FixedBetStrategy.chooseBet: amount={}", amount);
        return amount;
    }
}
