package com.vingame.bot.domain.game.mapper;

import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GameMapper")
class GameMapperTest {

    private final GameMapper mapper = Mappers.getMapper(GameMapper.class);

    @Nested
    @DisplayName("toDTO")
    class ToDTOTests {

        @Test
        @DisplayName("Should map all fields from entity to DTO")
        void shouldMapAllFields() {
            Game entity = Game.builder()
                    .id("game-1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .description("Bau Cua game")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(11005)
                    .numberOfOptions(6)
                    .bettingOptions(List.of(1, 10, 100))
                    .offset(2000)
                    .md5(true)
                    .build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getId()).isEqualTo("game-1");
            assertThat(dto.getBrandCode()).isEqualTo(BrandCode.G2);
            assertThat(dto.getProductCode()).isEqualTo(ProductCode.P_097);
            assertThat(dto.getName()).isEqualTo("BauCua");
            assertThat(dto.getDescription()).isEqualTo("Bau Cua game");
            assertThat(dto.getGameType()).isEqualTo(GameType.BETTING_MINI);
            assertThat(dto.getPluginName()).isEqualTo("MiniGame3");
            assertThat(dto.getGameId()).isEqualTo(11005);
            assertThat(dto.getNumberOfOptions()).isEqualTo(6);
            assertThat(dto.getBettingOptions()).containsExactly(1, 10, 100);
            assertThat(dto.getOffset()).isEqualTo(2000);
            assertThat(dto.getMd5()).isTrue();
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
            GameDTO dto = GameDTO.builder()
                    .id("game-1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .description("desc")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(11005)
                    .numberOfOptions(6)
                    .bettingOptions(List.of(1, 10, 100))
                    .offset(2000)
                    .md5(true)
                    .build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.getId()).isEqualTo("game-1");
            assertThat(entity.getBrandCode()).isEqualTo(BrandCode.G2);
            assertThat(entity.getProductCode()).isEqualTo(ProductCode.P_097);
            assertThat(entity.getName()).isEqualTo("BauCua");
            assertThat(entity.getDescription()).isEqualTo("desc");
            assertThat(entity.getGameType()).isEqualTo(GameType.BETTING_MINI);
            assertThat(entity.getPluginName()).isEqualTo("MiniGame3");
            assertThat(entity.getGameId()).isEqualTo(11005);
            assertThat(entity.getNumberOfOptions()).isEqualTo(6);
            assertThat(entity.getBettingOptions()).containsExactly(1, 10, 100);
            assertThat(entity.getOffset()).isEqualTo(2000);
            assertThat(entity.isMd5()).isTrue();
        }

        @Test
        @DisplayName("Should default numeric/boolean fields when DTO has nulls")
        void shouldDefaultPrimitivesWhenNull() {
            GameDTO dto = GameDTO.builder().name("Empty").build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.getName()).isEqualTo("Empty");
            assertThat(entity.getNumberOfOptions()).isEqualTo(0);
            assertThat(entity.isMd5()).isFalse();
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
            Game entity = Game.builder()
                    .id("game-1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("Old")
                    .description("desc")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(11005)
                    .numberOfOptions(6)
                    .bettingOptions(List.of(1, 2, 3))
                    .offset(2000)
                    .md5(true)
                    .build();

            GameDTO dto = GameDTO.builder().name("New").build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.getName()).isEqualTo("New");
            // Other fields untouched
            assertThat(entity.getId()).isEqualTo("game-1");
            assertThat(entity.getBrandCode()).isEqualTo(BrandCode.G2);
            assertThat(entity.getProductCode()).isEqualTo(ProductCode.P_097);
            assertThat(entity.getDescription()).isEqualTo("desc");
            assertThat(entity.getGameType()).isEqualTo(GameType.BETTING_MINI);
            assertThat(entity.getPluginName()).isEqualTo("MiniGame3");
            assertThat(entity.getGameId()).isEqualTo(11005);
            assertThat(entity.getNumberOfOptions()).isEqualTo(6);
            assertThat(entity.getBettingOptions()).containsExactly(1, 2, 3);
            assertThat(entity.getOffset()).isEqualTo(2000);
            assertThat(entity.isMd5()).isTrue();
        }

        @Test
        @DisplayName("Should be a no-op when DTO is null")
        void shouldBeNoOpWhenDtoNull() {
            Game entity = Game.builder().id("id").name("Old").build();

            mapper.updateEntityFromDTO(null, entity);

            assertThat(entity.getName()).isEqualTo("Old");
        }
    }
}
