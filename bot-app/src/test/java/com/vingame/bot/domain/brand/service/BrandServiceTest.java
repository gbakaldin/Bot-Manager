package com.vingame.bot.domain.brand.service;

import com.vingame.bot.domain.brand.dto.BrandProductsResponse;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrandService")
class BrandServiceTest {

    private final BrandService service = new BrandService();

    @Nested
    @DisplayName("getBrandProducts")
    class GetBrandProductsTests {

        @Test
        @DisplayName("Should return a non-null response with a non-null brandProducts map")
        void shouldReturnNonNullResponse() {
            BrandProductsResponse response = service.getBrandProducts();

            assertThat(response).isNotNull();
            assertThat(response.getBrandProducts()).isNotNull();
        }

        @Test
        @DisplayName("Should expose all six known brand codes")
        void shouldExposeAllSixBrandCodes() {
            Map<BrandCode, List<ProductCode>> brandProducts = service.getBrandProducts().getBrandProducts();

            assertThat(brandProducts).containsOnlyKeys(
                    BrandCode.G0,
                    BrandCode.G2,
                    BrandCode.G3,
                    BrandCode.G4,
                    BrandCode.G5,
                    BrandCode.GTH
            );
        }

        @Test
        @DisplayName("Should map G0 to [P_103]")
        void shouldMapG0() {
            Map<BrandCode, List<ProductCode>> brandProducts = service.getBrandProducts().getBrandProducts();

            assertThat(brandProducts.get(BrandCode.G0))
                    .containsExactly(ProductCode.P_103);
        }

        @Test
        @DisplayName("Should map G2 to [P_097, P_098]")
        void shouldMapG2() {
            Map<BrandCode, List<ProductCode>> brandProducts = service.getBrandProducts().getBrandProducts();

            assertThat(brandProducts.get(BrandCode.G2))
                    .containsExactly(ProductCode.P_097, ProductCode.P_098);
        }

        @Test
        @DisplayName("Should map G3 to [P_105, P_114, P_116]")
        void shouldMapG3() {
            Map<BrandCode, List<ProductCode>> brandProducts = service.getBrandProducts().getBrandProducts();

            assertThat(brandProducts.get(BrandCode.G3))
                    .containsExactly(ProductCode.P_105, ProductCode.P_114, ProductCode.P_116);
        }

        @Test
        @DisplayName("Should map G4 to [P_118, P_119]")
        void shouldMapG4() {
            Map<BrandCode, List<ProductCode>> brandProducts = service.getBrandProducts().getBrandProducts();

            assertThat(brandProducts.get(BrandCode.G4))
                    .containsExactly(ProductCode.P_118, ProductCode.P_119);
        }

        @Test
        @DisplayName("Should map G5 to [P_066]")
        void shouldMapG5() {
            Map<BrandCode, List<ProductCode>> brandProducts = service.getBrandProducts().getBrandProducts();

            assertThat(brandProducts.get(BrandCode.G5))
                    .containsExactly(ProductCode.P_066);
        }

        @Test
        @DisplayName("Should map GTH to [P_222]")
        void shouldMapGTH() {
            Map<BrandCode, List<ProductCode>> brandProducts = service.getBrandProducts().getBrandProducts();

            assertThat(brandProducts.get(BrandCode.GTH))
                    .containsExactly(ProductCode.P_222);
        }

        @Test
        @DisplayName("Should return equal mappings on repeated calls (stable contract)")
        void shouldReturnStableMapping() {
            BrandProductsResponse first = service.getBrandProducts();
            BrandProductsResponse second = service.getBrandProducts();

            assertThat(first.getBrandProducts()).isEqualTo(second.getBrandProducts());
        }
    }
}
