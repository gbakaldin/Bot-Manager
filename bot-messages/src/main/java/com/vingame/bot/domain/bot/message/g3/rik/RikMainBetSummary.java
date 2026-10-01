package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the RIK EndGame {@code mbs} array — <b>this connection's own bet</b>
 * for the just-completed round, carrying a stake, a gross return and one further
 * figure.
 *
 * <h2>{@code mbs} is the MAIN (own) bet array</h2>
 *
 * The backend names the two EndGame arrays explicitly —
 * {@code MAIN_BET_ARRAY = "mbs"} and {@code OTHER_BET_ARRAY = "obs"} — so {@code mbs}
 * is mine and {@code obs} is the room's. That settles what the capture on its own
 * could not: {@code mbs[].wm} is byte-identical to the {@code mW} on the room-wide
 * 13018 winner announcement for the same {@code sid} only because <b>this account was
 * the only bettor in every captured round</b>, which makes "my win" and "the room's
 * biggest win" trivially the same number. It is per-recipient, and
 * {@link RikEndGameMessage} reads it for {@code HasBotWinnings} / {@code HasBetTotals}.
 *
 * <h2>{@code wm} is a GROSS RETURN INCLUDING THE STAKE, not a profit (AD-15)</h2>
 *
 * {@code stockPlugin} pays {@code stake x (100 + d1)/100 x 0.98} — exact in 3/3
 * captured rounds — so a stake of 3000 on a {@code d1} of {@code -75} reports
 * {@code wm = 735}, a <b>net loss that still carries a positive {@code wm}</b>. This
 * is the same convention {@code TaiXiuEndGameMessage.winningsFor} spells out
 * ({@code GX - gR}, the gross return, never {@code GX - gB}). Never net the stake off
 * it: doing so turns the ~0.98 RTP anchor into ~-0.02.
 * <p>
 * <b>That formula is verified for the Up door only (OI-5).</b> All three captured
 * rounds were {@code eid 0} bets. The down door is a short, so its multiplier is
 * expected to be {@code (100 - d1)%} before the same 2% — but that inversion is the
 * user's description plus an arithmetic fit on three Up bets, <b>not observed
 * traffic</b>. Nothing here depends on it: this class models a reported number and
 * the message layer never computes a payout. It matters only to someone checking an
 * RTP figure by hand, who should know which half of the game has evidence behind it.
 *
 * @param b  this connection's own stake for the round
 * @param wm this connection's own gross return, stake included (see above)
 * @param m  a further figure reported alongside. It equals {@code b} in 3/3 captured
 *           rounds, so it is <b>not</b> a balance; its meaning is unknown and it is
 *           modelled without interpretation. Nothing reads it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RikMainBetSummary(
        @JsonProperty("b") long b,
        @JsonProperty("wm") long wm,
        @JsonProperty("m") long m) {
}
