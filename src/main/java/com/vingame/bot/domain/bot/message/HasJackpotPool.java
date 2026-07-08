package com.vingame.bot.domain.bot.message;

/**
 * Marker for {@link EndGameMessage} subtypes exposing the game's <b>live running
 * jackpot pool meter</b> ({@code tJpV}) for the just-completed round — the total
 * winnable jackpot displayed in the game UI as it grows toward a discharge.
 * <p>
 * <b>DISTINCT from {@link HasJackpot}.</b> {@code HasJackpot.jackpotFor(userName)}
 * is a <i>per-bot payout</i> (the {@code jpV}/{@code iJp} value a single bot won on
 * this round) and drives {@code bot_jackpots_total}. This marker is the group-wide
 * <i>pool meter</i> ({@code tJpV}) that rises across rounds and is read by the
 * jackpot-scale lever (JACKPOT_SCALE_AND_RAMP, AD-J2) to derive a per-round volume
 * factor. The two fields are unrelated: a round can have a large pool and a zero
 * per-bot payout, or vice versa. Do NOT read {@code jpV}/{@code iJp} here.
 * <p>
 * The value is a primitive {@code long}, so a frame that omits {@code tJpV} (e.g. the
 * plain P_116 {@code taixiuPlugin} EndGame, which carries no live meter) yields
 * {@code 0} — treated downstream as "not observed" and mapped to the neutral factor
 * (AD-J5), never a throttle-to-floor.
 */
public interface HasJackpotPool {

    /**
     * @return the game's live running jackpot pool meter ({@code tJpV}) for the
     *         just-completed round; {@code 0} when the frame carries no meter
     *         (defaults to neutral downstream).
     */
    long jackpotPool();
}
