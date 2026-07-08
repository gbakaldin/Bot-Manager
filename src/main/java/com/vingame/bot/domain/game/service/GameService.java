package com.vingame.bot.domain.game.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import com.vingame.bot.domain.game.repository.GameRepository;
import org.springframework.context.annotation.Lazy;
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
    private final BotGroupService botGroupService;

    public GameService(GameRepository repository, GameMapper mapper, MongoTemplate mongoTemplate,
                       @Lazy BotGroupService botGroupService) {
        this.repository = repository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
        this.botGroupService = botGroupService;
    }

    public Game findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found with id: " + id));
    }

    /**
     * All games belonging to an environment (strict {@code environmentId} match).
     * Backs the Environment→Games cascade delete (BOTGROUP_GAME_MANAGEMENT
     * AD-15 / Phase 7).
     */
    public List<Game> findByEnvironmentId(String environmentId) {
        return repository.findByEnvironmentId(environmentId);
    }

    /**
     * List the games for a {@code (brandCode, productCode, environmentId)} scope.
     * Env-scoped per BOTGROUP_GAME_MANAGEMENT AD-4 — the same logical game in two
     * environments is two distinct {@link Game} documents.
     * <p>
     * Shares the same env criteria as {@link #filter} — including the defensive
     * null-env read-side fallback (AD-3) — so the two read paths cannot drift: an
     * unmigrated (null-env) doc stays visible on the list route during the deploy
     * window exactly as it does on the filter route.
     */
    public List<Game> findByBrandProductEnv(BrandCode brandCode, ProductCode productCode, String environmentId) {
        return mongoTemplate.find(scopeQuery(brandCode, productCode, environmentId), Game.class);
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
        Query query = scopeQuery(brandCode, productCode, environmentId);
        if (filter.getGameType() != null) {
            query.addCriteria(Criteria.where("gameType").is(filter.getGameType()));
        }
        if (filter.getName() != null) {
            query.addCriteria(Criteria.where("name").regex(Pattern.quote(filter.getName()), "i"));
        }
        return mongoTemplate.find(query, Game.class);
    }

    /**
     * Build the shared {@code (brandCode, productCode, environmentId)} scope query
     * used by both env-scoped read paths ({@link #findByBrandProductEnv} and
     * {@link #filter}). Brand and product are always constrained; the env criteria
     * carries the defensive null-env fallback (AD-3) so a game whose
     * {@code environmentId} is null/absent matches any env during the deploy window.
     */
    private Query scopeQuery(BrandCode brandCode, ProductCode productCode, String environmentId) {
        Query query = new Query();
        query.addCriteria(Criteria.where("brandCode").is(brandCode));
        query.addCriteria(Criteria.where("productCode").is(productCode));
        // Defensive null-env fallback: match the scoped env OR a null/absent
        // environmentId. Criteria.is(null) matches both explicit null and missing.
        query.addCriteria(new Criteria().orOperator(
                Criteria.where("environmentId").is(environmentId),
                Criteria.where("environmentId").is(null)));
        return query;
    }

    /**
     * Seed floor of the jackpot pool (JACKPOT_SCALE_AND_RAMP AD-J6) — the known
     * ~500k reset. A configured {@code jackpotCeiling} at or below this makes the
     * scale transfer function degenerate (AD-J5), so we reject it as a clean 400.
     */
    private static final long JACKPOT_SEED_FLOOR = 500_000L;

    public Game save(Game game) {
        validateJackpotScale(game);
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

    /**
     * When jackpot-scale is enabled the ceiling must exceed the ~500k seed floor
     * (JACKPOT_SCALE_AND_RAMP AD-J1/AD-J5) — otherwise the linear seed→ceiling
     * transfer function is degenerate. Disabled games ignore the ceiling.
     */
    private void validateJackpotScale(Game game) {
        if (game.isJackpotScaleEnabled() && game.getJackpotCeiling() <= JACKPOT_SEED_FLOOR) {
            throw new BadRequestException(
                    "jackpotCeiling must be greater than " + JACKPOT_SEED_FLOOR
                            + " when jackpotScaleEnabled is true");
        }
    }

    public Game update(String id, GameDTO updateDTO) {
        Game existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        // Route through save so updatedAt is (re)stamped on every mutation (AD-16).
        return save(existing);
    }

    /**
     * Delete a game, cascading to every bot group that references it
     * (BOTGROUP_GAME_MANAGEMENT AD-15 / Phase 7). {@code BotGroup.gameId} is the
     * Game Mongo {@code _id} (Implementation Note 1), so the referencing groups
     * are those returned by {@code findByGameId(id)}. Each is deleted via
     * {@link BotGroupService#delete(String)} (stop → logout → stop-managing)
     * before the game document itself, so no orphan group is left behind.
     */
    public void delete(String id) {
        for (BotGroup group : botGroupService.findByGameId(id)) {
            botGroupService.delete(group.getId());
        }
        repository.deleteById(id);
    }
}
