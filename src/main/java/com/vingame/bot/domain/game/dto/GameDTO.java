package com.vingame.bot.domain.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
import com.vingame.bot.domain.game.model.GameType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameDTO {

    private String id;
    private BrandCode brandCode;
    private ProductCode productCode;

    private String name;
    private String description;
    private GameType gameType;

    private String pluginName;
    private Integer gameId;
    private Integer numberOfOptions;
    private Integer offset;
    private Boolean md5;
}
