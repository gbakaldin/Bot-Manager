package com.vingame.bot.domain.game.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.repository.GameRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GameService {

    private final GameRepository repository;
    private final GameMapper mapper;
    private final MongoTemplate mongoTemplate;

    public GameService(GameRepository repository, GameMapper mapper, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
    }

    public Game findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found with id: " + id));
    }

    public List<Game> findByBrandAndProduct(BrandCode brandCode, ProductCode productCode) {
        return repository.findByBrandCodeAndProductCode(brandCode, productCode);
    }

    public List<Game> filter(GameFilter filter) {
        Query query = new Query();
        if (filter.getBrandCode() != null) {
            query.addCriteria(Criteria.where("brandCode").is(filter.getBrandCode()));
        }
        if (filter.getProductCode() != null) {
            query.addCriteria(Criteria.where("productCode").is(filter.getProductCode()));
        }
        if (filter.getGameType() != null) {
            query.addCriteria(Criteria.where("gameType").is(filter.getGameType()));
        }
        if (filter.getName() != null) {
            query.addCriteria(Criteria.where("name").regex(Pattern.quote(filter.getName()), "i"));
        }
        return mongoTemplate.find(query, Game.class);
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
