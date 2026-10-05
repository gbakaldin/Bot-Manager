package com.vingame.bot.domain.bot.message.crash;

/**
 * Per-runner reading of a crash flight tick ({@code docs/plans/AVIATOR_BOT.md} AD-2).
 *
 * <p>A crash game may fly several runners on one shared round (119 Avatar: Jake and
 * Neytiri). The runner is protocol metadata: the bot only knows an {@code eid} in
 * {@code [1..CrashMessageTypes.runnerCount()]}, drawn once per round, and this
 * interface is how it reads <em>its own</em> runner. The brand's message class maps
 * the {@code eid} to its wire keys; the wire word "odd" (multiplier, payout ratio —
 * never a probability) stops there.
 *
 * <h2>The crashed flag is checked first (F-1)</h2>
 *
 * After a runner crashes, its multiplier freezes at the crash point and its flag goes
 * true, while the other runner can keep climbing. The frozen value is a real number
 * that can be at or above a bot's target (crash tick 2.86 against a 2.85 target), so
 * the cash-out gate is always {@code !crashedFor(eid) && multiplierFor(eid) >= target}.
 *
 * <h2>Unknown runners never cash out</h2>
 *
 * An {@code eid} outside the brand's runners returns {@code 0} from
 * {@link #multiplierFor(int)} and {@code true} from {@link #crashedFor(int)}. A frame
 * handler must never throw on it.
 */
public interface HasRunnerMultiplier {

    /**
     * @param eid the runner, {@code 1..runnerCount()}
     * @return the runner's current multiplier in hundredths ({@code 2.86 → 286},
     *         {@code 6 → 600}); {@code 0} for an unknown runner or an absent value
     */
    long multiplierFor(int eid);

    /**
     * @param eid the runner, {@code 1..runnerCount()}
     * @return whether that runner has crashed; {@code true} for an unknown runner
     */
    boolean crashedFor(int eid);
}
