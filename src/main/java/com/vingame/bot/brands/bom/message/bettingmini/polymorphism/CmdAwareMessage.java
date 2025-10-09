package com.vingame.bot.brands.bom.message.bettingmini.polymorphism;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.vingame.bot.brands.bom.message.bettingmini.response.EndGameData;
import com.vingame.bot.brands.bom.message.bettingmini.response.StartGameData;
import com.vingame.bot.brands.bom.message.bettingmini.response.Subscribe;
import com.vingame.bot.brands.bom.message.bettingmini.response.UpdateBet;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "cmd"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = EndGameData.class, name = "9006"),
        @JsonSubTypes.Type(value = StartGameData.class, name = "9005"),
        @JsonSubTypes.Type(value = Subscribe.class, name = "9000"),
        @JsonSubTypes.Type(value = UpdateBet.class, name = "9002"),

        @JsonSubTypes.Type(value = EndGameData.class, name = "5006"),
        @JsonSubTypes.Type(value = StartGameData.class, name = "5005"),
        @JsonSubTypes.Type(value = Subscribe.class, name = "5000"),
        @JsonSubTypes.Type(value = UpdateBet.class, name = "5002"),
})
public interface CmdAwareMessage {} //extends BauCuaMiniCmdAwareMessage, BauCuaCmdAwareMessage {
//}
