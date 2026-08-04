package com.vingame.bot.domain.brand.service;

import com.vingame.bot.domain.brand.dto.BrandProductsResponse;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.vingame.bot.domain.brand.model.BrandCode.*;
import static com.vingame.bot.domain.brand.model.ProductCode.*;

@Service
public class BrandService {

    private static final Map<BrandCode, List<ProductCode>> BRAND_PRODUCTS = Map.of(
            G0, List.of(P_103),
            G2, List.of(P_097, P_098),
            G3, List.of(P_105, P_114, P_116),
            G4, List.of(P_118, P_119),
            G5, List.of(P_066),
            GTH, List.of(P_222)
    );

    public BrandProductsResponse getBrandProducts() {
        return BrandProductsResponse.builder()
                .brandProducts(BRAND_PRODUCTS)
                .build();
    }
}
