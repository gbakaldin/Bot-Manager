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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GameMapper")
class GameMapperTest {

    private final GameMapper mapper = Mappers.getMapper(GameMapper.class);

    @Nested
    @DisplayName("toDTO")
    class ToDTOTests {

        @Test
        @DisplayName("Maps all fields and emits optionAffinities from entity")
        void shouldMapAllFields() {
            Map<Integer, Integer> affinities = Map.of(0, 1, 1, 1, 2, 1);
            Game entity = Game.builder()
                    .id("game-1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .description("Bau Cua game")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(11005)
                    .optionAffinities(affinities)
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
            assertThat(dto.getOptionAffinities()).isEqualTo(affinities);
            assertThat(dto.getOffset()).isEqualTo(2000);
            assertThat(dto.getMd5()).isTrue();
            // Convenience shorthand must never leak into the read response.
            assertThat(dto.getNumberOfOptions()).isNull();
        }

        @Test
        @DisplayName("Synthesizes optionAffinities from legacy numberOfOptions on read for unmigrated docs")
        void shouldSynthesizeAffinitiesFromLegacyOnRead() {
            // Simulates a Mongo doc that pre-dates Phase 1 — only numberOfOptions.
            Game entity = Game.builder()
                    .id("legacy-1")
                    .name("LegacyGame")
                    .numberOfOptions(4)
                    .build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getOptionAffinities()).hasSize(4).containsKeys(0, 1, 2, 3);
            assertThat(dto.getOptionAffinities().values()).containsOnly(1);
        }

        @Test
        @DisplayName("Returns null when entity is null")
        void shouldReturnNullForNull() {
            assertThat(mapper.toDTO(null)).isNull();
        }

