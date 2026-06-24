package com.vingame.bot.domain.bot.message.taixiu;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import lombok.Getter;
import lombok.Setter;

/**
 * Tai Xiu StartGame for the captured {@code MiniGame}/{@code taixiuPlugin} product
 * (cmd {@code 1002}, AD-3).
 * <p>
 * <b>Captured payload (source of truth: {@code TaiXiuMessages/Start.js}):</b>
 * {@code {"odE":3.99,"iES":true,"cmd":1002,"sid":2670572}}.
 * <ul>
 *   <li>{@code sid} — session id; the only field the inherited round handler needs
 *       (drives {@code onStartGame} session tracking).</li>
 *   <li>{@code odE} — odds/payout multiplier (inferred); modeled but not consumed by
 *       the bot in v1.</li>
 *   <li>{@code iES} — boolean flag (inferred); modeled but not consumed in v1.</li>
 * </ul>
 * No explicit {@code timeForBetting} field was captured on StartGame (OI-5).
 * <p>
 * <b>md5 (AD-10):</b> no md5 StartGame variant was captured for this product, so
 * there is no separate {@code TaiXiuStartGameMd5Message}; the fixed
 * {@code START_GAME_CMD=1002} is shared by both variants (md5 only changes the body).
 */
@Getter
@Setter
public class TaiXiuStartGameMessage extends StartGameMessage {

    private double odE;
    private boolean iES;
    private long sid;

    @JsonCreator
    public TaiXiuStartGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("odE") double odE,
            @JsonProperty("iES") boolean iES,
            @JsonProperty("sid") long sid) {
        super(cmd);
        this.odE = odE;
        this.iES = iES;
        this.sid = sid;
    }

    @Override
    public long getSessionId() {
        return sid;
    }
}
