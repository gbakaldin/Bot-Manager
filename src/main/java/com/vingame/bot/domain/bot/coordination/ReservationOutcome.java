package com.vingame.bot.domain.bot.coordination;

/**
 * The result of a single {@link BetCoordinator#reserve(long, int, long)} call.
 *
 * <p>See {@code docs/plans/BET_COORDINATION.md}, AD-5.
 *
 * @param decision APPROVE (proposal committed verbatim), TRIM (committed a
 *                 smaller, grid-aligned amount), or REJECT (nothing committed).
 * @param amount   the amount actually committed. Equal to the proposed amount
 *                 for APPROVE, a smaller grid-aligned amount for TRIM, and
 *                 {@code 0} for REJECT.
 */
public record ReservationOutcome(Decision decision, long amount) {

    public enum Decision {
        APPROVE,
        TRIM,
        REJECT
    }

    static ReservationOutcome approve(long amount) {
        return new ReservationOutcome(Decision.APPROVE, amount);
    }

    static ReservationOutcome trim(long amount) {
        return new ReservationOutcome(Decision.TRIM, amount);
    }

    static ReservationOutcome reject() {
        return new ReservationOutcome(Decision.REJECT, 0L);
    }
}
