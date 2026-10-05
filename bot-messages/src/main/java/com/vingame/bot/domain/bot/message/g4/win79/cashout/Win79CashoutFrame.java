package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import lombok.ToString;

import java.util.Map;
import java.util.OptionalLong;

/**
 * The 119 wire shape shared by the two inbound bet frames — progress
 * ({@link Win79CashoutProgressFrame}, {@code X501}) and cash-out reply
 * ({@link Win79CashoutResultFrame}, {@code X502}). Captured (2026-10-05,
 * {@code balloonPlugin}):
 * <pre>{@code
 * 1501 {"b":100000,"aS":false,"nextOdds":2.627…,"aSt":false,"crd":260908.608…,"nextCrd":262741.375…,"odds":2.609…,"blS":0,"cmd":1501,"iF":false,"sid":1801744}
 * 1502 {"aS":false,"aSt":false,"crd":0.0,"odds":4.0,"blS":-1,"sL":0.0,"cmd":1502,"iF":true,"sid":1801742}
 * }</pre>
 * Every field the capture shows is declared; anything else lands in
 * {@link #unmapped()} ({@code docs/plans/CASHOUT_BOT.md} AD-4, read by V-8).
 *
 * <p><b>Why there is no Lombok {@code @Getter} here.</b> {@code iF}, {@code blS},
 * {@code sL}, {@code aS}, {@code aSt} are mixed-case keys; Lombok's {@code getIF()} /
 * {@code getBlS()} de-mangle to {@code if} / {@code bls} and would add phantom
 * properties beside the creator's. The wire is read through the {@code @JsonCreator}
 * of each concrete class and exposed through the contract's semantic accessors only.
 *
 * <p>The burst rule lives on {@link CashoutBetFrame#isBurst()}; this class supplies
 * the 119 marker, {@code blS == -1}.
 */
@ToString
public abstract class Win79CashoutFrame extends CashoutBetFrame {

    /** Stake; absent on the captured burst frame. */
    private final Long b;
    private final double odds;
    private final double crd;
    private final Double nextOdds;
    private final Double nextCrd;
    /** {@code -1} on a burst, {@code 0} otherwise as far as captured. Absent → {@code null}. */
    private final Integer blS;
    private final boolean iF;
    private final long sid;
    /** Echoed on the captured burst as {@code 0.0}; on the bet we send it is {@code 2}. Not read (OI-4). */
    private final Double sL;
    private final Boolean aS;
    private final Boolean aSt;

    @SuppressWarnings("java:S107") // one parameter per captured wire key, by design
    protected Win79CashoutFrame(int cmd, Long b, double odds, double crd, Double nextOdds, Double nextCrd,
                                Integer blS, boolean iF, long sid, Double sL, Boolean aS, Boolean aSt) {
        super(cmd);
        this.b = b;
        this.odds = odds;
        this.crd = crd;
        this.nextOdds = nextOdds;
        this.nextCrd = nextCrd;
        this.blS = blS;
        this.iF = iF;
        this.sid = sid;
        this.sL = sL;
        this.aS = aS;
        this.aSt = aSt;
    }

    @Override
    public long sid() {
        return sid;
    }

    @Override
    public OptionalLong stake() {
        return b == null ? OptionalLong.empty() : OptionalLong.of(b);
    }

    @Override
    public double multiplier() {
        return odds;
    }

    @Override
    public double cashoutValue() {
        return crd;
    }

    @Override
    public boolean isFinal() {
        return iF;
    }

    @Override
    protected boolean burstSignalled() {
        return blS != null && blS == -1;
    }

    /** @return the raw {@code blS} value, or {@code null} if absent. Diagnostics only (AD-13 one-shot line). */
    public Integer burstState() {
        return blS;
    }

    @ToString.Include(name = "cmd")
    private int cmdForToString() {
        return getCmd();
    }

    @ToString.Include(name = "unmapped")
    private Map<String, Object> unmappedForToString() {
        return unmapped();
    }
}
