package com.vingame.bot.domain.bot.strategy.slot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v1 slot strategy — uniform-random pick over the server-sourced allowed
 * bet-value set on every spin. Stateless; the RNG comes from
 * {@link SlotBetContext#rng()} (owned by the bot), so the strategy never holds
 * its own.
 *
 * <p><b>Spring scope.</b> Prototype-scoped so {@link SlotStrategyFactory}
 * produces a fresh instance per bot (mirrors the betting strategy beans).
 */
@Slf4j
@Component
@Scope("prototype")
@SlotStrategyImpl(SlotStrategyId.RANDOM)
public final class RandomBetStrategy implements SlotStrategy {

    @Override
    public long chooseBet(SlotBetContext ctx) {
        List<Long> allowed = ctx.allowedBetValues();
        long amount = allowed.get(ctx.rng().nextInt(allowed.size()));
        log.debug("RandomBetStrategy.chooseBet: amount={}", amount);
        return amount;
    }
}
