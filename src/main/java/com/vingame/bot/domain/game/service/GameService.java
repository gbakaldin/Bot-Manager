package com.vingame.bot.domain.game.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.repository.GameRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class GameService {

    private final GameRepository repository;
    private final GameMapper mapper;

    public GameService(GameRepository repository, GameMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public Game findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found with id: " + id));
    }

    public List<Game> findByBrandAndProduct(BrandCode brandCode, ProductCode productCode) {
        return repository.findByBrandCodeAndProductCode(brandCode, productCode);
    }

    public List<Game> filter(GameFilter filter) {
        return repository.findAll().stream()
                .filter(g -> filter.getBrandCode() == null || g.getBrandCode() == filter.getBrandCode())
                .filter(g -> filter.getProductCode() == null || g.getProductCode() == filter.getProductCode())
                .filter(g -> filter.getGameType() == null || g.getGameType() == filter.getGameType())
                .filter(g -> filter.getName() == null || g.getName().toLowerCase().contains(filter.getName().toLowerCase()))
                .toList();
    }

    public Game save(Game game) {
        if (game.getId() == null || game.getId().isEmpty()) {
            game.setId(UUID.randomUUID().toString());
        }
        return repository.save(game);
    }

    public Game update(String id, GameDTO updateDTO) {
        Game existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return repository.save(existing);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }
}