        @Test
        @DisplayName("Surfaces a null optionAffinities (not a crash) when entity is misconfigured")
        void shouldNotCrashOnMisconfiguredEntity() {
            // A Game with neither field set is misconfigured. toDTO must still
            // return a usable response so the operator can spot it via the
            // missing/empty affinity map rather than seeing a 500.
            Game entity = Game.builder().id("broken").name("Broken").build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto).isNotNull();
            assertThat(dto.getOptionAffinities()).isNull();
        }
    }

    @Nested
    @DisplayName("toEntity")
    class ToEntityTests {

        @Test
        @DisplayName("Uses optionAffinities from DTO as-is when present")
        void shouldUseExplicitAffinities() {
            Map<Integer, Integer> affinities = Map.of(0, 2, 1, 3);
            GameDTO dto = GameDTO.builder()
                    .id("game-1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .description("desc")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(11005)
                    .optionAffinities(affinities)
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
            assertThat(entity.getOptionAffinities()).isEqualTo(affinities);
            assertThat(entity.getOffset()).isEqualTo(2000);
            assertThat(entity.isMd5()).isTrue();
        }

        @Test
        @DisplayName("Expands numberOfOptions shorthand into a flat-prior optionAffinities map")
        void shouldExpandNumberOfOptionsShorthand() {
            GameDTO dto = GameDTO.builder()
                    .name("CreateMe")
                    .numberOfOptions(4)
                    .build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.getOptionAffinities())
                    .hasSize(4)
                    .containsEntry(0, 1)
                    .containsEntry(1, 1)
                    .containsEntry(2, 1)
                    .containsEntry(3, 1);
        }

        @Test
        @DisplayName("optionAffinities wins over numberOfOptions when both provided on create")
        void shouldPreferAffinitiesOverShorthandOnCreate() {
            Map<Integer, Integer> explicit = Map.of(0, 5);
            GameDTO dto = GameDTO.builder()
                    .name("CreateMe")
                    .optionAffinities(explicit)
                    .numberOfOptions(99)
                    .build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.getOptionAffinities()).isEqualTo(explicit);
        }

        @Test
        @DisplayName("Defaults md5 to false and leaves optionAffinities null when DTO carries neither shape")
        void shouldDefaultWhenNoAffinityInfoProvided() {
            GameDTO dto = GameDTO.builder().name("Empty").build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.getName()).isEqualTo("Empty");
            assertThat(entity.getOptionAffinities()).isNull();
            assertThat(entity.isMd5()).isFalse();
        }

        @Test
        @DisplayName("Returns null when DTO is null")
        void shouldReturnNullForNull() {
            assertThat(mapper.toEntity(null)).isNull();
        }
    }

    @Nested
    @DisplayName("updateEntityFromDTO")
    class UpdateEntityFromDTOTests {

        @Test
        @DisplayName("Keeps existing values for fields the DTO does not provide")
        void shouldKeepFieldsWhenDtoNull() {
            Map<Integer, Integer> existing = Map.of(0, 1, 1, 1, 2, 1);
            Game entity = Game.builder()
                    .id("game-1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("Old")
                    .description("desc")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(11005)
                    .optionAffinities(existing)
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
            assertThat(entity.getOptionAffinities()).isEqualTo(existing);
            assertThat(entity.getOffset()).isEqualTo(2000);
            assertThat(entity.isMd5()).isTrue();
        }

        @Test
        @DisplayName("PATCH on optionAffinities is full-replace (not field-merge)")
        void shouldFullReplaceOptionAffinitiesOnPatch() {
            Game entity = Game.builder()
                    .id("game-1")
                    .name("BauCua")
                    .optionAffinities(Map.of(0, 1, 1, 1, 2, 1, 3, 1))
                    .build();

            GameDTO dto = GameDTO.builder()
                    .optionAffinities(Map.of(0, 2, 1, 1))
                    .build();

            mapper.updateEntityFromDTO(dto, entity);

            // Full-replace: keys 2 and 3 are gone; only the DTO's map remains.
            assertThat(entity.getOptionAffinities())
                    .hasSize(2)
                    .containsEntry(0, 2)
                    .containsEntry(1, 1)
                    .doesNotContainKey(2)
                    .doesNotContainKey(3);
        }

        @Test
        @DisplayName("Ignores numberOfOptions shorthand on PATCH (create-only convenience)")
        void shouldIgnoreNumberOfOptionsOnPatch() {
            Map<Integer, Integer> existing = Map.of(0, 1, 1, 1);
            Game entity = Game.builder()
                    .id("game-1")
                    .name("BauCua")
                    .optionAffinities(existing)
                    .build();

            // Caller mistakenly used the create shorthand on PATCH — must not
            // alter the existing affinity map.
            GameDTO dto = GameDTO.builder().numberOfOptions(7).build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.getOptionAffinities()).isEqualTo(existing);
        }

        @Test
        @DisplayName("Is a no-op when DTO is null")
        void shouldBeNoOpWhenDtoNull() {
            Game entity = Game.builder().id("id").name("Old").build();

            mapper.updateEntityFromDTO(null, entity);

            assertThat(entity.getName()).isEqualTo("Old");
        }
    }

    @Nested
    @DisplayName("SLOT game type")
    class SlotGameTypeTests {

        /**
         * SLOT_MACHINE_BOT Phase 3: confirms a SLOT game shaped as
         * {@code {gameType:SLOT, gameId:<gid>, productCode, pluginName}} round-trips
         * through the existing DTO/mapper with no new fields. The {@code gameId}
         * carries the slot {@code gid} (AD-2); winline count and bet values are
         * server-sourced from the 1300 response (AD-8/AD-11), not config — so there
         * is intentionally nothing slot-specific to map.
         */
        @Test
        @DisplayName("Round-trips gameType=SLOT and gameId (gid) through DTO → entity → DTO")
        void shouldRoundTripSlotGame() {
            GameDTO in = GameDTO.builder()
                    .gameType(GameType.SLOT)
                    .gameId(204)
                    .productCode(ProductCode.P_116)
                    .pluginName("Tip")
                    .name("SlotTipTest")
                    .build();

            Game entity = mapper.toEntity(in);

            assertThat(entity.getGameType()).isEqualTo(GameType.SLOT);
            assertThat(entity.getGameId()).isEqualTo(204);
            assertThat(entity.getProductCode()).isEqualTo(ProductCode.P_116);
            assertThat(entity.getPluginName()).isEqualTo("Tip");
            // No slot-specific config: optionAffinities stays null (server-sourced
            // winline count + bet values are not modeled here).
            assertThat(entity.getOptionAffinities()).isNull();

            GameDTO out = mapper.toDTO(entity);

            assertThat(out.getGameType()).isEqualTo(GameType.SLOT);
            assertThat(out.getGameId()).isEqualTo(204);
            assertThat(out.getProductCode()).isEqualTo(ProductCode.P_116);
            assertThat(out.getPluginName()).isEqualTo("Tip");
            assertThat(out.getName()).isEqualTo("SlotTipTest");
        }
    }
}
