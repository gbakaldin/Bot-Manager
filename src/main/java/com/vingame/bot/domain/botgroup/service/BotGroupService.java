package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
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
    private final MongoTemplate mongoTemplate;

    public BotGroupService(BotGroupRepository repository, BotGroupMapper mapper,
                           EnvironmentClientRegistry clientRegistry, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mapper = mapper;
        this.clientRegistry = clientRegistry;
        this.mongoTemplate = mongoTemplate;
    }

    public BotGroup findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BotGroup not found"));
    }

    public List<BotGroup> findAll() {
        return repository.findAll();
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
     * Save or update a bot group.
     * <p>
     * If the bot group is NEW (ID is null):
     * - Registers all bot users on the authentication server
     * - Generates a new ID
     * - Persists to MongoDB
     * - Throws exception if user registration completely fails
     * <p>
     * If the bot group ALREADY EXISTS (ID is set):
     * - Skips user registration (users already registered)
     * - Updates the existing document
     *
     * @param botGroup The bot group to save or update
     * @return The saved/updated bot group
     * @throws IllegalStateException if user registration completely fails (new groups only)
     * @throws ResourceNotFoundException if the environment doesn't exist
     */
    public BotGroup save(BotGroup botGroup) {
        boolean isNewGroup = (botGroup.getId() == null || botGroup.getId().isEmpty());

        if (isNewGroup) {
            log.info("Creating new bot group '{}' with {} bots in environment {}",
                     botGroup.getName(), botGroup.getBotCount(), botGroup.getEnvironmentId());

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
                throw new IllegalStateException(errorMsg);
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

            botGroup.setId(UUID.randomUUID().toString());
            log.info("Bot group '{}' created with ID {}", botGroup.getName(), botGroup.getId());
        } else {
            log.debug("Updating existing bot group '{}' (ID: {})", botGroup.getName(), botGroup.getId());
        }

        return repository.save(botGroup);
    }

    public BotGroup update(String id, BotGroupDTO updateDTO) {
        BotGroup existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return repository.save(existing);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }
}
