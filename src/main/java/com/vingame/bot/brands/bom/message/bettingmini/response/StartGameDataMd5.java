package com.vingame.bot.brands.bom.message.bettingmini.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class StartGameDataMd5 extends StartGameData {

    private String md5;

    public StartGameDataMd5(@JsonProperty("cmd") int cmd, @JsonProperty("sid") long sid, @JsonProperty("md5") String md5) {
        super(cmd, sid);
        this.md5 = md5;
    }
}
