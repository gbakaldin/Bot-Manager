package com.vingame.bot.domain.bot.message.g4.nohu;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class NohuStartGameMd5Message extends StartGameMd5Message {

    private long sid;
    private String md5;

    @JsonCreator
    public NohuStartGameMd5Message(
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

    @Override
    public String getMd5Hash() {
        return md5;
    }
}
