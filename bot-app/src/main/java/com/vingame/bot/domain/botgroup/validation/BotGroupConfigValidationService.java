package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single integration seam for game-type-specific bot-group config validation
 * (AD-9). Both {@code BotGroupService.save} (create) and
 * {@code BotGroupService.update} (PATCH, post-merge) call {@link #validate}.
 *
 * <p>This keeps {@code BotGroupService} free of any {@link GameType} branching:
 * the orchestrator resolves the {@link Game}/{@link GameType} from the group's
 * {@code gameId}, selects the validator via {@link GameConfigValidatorFactory},
 * and invokes it.
 *
 * <p>A null/blank/unresolvable {@code gameId} is a universal required-field
 * failure surfaced as a {@link BadRequestException} (400) <b>before</b> a
 * validator is selected — the factory cannot pick one without a
 * {@link GameType}. {@link GameService#findById} throws a 404
 * {@link ResourceNotFoundException} on an unknown id; per AD-7 that is a client
 * error on a create body, so it is caught and rethrown as a 400.
 */
@Service
public class BotGroupConfigValidationService {

    private final GameConfigValidatorFactory validatorFactory;
    private final GameService gameService;
    private final BettingStrategyFactory bettingStrategyFactory;
    private final SlotStrategyFactory slotStrategyFactory;

    public BotGroupConfigValidationService(GameConfigValidatorFactory validatorFactory,
                                           GameService gameService,
                                           BettingStrategyFactory bettingStrategyFactory,
                                           SlotStrategyFactory slotStrategyFactory) {
        this.validatorFactory = validatorFactory;
        this.gameService = gameService;
        this.bettingStrategyFactory = bettingStrategyFactory;
        this.slotStrategyFactory = slotStrategyFactory;
    }

    /**
     * Resolve the group's {@link GameType} and run its validator.
     *
     * @param group the bot group to validate (post-merge on the PATCH path).
     * @throws BadRequestException if a strategy key names no registered bean, if
     *         {@code gameId} is missing/unresolvable, or if the game-type
     *         validator rejects the configuration.
     */
    public void validate(BotGroup group) {
        // Strategy keys first, because that is where they used to be rejected:
        // until PLUGIN_HOT_RELOAD Phase 2b these were enum-typed, so an unknown
        // key failed Jackson deserialization of the request body — before any
        // service ran. Keeping it first preserves which error wins *within this
        // method*, so a group with both a bad strategy key and a bad activation
        // window reports the strategy key — the relative order Jackson used to
        // impose (PLUGIN_HOT_RELOAD AD-15).
        //
        // It does not preserve which error a whole create *body* reports, and an
        // earlier version of this comment claimed it did. BotGroupController.save
        // is @Validated(OnCreate.class) and BotGroupDTO carries @NotBlank /
        // @Positive, and bean validation runs after Jackson but before the
        // controller body — so a create body with a blank namePrefix and
        // strategyId "NONSENSE" used to report the enum fault and now reports
        // "namePrefix must not be blank". Both are 400 and no client parses which
        // of two faults comes first, so this is comment accuracy, not behaviour.
        // The PATCH path has no @Validated and does behave as described above.
        validateStrategyKeys(group);

        // Activation config is game-type-independent (TIMED_ACTIVATION AD-7), so
        // it is checked here for every group rather than in a per-GameType
        // validator. A null/legacy activationMode passes through unchanged.
        ActivationRules.validate(group);

        String gameId = group.getGameId();
        if (gameId == null || gameId.isBlank()) {
            throw new BadRequestException("gameId is required");
        }

        Game game;
        try {
            game = gameService.findById(gameId);
        } catch (ResourceNotFoundException e) {
            // AD-7: an unknown gameId in a create/update body is a client error
            // (400), not a 404.
            throw new BadRequestException("Game not found: " + gameId, e);
        }

        GameType gameType = game.getGameType();
        if (gameType == null) {
            throw new BadRequestException("Game " + gameId + " has no gameType configured");
        }

        validatorFactory.forType(gameType).validate(group);
    }

    /**
     * PLUGIN_HOT_RELOAD AD-15 — the validation Jackson used to do implicitly,
     * made explicit at the same HTTP status.
     *
     * <p>Before Phase 2b {@code strategyMix[].strategyId} and
     * {@code slotStrategyId} were enum-typed, so {@code "NONSENSE"} failed enum
     * deserialization and the request was a 400 before it reached any service.
     * They are {@code String}s now, so nothing would reject it — this does,
     * against the registries that actually have to resolve the key at group
     * start.
     *
     * <p>Running here rather than at the mapper means it covers <b>create and
     * PATCH</b> (both call {@link #validate}). Note what that does and does not
     * buy — AD-15 originally claimed "strictly better coverage" and Amendment A5
     * falsified it in both directions. {@link #validate} is <b>not</b> on the
     * group-start path ({@code BotGroupBehaviorService} never calls it), so a
     * group whose strategy bean vanished in a deploy still fails at
     * {@code BettingStrategyFactory.create} on a bot thread, exactly as before.
     * And because {@link #validate} runs <b>post-merge over the whole entity</b>
     * on PATCH (TIMED_ACTIVATION AD-6, for cross-field rules), this reads
     * persisted state as well as the request body: a group holding an
     * unregistered key fails <em>any</em> PATCH — renaming it, adjusting
     * {@code maxBet} — until its mix is replaced. That is accepted deliberately.
     * The group stays startable, stoppable and deletable, {@code strategyMix} is
     * full-replace when supplied so a single PATCH clears the fault, and the 400
     * names the bad key and lists the catalogue. But it is a new failure mode,
     * not pure upside, and steps 5-7 are exactly when a registry shrinks.
     *
     * <p>Null and empty short-circuit before either registry is consulted. That
     * is not an optimisation: {@code strategyMix} is null on every group that
     * predates BETTING_STRATEGIES and {@code slotStrategyId} is null on every
     * non-SLOT group, and null {@code slotStrategyId} is meaningful — it means
     * "fall back to FIXED at bot-build time".
     *
     * <p>{@code slotStrategyId} is checked even for non-SLOT groups: it is a
     * persisted field on every group and a bad value is a bad value. The
     * game-type-specific rules live in the per-{@link GameType} validators.
     */
    private void validateStrategyKeys(BotGroup group) {
        List<WeightedStrategy> mix = group.getStrategyMix();
        if (mix != null && !mix.isEmpty()) {
            Set<String> registered = bettingStrategyFactory.registeredKeys();
            for (WeightedStrategy entry : mix) {
                String key = entry == null ? null : entry.strategyId();
                if (key == null || !registered.contains(key)) {
                    throw new BadRequestException(
                            "Unknown strategyId '" + key + "' in strategyMix — registered strategies: "
                                    + sorted(registered));
                }
            }
        }

        String slotStrategyId = group.getSlotStrategyId();
        if (slotStrategyId != null) {
            Set<String> registered = slotStrategyFactory.registeredKeys();
            if (!registered.contains(slotStrategyId)) {
                throw new BadRequestException(
                        "Unknown slotStrategyId '" + slotStrategyId
                                + "' — registered slot strategies: " + sorted(registered));
            }
        }
    }

    /**
     * The registries iterate in Spring's bean-discovery order, which is
     * arbitrary (plan Amendment A4). Sort the list an operator reads out of a
     * 400 body so it is stable across machines and packagings.
     */
    private static Set<String> sorted(Set<String> keys) {
        return new TreeSet<>(keys);
    }
}
