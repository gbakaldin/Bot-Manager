package com.vingame.bot.domain.bot.core;

import com.vingame.websocketparser.scenario.Scenario;

/**
 * Slot-machine bot: request/response per spin (subscribe {@code cmd:1300} →
 * spin loop {@code cmd:1302}), with no shared round clock, no BET/PAYOUT phase
 * and no broadcast watchdog. See {@code docs/plans/SLOT_MACHINE_BOT.md}.
 * <p>
 * <b>Skeleton.</b> The scenario, accounting, strategy wiring and factory hook-up
 * land in Phase 4+ of the plan. Phase 1 only delivers the slot message classes
 * and their Jackson registration; this class is a compiling placeholder so the
 * build stays green until Phase 4 fills it in.
 */
public class SlotMachineBot extends Bot {

    @Override
    protected void initializeSubclass() {
        // Phase 4: read gid from game, build SlotRequest, seed rng, resolve strategy.
    }

    @Override
    protected Scenario botBehaviorScenario() {
        // Phase 4: subscribe -> waitForMessage(1300) -> onSubscribe -> sendAsync(spin) -> onSpinResult.
        return null;
    }

    @Override
    protected void onStart() {
        // Phase 4: onNewSession(), register debug printer for cmds [1300, 1302], addScenario(...).
    }
}
