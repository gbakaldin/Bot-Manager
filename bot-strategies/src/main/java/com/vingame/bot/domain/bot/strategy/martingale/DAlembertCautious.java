package com.vingame.bot.domain.bot.strategy.martingale;

import com.vingame.bot.domain.bot.strategy.StrategyImpl;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * D'Alembert with the CAUTIOUS option-picking profile — see
 * {@code docs/plans/MARTINGALE_STRATEGIES.md} Architecture Decision A4.
 *
 * <p>Thin subclass: the entire progression lives in {@link DAlembertStrategy},
 * the picker weighting lives in {@link AffinityOptionPicker} (configured by
 * the {@link RiskProfile} forwarded via {@code super(...)}). This class exists
 * only to claim the {@code "DALEMBERT_CAUTIOUS"} key for Spring discovery.
 *
 * <p>Prototype-scoped: {@link com.vingame.bot.domain.bot.strategy.BettingStrategyFactory}
 * returns a fresh instance per bot via the no-arg constructor.
 */
@Component
@Scope("prototype")
@StrategyImpl("DALEMBERT_CAUTIOUS")
public final class DAlembertCautious extends DAlembertStrategy {

    public DAlembertCautious() {
        super(RiskProfile.CAUTIOUS);
    }
}
