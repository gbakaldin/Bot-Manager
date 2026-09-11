package com.vingame.bot.domain.bot.message.g4.win79;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the WIN79 {@code bs} crowd array, present on the Subscribe,
 * UpdateBet and EndGame frames alike.
 * <p>
 * Field semantics follow {@code CrowdOption}'s contract:
 * <ul>
 *   <li>{@code eid} — option id (0-5 for {@code gourdCrabPlugin}); the same id
 *       space as the outbound {@code Bet.eid} and the coordinator's option keys.</li>
 *   <li>{@code v} — crowd aggregate stake on this option, including our own bots'
 *       stake (they are subscribers too).</li>
 *   <li>{@code b} — <b>this</b> bot's own stake on the option.</li>
 *   <li>{@code bc} — the ambiguous count field (bets vs distinct players is not
 *       settled for this product); carried for observability only.</li>
 * </ul>
 * Note WIN79 names the count {@code bc}, not {@code c}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Win79BetInfo(
        @JsonProperty("eid") int eid,
        @JsonProperty("bc") int bc,
        @JsonProperty("b") long b,
        @JsonProperty("v") long v) {
}
