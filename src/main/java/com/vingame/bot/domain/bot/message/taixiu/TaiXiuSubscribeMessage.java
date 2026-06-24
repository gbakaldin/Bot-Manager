package com.vingame.bot.domain.bot.message.taixiu;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import lombok.Getter;
import lombok.Setter;

/**
 * Tai Xiu subscribe response for the captured {@code MiniGame}/{@code taixiuPlugin}
 * product (cmd {@code 1005}, AD-3).
 * <p>
 * <b>Captured payload (source of truth: {@code TaiXiuMessages/Subscribe.js}).</b>
 * The inbound subscribe body carries only {@code {"cmd":1005}} — no betting-window
 * timing fields were present in the capture. BettingMini sources its countdown from
 * {@code getTimeForBetting()}/{@code getTimeForDecision()} on this message; Tai Xiu's
 * countdown source is therefore <b>unconfirmed</b> (residual OI-5 — the round clock
 * may come from the shared server clock or a StartGame field rather than the
 * subscribe response). Both accessors return {@code 0} until a richer subscribe frame
 * is captured. {@code 0} is the same value the inherited
 * {@code BettingMiniGameBot.startRemainingTimeCountDown} treats as "no explicit
 * window" — it does not crash, and the bot still reacts to StartGame/EndGame.
 */
@Getter
@Setter
public class TaiXiuSubscribeMessage extends SubscribeMessage {

    @JsonCreator
    public TaiXiuSubscribeMessage(@JsonProperty("cmd") int cmd) {
        super(cmd);
    }

    /**
     * @return {@code 0} — no {@code timeForBetting} field was present in the captured
     *         Tai Xiu subscribe frame (OI-5). Revisit when a richer frame is captured.
     */
    @Override
    public long getTimeForBetting() {
        return 0L;
    }

    /**
     * @return {@code 0} — no decision-window field was present in the captured Tai Xiu
     *         subscribe frame (OI-5).
     */
    @Override
    public long getTimeForDecision() {
        return 0L;
    }
}
