package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.exception.UpstreamRegistrationException;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.validation.BotGroupConfigValidationService;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
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
    private final EnvironmentService environmentService;
    private final GameService gameService;
    private final MongoTemplate mongoTemplate;
    private final BotGroupConfigValidationService configValidation;
    private final BotGroupBehaviorService behaviorService;
    private final RegistrationWorker registrationWorker;

    public BotGroupService(BotGroupRepository repository, BotGroupMapper mapper,
                           EnvironmentService environmentService,
                           GameService gameService,
                           MongoTemplate mongoTemplate,
                           BotGroupConfigValidationService configValidation,
                           @Lazy BotGroupBehaviorService behaviorService,
                           RegistrationWorker registrationWorker) {
        this.repository = repository;
        this.mapper = mapper;
        this.environmentService = environmentService;
        this.gameService = gameService;
        this.mongoTemplate = mongoTemplate;
        this.configValidation = configValidation;
        this.behaviorService = behaviorService;
        this.registrationWorker = registrationWorker;
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
            query.addCriteria(Criteria.where("name").regex(Pattern.quote(filter.getName()), "i"));
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
     * If the bot group is NEW (ID is null): validates, generates an id, persists, and — unless
     * {@code skipRegistration} — marks it {@code REGISTRATION_PENDING} so
     * {@link RegistrationWorker} creates its accounts in the background. <b>No upstream call is
     * made on this thread</b> (GATEWAY_REQUEST_BUDGET A2, Phase 4); the 200 means the record
     * exists, not that the accounts do.
     * <p>
     * If the bot group ALREADY EXISTS (ID is set): updates the document. No registration state is
     * touched here — {@link #update(String, BotGroupDTO)} owns the one PATCH that can restart
     * registration (a {@code botCount} raise, A2.7).
     *
     * @param botGroup The bot group to save or update
     * @param skipRegistration If true, no accounts are registered at all (migrating existing bots)
     * @return The saved/updated bot group, carrying its generated id
     * @throws ResourceNotFoundException if the environment doesn't exist
     * @throws BadRequestException on a config or username-length problem — still answered
     *         synchronously, because those are decidable without asking the gateway anything
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
                // existingGroup=true. No upstream call, so there is nothing to pace and nothing
                // to resume: the group is left with no registration state at all, exactly as it
                // has been since the migration flag existed (A2.8).
                log.info("Skipping user registration for bot group '{}' (existing group migration)",
                         botGroup.getName());
            } else {
                validateUsernameLength(botGroup);

                // ASYNCHRONOUS from Phase 4 (A2). Nothing upstream happens on this thread: the
                // group is persisted as REGISTRATION_PENDING at 0/botCount and RegistrationWorker
                // creates the accounts one at a time, off the request thread.
                //
                // The predecessor called registerUsers(prefix, password, botCount) right here,
                // synchronously, and waited for all of it. Under the Cloudflare budget that is not
                // a slow endpoint, it is an impossible one: a 200-account create was a worst case
                // of ~5 hours parked on a Tomcat worker (A25.2), and a restart in the middle left
                // a group whose document said nothing about how many accounts existed.
                //
                // What a client sees: 200 with targetStatus "REGISTRATION_PENDING" and
                // registeredCount 0, then GET /{id} or GET /{id}/status for progress. Same
                // 200-means-accepted contract as /start and /restart (A3).
                botGroup.setRegistrationState(RegistrationState.PENDING);
                botGroup.setRegisteredCount(0);
                botGroup.setNamedCount(0);
                botGroup.setRegistrationError(null);
                log.info("Bot group '{}' will register {} accounts with prefix '{}' in the "
                                + "background", botGroup.getName(), botGroup.getBotCount(),
                        botGroup.getNamePrefix());
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

        BotGroup saved = repository.save(botGroup);

        // AFTER the persist, never before: the worker selects by the persisted state, so a group
        // enqueued ahead of its own document would be looked for and not found. Purely a
        // promptness hand-off — the next tick would pick it up regardless, which is also what
        // makes it safe for the enqueue to be lost.
        if (RegistrationState.isPending(saved.getRegistrationState())) {
            registrationWorker.enqueue(saved.getId());
        }

        return saved;
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

    /**
     * Persist an operator-initiated manual activation-mode override
     * (TIMED_ACTIVATION AD-4). Flips only the {@code activationMode} of a group
     * and re-stamps {@code updatedAt}; it does not touch {@code targetStatus}
     * (that is driven by the explicit start/stop paths) and deliberately does
     * not re-run game-config validation — parking a group as
     * {@link com.vingame.bot.domain.botgroup.model.ActivationMode#MANUAL_ON}/
     * {@link com.vingame.bot.domain.botgroup.model.ActivationMode#MANUAL_OFF}
     * carries no window requirement.
     * <p>
     * <b>Operator-initiated only.</b> The reconciler and the {@code onStartup}
     * auto-start path must never call this — flipping the mode from those paths
     * would make a scheduled group permanently park itself on its first
     * schedule-driven stop, defeating scheduling (AD-4, Implementation Notes).
     * The controller layer is the sole caller.
     */
    public void setActivationMode(String id, ActivationMode mode) {
        setActivationMode(findById(id), mode);
    }

    /**
     * Same contract as {@link #setActivationMode(String, ActivationMode)} but
     * operates on an already-loaded group, avoiding a redundant {@code findById}
     * when the caller has read the document (the controller manual-override path
     * loads it once to inspect {@code activationMode}).
     */
    public void setActivationMode(BotGroup group, ActivationMode mode) {
        group.setActivationMode(mode);
        group.setUpdatedAt(Instant.now());
        repository.save(group);
    }

    /**
     * PATCH one bot group, merging the DTO's non-null fields over the persisted document.
     * <p>
     * <b>Raising {@code botCount} extends the registration target</b> (GATEWAY_REQUEST_BUDGET
     * A2.7). That is what turns "register 200 more bots for this group" from a script into a
     * product feature: the merged group goes back to {@code REGISTRATION_PENDING} and the worker
     * resumes from {@code registeredCount + 1}, so the accounts that exist are never touched.
     * <p>
     * <b>Lowering it never un-registers anything</b>, and {@code registeredCount} is allowed to
     * exceed {@code botCount} as a result — it is a fact about accounts that exist, not an
     * intent. That is also the clean exit from a half-failed 500-account group: PATCH
     * {@code botCount} down to whatever registered, and start it.
     */
    public BotGroup update(String id, BotGroupDTO updateDTO) {
        BotGroup existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        // Validate the post-merge entity (AD-6) so cross-field PATCH rules — e.g.
        // lowering maxBet below the persisted minBet — are caught before save.
        configValidation.validate(existing);

        // A2.7. Checked AFTER the merge, against the merged count, because "did this PATCH ask
        // for more accounts than exist?" is a question about the result and not about the body.
        // A group created with existingGroup=true is deliberately included: its accounts were
        // migrated, so raising its botCount has to register the NEW indices, and there is no
        // other way to ask for that. registeredCount is 0 for such a group, so the worker starts
        // at index 1 and the migrated accounts answer EXISTED — two requests each and no harm,
        // which is why this does not need a special case.
        if (existing.getBotCount() > existing.getRegisteredCount()
                && !RegistrationState.isFailed(existing.getRegistrationState())) {
            existing.setRegistrationState(RegistrationState.PENDING);
        }

        // Route through save so updatedAt is (re)stamped on every mutation (AD-16);
        // existing has an id so this is the update path (no new-group branch). save() also owns
        // the enqueue — its guard is on the PERSISTED state, so a PATCH that just set PENDING
        // above is picked up there and must not be enqueued a second time here.
        return save(existing);
    }

    /**
     * Resume a registration that stopped on a refused account
     * (GATEWAY_REQUEST_BUDGET A2.6) — the one new action endpoint this feature adds.
     * <p>
     * Clears {@code FAILED} back to {@code PENDING}, clears the recorded reason, and re-enqueues.
     * The worker resumes from {@code registeredCount + 1}: <b>nothing starts over</b>, and the
     * accounts that exist are not re-created. It also resets the in-memory attempt budget, which
     * is what makes this an operator action rather than a retry the worker could have done
     * itself — a group that has run out of attempts has already been reported as needing a human,
     * and retrying it silently is exactly what the budget exists to stop.
     *
     * @throws BadRequestException if the group is not in {@code REGISTRATION_FAILED}. A retry of
     *         a group that is registering happily, or of one that finished, is a client mistake
     *         worth naming rather than a no-op worth hiding.
     */
    public BotGroup retryRegistration(String id) {
        BotGroup group = findById(id);
        if (!RegistrationState.isFailed(group.getRegistrationState())) {
            throw new BadRequestException(String.format(
                    "Bot group '%s' is not in REGISTRATION_FAILED (%d/%d accounts registered, "
                            + "state %s), so there is nothing to retry.",
                    group.getName(), group.getRegisteredCount(), group.getBotCount(),
                    group.getRegistrationState() == null ? "complete"
                            : group.getRegistrationState()));
        }
        group.setRegistrationState(RegistrationState.PENDING);
        group.setRegistrationError(null);
        group.setUpdatedAt(Instant.now());
        BotGroup saved = repository.save(group);
        log.info("group {} ({}): registration retry requested — resuming from account {}",
                id, saved.getName(), saved.getRegisteredCount() + 1);
        registrationWorker.enqueue(id);
        return saved;
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
        // Call the registration off FIRST, before stopAndLogout takes the group lock
        // (GATEWAY_REQUEST_BUDGET A28.6). Same ordering rule as stop()'s cancel-then-lock: a
        // registration request parked inside the budget can be waiting up to
        // `registration.max-wait` (15 m), and cancelScope is the only thing that wakes it. It is
        // a no-op for a group that is not registering, which is the common case.
        //
        // Read before the delete because the environment id is how the budget is found, and the
        // document is about to stop existing.
        repository.findById(id).ifPresent(
                group -> registrationWorker.cancel(id, group.getEnvironmentId()));
        behaviorService.stopAndLogout(id);
        repository.deleteById(id);
    }
}
