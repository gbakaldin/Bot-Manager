package com.vingame.bot.domain.brand.controller;

import com.vingame.bot.domain.brand.dto.BrandProductsResponse;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.brand.service.BrandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BrandController.class)
@DisplayName("BrandController")
class BrandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrandService service;

    @Test
    @DisplayName("GET /api/v1/brand/products should return 200 OK with brand-to-products map")
    void shouldReturnOkWithBrandProducts() throws Exception {
        // Arrange — use a LinkedHashMap so jsonPath assertions don't depend on hash ordering.
        Map<BrandCode, List<ProductCode>> brandProducts = new LinkedHashMap<>();
        brandProducts.put(BrandCode.G2, List.of(ProductCode.P_097, ProductCode.P_098));
        brandProducts.put(BrandCode.G4, List.of(ProductCode.P_118, ProductCode.P_119));

        BrandProductsResponse response = BrandProductsResponse.builder()
                .brandProducts(brandProducts)
                .build();

        when(service.getBrandProducts()).thenReturn(response);

        // Act & Assert
        mockMvc.perform(get("/api/v1/brand/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandProducts").exists())
                .andExpect(jsonPath("$.brandProducts.G2").isArray())
                .andExpect(jsonPath("$.brandProducts.G2.length()").value(2))
                .andExpect(jsonPath("$.brandProducts.G2[0].code").value("097"))
                .andExpect(jsonPath("$.brandProducts.G2[1].code").value("098"))
                .andExpect(jsonPath("$.brandProducts.G4.length()").value(2))
                .andExpect(jsonPath("$.brandProducts.G4[0].code").value("118"))
                .andExpect(jsonPath("$.brandProducts.G4[1].code").value("119"));
    }
}
