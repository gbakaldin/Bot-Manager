package com.vingame.bot.domain.game.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.model.CrowdCountSemantic;
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
        @DisplayName("Maps jackpotScaleEnabled and jackpotCeiling onto the read DTO (Phase J2)")
        void shouldMapJackpotScaleFields() {
            Game entity = Game.builder()
                    .id("game-jp")
                    .name("BomJackpot")
                    .jackpotScaleEnabled(true)
                    .jackpotCeiling(20_000_000L)
                    .build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getJackpotScaleEnabled()).isTrue();
            assertThat(dto.getJackpotCeiling()).isEqualTo(20_000_000L);
        }

        @Test
        @DisplayName("Emits jackpot defaults (false/0) for a game that never set them")
        void shouldEmitJackpotDefaults() {
            Game entity = Game.builder().id("game-plain").name("Plain").numberOfOptions(2).build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getJackpotScaleEnabled()).isFalse();
            assertThat(dto.getJackpotCeiling()).isEqualTo(0L);
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
        @DisplayName("Maps environmentId, createdAt and updatedAt onto the read DTO (Phase 1)")
        void shouldMapEnvScopeAndTimestamps() {
            java.time.Instant created = java.time.Instant.parse("2026-07-03T00:00:00Z");
            java.time.Instant updated = java.time.Instant.parse("2026-07-04T12:00:00Z");
            Game entity = Game.builder()
                    .id("game-1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .environmentId("env-097")
                    .createdAt(created)
                    .updatedAt(updated)
                    .name("BauCua")
                    .build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getEnvironmentId()).isEqualTo("env-097");
            assertThat(dto.getCreatedAt()).isEqualTo(created);
            assertThat(dto.getUpdatedAt()).isEqualTo(updated);
        }

        @Test
        @DisplayName("Leaves environmentId/createdAt/updatedAt null when the (unmigrated) entity has none")
        void shouldEmitNullEnvScopeForUnmigratedEntity() {
            Game entity = Game.builder().id("legacy").name("Legacy").numberOfOptions(2).build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getEnvironmentId()).isNull();
            assertThat(dto.getCreatedAt()).isNull();
            assertThat(dto.getUpdatedAt()).isNull();
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
        @DisplayName("Maps jackpotScaleEnabled and jackpotCeiling from DTO onto the entity")
        void shouldMapJackpotScaleFields() {
            GameDTO dto = GameDTO.builder()
                    .name("BomJackpot")
                    .jackpotScaleEnabled(true)
                    .jackpotCeiling(20_000_000L)
                    .build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.isJackpotScaleEnabled()).isTrue();
            assertThat(entity.getJackpotCeiling()).isEqualTo(20_000_000L);
        }

        @Test
        @DisplayName("Defaults jackpotScaleEnabled to false and jackpotCeiling to 0 when DTO omits them")
        void shouldDefaultJackpotScaleFields() {
            GameDTO dto = GameDTO.builder().name("Plain").build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.isJackpotScaleEnabled()).isFalse();
            assertThat(entity.getJackpotCeiling()).isEqualTo(0L);
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
        @DisplayName("Replaces jackpotScaleEnabled and jackpotCeiling when present on PATCH")
        void shouldReplaceJackpotScaleFieldsWhenPresent() {
            Game entity = Game.builder()
                    .id("game-1")
                    .name("BomJackpot")
                    .jackpotScaleEnabled(false)
                    .jackpotCeiling(0L)
                    .build();

            GameDTO dto = GameDTO.builder()
                    .jackpotScaleEnabled(true)
                    .jackpotCeiling(15_000_000L)
                    .build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.isJackpotScaleEnabled()).isTrue();
            assertThat(entity.getJackpotCeiling()).isEqualTo(15_000_000L);
        }

        @Test
        @DisplayName("Keeps existing jackpotScaleEnabled and jackpotCeiling when the DTO omits them (PATCH-null = keep)")
        void shouldKeepJackpotScaleFieldsWhenDtoNull() {
            Game entity = Game.builder()
                    .id("game-1")
                    .name("BomJackpot")
                    .jackpotScaleEnabled(true)
                    .jackpotCeiling(20_000_000L)
                    .build();

            // A PATCH touching only the name must not reset the boxed jackpot fields
            // to their primitive defaults.
            GameDTO dto = GameDTO.builder().name("Renamed").build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.getName()).isEqualTo("Renamed");
            assertThat(entity.isJackpotScaleEnabled()).isTrue();
            assertThat(entity.getJackpotCeiling()).isEqualTo(20_000_000L);
        }

        @Test
        @DisplayName("Is a no-op when DTO is null")
        void shouldBeNoOpWhenDtoNull() {
            Game entity = Game.builder().id("id").name("Old").build();

            mapper.updateEntityFromDTO(null, entity);

            assertThat(entity.getName()).isEqualTo("Old");
        }

        @Test
        @DisplayName("Never overwrites environmentId/createdAt/updatedAt from the write DTO (path + service authoritative)")
        void shouldNotOverwriteEnvScopeOrTimestamps() {
            java.time.Instant created = java.time.Instant.parse("2026-01-01T00:00:00Z");
            java.time.Instant updated = java.time.Instant.parse("2026-01-02T00:00:00Z");
            Game entity = Game.builder()
                    .id("game-1")
                    .name("Old")
                    .environmentId("env-original")
                    .createdAt(created)
                    .updatedAt(updated)
                    .build();

            // A DTO that (mistakenly or maliciously) carries these read-side-only
            // fields must NOT be able to move a game between environments or rewrite
            // its audit stamps. The controller path sets environmentId; GameService
            // stamps createdAt/updatedAt. The mapper must ignore all three.
            GameDTO dto = GameDTO.builder()
                    .name("New")
                    .environmentId("env-hijack")
                    .createdAt(java.time.Instant.parse("1999-01-01T00:00:00Z"))
                    .updatedAt(java.time.Instant.parse("1999-01-02T00:00:00Z"))
                    .build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.getName()).isEqualTo("New");
            assertThat(entity.getEnvironmentId()).isEqualTo("env-original");
            assertThat(entity.getCreatedAt()).isEqualTo(created);
            assertThat(entity.getUpdatedAt()).isEqualTo(updated);
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

    @Nested
    @DisplayName("crowdCountSemantic mapping (CROWD_AWARE_COORDINATION Phase 1)")
    class CrowdCountSemanticTests {

        @Test
        @DisplayName("toDTO emits the semantic from the entity")
        void toDtoEmitsSemantic() {
            Game entity = Game.builder()
                    .id("game-1")
                    .name("BomBauCua")
                    .crowdCountSemantic(CrowdCountSemantic.BETS)
                    .build();

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getCrowdCountSemantic()).isEqualTo(CrowdCountSemantic.BETS);
        }

        @Test
        @DisplayName("toDTO emits UNKNOWN when the entity value is null (legacy Mongo doc)")
        void toDtoEmitsUnknownForNullEntity() {
            // A legacy doc hydrated from Mongo has no crowdCountSemantic — the
            // @Builder.Default does not apply, so the field is null. The read path
            // must surface the UNKNOWN fail-safe, not a null.
            Game entity = new Game();
            entity.setId("game-legacy");
            entity.setName("LegacyGame");
            entity.setCrowdCountSemantic(null);

            GameDTO dto = mapper.toDTO(entity);

            assertThat(dto.getCrowdCountSemantic()).isEqualTo(CrowdCountSemantic.UNKNOWN);
        }

        @Test
        @DisplayName("toEntity persists the semantic from the DTO")
        void toEntityPersistsSemantic() {
            GameDTO dto = GameDTO.builder()
                    .name("BomBauCua")
                    .crowdCountSemantic(CrowdCountSemantic.PLAYERS)
                    .build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.getCrowdCountSemantic()).isEqualTo(CrowdCountSemantic.PLAYERS);
        }

        @Test
        @DisplayName("toEntity defaults the semantic to UNKNOWN when the DTO omits it")
        void toEntityDefaultsSemanticWhenNull() {
            GameDTO dto = GameDTO.builder().name("BomBauCua").build();

            Game entity = mapper.toEntity(dto);

            assertThat(entity.getCrowdCountSemantic()).isEqualTo(CrowdCountSemantic.UNKNOWN);
        }

        @Test
        @DisplayName("PATCH full-replaces the semantic when the DTO supplies it")
        void patchFullReplacesSemantic() {
            Game entity = Game.builder()
                    .id("game-1")
                    .name("BomBauCua")
                    .crowdCountSemantic(CrowdCountSemantic.UNKNOWN)
                    .build();
            GameDTO patch = GameDTO.builder()
                    .crowdCountSemantic(CrowdCountSemantic.BETS)
                    .build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.getCrowdCountSemantic()).isEqualTo(CrowdCountSemantic.BETS);
        }

        @Test
        @DisplayName("PATCH retains the existing semantic when the DTO omits it (PATCH-null = keep)")
        void patchKeepsExistingWhenDtoNull() {
            Game entity = Game.builder()
                    .id("game-1")
                    .name("BomBauCua")
                    .crowdCountSemantic(CrowdCountSemantic.PLAYERS)
                    .build();
            GameDTO patch = GameDTO.builder().name("Renamed").build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.getName()).isEqualTo("Renamed");
            assertThat(entity.getCrowdCountSemantic()).isEqualTo(CrowdCountSemantic.PLAYERS);
        }

        @Test
        @DisplayName("Jackson (de)serializes the enum by name on the DTO (mirrors GameType)")
        void jacksonRoundTripsEnumByName() throws Exception {
            ObjectMapper json = new ObjectMapper();

            GameDTO dto = GameDTO.builder()
                    .name("BomBauCua")
                    .crowdCountSemantic(CrowdCountSemantic.BETS)
                    .build();

            String serialized = json.writeValueAsString(dto);
            assertThat(serialized).contains("\"crowdCountSemantic\":\"BETS\"");

            GameDTO parsed = json.readValue(
                    "{\"name\":\"BomBauCua\",\"crowdCountSemantic\":\"PLAYERS\"}", GameDTO.class);
            assertThat(parsed.getCrowdCountSemantic()).isEqualTo(CrowdCountSemantic.PLAYERS);
        }
    }
}
