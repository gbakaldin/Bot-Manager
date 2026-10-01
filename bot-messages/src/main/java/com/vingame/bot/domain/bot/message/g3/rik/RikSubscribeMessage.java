package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * RIK (P_114) subscribe response (CMD {@code 3000 + offset}; {@code 13000} for
 * {@code stockPlugin} at offset 10000).
 * <p>
 * The two abstract accessors drive the bot's bet window: {@code tFB} (21 000 ms in
 * the capture) is how long betting is open, {@code tFD} (1 000 ms) is the tail
 * during which bets are refused. Same field names and roles as Bom / Tip / Nohu /
 * Win79. The measured cadence matches: StartGame to EndGame was 21 s and EndGame to
 * the next StartGame 11 s ({@code tFP}).
 * <p>
 * The outbound subscribe needed no work — the real client sends a bare
 * {@code {"cmd":13000}}, byte-identical to what both {@code Request.subscribe()} and
 * {@code RikStockRequest.subscribe()} emit. (The real client also sends a bare
 * {@code 13012} right afterwards and a {@code {"cmd":13022,"sId":…}}. Since Phase 3 a
 * stock bot sends {@code 13022} after <b>every</b> bet — the legacy Node bot's cadence,
 * AD-29 — via {@code RikStockRequest.commit(sid)}; it does <b>not</b> send {@code 13012},
 * which is Phase 3b, gated on V-17 still reading zero (AD-34). The Win79 bot likewise
 * does not replicate the real client's full frame set and still plays.)
 * <p>
 * Phase 2 forked stock's outbound <b>bet</b> onto {@code RikStockRequest}, so on
 * {@code stockPlugin} it is that class — not {@code Request} — a bot now subscribes
 * through. The subscribe frame itself did not change, and
 * {@code RikStockRequestTest.subscribeIsIdenticalToTheSharedRequest} serializes both
 * and compares them so it stays that way.
 * <p>
 * {@code gS} is modelled but unread — the bot enters {@code BET} on StartGame.
 * <p>
 * <b>Chat history ({@code cH}), round history ({@code htr}) and the price history
 * ({@code bH}) are deliberately not modelled</b> (AD-6). The engine reads none of
 * them, and not creating the fields is what disposes of two hazards for free:
 * {@code cH[].tst} is an epoch-millis value ({@code 1789389408635}) that an
 * {@code int} field would silently truncate, and {@code bH} is a nested
 * {@code List<List<Integer>>}. The subscribe fixture keeps the real 50-entry
 * {@code cH} and the nested {@code bH} verbatim, so this tolerance is a regression
 * test rather than a claim. If either is ever modelled, {@code tst} is a
 * {@code long}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RikSubscribeMessage extends SubscribeMessage implements HasCrowdBets {

    private long sid;

    /** Game state at subscribe time ({@code 2} in the capture). Modelled, unread. */
    private int gS;

    /** Milliseconds the bet window is open. */
    private long tFB;
    /** Milliseconds before round end during which bets are refused. */
    private long tFD;
    /** Milliseconds of the payout / between-rounds phase. */
    private long tFP;
    /** Milliseconds remaining in the current phase at subscribe time. */
    private long rmT;

    /** Maximum bet the server accepts on this game ({@code 500 000 000}). */
    private long mB;

    /**
     * Same key as the EndGame's own gross return ({@code wm}), so read as this
     * connection's win on the round already in progress. <b>Carried over, not observed
     * here:</b> it was {@code 0} in the stock sample and {@code 0} on
     * {@code taixiuMd5Plugin} — both were clean subscribes — so no sample distinguishes
     * that reading from any other. Nothing reads the field.
     */
    private long wm;
    /**
     * Observed as {@code 0} in the single {@code stockPlugin} sample and <b>absent
     * entirely</b> on {@code taixiuMd5Plugin}. <b>Meaning unknown, and deliberately not
     * interpreted</b> — in particular it is <i>not</i> called a balance here, because
     * the one place on this product where {@code m} has non-zero samples contradicts
     * that: {@link RikMainBetSummary}'s {@code m} equals the stake in 3/3 rounds. The
     * two may or may not be the same quantity; a constant {@code 0} cannot settle it.
     * Nothing reads the field.
     */
    private long m;

    private long tTU;
    private long tSv;
    private long tLv;
    private long tLp;
    private long tSp;

    private boolean iab;

    private List<RikBetInfo> bs;

    @JsonCreator
    public RikSubscribeMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("gS") int gS,
            @JsonProperty("tFB") long tFB,
            @JsonProperty("tFD") long tFD,
            @JsonProperty("tFP") long tFP,
            @JsonProperty("rmT") long rmT,
            @JsonProperty("mB") long mB,
            @JsonProperty("wm") long wm,
            @JsonProperty("m") long m,
            @JsonProperty("tTU") long tTU,
            @JsonProperty("tSv") long tSv,
            @JsonProperty("tLv") long tLv,
            @JsonProperty("tLp") long tLp,
            @JsonProperty("tSp") long tSp,
            @JsonProperty("iab") boolean iab,
            @JsonProperty("bs") List<RikBetInfo> bs) {
        super(cmd);
        this.sid = sid;
        this.gS = gS;
        this.tFB = tFB;
        this.tFD = tFD;
        this.tFP = tFP;
        this.rmT = rmT;
        this.mB = mB;
        this.wm = wm;
        this.m = m;
        this.tTU = tTU;
        this.tSv = tSv;
        this.tLv = tLv;
        this.tLp = tLp;
        this.tSp = tSp;
        this.iab = iab;
        this.bs = bs;
    }

    @Override
    public long getTimeForBetting() {
        return tFB;
    }

    @Override
    public long getTimeForDecision() {
        return tFD;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Entries arrive in server order — the capture's {@code bs} is
     * {@code [{eid:1},{eid:0}]} — so never index this list by position.
     * {@link CrowdOption} is keyed on {@code eid}.
     */
    @Override
    public List<CrowdOption> crowdBets() {
        if (bs == null) {
            return List.of();
        }
        return bs.stream()
                .map(e -> new CrowdOption(e.eid(), e.v(), e.b(), e.bc()))
                .toList();
    }
}
