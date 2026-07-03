package com.vingame.bot.domain.game.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.repository.GameRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    /**
     * List the games for a {@code (brandCode, productCode, environmentId)} scope.
     * Env-scoped per BOTGROUP_GAME_MANAGEMENT AD-4 — the same logical game in two
     * environments is two distinct {@link Game} documents.
     */
    public List<Game> findByBrandProductEnv(BrandCode brandCode, ProductCode productCode, String environmentId) {
        return repository.findByBrandCodeAndProductCodeAndEnvironmentId(brandCode, productCode, environmentId);
    }

    /**
     * Filter games within a {@code (brandCode, productCode, environmentId)} scope.
     * <p>
     * The env criteria keeps a <b>defensive read-side fallback</b> (AD-3): a game
     * whose {@code environmentId} is null/absent matches any env, so an unmigrated
     * doc (or one created between deploy and backfill) stays visible. This should
     * never fire on backfilled data.
     */
    public List<Game> filter(BrandCode brandCode, ProductCode productCode, String environmentId, GameFilter filter) {
        Query query = new Query();
        query.addCriteria(Criteria.where("brandCode").is(brandCode));
        query.addCriteria(Criteria.where("productCode").is(productCode));
        // Defensive null-env fallback: match the scoped env OR a null/absent
        // environmentId. Criteria.is(null) matches both explicit null and missing.
        query.addCriteria(new Criteria().orOperator(
                Criteria.where("environmentId").is(environmentId),
                Criteria.where("environmentId").is(null)));
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
        Instant now = Instant.now();
        if (game.getCreatedAt() == null) {
            game.setCreatedAt(now);
        }
        game.setUpdatedAt(now);
        return repository.save(game);
    }

    public Game update(String id, GameDTO updateDTO) {
        Game existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        // Route through save so updatedAt is (re)stamped on every mutation (AD-16).
        return save(existing);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }
}
