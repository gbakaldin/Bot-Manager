package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One <b>ball</b> of the {@code ziczacPlugin} (RIK / P_114 Plinko) EndGame
 * {@code mbs} array — the backend's {@code MAIN_BET_ARRAY}, i.e. <b>this
 * connection's own</b> drops for the just-completed round.
 *
 * <h2>The payout model is closed-form and verified</h2>
 *
 * The game is a 16-row Galton board whose 17 buckets are quoted on the subscribe
 * frame as {@code odds = [100,15,8,5,3,2,1.2,0.3,0,0.3,1.2,2,3,5,8,15,100]}. Each
 * ball lands in one bucket and the frame reports the bucket's multiplier as
 * {@code odd} and the resulting return as {@code r}. Across the committed capture
 * {@code /captures/rik-ziczacPlugin-12000.jsonl}, <b>{@code r == b × odd} in 81 of
 * 81 balls</b>, zero mismatches — pinned by
 * {@code RikZicZacCaptureArithmeticTest}. The binomial over that table gives a
 * theoretical RTP of <b>0.95635</b>; the capture's 81 balls measured
 * {@code 46 595 000 / 48 910 000 = 0.9527}.
 *
 * <h2>{@code r} is a GROSS return INCLUDING the stake</h2>
 *
 * {@code odd} is a <b>total</b> multiplier, not a profit multiplier: a ball in an
 * {@code odd:0.3} bucket returns 30% of its stake — a loss that still reports a
 * positive {@code r}, and a ball in the centre bucket ({@code odd:0}) returns
 * nothing. This is the same convention as {@code TaiXiuEndGameMessage.winningsFor}
 * and as {@link RikMainBetSummary#wm()} on the other two 114 games.
 * <b>Never net the stake off it</b> — doing so turns a ~0.96 RTP into ~-0.04.
 *
 * <h2>{@code odd} is a {@code double}, and that is load-bearing</h2>
 *
 * The bucket table contains {@code 1.2} and {@code 0.3}, and the wire mixes integer
 * and double forms of the same field (54 doubles / 27 integers across the capture).
 * Jackson's {@code ACCEPT_FLOAT_AS_INT} is on by default, so a {@code long} field
 * would <b>silently truncate {@code 1.2} to {@code 1}</b> with no error anywhere —
 * corrupting the {@code r = b × odd} cross-check and any future payout arithmetic.
 * Money ({@code b}, {@code r}) stays {@code long}: {@code r} reaches 15 900 000 in
 * this capture and the same frame carries a balance-shaped figure at 82% of
 * {@code Integer.MAX_VALUE}.
 *
 * <h2>{@code p} is deliberately NOT modelled</h2>
 *
 * Each entry also carries a {@code p} whose meaning is <b>unknown</b>: it runs
 * {@code 0..49} and <b>repeats within a single round</b> (one captured round has 19
 * entries and 17 distinct values), so it is neither a bucket index — there are only
 * 17 buckets — nor a unique slot index. Guessing a name for it would be worse than
 * leaving it out, and an unmodelled field can never fail deserialization while a
 * modelled one whose real type turns out to be something else fails the whole frame.
 * It is preserved in the committed capture, which is where an unknown belongs
 * (OI-1).
 *
 * @param b   this ball's stake — the per-ball chip total the client sent as the
 *            outbound {@code b}
 * @param r   this ball's gross return, stake included (see above); {@code 0} for a
 *            centre-bucket ball
 * @param odd the bucket's total multiplier; {@code double} for the reason above
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RikZicZacBallResult(
        @JsonProperty("b") long b,
        @JsonProperty("r") long r,
        @JsonProperty("odd") double odd) {
}
