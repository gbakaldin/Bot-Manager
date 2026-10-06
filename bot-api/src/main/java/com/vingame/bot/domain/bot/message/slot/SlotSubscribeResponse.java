package com.vingame.bot.domain.bot.message.slot;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inbound slot subscribe response, {@code cmd:1300}. This is the source of
 * both the winline count and the allowed bet-value set at runtime (SLOT_MACHINE_BOT
 * plan AD-11/AD-12): neither is a Game-config field.
 * <p>
 * Note the {@code ls}-name clash with the {@code 1302} spin request: here
 * {@code ls} = winline <b>definitions</b> ({@code List<WinlineDef>}, count
 * matters); on the spin request {@code ls} = selected winline indices
 * ({@code int[]}). Different fields on different classes — see AD-8.
 */
@Getter
@Setter
public class SlotSubscribeResponse extends SlotMessage {

    private int gid;
    /** Winline definitions. {@code ls.size()} = number of winlines (AD-8/AD-11). */
    private List<WinlineDef> ls;
    /** Jackpot tiers keyed by bet amount; {@code Js[].b} = the allowed bet-value set. */
    private List<JackpotTier> Js;

    @JsonCreator
    public SlotSubscribeResponse(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("gid") int gid,
            @JsonProperty("ls") List<WinlineDef> ls,
            @JsonProperty("Js") List<JackpotTier> Js) {

        super(cmd);
        this.gid = gid;
        this.ls = ls;
        this.Js = Js;
    }

    /** @return number of winlines = {@code ls.size()} (AD-8); {@code 0} if absent. */
    public int numLines() {
        return ls == null ? 0 : ls.size();
    }

    /**
     * @return the allowed bet-value set = {@code Js[].b}, sorted ascending
     *         (AD-11); empty list if {@code Js} absent.
     */
    public List<Long> allowedBetValues() {
        if (Js == null) {
            return List.of();
        }
        List<Long> values = new ArrayList<>(Js.size());
        for (JackpotTier tier : Js) {
            values.add(tier.getB());
        }
        Collections.sort(values);
        return values;
    }

    @Getter
    @Setter
    public static class WinlineDef {
        private int lid;
        private int[] poss;

        @JsonCreator
        public WinlineDef(
                @JsonProperty("lid") int lid,
                @JsonProperty("poss") int[] poss) {
            this.lid = lid;
            this.poss = poss;
        }
    }

    @Getter
    @Setter
    public static class JackpotTier {
        private long b;
        private int gid;
        private long J;
        private int aid;

        @JsonCreator
        public JackpotTier(
                @JsonProperty("b") long b,
                @JsonProperty("gid") int gid,
                @JsonProperty("J") long J,
                @JsonProperty("aid") int aid) {
            this.b = b;
            this.gid = gid;
            this.J = J;
            this.aid = aid;
        }
    }
}
