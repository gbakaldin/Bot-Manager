package com.vingame.bot.domain.environment.service;

import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentFilter;
import com.vingame.bot.domain.environment.model.EnvironmentType;
import com.vingame.bot.domain.environment.repository.EnvironmentRepository;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class EnvironmentService {

    private final EnvironmentRepository repository;
    private final EnvironmentMapper mapper;
    private final MongoTemplate mongoTemplate;
    private final GameService gameService;
    private final BotGroupService botGroupService;

    @Value("${bot.periodic-logout.enabled:true}")
    private boolean defaultPeriodicLogoutEnabled;

    @Value("${bot.periodic-logout.interval-minutes:60}")
    private int defaultPeriodicLogoutIntervalMinutes;

    public EnvironmentService(EnvironmentRepository repository, EnvironmentMapper mapper, MongoTemplate mongoTemplate,
                              GameService gameService, @Lazy BotGroupService botGroupService) {
        this.repository = repository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
        this.gameService = gameService;
        this.botGroupService = botGroupService;
    }

    public Environment findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Environment not found"));
    }

    public List<Environment> findAll() {
        return repository.findAll();
    }

    public List<Environment> filter(EnvironmentFilter filter) {
        Query query = new Query();
        if (filter.getType() != null) {
            query.addCriteria(Criteria.where("type").is(filter.getType()));
        }
        if (filter.getName() != null) {
            query.addCriteria(Criteria.where("name").regex(Pattern.quote(filter.getName()), "i"));
        }
        if (filter.getBrandCode() != null) {
            query.addCriteria(Criteria.where("brandCode").is(filter.getBrandCode()));
        }
        if (filter.getProductCode() != null) {
            query.addCriteria(Criteria.where("productCode").is(filter.getProductCode()));
        }
        return mongoTemplate.find(query, Environment.class);
    }

    public Environment save(Environment environment) {
        environment.setHeaders(validateAndMergeWsHeaders(environment.getHeaders()));
        applyPeriodicLogoutDefaults(environment);
        if (environment.getId() == null || environment.getId().isEmpty()) {
            environment.setId(UUID.randomUUID().toString());
        }
        return repository.save(environment);
    }

    private void applyPeriodicLogoutDefaults(Environment environment) {
        if (environment.getPeriodicLogoutEnabled() == null) {
            environment.setPeriodicLogoutEnabled(defaultPeriodicLogoutEnabled);
        }
        if (environment.getPeriodicLogoutIntervalMinutes() == null) {
            environment.setPeriodicLogoutIntervalMinutes(defaultPeriodicLogoutIntervalMinutes);
        }
    }

    public Environment update(String id, EnvironmentDTO updateDTO) {
        if (updateDTO.getHeaders() != null) {
            updateDTO.setHeaders(validateAndMergeWsHeaders(updateDTO.getHeaders()));
        }
        Environment existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return repository.save(existing);
    }

    /**
     * Default WebSocket connection headers. Frontend-provided values override
     * these on a per-key basis; absent keys fall back to the default. {@code Host}
     * and {@code Origin} are intentionally not defaulted — frontend must supply
     * them per environment.
     */
    static final Map<String, String> DEFAULT_WS_HEADERS = Map.of(
            "Connection", "Upgrade",
            "Pragma", "no-cache",
            "Cache-Control", "no-cache",
            "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Upgrade", "websocket",
            "Sec-WebSocket-Version", "13",
            "Accept-Encoding", "gzip, deflate, br, zstd",
            "Accept-Language", "en-US,en;q=0.9",
            "Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits"
    );

    private Map<String, String> validateAndMergeWsHeaders(Map<String, String> provided) {
        if (provided == null || !provided.containsKey("Host") || !provided.containsKey("Origin")) {
            throw new BadRequestException(
                    "WebSocket headers must include both 'Host' and 'Origin' (case-sensitive).");
        }
        Map<String, String> merged = new LinkedHashMap<>(DEFAULT_WS_HEADERS);
        merged.putAll(provided);
        return merged;
    }

    /**
     * Delete an environment, cascading in the order Environment → Games →
     * BotGroups (BOTGROUP_GAME_MANAGEMENT AD-15 / Phase 7).
     * <p>
     * Each game in the env is deleted first via {@link GameService#delete(String)},
     * which itself cascades to the bot groups that reference it (stop → logout →
     * stop-managing → delete). Any bot group still pointing at this environment
     * afterwards — e.g. one whose {@code gameId} is null or resolves to a game in
     * another env — is then deleted via {@link BotGroupService#delete(String)}
     * (already-deleted groups no longer appear in the fresh query, and delete is
     * idempotent). Only once no group or game references the environment is the
     * environment document removed, so no orphan is left behind. No users are
     * deregistered on the bot server — there is no such API and leftover accounts
     * are expected.
     */
    public void delete(String id) {
        for (Game game : gameService.findByEnvironmentId(id)) {
            gameService.delete(game.getId());
        }
        for (BotGroup group : botGroupService.findByEnvironmentId(id)) {
            botGroupService.delete(group.getId());
        }
        repository.deleteById(id);
    }
}
