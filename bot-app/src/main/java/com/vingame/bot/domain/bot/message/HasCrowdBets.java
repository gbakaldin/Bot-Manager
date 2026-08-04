package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.coordination.CrowdOption;

import java.util.List;

/**
 * Marker for message subtypes that carry the game's per-option <b>crowd bet
 * distribution</b> — the {@code bs} array broadcast by the server, one entry per
 * option with the aggregate crowd stake on that option
 * (CROWD_AWARE_COORDINATION AD-C1/AD-C3).
 *
 * <p>Implemented on every message class whose {@code bs} array is already parsed:
 * <ul>
 *   <li><b>Tip</b> ({@code g3/tip}): {@code TipUpdateBetMessage} (the live,
 *       intra-round signal — re-broadcast each tick through the bet window),
 *       {@code TipEndGameMessage}, {@code TipSubscribeMessage}.</li>
 *   <li><b>BOM / B52 / Nohu</b> ({@code g2/bom}, {@code g2/b52}, {@code g4/nohu}):
 *       their {@code EndGameMessage} (one-round-lagged full-round distribution) and
 *       {@code SubscribeMessage} (round-open seed). Their {@code UpdateBet} carries
 *       only {@code {gS, rmT}} and does <b>not</b> implement this marker (no faked
 *       intra-round signal — Findings / AD-C3).</li>
 *   <li><b>Tai Xiu</b>: implements NOTHING — no {@code bs} on any parsed frame, so
 *       crowd-aware steering is a safe no-op there (AD-C7).</li>
 * </ul>
 *
 * <p>Read in {@code BettingMiniGameBot} where the carrying frames are handled: when
 * {@code coordinator != null} and the message {@code instanceof HasCrowdBets}, the
 * bot feeds {@code coordinator.observeCrowd(sid, crowdBets())}. Mirrors the
 * {@link HasJackpotPool} marker-dispatch precedent (a per-option array instead of a
 * scalar meter). The coordinator only reads {@link CrowdOption#optionId()} and
 * {@link CrowdOption#value()} ({@code v}); {@code count} ({@code bc}) is carried for
 * observability only and never enters the v1 budget math (AD-C5).
 */
public interface HasCrowdBets {

    /**
     * @return the per-option crowd distribution derived from the frame's {@code bs}
     *         array, each entry mapped to a {@link CrowdOption} ({@code eid → optionId},
     *         {@code v → value}, {@code b → ownBet} when present else {@code 0},
     *         {@code bc → count}); an empty list when the frame carries no {@code bs}.
     */
    List<CrowdOption> crowdBets();
}
