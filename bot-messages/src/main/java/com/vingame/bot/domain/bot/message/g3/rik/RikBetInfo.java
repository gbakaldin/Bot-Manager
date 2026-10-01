package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of a RIK (P_114) crowd array. Serves <b>every</b> crowd array on the
 * product: {@code bs} on Subscribe / UpdateBet / EndGame and {@code obs} on the
 * {@code stockPlugin} EndGame. The shapes differ only in that {@code obs} carries no
 * {@code b}, which Jackson leaves at {@code 0} (AD-4).
 * <p>
 * Field semantics follow {@code CrowdOption}'s contract:
 * <ul>
 *   <li>{@code eid} — option id. <b>Per game, not per product</b>: {@code 0}/{@code 1}
 *       on {@code stockPlugin}, {@code 1}/{@code 2} on {@code taixiuMd5Plugin}. The
 *       same id space as the outbound bet's {@code eid} — {@code Bet.eid} on every
 *       114 game but {@code stockPlugin}, which since Phase 2 sends
 *       {@code RikStockBet.eid}. Nothing here cares —
 *       {@code CrowdOption} is keyed on whatever arrives — but the {@code Game}
 *       record's {@code optionAffinities} must use the right pair.</li>
 *   <li>{@code b} — <b>this</b> bot's own stake on the option. Supported by
 *       arithmetic, not only by cross-product convention: {@code taixiuMd5Plugin}'s
 *       EndGame reports {@code b:100000} on the one option this account bet and pays
 *       {@code wm:198000} = 1.98 x 100 000, the game's own quoted return. Note it is
 *       <b>absent, not zero</b>, on a round the bot did not bet — as it is on every
 *       {@code obs} entry, where it reads {@code 0}. {@code 0} is also a legitimate
 *       stake, so do not build a "did this bot bet?" check on it.</li>
 *   <li>{@code v} — the crowd aggregate on this option. <b>Still unresolved (OI-4):</b>
 *       this account was the only bettor in <i>both</i> captures, so {@code v == b}
 *       throughout and "room total including self" cannot be told from "others only".
 *       {@code crowdBets()} reads it as the room total including self, the
 *       cross-product convention and what the coordinator expects; one round with a
 *       second player in it settles it. A wrong reading would double-count our own
 *       stake in crowd-aware steering, which is opt-in and off.</li>
 *   <li>{@code bc} — the count field. On this product it counts <b>distinct players,
 *       not bets</b>: the capture shows {@code bc:1} on a round in which this single
 *       account placed three bets. That is what {@code Game.crowdCountSemantic =
 *       PLAYERS} records; it is observability-only either way, and it is why
 *       {@code RikEndGameMessage.betCountFor} does not use it.</li>
 * </ul>
 * The names are identical to {@code Win79BetInfo} / {@code TipBetInfo}.
 * <p>
 * Money fields are {@code long} on purpose (AD-3): this environment has already
 * carried values at 82-86% of {@code Integer.MAX_VALUE} through this app.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RikBetInfo(
        @JsonProperty("eid") int eid,
        @JsonProperty("bc") int bc,
        @JsonProperty("b") long b,
        @JsonProperty("v") long v) {
}
