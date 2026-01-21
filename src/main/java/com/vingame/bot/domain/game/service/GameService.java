package com.vingame.bot.domain.game.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.model.GameType;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GameService {

    private final List<Game> games = new ArrayList<>();
    private final GameMapper mapper;

    public GameService(GameMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void prepopulateGames() {
        games.add(Game.builder()
                .id("3cda38f9-2c3d-465f-a52a-18ce83207761")
                .brandCode(BrandCode.G2)
                .productCode(ProductCode.P_097)
                .name("TaiXiu Seven")
                .description("")
                .gameType(GameType.BETTING_MINI)
                .pluginName("taiXiuSevenPlugin")
                .gameId(null)
                .numberOfOptions(10)
                .offset(8000)
                .md5(true)
                .build());

        games.add(Game.builder()
                .id("3cda38f9-2c3d-465f-a52a-18ce83207762")
                .brandCode(BrandCode.G4)
                .productCode(ProductCode.P_118)
                .name("BauCua")
                .description("")
                .gameType(GameType.BETTING_MINI)
                .pluginName("gourdCrabPlugin")
                .gameId(null)
                .numberOfOptions(6)
                .offset(2000)
                .md5(false)
                .build());
    }

    public Game findById(String id) {
        return games.stream()
                .filter(g -> g.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Game not found with id: " + id));
    }

    public List<Game> findByBrandAndProduct(BrandCode brandCode, ProductCode productCode) {
        return games.stream()
                .filter(g -> g.getBrandCode() == brandCode)
                .filter(g -> g.getProductCode() == productCode)
                .toList();
    }

    public List<Game> filter(GameFilter filter) {
        return games.stream()
                .filter(g -> filter.getBrandCode() == null || g.getBrandCode() == filter.getBrandCode())
                .filter(g -> filter.getProductCode() == null || g.getProductCode() == filter.getProductCode())
                .filter(g -> filter.getGameType() == null || g.getGameType() == filter.getGameType())
                .filter(g -> filter.getName() == null || g.getName().toLowerCase().contains(filter.getName().toLowerCase()))
                .toList();
    }

    public Game save(Game game) {
        game.setId(UUID.randomUUID().toString());
        games.add(game);
        return game;
    }

    public Game update(String id, GameDTO updateDTO) {
        Game existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return existing;
    }

    public void delete(String id) {
        games.removeIf(g -> g.getId().equals(id));
    }
}
