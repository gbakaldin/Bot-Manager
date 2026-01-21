package com.vingame.bot.domain.game.model;

import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameFilter {

    private BrandCode brandCode;
    private ProductCode productCode;
    private GameType gameType;
    private String name;
}
