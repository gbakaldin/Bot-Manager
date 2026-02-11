package com.vingame.bot.domain.environment.model;

import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EnvironmentFilter {

    private String name;
    private EnvironmentType type;
    private BrandCode brandCode;
    private ProductCode productCode;
}
