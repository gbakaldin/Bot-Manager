package com.vingame.bot.domain.game.model;

/**
 * Per-game interpretation of the crowd feed's {@code bs[].bc} count field
 * (CROWD_AWARE_COORDINATION AD-C5). The count's bets-vs-players meaning varies
 * per game and is UNVERIFIED without live captures, so it is modelled as an
 * explicit game-intrinsic protocol trait.
 *
 * <p><b>Observability-only in v1.</b> The crowd steering math (AD-C2) is
 * stake-based ({@code bs[].v}), never count-based — {@code bc} is carried into
 * the health block and TRACE for future count-aware steering but does not drive
 * the per-round budget. A mis-set semantic is therefore an observability-only
 * error, never a steering error.
 *
 * <ul>
 *   <li>{@link #BETS} — {@code bc} is the number of bets on the option.</li>
 *   <li>{@link #PLAYERS} — {@code bc} is the number of distinct players on the option.</li>
 *   <li>{@link #UNKNOWN} — semantic uncaptured (the safe default); the coordinator
 *       steers on {@code v} alone and never reads {@code bc}.</li>
 * </ul>
 */
public enum CrowdCountSemantic {

    BETS,
    PLAYERS,
    UNKNOWN
}
