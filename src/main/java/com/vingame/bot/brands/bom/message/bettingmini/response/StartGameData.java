package com.vingame.bot.brands.bom.message.bettingmini.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.webocketparser.message.request.Data;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class StartGameData extends Data {

    private long sid;

    @JsonCreator
    public StartGameData(@JsonProperty("cmd") int cmd, @JsonProperty("sid") long sid) {
        super(cmd);

        this.sid = sid;
    }
}
