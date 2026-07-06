package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.exception.UpstreamRegistrationException;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.validation.BotGroupConfigValidationService;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BotGroupService {

    private final BotGroupRepository repository;
    private final BotGroupMapper mapper;
    private final EnvironmentClientRegistry clientRegistry;
    private final EnvironmentService environmentService;
    private final GameService gameService;
    private final MongoTemplate mongoTemplate;
    private final BotGroupConfigValidationService configValidation;
    private final BotGroupBehaviorService behaviorService;

    public BotGroupService(BotGroupRepository repository, BotGroupMapper mapper,
                           EnvironmentClientRegistry clientRegistry,
                           EnvironmentService environmentService,
                           GameService gameService,
                           MongoTemplate mongoTemplate,
                           BotGroupConfigValidationService configValidation,
                           @Lazy BotGroupBehaviorService behaviorService) {
        this.repository = repository;
        this.mapper = mapper;
        this.clientRegistry = clientRegistry;
        this.environmentService = environmentService;
        this.gameService = gameService;
        this.mongoTemplate = mongoTemplate;
        this.configValidation = configValidation;
        this.behaviorService = behaviorService;
    }

    public BotGroup findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BotGroup not found"));
    }

    public List<BotGroup> findAll() {
        return repository.findAll();
    }

    public List<BotGroup> findByEnvironmentId(String environmentId) {
        return repository.findByEnvironmentId(environmentId);
    }

    public List<BotGroup> findByTargetStatus(BotGroupStatus status) {
        return repository.findByTargetStatus(status);
    }

    /**
     * All bot groups referencing a given game. {@code gameId} is the Game Mongo
     * {@code _id} (BOTGROUP_GAME_MANAGEMENT Implementation Note 1), matching
     * {@code BotGroup.gameId}. Used by the Phase 5 game-sort aggregate enrichment.
     */
    public List<BotGroup> findByGameId(String gameId) {
        return repository.findByGameId(gameId);
    }

    /**
     * Filter bot groups within a single environment. The environment is taken
     * from the path (mandatory) per BOTGROUP_GAME_MANAGEMENT AD-5 — it is no
     * longer a body field. An empty filter body returns every group in that env;
     * {@code name}/{@code gameId} narrow within the scope.
     */
    public List<BotGroup> filter(String environmentId, BotGroupFilter filter) {
        Query query = new Query();
        query.addCriteria(Criteria.where("environmentId").is(environmentId));
        if (filter.getName() != null) {
            query.addCriteria(Criteria.where("name").regex("^" + Pattern.quote(filter.getName()) + "$", "i"));
        }
        if (filter.getGameId() != null) {
            query.addCriteria(Criteria.where("gameId").is(filter.getGameId()));
        }
        return mongoTemplate.find(query, BotGroup.class);
    }

    /**
     * Save or update a bot group with user registration.
     *
     * @see #save(BotGroup, boolean)
     */
    public BotGroup save(BotGroup botGroup) {
        return save(botGroup, false);
    }

    /**
     * Save or update a bot group.
     * <p>
     * If the bot group is NEW (ID is null):
     * - Registers all bot users on the authentication server (unless skipRegistration is true)
     * - Generates a new ID
     * - Persists to MongoDB
     * - Throws exception if user registration completely fails
     * <p>
     * If the bot group ALREADY EXISTS (ID is set):
     * - Skips user registration (users already registered)
     * - Updates the existing document
     *
     * @param botGroup The bot group to save or update
     * @param skipRegistration If true, skips user registration (for migrating existing bots)
     * @return The saved/updated bot group
     * @throws UpstreamRegistrationException if user registration completely fails (new groups only);
     *         mapped to HTTP 502 by {@link com.vingame.bot.common.exception.RestExceptionHandler}.
     * @throws ResourceNotFoundException if the environment doesn't exist
     */
    public BotGroup save(BotGroup botGroup, boolean skipRegistration) {
        boolean isNewGroup = (botGroup.getId() == null || botGroup.getId().isEmpty());

        if (isNewGroup) {
            log.info("Creating new bot group '{}' with {} bots in environment {}",
                     botGroup.getName(), botGroup.getBotCount(), botGroup.getEnvironmentId());

            // Game-type-specific config validation runs before registration so a
            // bad config fails fast with one clean 400 instead of fanning out N
            // wasted auth calls (AD-9). The seam is game-type-agnostic here — the
            // orchestrator resolves the GameType and selects the validator.
            configValidation.validate(botGroup);

            // Referenced game must belong to the group's environment (AD-7).
            validateGameEnvironmentMatch(botGroup);

            if (skipRegistration) {
                log.info("Skipping user registration for bot group '{}' (existing group migration)",
                         botGroup.getName());
            } else {
                validateUsernameLength(botGroup);

                EnvironmentClients clients = clientRegistry.getClients(botGroup.getEnvironmentId());

                log.info("Registering {} users with prefix '{}' and password '{}'",
                         botGroup.getBotCount(), botGroup.getNamePrefix(), botGroup.getPassword());

                UserRegistrationResult registrationResult = clients.getApiGatewayClient().registerUsers(
                        botGroup.getNamePrefix(),
                        botGroup.getPassword(),
                        botGroup.getBotCount());

                if (registrationResult.isCompleteFailure()) {
                    String errorMsg = String.format(
                        "Failed to register any users for bot group '%s'. Errors: %s",
                        botGroup.getName(),
                        String.join("; ", registrationResult.getErrors())
                    );
                    log.error(errorMsg);
                    throw new UpstreamRegistrationException(errorMsg);
                }

                if (registrationResult.isPartialSuccess()) {
                    log.warn("Partial user registration for bot group '{}': {}/{} succeeded. Errors: {}",
                             botGroup.getName(),
                             registrationResult.getSuccessCount(),
                             registrationResult.getTotalRequested(),
                             String.join("; ", registrationResult.getErrors()));
                } else {
                    log.info("Successfully registered all {} users for bot group '{}'",
                             registrationResult.getSuccessCount(),
                             botGroup.getName());
                }
            }

            botGroup.setId(UUID.randomUUID().toString());
            log.info("Bot group '{}' created with ID {}", botGroup.getName(), botGroup.getId());
        } else {
            log.debug("Updating existing bot group '{}' (ID: {})", botGroup.getName(), botGroup.getId());
        }

        // Timestamp stamping (BOTGROUP_GAME_MANAGEMENT AD-14 / AD-16): createdAt is
        // set once on first persist; updatedAt is (re)stamped on every save so the
        // CREATED_TIME / UPDATED_TIME sort keys always have a value.
        Instant now = Instant.now();
        if (botGroup.getCreatedAt() == null) {
            botGroup.setCreatedAt(now);
        }
        botGroup.setUpdatedAt(now);

        return repository.save(botGroup);
    }

    /**
     * Pre-flight check that rejects bot-group creation when the longest generated
     * username ({@code namePrefix + botCount}) would exceed the auth gateway's
     * per-product username cap.
     * <p>
     * Without this guard, every single registration call fans out to the gateway
     * and fails upstream (observed on Tip / P_116, cap 12), surfacing N forwarded
     * gateway errors instead of one clean 400.
     * <p>
     * No-ops when:
     * <ul>
     *   <li>the environment has no {@link ProductCode}, or</li>
     *   <li>the product code has no documented cap
     *       ({@link ProductCode#getUsernameMaxLength()} returns null).</li>
     * </ul>
     *
     * @throws BadRequestException if the longest username exceeds the cap;
     *         mapped to HTTP 400 by
     *         {@link com.vingame.bot.common.exception.RestExceptionHandler}.
     */
    private void validateUsernameLength(BotGroup botGroup) {
        Environment environment = environmentService.findById(botGroup.getEnvironmentId());
        ProductCode productCode = environment.getProductCode();
        if (productCode == null) {
            return;
        }
        Integer cap = productCode.getUsernameMaxLength();
        if (cap == null) {
            return;
        }
        String prefix = botGroup.getNamePrefix();
        int botCount = botGroup.getBotCount();
        int maxLength = (prefix == null ? 0 : prefix.length()) + String.valueOf(botCount).length();
        if (maxLength > cap) {
            throw new BadRequestException(String.format(
                    "Username too long for product %s: prefix '%s' + botCount %d yields max length %d, " +
                    "but product cap is %d. Shorten the prefix or reduce the bot count.",
                    productCode.name(), prefix, botCount, maxLength, cap));
        }
    }

    /**
     * Validates that the game referenced by {@code botGroup.gameId} (the Game
     * Mongo {@code _id}) belongs to the same environment as the group (AD-7).
     * <p>
     * The check is <b>guarded</b>: it is skipped when the game's
     * {@code environmentId} is null, which is the defensive read-side state for an
     * unmigrated Game during the Phase 1 backfill window — it must never block
     * group creation while the migration is in flight (AD-3/AD-7). Once the
     * game is env-scoped, a mismatch is a client error (HTTP 400).
     * <p>
     * No-op when the group carries no {@code gameId}.
     *
     * @throws BadRequestException if the game's non-null {@code environmentId}
     *         differs from the group's {@code environmentId}; mapped to HTTP 400 by
     *         {@link com.vingame.bot.common.exception.RestExceptionHandler}.
     */
    private void validateGameEnvironmentMatch(BotGroup botGroup) {
        String gameId = botGroup.getGameId();
        if (gameId == null) {
            return;
        }
        Game game = gameService.findById(gameId);
        String gameEnvironmentId = game.getEnvironmentId();
        if (gameEnvironmentId != null && !gameEnvironmentId.equals(botGroup.getEnvironmentId())) {
            throw new BadRequestException(String.format(
                    "Game %s belongs to environment %s but bot group '%s' targets environment %s. " +
                    "The referenced game must belong to the group's environment.",
                    gameId, gameEnvironmentId, botGroup.getName(), botGroup.getEnvironmentId()));
        }
    }

    public BotGroup update(String id, BotGroupDTO updateDTO) {
        BotGroup existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        // Validate the post-merge entity (AD-6) so cross-field PATCH rules — e.g.
        // lowering maxBet below the persisted minBet — are caught before save.
        configValidation.validate(existing);
        // Route through save so updatedAt is (re)stamped on every mutation (AD-16);
        // existing has an id so this is the update path (no registration).
        return save(existing);
    }

    /**
     * Delete a bot group, first stopping it and logging every bot out of the
     * game server (BOTGROUP_GAME_MANAGEMENT AD-15 / Phase 7). The
     * stop→logout→stop-managing sequence lives in
     * {@link BotGroupBehaviorService#stopAndLogout(String)} (it owns the runtime
     * map); it is a no-op for a group that is not running, so deleting an
     * already-stopped group is safe. No attempt is made to deregister users on
     * the bot server — there is no such API and leftover accounts are expected.
     */
    public void delete(String id) {
        behaviorService.stopAndLogout(id);
        repository.deleteById(id);
    }
}
