package com.vingame.bot.domain.bot.strategy.martingale;

import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.StrategyImpl;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Classic Martingale with the AGGRESSIVE option-picking profile — see
 * {@code docs/plans/MARTINGALE_STRATEGIES.md} Architecture Decision A4.
 *
 * <p>Thin subclass: the entire progression lives in
 * {@link ClassicMartingaleStrategy}, the picker weighting lives in
 * {@link AffinityOptionPicker} (configured by the {@link RiskProfile} forwarded
 * via {@code super(...)}). This class exists only to claim
 * {@link StrategyId#MARTINGALE_CLASSIC_AGGRESSIVE} for Spring discovery.
 *
 * <p>Prototype-scoped: {@link com.vingame.bot.domain.bot.strategy.BettingStrategyFactory}
 * returns a fresh instance per bot via the no-arg constructor.
 */
@Component
@Scope("prototype")
@StrategyImpl(StrategyId.MARTINGALE_CLASSIC_AGGRESSIVE)
public final class ClassicMartingaleAggressive extends ClassicMartingaleStrategy {

    public ClassicMartingaleAggressive() {
        super(RiskProfile.AGGRESSIVE);
    }
}
