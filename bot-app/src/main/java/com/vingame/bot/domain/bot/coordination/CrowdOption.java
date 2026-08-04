package com.vingame.bot.domain.bot.coordination;

/**
 * One per-option crowd observation derived from the game's {@code bs} array
 * (CROWD_AWARE_COORDINATION AD-C1), keyed on {@code eid} — the same option id
 * space as the coordinator's option keys and the outbound {@code Bet.eid}.
 *
 * @param optionId option / position id ({@code bs[].eid}); the coordinator's
 *                 option key.
 * @param value    {@code bs[].v} — the crowd aggregate stake on this option
 *                 (total of all users' bets, <em>including</em> the fleet's own
 *                 bets, since the bots are subscribers too — see AD-C4). This is
 *                 the primary, semantically-unambiguous steering quantity.
 * @param ownBet   {@code bs[].b} — this bot's own bet on this option, present
 *                 only on the {@code WithTotal} frame variants (Tip). Carried for
 *                 the deferred Tip-only self-subtraction refinement (D3); the v1
 *                 core does <em>not</em> use it.
 * @param count    {@code bs[].bc} — the ambiguous count field (bets vs players).
 *                 Carried for observability / future count-aware steering only;
 *                 the v1 budget math never reads it (AD-C5).
 */
public record CrowdOption(int optionId, long value, long ownBet, int count) {
}
