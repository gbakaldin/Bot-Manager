package com.vingame.bot.domain.bot.message.cashout;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.vingame.bot.domain.bot.message.HasBotWinnings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * One server frame about the bot's live cash-out bet — either a progress tick
 * ({@code offset + BET_CODE}, e.g. {@code 1501}) or a cash-out reply
 * ({@code offset + CASHOUT_CODE}, e.g. {@code 1502}). This is the contract the bot
 * reads; the wire vocabulary ({@code odds}, {@code crd}, {@code iF}, {@code blS}, …)
 * stops at the concrete product class ({@code docs/plans/CASHOUT_BOT.md} AD-4).
 *
 * <h2>The outcome is keyed on {@link #isFinal()}, never on the cmd</h2>
 *
 * A burst can arrive on <b>either</b> cmd (F-4): legacy saw every burst on X501
 * ({@code crd == 0}) and the user's capture shows one on X502 ({@code blS:-1}). So
 * {@link #isBurst()} is defined once, here, over {@link #isFinal()},
 * {@link #burstSignalled()} and {@link #cashoutValue()}, and the bot registers one
 * handler for both concrete classes (AD-5).
 *
 * <h2>Winnings are the gross payout</h2>
 *
 * {@link #winningsFor(String)} returns {@code round(crd)} for a final non-burst frame
 * and {@code 0} otherwise. Legacy logs show {@code crd ≈ stake × multiplier} on a win
 * (F-5), i.e. gross, credited the same way slot credits gross spin winnings. The full
 * winning terminal frame is still uncaptured (OI-1); {@link #unmapped()} exists so the
 * first one on staging can be read without TRACE.
 */
public abstract class CashoutBetFrame extends CashoutMessage implements HasBotWinnings {

    /**
     * Every wire key the concrete class does not declare, in arrival order. Filled by
     * {@link #putUnmapped}. Read by the bot's one-shot first-terminal-frame DEBUG line
     * (AD-13) to close OI-1 on staging.
     */
    private final Map<String, Object> unmapped = new LinkedHashMap<>();

    protected CashoutBetFrame(int cmd) {
        super(cmd);
    }

    /** @return the server's id for this bet (wire {@code sid}). The bet frame we send carries none. */
    public abstract long sid();

    /**
     * @return the stake (wire {@code b}) when the frame carries it — progress frames
     *         do, the captured burst frame does not.
     */
    public abstract OptionalLong stake();

    /**
     * @return the current payout ratio (wire {@code odds}). A multiplier on the stake,
     *         never a probability.
     */
    public abstract double multiplier();

    /** @return the current cash-out value (wire {@code crd}); {@code 0} on a burst. */
    public abstract double cashoutValue();

    /** @return whether the bet is over (wire {@code iF}). */
    public abstract boolean isFinal();

    /**
     * The product's explicit burst marker (119: {@code blS == -1}). Kept protected so
     * the rule in {@link #isBurst()} stays the only place the outcome is decided.
     *
     * @return whether the frame itself flags a burst
     */
    protected abstract boolean burstSignalled();

    /**
     * {@code isFinal() && (burstSignalled() || cashoutValue() <= 0)} (AD-5). Covers the
     * captured X502 burst ({@code blS:-1}) and the legacy X501 {@code crd == 0} burst.
     *
     * @return whether this frame ends the bet with a loss
     */
    public boolean isBurst() {
        return isFinal() && (burstSignalled() || cashoutValue() <= 0);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The frame is about one player's private bet, so {@code userName} is not needed
     * to find the bot's entry; it is accepted to satisfy {@link HasBotWinnings}.
     *
     * @return {@code round(cashoutValue())} for a final non-burst frame, else {@code 0}
     */
    @Override
    public long winningsFor(String userName) {
        return isFinal() && !isBurst() ? Math.round(cashoutValue()) : 0L;
    }

    /** @return an unmodifiable view of every wire key the concrete class does not declare. */
    public Map<String, Object> unmapped() {
        return Collections.unmodifiableMap(unmapped);
    }

    /** Jackson hook: collects every key the concrete class has no property for. */
    @JsonAnySetter
    public void putUnmapped(String key, Object value) {
        unmapped.put(key, value);
    }
}
