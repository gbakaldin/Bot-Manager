package com.vingame.bot.brands.bom.message.bettingmini.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.brands.bom.message.bettingmini.polymorphism.CmdAwareMessage;
import com.vingame.webocketparser.message.request.Body;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionDetail extends Body implements CmdAwareMessage {

    private String rs;
    private long st;
    private boolean ended;
    private int d1;
    private int d2;
    private int d3;
    private long sid;
    private String md5;
    private long et;

    public SessionDetail(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("rs") String rs,
            @JsonProperty("st") long st,
            @JsonProperty("ended") boolean ended,
            @JsonProperty("d1") int d1,
            @JsonProperty("d2") int d2,
            @JsonProperty("d3") int d3,
            @JsonProperty("sid") long sid,
            @JsonProperty("md5") String md5,
            @JsonProperty("et") long et) {

        super(cmd);
        this.rs = rs;
        this.st = st;
        this.ended = ended;
        this.d1 = d1;
        this.d2 = d2;
        this.d3 = d3;
        this.sid = sid;
        this.md5 = md5;
        this.et = et;
    }

}
