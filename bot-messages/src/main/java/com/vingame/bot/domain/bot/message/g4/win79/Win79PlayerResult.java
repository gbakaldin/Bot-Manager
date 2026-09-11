package com.vingame.bot.domain.bot.message.g4.win79;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the WIN79 EndGame {@code ps} array — the per-player settlement list.
 * <p>
 * {@code uid} is the server-side user id (e.g. {@code "18_1973"}), <b>not</b> the
 * username the bot knows itself by, which is why
 * {@link Win79EndGameMessage#winningsFor(String)} reads the top-level {@code wm}
 * rather than searching this list. Modelled because it is the only place a
 * per-player payout breakdown appears, and it is useful for future data collection.
 *
 * @param uid server user id, agency-prefixed
 * @param wm  that player's gross win for the round
 * @param m   that player's post-settlement balance
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Win79PlayerResult(
        @JsonProperty("uid") String uid,
        @JsonProperty("wm") long wm,
        @JsonProperty("m") long m) {
}
