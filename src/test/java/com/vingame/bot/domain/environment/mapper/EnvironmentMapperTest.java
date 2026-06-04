package com.vingame.bot.domain.environment.mapper;

import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EnvironmentMapper")
class EnvironmentMapperTest {

    private final EnvironmentMapper mapper = Mappers.getMapper(EnvironmentMapper.class);

    @Nested
    @DisplayName("toDTO")
    class ToDTOTests {

        @Test
        @DisplayName("Should map all fields from entity to DTO")
        void shouldMapAllFields() {
            Environment entity = Environment.builder()
                    .id("env-1")
                    .name("Staging")
                    .type(EnvironmentType.STAGING)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .webSocketMiniUrl("wss://mini")
                    .webSocketCardUrl("wss://card")
                    .hostUrl("https://host")
                    .apiGatewayUrl("https://api")
                    .appId("app-1")
                    .headers(Map.of("X-Auth", "value"))
                    .customZone(true)
                    .binaryFrame(true)
                    .miniZoneName("MiniZone")
                    .cardZoneName("CardZone")
                    .encryptionKey("key")
                    .encryptionIv("iv")
                    .alertOnLowBalance(true)
                    .build();

            EnvironmentDTO dto = mapper.toDTO(entity);

            assertThat(dto.getId()).isEqualTo("env-1");
            assertThat(dto.getName()).isEqualTo("Staging");
            assertThat(dto.getType()).isEqualTo(EnvironmentType.STAGING);
            assertThat(dto.getBrandCode()).isEqualTo(BrandCode.G2);
            assertThat(dto.getProductCode()).isEqualTo(ProductCode.P_097);
            assertThat(dto.getWebSocketMiniUrl()).isEqualTo("wss://mini");
            assertThat(dto.getWebSocketCardUrl()).isEqualTo("wss://card");
            assertThat(dto.getHostUrl()).isEqualTo("https://host");
            assertThat(dto.getApiGatewayUrl()).isEqualTo("https://api");
            assertThat(dto.getAppId()).isEqualTo("app-1");
            assertThat(dto.getHeaders()).containsEntry("X-Auth", "value");
            assertThat(dto.getCustomZone()).isTrue();
            assertThat(dto.getBinaryFrame()).isTrue();
            assertThat(dto.getMiniZoneName()).isEqualTo("MiniZone");
            assertThat(dto.getCardZoneName()).isEqualTo("CardZone");
            assertThat(dto.getEncryptionKey()).isEqualTo("key");
            assertThat(dto.getEncryptionIv()).isEqualTo("iv");
            assertThat(dto.getAlertOnLowBalance()).isTrue();
        }

