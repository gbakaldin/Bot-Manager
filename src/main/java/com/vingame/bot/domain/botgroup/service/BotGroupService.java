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
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

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
    private final MongoTemplate mongoTemplate;
    private final BotGroupConfigValidationService configValidation;

    public BotGroupService(BotGroupRepository repository, BotGroupMapper mapper,
                           EnvironmentClientRegistry clientRegistry,
                           EnvironmentService environmentService,
                           MongoTemplate mongoTemplate,
                           BotGroupConfigValidationService configValidation) {
        this.repository = repository;
        this.mapper = mapper;
        this.clientRegistry = clientRegistry;
        this.environmentService = environmentService;
        this.mongoTemplate = mongoTemplate;
        this.configValidation = configValidation;
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

    public List<BotGroup> filter(BotGroupFilter filter) {
        Query query = new Query();
        if (filter.getEnvironmentId() != null) {
            query.addCriteria(Criteria.where("environmentId").is(filter.getEnvironmentId()));
        }
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

    public BotGroup update(String id, BotGroupDTO updateDTO) {
        BotGroup existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        // Validate the post-merge entity (AD-6) so cross-field PATCH rules — e.g.
        // lowering maxBet below the persisted minBet — are caught before save.
        configValidation.validate(existing);
        return repository.save(existing);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }
}
