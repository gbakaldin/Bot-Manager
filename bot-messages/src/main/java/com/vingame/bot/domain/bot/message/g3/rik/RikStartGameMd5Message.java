package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import lombok.Getter;
import lombok.Setter;

/**
 * RIK (P_114) StartGame, md5 variant (CMD {@code 3005 + offset}).
 *
 * <h2>Why this exists when Win79's equivalent is {@code null} (AD-12)</h2>
 *
 * {@code Win79GameMessageTypes.startGameMd5Type()} returns {@code null} and relies
 * on its games' {@code Game.md5} staying {@code false}; ticking the md5 box there
 * NPEs at registration. RIK does not get that treatment for two reasons: the real
 * 13005 frame <b>does</b> carry an {@code md5} field, and the provider is
 * product-wide (AD-11), so a future 114 mini game may genuinely be md5. Twenty
 * lines turn an operator's mis-tick from an NPE into a working parse.
 * <p>
 * {@link #getMd5Hash()} returns the raw string. On {@code stockPlugin} that is
 * always {@code "-"}, and it is <b>deliberately not normalised to {@code null}</b>:
 * "this game publishes no hash" is a fact worth preserving, and nothing in
 * production reads the value (the accessor has no production consumer at all).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RikStartGameMd5Message extends StartGameMd5Message {

    private long sid;
    private String md5;

    @JsonCreator
    public RikStartGameMd5Message(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("md5") String md5) {
        super(cmd);
        this.sid = sid;
        this.md5 = md5;
    }

    @Override
    public long getSessionId() {
        return sid;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The raw field. {@code "-"} is returned as {@code "-"} — see the class javadoc.
     */
    @Override
    public String getMd5Hash() {
        return md5;
    }
}