        @Test
        @DisplayName("Should return null when entity is null")
        void shouldReturnNullForNull() {
            assertThat(mapper.toDTO(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toEntity")
    class ToEntityTests {

        @Test
        @DisplayName("Should map all fields from DTO to entity")
        void shouldMapAllFields() {
            EnvironmentDTO dto = EnvironmentDTO.builder()
                    .id("env-1")
                    .name("Staging")
                    .type(EnvironmentType.STAGING)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .webSocketMiniUrl("wss://mini")
                    .webSocketCardUrl("wss://card")
                    .hostUrl("https://host")
                    .apiGatewayUrl("https://api")
                    .appId("app-1")
                    .headers(Map.of("X-Auth", "value"))
                    .customZone(true)
                    .binaryFrame(true)
                    .miniZoneName("MiniZone")
                    .cardZoneName("CardZone")
                    .encryptionKey("key")
                    .encryptionIv("iv")
                    .alertOnLowBalance(true)
                    .build();

            Environment entity = mapper.toEntity(dto);

            assertThat(entity.getId()).isEqualTo("env-1");
            assertThat(entity.getName()).isEqualTo("Staging");
            assertThat(entity.getType()).isEqualTo(EnvironmentType.STAGING);
            assertThat(entity.getBrandCode()).isEqualTo(BrandCode.G2);
            assertThat(entity.getProductCode()).isEqualTo(ProductCode.P_097);
            assertThat(entity.getWebSocketMiniUrl()).isEqualTo("wss://mini");
            assertThat(entity.getWebSocketCardUrl()).isEqualTo("wss://card");
            assertThat(entity.getHostUrl()).isEqualTo("https://host");
            assertThat(entity.getApiGatewayUrl()).isEqualTo("https://api");
            assertThat(entity.getAppId()).isEqualTo("app-1");
            assertThat(entity.getHeaders()).containsEntry("X-Auth", "value");
            assertThat(entity.isCustomZone()).isTrue();
            assertThat(entity.isBinaryFrame()).isTrue();
            assertThat(entity.getMiniZoneName()).isEqualTo("MiniZone");
            assertThat(entity.getCardZoneName()).isEqualTo("CardZone");
            assertThat(entity.getEncryptionKey()).isEqualTo("key");
            assertThat(entity.getEncryptionIv()).isEqualTo("iv");
            assertThat(entity.isAlertOnLowBalance()).isTrue();
        }

        @Test
        @DisplayName("Should default boolean fields when DTO has nulls")
        void shouldDefaultPrimitivesWhenNull() {
            EnvironmentDTO dto = EnvironmentDTO.builder().name("Empty").build();

            Environment entity = mapper.toEntity(dto);

            assertThat(entity.getName()).isEqualTo("Empty");
            assertThat(entity.isCustomZone()).isFalse();
            assertThat(entity.isBinaryFrame()).isFalse();
            assertThat(entity.isAlertOnLowBalance()).isFalse();
        }

        @Test
        @DisplayName("Should return null when DTO is null")
        void shouldReturnNullForNull() {
            assertThat(mapper.toEntity(null)).isNull();
        }
    }

    @Nested
    @DisplayName("updateEntityFromDTO")
    class UpdateEntityFromDTOTests {

        @Test
        @DisplayName("Should overwrite only fields that DTO provides, keeping the rest unchanged")
        void shouldKeepFieldsWhenDtoNull() {
            Environment entity = Environment.builder()
                    .id("env-1")
                    .name("Old")
                    .type(EnvironmentType.STAGING)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .webSocketMiniUrl("wss://mini")
                    .hostUrl("https://host")
                    .apiGatewayUrl("https://api")
                    .appId("app-1")
                    .customZone(true)
                    .binaryFrame(true)
                    .miniZoneName("MiniZone")
                    .encryptionKey("key")
                    .encryptionIv("iv")
                    .alertOnLowBalance(true)
                    .build();

            EnvironmentDTO dto = EnvironmentDTO.builder().name("New").build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.getName()).isEqualTo("New");
            // Other fields untouched
            assertThat(entity.getId()).isEqualTo("env-1");
            assertThat(entity.getType()).isEqualTo(EnvironmentType.STAGING);
            assertThat(entity.getBrandCode()).isEqualTo(BrandCode.G2);
            assertThat(entity.getProductCode()).isEqualTo(ProductCode.P_097);
            assertThat(entity.getWebSocketMiniUrl()).isEqualTo("wss://mini");
            assertThat(entity.getHostUrl()).isEqualTo("https://host");
            assertThat(entity.getApiGatewayUrl()).isEqualTo("https://api");
            assertThat(entity.getAppId()).isEqualTo("app-1");
            assertThat(entity.isCustomZone()).isTrue();
            assertThat(entity.isBinaryFrame()).isTrue();
            assertThat(entity.getMiniZoneName()).isEqualTo("MiniZone");
            assertThat(entity.getEncryptionKey()).isEqualTo("key");
            assertThat(entity.getEncryptionIv()).isEqualTo("iv");
            assertThat(entity.isAlertOnLowBalance()).isTrue();
        }

        @Test
        @DisplayName("Should be a no-op when DTO is null")
        void shouldBeNoOpWhenDtoNull() {
            Environment entity = Environment.builder().id("id").name("Old").build();

            mapper.updateEntityFromDTO(null, entity);

            assertThat(entity.getName()).isEqualTo("Old");
        }
    }
}
