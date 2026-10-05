package com.vingame.bot.domain.bot.core.support;

/**
 * The bounded reconnect ladder shared by bots that escalate a repeated failure to a full
 * reconnect ({@code docs/plans/AVIATOR_BOT.md} AD-9). Pure.
 *
 * <p>A count is a rung at {@code r·2^k} for {@code k = 0..topShift}, then at every
 * multiple of {@code r·2^topShift}. With {@code r = 1, topShift = 5}:
 * 1, 2, 4, 8, 16, 32, 64, 96, 128, ... — so a bot that never recovers re-logs in at most
 * once per 32 counts in steady state.
 *
 * <p>Same semantics as {@code CashoutBetStateMachine.isReconnectRung}, which is
 * deliberately <b>not</b> migrated onto this class in the AVIATOR_BOT feature (OI-5);
 * {@code ReconnectLadderParityTest} pins the two together until it is.
 */
public final class ReconnectLadder {

    private ReconnectLadder() {
    }

    /**
     * @param count    the consecutive-failure count, including this one
     * @param r        the first rung; {@code <= 0} means "never reconnect"
     * @param topShift the highest doubling step; after {@code r·2^topShift} the ladder is
     *                 periodic with that period. Must be in {@code [0, 30]}.
     * @return whether {@code count} is a reconnect rung
     */
    public static boolean isRung(int count, int r, int topShift) {
        if (topShift < 0 || topShift > 30) {
            throw new IllegalArgumentException("topShift must be in [0, 30], got " + topShift);
        }
        if (r <= 0 || count <= 0) {
            return false;
        }
        long top = (long) r << topShift;
        if (count > top) {
            return count % top == 0;
        }
        if (count % r != 0) {
            return false;
        }
        return Integer.bitCount(count / r) == 1;
    }
}
