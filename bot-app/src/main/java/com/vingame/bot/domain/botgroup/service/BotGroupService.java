package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
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
import org.springframework.beans.factory.annotation.Value;
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
    private final DepositLedger depositLedger;

    /**
     * Upper bound on {@code initialDeposit} (BOT_PROVISIONING AD-3), in brand currency units.
     * The initialiser is for Mockito fixtures that construct this service directly.
     */
    @Value("${bot.provisioning.max-initial-deposit:1000000000}")
    private long maxInitialDeposit = 1_000_000_000L;

    public BotGroupService(BotGroupRepository repository, BotGroupMapper mapper,
                           EnvironmentService environmentService,
                           GameService gameService,
                           MongoTemplate mongoTemplate,
                           BotGroupConfigValidationService configValidation,
                           @Lazy BotGroupBehaviorService behaviorService,
                           RegistrationWorker registrationWorker,
                           DepositLedger depositLedger) {
        this.repository = repository;
        this.mapper = mapper;
        this.environmentService = environmentService;
        this.gameService = gameService;
        this.mongoTemplate = mongoTemplate;
        this.configValidation = configValidation;
        this.behaviorService = behaviorService;
        this.registrationWorker = registrationWorker;
        this.depositLedger = depositLedger;
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

            // BOT_PROVISIONING AD-3: range, and no money for a group that registers nothing.
            validateInitialDepositRange(botGroup.getInitialDeposit());
            if (skipRegistration && botGroup.getInitialDeposit() > 0) {
                throw new BadRequestException(String.format(
                        "existingGroup=true registers no accounts, so initialDeposit %d would fund "
                                + "nothing. Omit initialDeposit (or send 0) for a migrated group.",
                        botGroup.getInitialDeposit()));
            }

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
                botGroup.setDepositedCount(0);
                botGroup.setDepositInFlight(null);
                log.info("Bot group '{}' will register {} accounts with prefix '{}' in the "
                                + "background{}", botGroup.getName(), botGroup.getBotCount(),
                        botGroup.getNamePrefix(),
                        botGroup.getInitialDeposit() > 0
                                ? ", funding each with " + botGroup.getInitialDeposit() : "");
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
     * A2.7, A31.1). That is what turns "register 200 more bots for this group" from a script into
     * a product feature: the merged group goes back to {@code REGISTRATION_PENDING} and the worker
     * resumes from {@code registeredCount + 1}, so the accounts that exist are never touched.
     * <b>Only a raise does it</b>, and a raise is a comparison against the <em>pre-merge</em>
     * {@code botCount} — see {@link #applyRegistrationTargetChange}, which is where review B1
     * lived.
     * <p>
     * <b>Lowering it never un-registers anything</b>, and {@code registeredCount} is allowed to
     * exceed {@code botCount} as a result — it is a fact about accounts that exist, not an
     * intent. That is also the clean exit from a half-failed 500-account group: PATCH
     * {@code botCount} down to whatever registered, and start it — which works because lowering
     * the target to a met one clears {@code REGISTRATION_FAILED} here (review B4).
     */
    public BotGroup update(String id, BotGroupDTO updateDTO) {
        BotGroup existing = findById(id);
        // Both captured BEFORE the merge. "Did this PATCH ask for more accounts?" is a question
        // about the body's effect on the target, and it is only answerable against the target the
        // document carried on the way in (review B1).
        int botCountBefore = existing.getBotCount();
        boolean registrationUntracked = isRegistrationUntracked(existing);
        long initialDepositBefore = existing.getInitialDeposit();
        String registrationStateBefore = existing.getRegistrationState();

        mapper.updateEntityFromDTO(updateDTO, existing);

        // BOT_PROVISIONING AD-3: range, and only while registration is complete — a PENDING or
        // FAILED job keeps the amount it started with, so one job never mixes two amounts.
        if (existing.getInitialDeposit() != initialDepositBefore) {
            validateInitialDepositRange(existing.getInitialDeposit());
            if (registrationStateBefore != null) {
                throw new BadRequestException(String.format(
                        "initialDeposit cannot change while registration is %s (%d/%d accounts): "
                                + "the job in progress keeps the amount it started with. Wait for it "
                                + "to complete, or resolve the failure first.",
                        registrationStateBefore, existing.getRegisteredCount(), existing.getBotCount()));
            }
        }
        // Validate the post-merge entity (AD-6) so cross-field PATCH rules — e.g.
        // lowering maxBet below the persisted minBet — are caught before save.
        configValidation.validate(existing);

        // BOT_PROVISIONING AD-4: the username pre-flight used to run on create only, so raising a
        // TIP group from 99 to 100 with a 10-char prefix passed PATCH and failed at the gateway
        // on index 100, after the worker had spent requests getting there. Only a raise can make
        // the longest username longer, so only a raise is checked — a rename or a maxBet change
        // on a group whose prefix predates the check must keep working.
        if (existing.getBotCount() > botCountBefore) {
            validateUsernameLength(existing);
        }

        applyRegistrationTargetChange(existing, botCountBefore, registrationUntracked);

        // Route through save so updatedAt is (re)stamped on every mutation (AD-16);
        // existing has an id so this is the update path (no new-group branch). save() also owns
        // the enqueue — its guard is on the PERSISTED state, so a PATCH that just set PENDING
        // above is picked up there and must not be enqueued a second time here.
        return save(existing);
    }

    /**
     * Whether this group has <b>no registration history at all</b> — a group created before
     * asynchronous registration existed, or one created with {@code existingGroup=true}.
     * <p>
     * This is the same encoding {@code BotGroupMapper.toDTO} and
     * {@code BotGroupBehaviorService.renderedRegisteredCount} already read the other way round,
     * and for the reason written there: {@code registrationState == null && registeredCount == 0}
     * means <em>"nobody ever tracked this group's accounts"</em>, which is the opposite situation
     * from <em>"this group has no accounts"</em>. Review B1 is exactly what happens when the
     * update path infers the second from the first.
     */
    private static boolean isRegistrationUntracked(BotGroup group) {
        return group.getRegistrationState() == null && group.getRegisteredCount() == 0;
    }

    /**
     * Apply a PATCH's effect on the registration target (A2.7 / A31.1, reviews B1 and B4).
     *
     * <p><b>A raise, and only a raise, re-arms registration.</b> The predecessor tested
     * {@code botCount > registeredCount}, which reads like "did this PATCH ask for more accounts
     * than exist" and is in fact <em>unconditionally true for every group that predates Phase
     * 4</em>: Mongo has no {@code registeredCount} field on those documents, so Spring Data maps
     * the absent field to {@code int} {@code 0}. Any PATCH at all — a {@code maxBet} change, a
     * rename — therefore flipped a live 250-bot group to {@code REGISTRATION_PENDING}, made it
     * unstartable, and handed the worker 250 indices to walk. The {@code EXISTED} answers make the
     * <em>registration</em> half harmless; the <b>naming</b> half is not, because
     * {@code namedCount} is absent for the same reason, so every one of those live accounts would
     * be renamed from the display-name pool. That is ~500 gateway requests — half the Cloudflare
     * five-minute allowance — and it silently undoes hand-naming work.
     *
     * <p><b>An untracked group's counters are seeded, not walked.</b> When a raise lands on a
     * group with no registration history, the accounts for indices {@code 1..botCountBefore}
     * already exist (that is what the group asked for when it was created) and nothing about them
     * needs touching, so the high-water mark is set to the old target and the worker starts at the
     * first genuinely new index. The assumption is explicit: if a legacy group's original
     * synchronous registration had partially failed, the missing index is not re-created here and
     * surfaces as it always has — one bot that cannot authenticate at start. The alternative is
     * {@code 2 x botCount} gateway requests plus the rename, on every routine PATCH.
     *
     * <p><b>Lowering the target to a met one clears {@code REGISTRATION_FAILED}.</b> The state is
     * a statement about an <em>unmet</em> target, so meeting the target discharges it. Without
     * this, the repair the 400, the hand-off ERROR and the {@code RegistrationStalled} annotation
     * all advise — "PATCH botCount down to what registered and start it" — could not work:
     * {@code FAILED} was cleared in {@code retryRegistration} and nowhere else, so the operator
     * looped on the same 400 repeating the same advice (review B4). A trailing index that
     * registered but was never named stays nameless; {@code /registration/retry} is the way to
     * finish that, and the worker's WARN is how it is known.
     *
     * <p>The {@code PENDING} half of the same advice needs nothing here: a {@code PENDING} group
     * whose target has been lowered to a met one is completed by the worker's own next pass,
     * without a single gateway request, because {@code isComplete} is evaluated before any call.
     */
    private void applyRegistrationTargetChange(BotGroup group, int botCountBefore,
                                               boolean untracked) {
        if (group.getBotCount() > botCountBefore
                && !RegistrationState.isFailed(group.getRegistrationState())) {
            if (group.getRegistrationState() == null) {
                // BOT_PROVISIONING AD-9 — no retro-funding. Registration is complete, so accounts
                // 1..botCountBefore exist and were funded (or were never meant to be: a
                // pre-feature group, one created with initialDeposit 0, an untracked one). This
                // raise funds only the indices it adds. Monotonic, so it can never move the mark
                // back. NOT done for a raise during PENDING: those indices genuinely still owe a
                // deposit.
                depositLedger.seedAtLeast(group.getId(), botCountBefore);
                group.setDepositedCount(Math.max(group.getDepositedCount(), botCountBefore));
            }
            if (untracked) {
                group.setRegisteredCount(botCountBefore);
                group.setNamedCount(botCountBefore);
                log.info("Bot group '{}' ({}): botCount raised {} → {} on a group with no "
                                + "registration history — indices 1-{} are assumed to exist and "
                                + "are left untouched; registering {} new accounts",
                        group.getName(), group.getId(), botCountBefore, group.getBotCount(),
                        botCountBefore, group.getBotCount() - botCountBefore);
            }
            group.setRegistrationState(RegistrationState.PENDING);
            return;
        }
        if (RegistrationState.isFailed(group.getRegistrationState())
                && group.getBotCount() <= group.getRegisteredCount()) {
            log.info("Bot group '{}' ({}): botCount lowered to {} with {} accounts registered — "
                            + "REGISTRATION_FAILED cleared, the group is startable with what it has",
                    group.getName(), group.getId(), group.getBotCount(), group.getRegisteredCount());
            group.setRegistrationState(null);
            group.setRegistrationError(null);
        }
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
        return retryRegistration(id, null);
    }

    /**
     * {@link #retryRegistration(String)} with an operator's resolution of an unknown deposit
     * outcome (BOT_PROVISIONING AD-8).
     * <p>
     * {@code depositOutcome} is <b>required</b> when the group stopped on an unknown outcome and
     * <b>rejected</b> otherwise: the worker refuses to send anything for a group whose ledger
     * carries an in-flight marker, so a retry without a decision would only fail again, and a
     * decision for a group that has no marker would be a statement about money nobody asked
     * about. {@code CREDITED} advances the funded high-water mark to the marker; {@code
     * NOT_CREDITED} leaves it, so the index is sent exactly once more. Both clear the marker.
     */
    public BotGroup retryRegistration(String id, DepositResolution depositOutcome) {
        BotGroup group = findById(id);
        if (!RegistrationState.isFailed(group.getRegistrationState())) {
            throw new BadRequestException(String.format(
                    "Bot group '%s' is not in REGISTRATION_FAILED (%d/%d accounts registered, "
                            + "state %s), so there is nothing to retry.",
                    group.getName(), group.getRegisteredCount(), group.getBotCount(),
                    group.getRegistrationState() == null ? "complete"
                            : group.getRegistrationState()));
        }
        DepositLedger.State ledger = depositLedger.read(id);
        if (ledger.depositInFlight() != null) {
            if (depositOutcome == null) {
                throw new BadRequestException(String.format(
                        "Bot group '%s' stopped on an unknown deposit outcome for %s%d (index %d). "
                                + "Check that account's balance, then retry with "
                                + "?depositOutcome=credited or ?depositOutcome=not-credited.",
                        group.getName(), group.getNamePrefix(), ledger.depositInFlight(),
                        ledger.depositInFlight()));
            }
        } else if (depositOutcome != null) {
            throw new BadRequestException(String.format(
                    "Bot group '%s' has no deposit with an unknown outcome, so depositOutcome does "
                            + "not apply. Retry without it.", group.getName()));
        }
        if (ledger.depositInFlight() != null) {
            int index = ledger.depositInFlight();
            boolean credited = depositOutcome == DepositResolution.CREDITED;
            if (!depositLedger.resolve(id, index, credited)) {
                throw new BadRequestException(String.format(
                        "Bot group '%s': the unknown deposit outcome changed while this request was "
                                + "being handled. Re-read the group and retry.", group.getName()));
            }
            DepositLedger.State resolved = depositLedger.read(id);
            group.setDepositedCount(resolved.depositedCount());
            group.setDepositInFlight(null);
            log.info("group {} ({}): operator resolved the unknown deposit for {}{} (index {}) as {} — "
                            + "{}", id, group.getName(), group.getNamePrefix(), index, index,
                    credited ? "credited" : "not credited",
                    credited ? "it will not be sent again" : "it will be sent once more");
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
        depositLedger.delete(id);
    }

    /** BOT_PROVISIONING AD-3: {@code 0 <= initialDeposit <= bot.provisioning.max-initial-deposit}. */
    private void validateInitialDepositRange(long initialDeposit) {
        if (initialDeposit < 0 || initialDeposit > maxInitialDeposit) {
            throw new BadRequestException(String.format(
                    "initialDeposit must be between 0 and %d (bot.provisioning.max-initial-deposit), "
                            + "got %d", maxInitialDeposit, initialDeposit));
        }
    }

    /** The operator's answer to an unknown deposit outcome (AD-8). */
    public enum DepositResolution {
        CREDITED, NOT_CREDITED;

        /** {@code credited} / {@code not-credited}; {@code null} for an absent parameter. */
        public static DepositResolution parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "credited" -> CREDITED;
                case "not-credited" -> NOT_CREDITED;
                default -> throw new BadRequestException(
                        "depositOutcome must be 'credited' or 'not-credited', got '" + value + "'");
            };
        }
    }
}
