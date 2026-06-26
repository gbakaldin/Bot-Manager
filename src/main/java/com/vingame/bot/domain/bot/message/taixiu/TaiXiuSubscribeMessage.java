package com.vingame.bot.domain.bot.message.taixiu;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import lombok.Getter;
import lombok.Setter;

/**
 * Tai Xiu subscribe response for the captured {@code MiniGame}/{@code taixiuPlugin}
 * product (cmd {@code 1005}, AD-3).
 * <p>
 * <b>Captured payload (source of truth: {@code TaiXiuMessages/SubscribeResponse.js}).</b>
 * The earlier {@code Subscribe.js} was only the bare outbound request
 * ({@code {"cmd":1005}}); the real <b>inbound</b> subscribe response carries the game
 * state + round timing the bot needs to start its countdown immediately on subscribe,
 * before the first StartGame frame. Load-bearing fields:
 * <ul>
 *   <li>{@code tFB} (sample {@code 50000}) — <b>time for betting</b>, the betting-window
 *       length in ms; drives {@link #getTimeForBetting()} (the inherited
 *       {@code startRemainingTimeCountDown} source).</li>
 *   <li>{@code tFBB} (sample {@code 3000}) — <b>time-for-block-bet</b>, the late-bet
 *       cutoff before round end; drives {@link #getTimeForDecision()}.</li>
 *   <li>{@code sid} (sample {@code 2670594}) — current session id; exposed via
 *       {@link #getSid()}. Note: {@link SubscribeMessage} has no {@code getSessionId()}
 *       contract (only StartGame/EndGame do), so this is a plain getter, not an
 *       override — see divergence note in the Phase-4 report.</li>
 *   <li>{@code gS} (sample {@code 3}) — game state; modeled, not consumed in v1.</li>
 *   <li>{@code rmT} (sample {@code 13535}) — remaining time in the current phase (ms);
 *       modeled, not consumed in v1.</li>
 *   <li>{@code odE} (sample {@code 3.99}) — odds; {@code iES} (sample {@code true}) —
 *       is-entry-stage flag. Modeled, not consumed in v1.</li>
 * </ul>
 * The long {@code htr} (round history), {@code gi} (the two Tài/Xỉu entries),
 * {@code cH} (chat), {@code ag} (agency balance), {@code tP}/{@code tFP}/etc. are
 * tolerated but not modeled ({@link JsonIgnoreProperties}{@code (ignoreUnknown=true)});
 * the scenario mapper also sets {@code FAIL_ON_UNKNOWN_PROPERTIES=false}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaiXiuSubscribeMessage extends SubscribeMessage {

    /**
     * Fallback late-bet cutoff (ms) applied when the inbound subscribe response carries
     * no {@code tFBB} (or {@code tFBB <= 0}). The 114 jackpot subscribe response omits
     * {@code tFBB} entirely, which would otherwise leave {@code blockBetTime == 0} and
     * let bots bet right up to round end; 116 carries a real {@code tFBB=3000}, so this
     * default leaves its behaviour unchanged.
     */
    static final long DEFAULT_TIME_FOR_DECISION_MS = 3000L;

    /** {@code tFB}: betting-window length (ms). */
    private long tFB;
    /** {@code tFBB}: late-bet cutoff before round end (ms). */
    private long tFBB;
    /** {@code sid}: current session id. */
    private long sid;
    /** {@code gS}: game state. */
    private int gS;
    /** {@code rmT}: remaining time in the current phase (ms). */
    private long rmT;
    /** {@code odE}: odds / payout multiplier. */
    private double odE;
    /** {@code iES}: is-entry-stage flag. */
    private boolean iES;

    @JsonCreator
    public TaiXiuSubscribeMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("tFB") long tFB,
            @JsonProperty("tFBB") long tFBB,
            @JsonProperty("sid") long sid,
            @JsonProperty("gS") int gS,
            @JsonProperty("rmT") long rmT,
            @JsonProperty("odE") double odE,
            @JsonProperty("iES") boolean iES) {
        super(cmd);
        this.tFB = tFB;
        this.tFBB = tFBB;
        this.sid = sid;
        this.gS = gS;
        this.rmT = rmT;
        this.odE = odE;
        this.iES = iES;
    }

    /**
     * @return {@code tFB} — the betting-window length (ms) from the inbound subscribe
     *         response, feeding the inherited countdown.
     */
    @Override
    public long getTimeForBetting() {
        return tFB;
    }

    /**
     * @return {@code tFBB} — the late-bet cutoff (ms) before round end. Mirrors the
     *         betting-mini {@code getTimeForDecision()} (the {@code blockBetTime}
     *         window in which the bot stops placing bets). When the subscribe response
     *         carries no {@code tFBB} (or {@code tFBB <= 0}, as on 114), falls back to
     *         {@link #DEFAULT_TIME_FOR_DECISION_MS} so bots do not bet right up to round
     *         end. 116 carries a real {@code tFBB=3000} and is unaffected.
     */
    @Override
    public long getTimeForDecision() {
        return tFBB > 0 ? tFBB : DEFAULT_TIME_FOR_DECISION_MS;
    }
}
