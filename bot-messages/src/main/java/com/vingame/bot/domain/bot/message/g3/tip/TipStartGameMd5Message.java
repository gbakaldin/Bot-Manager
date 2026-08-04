package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TipStartGameMd5Message extends StartGameMd5Message {

    private int gid;
    private long sid;
    private String md5;

    @JsonCreator
    public TipStartGameMd5Message(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("gid") int gid,
            @JsonProperty("sid") long sid,
            @JsonProperty("md5") String md5) {
        super(cmd);
        this.gid = gid;
        this.sid = sid;
        this.md5 = md5;
    }

    @Override
    public long getSessionId() {
        return sid;
    }

    @Override
    public String getMd5Hash() {
        return md5;
    }
}
