package com.vingame.bot.domain.game.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.repository.GameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GameService")
class GameServiceTest {

    @Mock
    private GameRepository repository;

    @Mock
    private GameMapper mapper;

    @Mock
    private MongoTemplate mongoTemplate;

    @Captor
    private ArgumentCaptor<Query> queryCaptor;

    @InjectMocks
    private GameService service;

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("Should return game when found")
        void shouldReturnGameWhenFound() {
            Game game = Game.builder().id("game-1").name("BauCua").build();
            when(repository.findById("game-1")).thenReturn(Optional.of(game));

            Game result = service.findById("game-1");

            assertThat(result).isEqualTo(game);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("missing"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Nested
    @DisplayName("findByBrandProductEnv")
    class FindByBrandProductEnvTests {

        @Test
        @DisplayName("Should scope by brand, product and env (with the same null-env fallback as filter)")
        void shouldScopeBrandProductEnv() {
            List<Game> games = List.of(Game.builder().id("1").build());
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(games);

            List<Game> result = service.findByBrandProductEnv(BrandCode.G2, ProductCode.P_097, "env-097");

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            Query capturedQuery = queryCaptor.getValue();
            assertThat(capturedQuery.getQueryObject().get("brandCode")).isEqualTo(BrandCode.G2);
            assertThat(capturedQuery.getQueryObject().get("productCode")).isEqualTo(ProductCode.P_097);
            // Same defensive null-env $or fallback that filter carries (AD-3) — the
            // two read paths must not drift.
            Object orClause = capturedQuery.getQueryObject().get("$or");
            assertThat(orClause).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<org.bson.Document> branches = (List<org.bson.Document>) orClause;
            assertThat(branches).hasSize(2);
            assertThat(branches).anySatisfy(b ->
                    assertThat(b.get("environmentId")).isEqualTo("env-097"));
            assertThat(branches).anySatisfy(b -> {
                assertThat(b.containsKey("environmentId")).isTrue();
                assertThat(b.get("environmentId")).isNull();
            });
        }

        @Test
        @DisplayName("List route returns unmigrated (null-env) games for a scope during the deploy window")
        void listRouteReturnsNullEnvGames() {
            // Before the Releaser runs the backfill, every game still has
            // environmentId == null. The list route must surface them (same as
            // filter) rather than returning an empty array (AD-3).
            Game unmigrated = Game.builder().id("legacy").brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097).environmentId(null).build();
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(List.of(unmigrated));

            List<Game> result = service.findByBrandProductEnv(BrandCode.G2, ProductCode.P_097, "env-097");

            assertThat(result).containsExactly(unmigrated);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            assertThat(queryCaptor.getValue().toString()).contains("environmentId").contains("$or");
        }
    }

    @Nested
    @DisplayName("filter (env-scoped)")
    class FilterTests {

        @Test
        @DisplayName("Should always constrain brand, product and env (with null-env fallback)")
        void shouldConstrainBrandProductEnv() {
            List<Game> expected = List.of(Game.builder().id("1").brandCode(BrandCode.G2).build());
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(expected);

            List<Game> result = service.filter(
                    BrandCode.G2, ProductCode.P_097, "env-097", GameFilter.builder().build());

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            Query capturedQuery = queryCaptor.getValue();
            assertThat(capturedQuery.getQueryObject().get("brandCode")).isEqualTo(BrandCode.G2);
            assertThat(capturedQuery.getQueryObject().get("productCode")).isEqualTo(ProductCode.P_097);
            // env is an $or of {environmentId: env} and {environmentId: null} (the
            // defensive null-env read-side fallback, AD-3).
            String queryString = capturedQuery.toString();
            assertThat(queryString).contains("environmentId");
            assertThat(queryString).contains("$or");
        }

        @Test
        @DisplayName("Null-env fallback: an unmigrated (null environmentId) game still matches")
        void nullEnvFallbackMatches() {
            // A game persisted before the backfill (environmentId absent) — the $or
            // branch {environmentId: null} keeps it visible in the scoped filter.
            Game unmigrated = Game.builder().id("legacy").brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097).environmentId(null).build();
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(List.of(unmigrated));

            List<Game> result = service.filter(
                    BrandCode.G2, ProductCode.P_097, "env-097", GameFilter.builder().build());

            assertThat(result).containsExactly(unmigrated);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            // The env criterion must include a null branch so the fallback is real.
            assertThat(queryCaptor.getValue().toString()).contains("environmentId").contains("$or");
        }

        @Test
        @DisplayName("Env criterion is an $or of {environmentId: env} and {environmentId: null} (structural)")
        void envCriterionOrStructure() {
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(List.of());

            service.filter(BrandCode.G2, ProductCode.P_097, "env-097", GameFilter.builder().build());

            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            Object orClause = queryCaptor.getValue().getQueryObject().get("$or");
            assertThat(orClause).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<org.bson.Document> branches = (List<org.bson.Document>) orClause;
            // Exactly two branches: the scoped env, and the defensive null-env fallback.
            assertThat(branches).hasSize(2);
            assertThat(branches).anySatisfy(b ->
                    assertThat(b.get("environmentId")).isEqualTo("env-097"));
            // The fallback branch must carry an explicit null (matches null OR absent
            // in Mongo) — this is what keeps unmigrated docs visible (AD-3).
            assertThat(branches).anySatisfy(b -> {
                assertThat(b.containsKey("environmentId")).isTrue();
                assertThat(b.get("environmentId")).isNull();
            });
        }

        @Test
        @DisplayName("Should filter by game type")
        void shouldFilterByGameType() {
            List<Game> expected = List.of(Game.builder().id("1").gameType(GameType.BETTING_MINI).build());
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(expected);

            GameFilter filter = GameFilter.builder().gameType(GameType.BETTING_MINI).build();

            List<Game> result = service.filter(BrandCode.G2, ProductCode.P_097, "env-097", filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            Query capturedQuery = queryCaptor.getValue();
            assertThat(capturedQuery.toString()).contains("gameType");
            assertThat(capturedQuery.getQueryObject().get("gameType")).isEqualTo(GameType.BETTING_MINI);
        }

        @Test
        @DisplayName("Should filter by name (case-insensitive, contains)")
        void shouldFilterByNameContains() {
            List<Game> expected = List.of(Game.builder().id("1").name("TaiXiu Seven").build());
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(expected);

            GameFilter filter = GameFilter.builder().name("taixiu").build();

            List<Game> result = service.filter(BrandCode.G3, ProductCode.P_114, "env-114", filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            Query capturedQuery = queryCaptor.getValue();
            Object nameCriterion = capturedQuery.getQueryObject().get("name");
            assertThat(nameCriterion).isInstanceOf(Pattern.class);
            Pattern namePattern = (Pattern) nameCriterion;
            assertThat(namePattern.flags() & Pattern.CASE_INSENSITIVE).isEqualTo(Pattern.CASE_INSENSITIVE);
            assertThat(namePattern.pattern()).isEqualTo(Pattern.quote("taixiu"));
        }

        @Test
        @DisplayName("Empty filter body still scopes to brand/product/env only")
        void emptyBodyScopesToPathOnly() {
            List<Game> all = List.of(
                    Game.builder().id("1").build(),
                    Game.builder().id("2").build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(all);

            List<Game> result = service.filter(
                    BrandCode.G4, ProductCode.P_118, "env-118", GameFilter.builder().build());

            assertThat(result).hasSize(2);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            Query capturedQuery = queryCaptor.getValue();
            // No gameType / name narrowing, but brand/product/env are always present.
            assertThat(capturedQuery.getQueryObject().get("brandCode")).isEqualTo(BrandCode.G4);
            assertThat(capturedQuery.getQueryObject().get("productCode")).isEqualTo(ProductCode.P_118);
            assertThat(capturedQuery.getQueryObject().get("gameType")).isNull();
            assertThat(capturedQuery.getQueryObject().get("name")).isNull();
        }
    }

    @Nested
    @DisplayName("save")
    class SaveTests {

        @Test
        @DisplayName("Should generate UUID when ID is null")
        void shouldGenerateIdWhenNull() {
            Game game = Game.builder().name("New Game").build();
            when(repository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

            Game result = service.save(game);

            assertThat(result.getId()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("Should generate UUID when ID is empty")
        void shouldGenerateIdWhenEmpty() {
            Game game = Game.builder().id("").name("New Game").build();
            when(repository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

            Game result = service.save(game);

            assertThat(result.getId()).isNotEmpty();
        }

        @Test
        @DisplayName("Should keep existing ID when set")
        void shouldKeepExistingId() {
            Game game = Game.builder().id("existing-id").build();
            when(repository.save(game)).thenReturn(game);

            Game result = service.save(game);

            assertThat(result.getId()).isEqualTo("existing-id");
        }

        @Test
        @DisplayName("Should stamp createdAt and updatedAt when createdAt is null (AD-14/AD-16)")
        void shouldStampTimestampsOnCreate() {
            Game game = Game.builder().id("g").name("New Game").build();
            when(repository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

            Game result = service.save(game);

            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isEqualTo(result.getCreatedAt());
        }

        @Test
        @DisplayName("Should preserve existing createdAt and re-stamp updatedAt on re-save")
        void shouldPreserveCreatedAtAndRestampUpdatedAt() {
            java.time.Instant created = java.time.Instant.parse("2020-01-01T00:00:00Z");
            Game game = Game.builder().id("g").createdAt(created).build();
            when(repository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

            Game result = service.save(game);

            assertThat(result.getCreatedAt()).isEqualTo(created);
            assertThat(result.getUpdatedAt()).isNotNull().isAfter(created);
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("Should update and persist entity")
        void shouldUpdateAndPersist() {
            Game existing = Game.builder().id("game-1").name("Old").build();
            GameDTO dto = GameDTO.builder().name("New").build();

            when(repository.findById("game-1")).thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            service.update("game-1", dto);

            verify(mapper).updateEntityFromDTO(dto, existing);
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update("missing", GameDTO.builder().build()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("update routes through save: re-stamps updatedAt and preserves createdAt (AD-16)")
        void updateRestampsUpdatedAtAndPreservesCreatedAt() {
            java.time.Instant created = java.time.Instant.parse("2020-01-01T00:00:00Z");
            java.time.Instant staleUpdated = java.time.Instant.parse("2020-06-01T00:00:00Z");
            Game existing = Game.builder()
                    .id("game-1")
                    .name("Old")
                    .createdAt(created)
                    .updatedAt(staleUpdated)
                    .build();
            GameDTO dto = GameDTO.builder().name("New").build();

            when(repository.findById("game-1")).thenReturn(Optional.of(existing));
            when(repository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

            Game result = service.update("game-1", dto);

            // createdAt is stamped once and must survive an update untouched...
            assertThat(result.getCreatedAt()).isEqualTo(created);
            // ...while updatedAt is re-stamped on this mutation (no longer the stale value).
            assertThat(result.getUpdatedAt()).isNotNull().isAfter(staleUpdated);
        }

        @Test
        @DisplayName("update stamps updatedAt even when the existing doc had none (unmigrated)")
        void updateStampsUpdatedAtWhenPreviouslyNull() {
            Game existing = Game.builder().id("game-1").name("Old").build(); // no timestamps
            GameDTO dto = GameDTO.builder().name("New").build();

            when(repository.findById("game-1")).thenReturn(Optional.of(existing));
            when(repository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

            Game result = service.update("game-1", dto);

            // First save through the update path stamps both; createdAt == updatedAt here.
            assertThat(result.getUpdatedAt()).isNotNull();
            assertThat(result.getCreatedAt()).isNotNull().isEqualTo(result.getUpdatedAt());
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should call repository.deleteById")
        void shouldCallRepositoryDelete() {
            service.delete("game-1");

            verify(repository).deleteById("game-1");
        }
    }
}
