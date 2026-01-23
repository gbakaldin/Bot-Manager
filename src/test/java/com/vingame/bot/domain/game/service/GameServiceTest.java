package com.vingame.bot.domain.game.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
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
    @DisplayName("findByBrandAndProduct")
    class FindByBrandAndProductTests {

        @Test
        @DisplayName("Should delegate to repository")
        void shouldDelegateToRepository() {
            List<Game> games = List.of(Game.builder().id("1").build());
            when(repository.findByBrandCodeAndProductCode(BrandCode.G2, ProductCode.P_097))
                    .thenReturn(games);

            List<Game> result = service.findByBrandAndProduct(BrandCode.G2, ProductCode.P_097);

            assertThat(result).hasSize(1);
            verify(repository).findByBrandCodeAndProductCode(BrandCode.G2, ProductCode.P_097);
        }
    }

    @Nested
    @DisplayName("filter")
    class FilterTests {

        @Test
        @DisplayName("Should filter by brand code")
        void shouldFilterByBrandCode() {
            List<Game> expected = List.of(Game.builder().id("1").brandCode(BrandCode.G2).build());
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(expected);

            GameFilter filter = GameFilter.builder().brandCode(BrandCode.G2).build();

            List<Game> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("brandCode");
        }

        @Test
        @DisplayName("Should filter by game type")
        void shouldFilterByGameType() {
            List<Game> expected = List.of(Game.builder().id("1").gameType(GameType.BETTING_MINI).build());
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(expected);

            GameFilter filter = GameFilter.builder().gameType(GameType.BETTING_MINI).build();

            List<Game> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("gameType");
        }

        @Test
        @DisplayName("Should filter by name (case-insensitive, contains)")
        void shouldFilterByNameContains() {
            List<Game> expected = List.of(Game.builder().id("1").name("TaiXiu Seven").build());
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(expected);

            GameFilter filter = GameFilter.builder().name("taixiu").build();

            List<Game> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("name");
        }

        @Test
        @DisplayName("Should apply multiple filter criteria")
        void shouldApplyMultipleCriteria() {
            List<Game> expected = List.of(
                    Game.builder().id("1").brandCode(BrandCode.G2).productCode(ProductCode.P_097).build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(expected);

            GameFilter filter = GameFilter.builder()
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .build();

            List<Game> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("brandCode");
            assertThat(queryString).contains("productCode");
        }

        @Test
        @DisplayName("Should query with no criteria when filter is empty")
        void shouldReturnAllWhenNoCriteria() {
            List<Game> all = List.of(
                    Game.builder().id("1").build(),
                    Game.builder().id("2").build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Game.class))).thenReturn(all);

            List<Game> result = service.filter(GameFilter.builder().build());

            assertThat(result).hasSize(2);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Game.class));
            assertThat(queryCaptor.getValue().getQueryObject()).isEmpty();
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
